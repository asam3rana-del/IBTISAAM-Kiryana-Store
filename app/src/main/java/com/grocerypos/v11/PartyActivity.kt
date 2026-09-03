package com.grocerypos.v11.ui

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Customer
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI layer only — this Activity builds views and forwards user actions to
 * [PartyViewModel]. It never touches Room or SyncQueueHelper directly; that
 * lives in PartyRepository (data) and PartyUseCases (domain). See those two
 * files plus PartyViewModel for the rest of the UI -> ViewModel -> UseCase ->
 * Repository -> Room chain.
 */
class PartyActivity : AppCompatActivity() {

    private val viewModel: PartyViewModel by viewModels { PartyViewModelFactory(applicationContext) }

    // ---- Light premium palette ----
    private val bg = "#FAFAFC"
    private val gradientStart = "#7C86F5"
    private val gradientEnd = "#A6ADFF"
    private val blue = "#5B6EE8"
    private val orange = "#F5A15C"
    private val green = "#4CAF50"
    private val red = "#E57373"
    private val cardWhite = "#FFFFFF"
    private val cardBorder = "#EEF0F7"
    private val labelGray = "#9AA0B4"

    private lateinit var tabRow: LinearLayout
    private lateinit var formCard: LinearLayout
    private lateinit var nameField: EditText
    private lateinit var phoneField: EditText
    private lateinit var creditLimitField: EditText
    private lateinit var creditLimitBox: LinearLayout
    private lateinit var openingBalanceField: EditText
    private lateinit var listContainer: LinearLayout
    private lateinit var saveButton: Button
    private lateinit var sectionAccentText: TextView

    // ---- Contact picker launchers ----
    private lateinit var contactPickerLauncher: ActivityResultLauncher<Void?>
    private lateinit var contactPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        contactPickerLauncher = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            uri?.let { fetchPhoneFromContact(it) }
        }
        contactPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                contactPickerLauncher.launch(null)
            } else {
                Toast.makeText(this, Loc.t(this, "Contacts permission denied", "رابطوں کی اجازت مسترد"), Toast.LENGTH_SHORT).show()
            }
        }

        // ---- TABLET/DENSITY FIX: header + root padding below used to be raw pixel values
        // (e.g. setPadding(28, 40, 24, 32)) instead of dp-scaled — fine on the density this was
        // designed at, but far too tight on a tablet's higher-density screen. That's why the
        // header sat cramped against the top and, more importantly, why the LAST party row's
        // Edit/Delete buttons ended up sitting right at the physical bottom edge of the screen —
        // inside the gesture-nav strip, which swallows the tap before it reaches the button
        // (looks like "last item's Edit doesn't work"). Fixed by scaling every padding value
        // below with density, and adding real breathing room at the bottom of the scroll
        // content so the last card can be scrolled clear of the gesture area. ----
        val d = resources.displayMetrics.density

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= GRADIENT HEADER =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((28 * d).toInt(), (44 * d).toInt(), (24 * d).toInt(), (32 * d).toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(gradientStart), Color.parseColor(gradientEnd))
            )
        }
        header.addView(TextView(this).apply {
            text = "\uD83D\uDC65"
            textSize = 22f
            gravity = Gravity.CENTER
            background = ovalBg(cardWhite)
            width = (48 * resources.displayMetrics.density).toInt()
            height = (48 * resources.displayMetrics.density).toInt()
        })
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerText.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Customers & Suppliers", "کسٹمرز اور سپلائرز")
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headerText.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Manage parties & view ledgers", "پارٹیز کا انتظام اور کھاتے دیکھیں")
            textSize = 12f
            setTextColor(Color.parseColor("#EDEFFC"))
        })
        header.addView(headerText)
        outer.addView(header)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Bottom padding raised from a raw 28px to a density-scaled 90dp so the last
            // list item always has room to scroll clear of the gesture-nav strip.
            setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (90 * d).toInt())
        }

        // ================= TABS (pill style) =================
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabRow)
        root.addView(spacer(16))

        // ================= ADD PARTY FORM CARD =================
        formCard = premiumCard().apply { setPadding(22, 20, 22, 20) }

        sectionAccentText = TextView(this).apply {
            text = "\uD83D\uDC64  " + Loc.t(this@PartyActivity, "Add Customer", "کسٹمر شامل کریں")
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(blue))
            setPadding(0, 0, 0, 12)
        }
        formCard.addView(sectionAccentText)

        val nameBox = innerField()
        nameField = EditText(this).apply { hint = Loc.t(this@PartyActivity, "Name *", "نام *"); background = null; textSize = 15f }
        nameBox.addView(nameField)
        formCard.addView(nameBox)
        formCard.addView(spacer(10))

        // ---- Phone field row: EditText + contact-picker icon button ----
        val phoneBox = innerField().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        phoneField = EditText(this).apply {
            hint = Loc.t(this@PartyActivity, "Phone (optional)", "فون (اختیاری)")
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_PHONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        phoneBox.addView(phoneField)
        phoneBox.addView(TextView(this).apply {
            text = "\uD83D\uDC64\u200D\uD83D\uDCDE"
            textSize = 18f
            setPadding(12, 0, 4, 0)
            setOnClickListener { openContactPicker() }
        })
        formCard.addView(phoneBox)
        formCard.addView(spacer(10))

        creditLimitBox = innerField()
        creditLimitField = EditText(this).apply {
            hint = Loc.t(this@PartyActivity, "Credit Limit (optional)", "کریڈٹ لیمٹ (اختیاری)")
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        creditLimitBox.addView(creditLimitField)
        formCard.addView(creditLimitBox)
        formCard.addView(spacer(10))

        val openingBox = innerField()
        openingBalanceField = EditText(this).apply {
            hint = Loc.t(this@PartyActivity, "Opening Balance (Rs, if any previous due)", "ابتدائی بیلنس (روپے، اگر کوئی پرانا واجب الادا ہو)")
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        openingBox.addView(openingBalanceField)
        formCard.addView(openingBox)
        formCard.addView(spacer(14))

        saveButton = Button(this).apply {
            text = Loc.t(this@PartyActivity, "SAVE", "محفوظ کریں")
            setTextColor(Color.WHITE)
            textSize = 14f
            background = roundedBackground(blue, 14)
            setPadding(0, 20, 0, 20)
            setOnClickListener { saveParty() }
        }
        formCard.addView(saveButton)
        root.addView(formCard)
        root.addView(spacer(18))

        // ================= LIST HEADER =================
        val listHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 10)
        }
        listHeaderRow.addView(TextView(this).apply {
            text = "\uD83D\uDCCB  "
            textSize = 15f
        })
        listHeaderRow.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Party List", "پارٹی لسٹ")
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        listHeaderRow.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Tap for history · icons to edit/delete", "تاریخ کے لیے ٹیپ کریں · ترمیم/حذف کے آئیکنز")
            textSize = 11f
            setTextColor(Color.parseColor(labelGray))
        })
        root.addView(listHeaderRow)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        val scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(root)
        }
        outer.addView(scrollArea)

        setContentView(outer)

        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state -> render(state) }
        }
        lifecycleScope.launch {
            viewModel.events.collectLatest { event -> handleEvent(event) }
        }
    }

    /** Single render pass driven by [PartyUiState] — tabs, form styling, and the
     * party list all follow from this one source of truth instead of being
     * mutated ad hoc from separate click handlers. */
    private fun render(state: PartyUiState) {
        buildTabs(state.showingCustomers)
        creditLimitBox.visibility = if (state.showingCustomers) View.VISIBLE else View.GONE
        sectionAccentText.text = if (state.showingCustomers)
            "\uD83D\uDC64  " + Loc.t(this, "Add Customer", "کسٹمر شامل کریں")
        else
            "\uD83D\uDCE6  " + Loc.t(this, "Add Supplier", "سپلائر شامل کریں")
        sectionAccentText.setTextColor(Color.parseColor(if (state.showingCustomers) blue else orange))
        saveButton.background = roundedBackground(if (state.showingCustomers) blue else orange, 14)

        listContainer.removeAllViews()
        if (state.showingCustomers) {
            if (state.customers.isEmpty()) {
                listContainer.addView(emptyCard(Loc.t(this, "No customers yet", "کوئی کسٹمر نہیں ہے")))
            }
            for (c in state.customers) {
                listContainer.addView(
                    partyRow(c.name, c.phone, c.openingBalance, c.balance, blue, "\uD83D\uDC64", isCustomer = true,
                        onClick = { openCustomerHistory(c) },
                        onEdit = { editCustomerDialog(c) },
                        onDelete = { confirmDeleteCustomer(c) }
                    )
                )
            }
        } else {
            if (state.suppliers.isEmpty()) {
                listContainer.addView(emptyCard(Loc.t(this, "No suppliers yet", "کوئی سپلائر نہیں ہے")))
            }
            for (s in state.suppliers) {
                listContainer.addView(
                    partyRow(s.name, s.phone, s.openingBalance, s.balance, orange, "\uD83D\uDCE6", isCustomer = false,
                        onClick = { openSupplierHistory(s) },
                        onEdit = { editSupplierDialog(s) },
                        onDelete = { confirmDeleteSupplier(s) }
                    )
                )
            }
        }
    }

    private fun handleEvent(event: PartyEvent) {
        val message = when (event) {
            PartyEvent.NameRequired -> Loc.t(this, "Name is required", "نام ضروری ہے")
            PartyEvent.Saved -> Loc.t(this, "Saved", "محفوظ ہو گیا")
            PartyEvent.Updated -> Loc.t(this, "Updated", "اپ ڈیٹ ہو گیا")
            PartyEvent.Deleted -> Loc.t(this, "Deleted", "حذف ہو گیا")
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        if (event == PartyEvent.Saved) {
            nameField.text.clear()
            phoneField.text.clear()
            creditLimitField.text.clear()
            openingBalanceField.text.clear()
        }
    }

    // ================= Contact picker =================
    private fun openContactPicker() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            contactPickerLauncher.launch(null)
        } else {
            contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun fetchPhoneFromContact(contactUri: Uri) {
        val cursor = contentResolver.query(contactUri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idIdx = it.getColumnIndex(ContactsContract.Contacts._ID)
                val hasPhoneIdx = it.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                val nameIdx = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val contactId = if (idIdx >= 0) it.getString(idIdx) else null
                val contactName = if (nameIdx >= 0) it.getString(nameIdx) else null

                if (contactId != null && hasPhoneIdx >= 0 && it.getInt(hasPhoneIdx) > 0) {
                    val phoneCursor = contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(contactId),
                        null
                    )
                    phoneCursor?.use { pc ->
                        if (pc.moveToFirst()) {
                            val numIdx = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            val number = if (numIdx >= 0) pc.getString(numIdx) else null
                            if (number != null) {
                                phoneField.setText(number.replace(Regex("[^0-9+]"), ""))
                            }
                            if (nameField.text.isNullOrBlank() && !contactName.isNullOrBlank()) {
                                nameField.setText(contactName)
                            }
                        }
                    }
                } else {
                    Toast.makeText(
                        this,
                        Loc.t(this, "No phone number for this contact", "اس رابطے کا کوئی نمبر نہیں"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ================= Tabs =================
    private fun buildTabs(showingCustomers: Boolean) {
        tabRow.removeAllViews()
        tabRow.addView(Button(this).apply {
            text = "\uD83D\uDC64  " + Loc.t(this@PartyActivity, "CUSTOMERS", "کسٹمرز")
            setTextColor(if (showingCustomers) Color.WHITE else Color.parseColor("#6B7280"))
            textSize = 12f
            background = roundedBackground(if (showingCustomers) blue else "#EEF0F7", 24)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
            setOnClickListener { viewModel.showCustomers() }
        })
        tabRow.addView(Button(this).apply {
            text = "\uD83D\uDCE6  " + Loc.t(this@PartyActivity, "SUPPLIERS", "سپلائرز")
            setTextColor(if (!showingCustomers) Color.WHITE else Color.parseColor("#6B7280"))
            textSize = 12f
            background = roundedBackground(if (!showingCustomers) orange else "#EEF0F7", 24)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 0) }
            setOnClickListener { viewModel.showSuppliers() }
        })
    }

    private fun saveParty() {
        val name = nameField.text.toString()
        val phone = phoneField.text.toString()
        val limit = creditLimitField.text.toString().toDoubleOrNull() ?: 0.0
        val opening = openingBalanceField.text.toString().toDoubleOrNull() ?: 0.0
        // ViewModel decides customer vs supplier from its own state and ignores
        // creditLimit on the supplier path, so this call doesn't need to branch.
        viewModel.addParty(name, phone, limit, opening)
    }

    // ================= Premium party row card =================
    // ---- FIX ----
    // Closing-balance color must be type-aware, not a blanket "positive = red" rule:
    //   - Customer closing > 0  => customer owes the shop (receivable)  -> green (good, You'll Get)
    //   - Customer closing < 0  => shop owes the customer                -> red (You'll Give)
    //   - Supplier closing > 0  => shop owes the supplier (payable)      -> red (You'll Give)
    //   - Supplier closing < 0  => supplier owes the shop (e.g. credit)  -> green (You'll Get)
    // This mirrors the same fix applied to PartyDashboardActivity's You'll Get/You'll Give totals.
    private fun partyRow(
        name: String,
        phone: String,
        opening: Double,
        running: Double,
        accentHex: String,
        icon: String,
        isCustomer: Boolean,
        onClick: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ): LinearLayout {
        val closing = opening + running
        val isGive = if (isCustomer) closing < 0 else closing > 0
        val outerRow = premiumCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 12)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }

        topRow.addView(TextView(this).apply {
            text = icon
            textSize = 18f
            gravity = Gravity.CENTER
            background = ovalBg(accentHex)
            width = (42 * resources.displayMetrics.density).toInt()
            height = (42 * resources.displayMetrics.density).toInt()
        })

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 0, 12, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoCol.addView(TextView(this).apply {
            text = name
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#2E3242"))
        })
        if (phone.isNotEmpty()) {
            infoCol.addView(TextView(this).apply {
                text = phone
                textSize = 12f
                setTextColor(Color.parseColor(labelGray))
            })
        }
        val balRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 0) }
        balRow.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Opening", "ابتدائی") + ": Rs %.2f".format(opening)
            textSize = 11f
            setTextColor(Color.parseColor(labelGray))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        infoCol.addView(balRow)
        infoCol.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Tap for full history  ›", "مکمل تاریخ کے لیے ٹیپ کریں  ›")
            textSize = 11f
            setTextColor(Color.parseColor(accentHex))
            setPadding(0, 4, 0, 0)
        })
        topRow.addView(infoCol)

        topRow.addView(TextView(this).apply {
            text = "Rs %.2f".format(closing)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(if (isGive) red else green))
        })
        outerRow.addView(topRow)

        // ---- action row: edit / delete ----
        val actionDivider = View(this).apply {
            setBackgroundColor(Color.parseColor(cardBorder))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, 12, 0, 8)
            }
        }
        outerRow.addView(actionDivider)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actionRow.addView(actionChip("\u270F\uFE0F", Loc.t(this@PartyActivity, "Edit", "ترمیم"), accentHex, isDelete = false) { onEdit() })
        actionRow.addView(spacer(10).apply { layoutParams = LinearLayout.LayoutParams((10 * resources.displayMetrics.density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT) })
        actionRow.addView(actionChip("\uD83D\uDDD1\uFE0F", Loc.t(this@PartyActivity, "Delete", "حذف کریں"), red, isDelete = true) { onDelete() })
        outerRow.addView(actionRow)

        return outerRow
    }

    private fun actionChip(icon: String, label: String, colorHex: String, isDelete: Boolean, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (isDelete) "#FDEDED" else "#EEF0FF"))
                cornerRadius = 20f
            }
            addView(TextView(this@PartyActivity).apply { text = icon; textSize = 12f })
            addView(TextView(this@PartyActivity).apply {
                text = "  $label"
                textSize = 12f
                setTextColor(Color.parseColor(colorHex))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            setOnClickListener { onClick() }
        }
    }

    // ================= Edit dialogs =================
    private fun editCustomerDialog(c: Customer) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }
        val nameEdit = EditText(this).apply { setText(c.name); hint = Loc.t(this@PartyActivity, "Name", "نام") }
        val phoneEdit = EditText(this).apply { setText(c.phone); hint = Loc.t(this@PartyActivity, "Phone", "فون"); inputType = InputType.TYPE_CLASS_PHONE }
        val limitEdit = EditText(this).apply { setText(c.creditLimit.toString()); hint = Loc.t(this@PartyActivity, "Credit Limit", "کریڈٹ لیمٹ"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val openingEdit = EditText(this).apply { setText(c.openingBalance.toString()); hint = Loc.t(this@PartyActivity, "Opening Balance", "ابتدائی بیلنس"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        for (f in listOf(nameEdit, phoneEdit, limitEdit, openingEdit)) {
            container.addView(f)
            container.addView(spacer(10))
        }

        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Edit Customer", "کسٹمر میں ترمیم کریں"))
            .setView(container)
            .setPositiveButton(Loc.t(this, "Save", "محفوظ کریں")) { d, _ ->
                val newName = nameEdit.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, Loc.t(this, "Name is required", "نام ضروری ہے"), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.editCustomer(
                    existing = c,
                    name = newName,
                    phone = phoneEdit.text.toString(),
                    creditLimit = limitEdit.text.toString().toDoubleOrNull() ?: c.creditLimit,
                    openingBalance = openingEdit.text.toString().toDoubleOrNull() ?: c.openingBalance
                )
                d.dismiss()
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    private fun editSupplierDialog(s: Supplier) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }
        val nameEdit = EditText(this).apply { setText(s.name); hint = Loc.t(this@PartyActivity, "Name", "نام") }
        val phoneEdit = EditText(this).apply { setText(s.phone); hint = Loc.t(this@PartyActivity, "Phone", "فون"); inputType = InputType.TYPE_CLASS_PHONE }
        val openingEdit = EditText(this).apply { setText(s.openingBalance.toString()); hint = Loc.t(this@PartyActivity, "Opening Balance", "ابتدائی بیلنس"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        for (f in listOf(nameEdit, phoneEdit, openingEdit)) {
            container.addView(f)
            container.addView(spacer(10))
        }

        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Edit Supplier", "سپلائر میں ترمیم کریں"))
            .setView(container)
            .setPositiveButton(Loc.t(this, "Save", "محفوظ کریں")) { d, _ ->
                val newName = nameEdit.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, Loc.t(this, "Name is required", "نام ضروری ہے"), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.editSupplier(
                    existing = s,
                    name = newName,
                    phone = phoneEdit.text.toString(),
                    openingBalance = openingEdit.text.toString().toDoubleOrNull() ?: s.openingBalance
                )
                d.dismiss()
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    // ================= Delete confirmations =================
    private fun confirmDeleteCustomer(c: Customer) {
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Delete Customer", "کسٹمر حذف کریں"))
            .setMessage(Loc.t(this, "Delete ${c.name}? This cannot be undone.", "${c.name} کو حذف کریں؟ اسے واپس نہیں لایا جا سکتا۔"))
            .setPositiveButton(Loc.t(this, "Delete", "حذف کریں")) { d, _ ->
                viewModel.removeCustomer(c)
                d.dismiss()
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    private fun confirmDeleteSupplier(s: Supplier) {
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Delete Supplier", "سپلائر حذف کریں"))
            .setMessage(Loc.t(this, "Delete ${s.name}? This cannot be undone.", "${s.name} کو حذف کریں؟ اسے واپس نہیں لایا جا سکتا۔"))
            .setPositiveButton(Loc.t(this, "Delete", "حذف کریں")) { d, _ ->
                viewModel.removeSupplier(s)
                d.dismiss()
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    // ================= Customer full history =================
    private fun openCustomerHistory(c: Customer) {
        lifecycleScope.launch {
            val sales = viewModel.customerHistory(c)
            val content = historyDialogContainer(c.name, blue, "\uD83D\uDC64", c.openingBalance, c.balance)
            val body = content.getChildAt(1) as LinearLayout

            if (sales.isEmpty()) {
                body.addView(emptyCard(Loc.t(this@PartyActivity, "No sales yet", "ابھی تک کوئی سیل نہیں ہوئی")))
            } else {
                val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                // ---- Newest first, and no invoice/reference number shown — date is the identifier ----
                for (s in sales.sortedByDescending { it.createdAt }) {
                    body.addView(historyRow(fmt.format(Date(s.createdAt)), s.total, s.paid, blue))
                }
            }

            val dialog = AlertDialog.Builder(this@PartyActivity).setView(content).create()
            (content.getChildAt(2) as LinearLayout).addView(Button(this@PartyActivity).apply {
                text = Loc.t(this@PartyActivity, "Close", "بند کریں")
                setTextColor(Color.WHITE)
                background = roundedBackground(blue, 14)
                setOnClickListener { dialog.dismiss() }
            })
            dialog.show()
        }
    }

    // ================= Supplier full history =================
    private fun openSupplierHistory(s: Supplier) {
        lifecycleScope.launch {
            val purchases = viewModel.supplierHistory(s)
            val content = historyDialogContainer(s.name, orange, "\uD83D\uDCE6", s.openingBalance, s.balance)
            val body = content.getChildAt(1) as LinearLayout

            if (purchases.isEmpty()) {
                body.addView(emptyCard(Loc.t(this@PartyActivity, "No purchases yet", "ابھی تک کوئی خریداری نہیں ہوئی")))
            } else {
                val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                // ---- Newest first, and no bill number shown — date is the identifier ----
                for (p in purchases.sortedByDescending { it.createdAt }) {
                    body.addView(historyRow(fmt.format(Date(p.createdAt)), p.total, p.paid, orange))
                }
            }

            val dialog = AlertDialog.Builder(this@PartyActivity).setView(content).create()
            (content.getChildAt(2) as LinearLayout).addView(Button(this@PartyActivity).apply {
                text = Loc.t(this@PartyActivity, "Close", "بند کریں")
                setTextColor(Color.WHITE)
                background = roundedBackground(orange, 14)
                setOnClickListener { dialog.dismiss() }
            })
            dialog.show()
        }
    }

    // ================= shared dialog helpers (premium gradient header dialog) =================
    private fun historyDialogContainer(name: String, colorHex: String, icon: String, opening: Double, running: Double): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bg))
                cornerRadius = 20f
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 24, 28, 24)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(colorHex), lighten(colorHex))
            ).apply {
                cornerRadii = floatArrayOf(20f, 20f, 20f, 20f, 0f, 0f, 0f, 0f)
            }
        }
        header.addView(TextView(this).apply {
            text = icon
            textSize = 18f
            gravity = Gravity.CENTER
            background = ovalBg(cardWhite)
            width = (40 * resources.displayMetrics.density).toInt()
            height = (40 * resources.displayMetrics.density).toInt()
        })
        val headerTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 0, 0)
        }
        headerTextCol.addView(TextView(this).apply {
            text = name; textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val balRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 0) }
        balRow.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Opening", "ابتدائی") + ": Rs %.2f".format(opening)
            setTextColor(Color.parseColor("#F2F3FF")); textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        balRow.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Closing", "اختتامی") + ": Rs %.2f".format(opening + running)
            setTextColor(Color.WHITE); textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headerTextCol.addView(balRow)
        header.addView(headerTextCol)
        outer.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 8)
        }
        outer.addView(body)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 8, 20, 20)
        }
        outer.addView(footer)
        return outer
    }

    // ---- No invoice/bill reference is passed in or shown anymore — just the date,
    // total, and amount paid. Date is bold since it's now the row's identifier. ----
    private fun historyRow(date: String, total: Double, paid: Double, colorHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            background = elevatedCardBg()
            elevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }

            val top = LinearLayout(this@PartyActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this@PartyActivity).apply {
                text = date; textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#2E3242"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this@PartyActivity).apply {
                text = "Rs %.2f".format(total)
                setTextColor(Color.parseColor(colorHex))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 14f
            })
            addView(top)
            addView(TextView(this@PartyActivity).apply {
                text = Loc.t(this@PartyActivity, "Paid", "ادا شدہ") + ": Rs %.2f".format(paid)
                textSize = 11f
                setTextColor(Color.parseColor(labelGray))
                setPadding(0, 4, 0, 0)
            })
        }
    }

    private fun emptyCard(text: String) = premiumCard().apply {
        gravity = Gravity.CENTER
        setPadding(20, 24, 20, 24)
        addView(TextView(this@PartyActivity).apply {
            this.text = text
            setTextColor(Color.parseColor(labelGray))
            textSize = 13f
        })
    }

    // ---- UI helpers ----

    /** Elevated white card, light premium look with soft border + shadow. */
    private fun premiumCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 16, 20, 16)
        background = elevatedCardBg()
        elevation = 3f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) }
    }

    /** Lighter inner wrapper used for text fields inside a card. */
    private fun innerField() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 10, 18, 10)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F7F8FC"))
            cornerRadius = 10f
            setStroke(1, Color.parseColor(cardBorder))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun elevatedCardBg() = GradientDrawable().apply {
        setColor(Color.parseColor(cardWhite))
        cornerRadius = 16f
        setStroke(1, Color.parseColor(cardBorder))
    }

    private fun ovalBg(colorHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
    }

    private fun roundedBackground(colorHex: String, cornerRadius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        this.cornerRadius = cornerRadius.toFloat()
    }

    private fun lighten(colorHex: String): Int {
        val c = Color.parseColor(colorHex)
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] *= 0.7f
        hsv[2] = (hsv[2] * 1.15f).coerceAtMost(1f)
        return Color.HSVToColor(hsv)
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(0xFFEEEEEE.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        }
    }
}
