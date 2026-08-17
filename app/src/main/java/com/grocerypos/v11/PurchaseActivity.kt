package com.grocerypos.v11.ui

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class PurchaseLine(
    val itemName: String,
    val barcode: String?,
    val qty: Double,
    val unit: String,
    val rate: Double,
    val amount: Double,
    val mainUnit: String,
    val secondaryUnit: String,
    val secondaryUnitQty: Double,
    val tertiaryUnit: String = "",
    val tertiaryUnitQty: Double = 0.0
)

// ---- Unit-conversion helpers: a PurchaseLine may be entered in the secondary or
// tertiary unit (e.g. "dozen" or "grams" while the product's stock is tracked in
// "pcs"). These convert the entered qty back to the product's main unit before it
// touches the DB / stock. Chain: 1 main = secondaryUnitQty secondary;
// 1 secondary = tertiaryUnitQty tertiary. ----
private fun PurchaseLine.isSecondary(): Boolean =
    secondaryUnit.isNotEmpty() && unit == secondaryUnit && secondaryUnitQty > 0

private fun PurchaseLine.isTertiary(): Boolean =
    tertiaryUnit.isNotEmpty() && unit == tertiaryUnit && tertiaryUnitQty > 0 && secondaryUnitQty > 0

private fun PurchaseLine.mainUnitQty(): Double = when {
    isTertiary() -> qty / (secondaryUnitQty * tertiaryUnitQty)
    isSecondary() -> qty / secondaryUnitQty
    else -> qty
}

private fun PurchaseLine.mainUnitRate(): Double = when {
    isTertiary() -> rate * secondaryUnitQty * tertiaryUnitQty
    isSecondary() -> rate * secondaryUnitQty
    else -> rate
}

private fun genBillNo(): String = "PUR" + System.currentTimeMillis()

class PurchaseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BILL_NO = "billNo"
        private const val PREFS_NAME = "purchase_draft_prefs"
        private const val KEY_DRAFT = "draft_json"
    }

    // ================= NAVY + TEAL + WHITE PALETTE =================
    private val bg = "#F4F6F8"
    private val cardWhite = "#FFFFFF"
    private val navy = "#0F9B8E"     // primary brand — header, Save button (swapped with teal)
    private val teal = "#0B2545"     // secondary accent — chips, Add Item, totals, "+" icons (swapped with navy)
    private val textDark = "#0B2545" // headings/values reuse navy
    private val textMuted = "#7C8798"
    private val border = "#E3E8EE"
    private val red = "#E5484D"      // functional — remove / delete / negative balance
    private val amberBadge = "#EEF2F6"

    private lateinit var dateValueText: TextView
    private lateinit var firmNameText: TextView
    private lateinit var supplierBalanceText: TextView
    private lateinit var partyName: AutoCompleteTextView
    private lateinit var itemEntrySection: LinearLayout
    private lateinit var addItemsTrigger: TextView
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var unitToggleRow: LinearLayout
    private lateinit var rate: EditText
    private lateinit var conversionInfo: TextView
    private lateinit var totalAmountText: TextView
    private lateinit var addItemButton: Button
    private lateinit var billedItemsHeader: LinearLayout
    private lateinit var billedItemsChevron: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var grandTotalText: TextView
    private lateinit var paymentSection: LinearLayout
    private lateinit var paidInput: EditText
    private lateinit var dueAmountText: TextView
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button
    private lateinit var overflowButton: TextView
    private lateinit var scrollArea: ScrollView

    private var suppliers = listOf<Supplier>()
    private var products = listOf<Product>()
    private var allUnits = listOf("pcs", "kg", "box", "dozen")
    private val lines = mutableListOf<PurchaseLine>()
    private var purchaseDateMillis = System.currentTimeMillis()
    private var selectedProduct: Product? = null
    private var itemsExpanded = true

    // ---- tracks the last-entered quantity converted to the product's MAIN unit, so
    // switching the unit chip (e.g. bag -> kg) can auto-convert the displayed qty
    // instead of leaving the old number sitting under the new unit. ----
    private var lastMainQty: Double = 0.0
    private var suppressQtyWatcher = false

    // ---- same idea for Rate: tracks the last-entered rate converted to a "per MAIN
    // unit" price, so switching the unit chip auto-converts the rate too (e.g. typing
    // 10000 while on "bag", where 1 bag = 50 kg, then switching to "kg" auto-fills 200
    // instead of leaving a stale 10000 under "kg"). ----
    private var lastMainRate: Double = 0.0
    private var suppressRateWatcher = false

    private var editBillNo: String? = null
    private var originalPurchase: Purchase? = null
    private var originalItems: List<PurchaseItem> = emptyList()

    // ---- draft persistence: guards against process death (e.g. fingerprint prompt,
    // switching apps, or the OS killing the app in the background) wiping out an
    // in-progress purchase that hasn't been saved yet. ----
    private var suppressDraftSave = false
    private var draftRestored = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        editBillNo = intent.getStringExtra(EXTRA_BILL_NO)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 28)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER (flat navy, teal chip + overflow) =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 22, 22)
            background = roundedBg(navy, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
            applyElevation(this, 6f)
        }
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = if (editBillNo != null) com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Edit Purchase", "خریداری میں ترمیم") else com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Purchase", "خریداری")
            textSize = 18.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = "Stock In / Supplier Billing"
            textSize = 11f
            setTextColor(Color.parseColor("#9FB4CC"))
            setPadding(0, 3, 0, 0)
        })
        header.addView(headerCol)
        header.addView(pillChip("History") {
            startActivity(Intent(this@PurchaseActivity, PurchaseHistoryActivity::class.java))
        })
        header.addView(spacer(8).apply {
            layoutParams = LinearLayout.LayoutParams((8 * resources.displayMetrics.density).toInt(), 1)
        })
        overflowButton = TextView(this).apply {
            text = "\u22EE"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(18, 8, 6, 8)
            setOnClickListener { showOverflowMenu(it) }
        }
        header.addView(overflowButton)
        root.addView(header)

        // ================= DATE (now sits right below the "Purchase" header) =================
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 14) }
        }
        val dateChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 10, 20, 10)
            background = strokedBg(border, cardWhite, 30)
            setOnClickListener { openDatePicker() }
        }
        dateValueText = TextView(this).apply {
            text = formatDate(purchaseDateMillis)
            textSize = 13f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        dateChip.addView(dateValueText)
        dateChip.addView(TextView(this).apply {
            text = " \u203A"
            textSize = 13f
            setTextColor(Color.parseColor(teal))
        })
        topRow.addView(dateChip)
        root.addView(topRow)

        // ================= FIRM NAME + PARTY BALANCE (stacked: balance right under the firm name) =================
        val firmBox = premiumCard().apply { setPadding(20, 14, 20, 14) }
        val firmCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        firmCol.addView(TextView(this).apply { text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Firm Name", "فرم کا نام"); textSize = 10f; setTextColor(Color.parseColor(textMuted)) })
        firmNameText = TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 13f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        firmCol.addView(firmNameText)
        firmCol.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Party Balance", "پارٹی بیلنس")
            textSize = 10f
            setTextColor(Color.parseColor(textMuted))
            setPadding(0, 8, 0, 0)
        })
        supplierBalanceText = TextView(this).apply {
            text = "Rs 0.00"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(textMuted))
        }
        firmCol.addView(supplierBalanceText)
        firmBox.addView(firmCol)
        root.addView(firmBox)

        // ================= PARTY NAME =================
        val partyBox = premiumCard()
        partyBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Party / Supplier", "پارٹی / سپلائر")))
        val partyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        partyName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Party Name (Supplier) *", "پارٹی کا نام (سپلائر) *")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        partyRow.addView(partyName)
        partyRow.addView(circleIcon("+", teal, 30) { promptAddSupplier() })
        partyBox.addView(partyRow)
        root.addView(partyBox)
        root.addView(spacer(18))

        // ================= ITEM ENTRY (Add Item button, always visible — no collapse gate) =================
        itemEntrySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
            setPadding(22, 20, 22, 20)
            background = strokedBg(border, cardWhite, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
            applyElevation(this, 2f)
        }

        addItemsTrigger = TextView(this).apply {
            text = "\u2795  " + com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Add Item", "آئٹم شامل کریں")
            textSize = 14.5f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 14)
        }
        itemEntrySection.addView(addItemsTrigger)

        val itemBox = innerField()
        itemBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Item Name", "آئٹم کا نام")))
        val itemNameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        itemName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Type to search…", "تلاش کے لیے لکھیں…")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        itemNameRow.addView(itemName)
        // Quick "add product" — lets a purchase item that isn't in Products yet get
        // saved directly from here, with full Primary/Secondary/Tertiary unit setup.
        itemNameRow.addView(circleIcon("+", teal, 30) { openAddProductDialog(itemName.text.toString().trim()) })
        itemBox.addView(itemNameRow)
        itemEntrySection.addView(itemBox)

        unitToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        itemEntrySection.addView(unitToggleRow)
        itemEntrySection.addView(spacer(10))

        val qtyUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qtyBox = innerField().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) }
        }
        qtyBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Quantity", "مقدار")))
        qty = EditText(this).apply {
            hint = "0"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { rate.requestFocus(); true } else false
            }
        }
        qtyBox.addView(qty)
        val unitBox = innerField().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
        }
        unitBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Unit", "یونٹ")))
        unitSpinner = Spinner(this)
        unitBox.addView(unitSpinner)
        qtyUnitRow.addView(qtyBox)
        qtyUnitRow.addView(unitBox)
        itemEntrySection.addView(qtyUnitRow)
        itemEntrySection.addView(spacer(10))

        val rateBox = innerField()
        rateBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Rate", "ریٹ")))
        rate = EditText(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Price / Unit", "قیمت / یونٹ")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            // Bulk entry: pressing Done on the keyboard adds the item immediately —
            // no need to reach for the ADD ITEM button for every single line when
            // punching in 10-20 items from one supplier.
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { addItem(); true } else false
            }
        }
        rateBox.addView(rate)
        itemEntrySection.addView(rateBox)

        conversionInfo = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(6, 10, 0, 0)
            visibility = View.GONE
        }
        itemEntrySection.addView(conversionInfo)

        totalAmountText = TextView(this).apply {
            text = "Total Amount: Rs 0.00"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(teal))
            setPadding(6, 10, 0, 10)
        }
        itemEntrySection.addView(totalAmountText)

        // ---- Qty watcher also records the entered qty converted to the product's
        // MAIN unit, so switching unit chips can auto-convert the number shown. ----
        qty.addTextChangedListener(simpleWatcher {
            if (!suppressQtyWatcher) {
                val entered = qty.text.toString().toDoubleOrNull() ?: 0.0
                lastMainQty = toMainUnitQty(entered)
            }
            updateLineTotal()
        })
        // ---- Rate watcher records the entered rate converted to a "per MAIN unit"
        // price, so switching unit chips can auto-convert the rate shown (e.g. 1 bag =
        // 50 kg, rate 10000 typed on "bag" -> switching to "kg" auto-fills 200). ----
        rate.addTextChangedListener(simpleWatcher {
            if (!suppressRateWatcher) {
                val entered = rate.text.toString().toDoubleOrNull() ?: 0.0
                lastMainRate = toMainUnitRate(entered)
            }
            updateLineTotal()
        })

        addItemButton = Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "ADD ITEM", "آئٹم شامل کریں")
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(teal, 14)
            setPadding(0, 24, 0, 24)
            setOnClickListener { addItem() }
        }
        itemEntrySection.addView(addItemButton)
        root.addView(itemEntrySection)

        // ================= BILLED ITEMS (collapsible header + numbered list) =================
        billedItemsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 14, 20, 14)
            background = roundedBg(teal, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 0) }
            visibility = View.GONE
            setOnClickListener { toggleBilledItems() }
        }
        billedItemsHeader.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Billed Items", "بل شدہ آئٹمز")
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        billedItemsChevron = TextView(this).apply {
            text = "\u25BE"
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        billedItemsHeader.addView(billedItemsChevron)
        root.addView(billedItemsHeader)

        itemsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
        }
        root.addView(itemsContainer)
        root.addView(spacer(14))

        // ================= TOTAL / PAID / DUE — one clean summary card =================
        val totalCard = premiumCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 18, 22, 18)
        }
        totalCard.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Total Amount", "کل رقم").uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        grandTotalText = TextView(this).apply {
            text = "Rs 0"
            textSize = 19f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        totalCard.addView(grandTotalText)
        root.addView(totalCard)

        // ---- PAID AMOUNT — its own dedicated card so it's easy to find and edit,
        // especially right after updating a purchase (see savePurchase()). No
        // Cash/Credit toggle — it's on credit whenever Amount Paid is less than the
        // total; leaving Paid blank means fully on credit (0 paid). ----
        paymentSection = premiumCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 16, 20, 16)
        }
        paymentSection.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Paid Amount", "ادا شدہ رقم").uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        paymentSection.addView(TextView(this).apply {
            text = "Rs "
            textSize = 16f
            setTextColor(Color.parseColor(navy))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        paidInput = EditText(this).apply {
            hint = "0"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(navy))
            background = null
            textSize = 17f
            gravity = Gravity.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minWidth = (90 * resources.displayMetrics.density).toInt()
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(0, 0, 0, 0)
        }
        paymentSection.addView(paidInput)
        root.addView(paymentSection)

        // ---- DUE AMOUNT — read-only, auto-computed (Total − Paid), its own card so it
        // reads as clearly as Total/Paid instead of being squeezed into a corner. ----
        val dueCard = premiumCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 16, 20, 16)
        }
        dueCard.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Due Amount", "باقی رقم").uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        dueAmountText = TextView(this).apply {
            text = "Rs 0"
            textSize = 17f
            setTextColor(Color.parseColor(navy))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        dueCard.addView(dueAmountText)
        root.addView(dueCard)
        root.addView(spacer(4))

        val totalsWatcher = simpleWatcher { updateGrandTotal() }
        paidInput.addTextChangedListener(totalsWatcher)

        // ================= EDIT + SAVE + DELETE (fixed bottom bar) =================
        saveButton = Button(this).apply {
            text = if (editBillNo != null) com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "UPDATE PURCHASE", "خریداری اپ ڈیٹ کریں") else com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "SAVE PURCHASE", "خریداری محفوظ کریں")
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(navy, 16)
            setPadding(0, 26, 0, 26)
            setOnClickListener { savePurchase() }
            applyElevation(this, 4f)
        }
        deleteButton = Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "DELETE", "حذف کریں")
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(red, 16)
            setPadding(0, 26, 0, 26)
            visibility = if (editBillNo != null) View.VISIBLE else View.GONE
            setOnClickListener { confirmDeletePurchase() }
        }
        // ---- No separate EDIT button: an existing bill opened from History is directly
        // editable right away, same as a brand-new purchase. ----
        val saveDeleteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveDeleteRow.addView(
            saveButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(if (editBillNo != null) 0 else 0, 0, 8, 0)
            }
        )
        saveDeleteRow.addView(
            deleteButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply { setMargins(8, 0, 0, 0) }
        )

        scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(root)
        }

        val saveBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 14, 24, 18)
            setBackgroundColor(Color.parseColor(cardWhite))
            applyElevation(this, 8f)
            addView(saveDeleteRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        // ---- Handles both system bars AND the on-screen keyboard (IME). Without the
        // ime() inset, the keyboard used to sit on top of Save/Delete, making them
        // unreachable while a field was focused (e.g. right after typing the Rate and
        // hitting the ADD ITEM flow, or when the keyboard was still up at Save time). ----
        ViewCompat.setOnApplyWindowInsetsListener(saveBar) { view, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(sysBars.bottom, ime.bottom)
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 18 + bottomInset)
            insets
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
            addView(scrollArea)
            addView(saveBar)
        })

        loadSuppliers()
        loadUnits()
        loadProducts()
        loadFirmName()
        editBillNo?.let { loadForEdit(it) }

        itemName.setOnItemClickListener { _, _, position, _ ->
            val pickedName = itemName.adapter.getItem(position).toString()
            onItemPicked(pickedName)
        }
        itemName.addTextChangedListener(simpleWatcher {
            val match = products.find { it.name.equals(itemName.text.toString().trim(), ignoreCase = true) }
            if (match == null) {
                selectedProduct = null
                unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, allUnits)
                conversionInfo.visibility = View.GONE
                unitToggleRow.visibility = View.GONE
            }
        })

        unitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                // FIX: unit switch should only auto-recalculate Rate (and therefore
                // Amount) — the typed Quantity number must stay exactly as entered.
                // refillAutoQty() used to overwrite it here, silently changing the
                // qty the user typed whenever they switched the unit chip.
                refillAutoRate()
                updateLineTotal()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        partyName.setOnItemClickListener { _, _, position, _ ->
            val pickedName = partyName.adapter.getItem(position).toString()
            updateSupplierBalanceDisplay(pickedName)
        }
        partyName.addTextChangedListener(simpleWatcher {
            updateSupplierBalanceDisplay(partyName.text.toString().trim())
        })

        // ---- Restore an unsaved draft (new purchase only — edit mode loads its own
        // data via loadForEdit). This recovers in-progress entries that were lost when
        // the OS killed the app in the background (fingerprint prompt, app switch,
        // low memory, etc.) before the user could tap Save. ----
        if (editBillNo == null) {
            restoreDraftIfAny()
        }
    }

    override fun onPause() {
        super.onPause()
        // Snapshot whatever is currently on screen so a process death while backgrounded
        // (fingerprint unlock, switching to another app, OS reclaiming memory) doesn't
        // wipe out an in-progress purchase. Only relevant for a brand-new purchase —
        // edits reload from the DB via loadForEdit(), and once a bill is actually saved
        // the draft is cleared (see savePurchase()/clearDraft()).
        if (editBillNo == null && !suppressDraftSave) {
            saveDraft()
        }
    }

    // ---- Draft persistence (SharedPreferences, JSON-encoded) ----

    private fun draftPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveDraft() {
        // Nothing worth saving if the form is effectively empty.
        val hasContent = lines.isNotEmpty() ||
            partyName.text.toString().isNotBlank() ||
            itemName.text.toString().isNotBlank() ||
            qty.text.toString().isNotBlank() ||
            rate.text.toString().isNotBlank()
        if (!hasContent) {
            clearDraft()
            return
        }

        val linesArray = JSONArray()
        lines.forEach { line ->
            linesArray.put(JSONObject().apply {
                put("itemName", line.itemName)
                put("barcode", line.barcode ?: "")
                put("qty", line.qty)
                put("unit", line.unit)
                put("rate", line.rate)
                put("amount", line.amount)
                put("mainUnit", line.mainUnit)
                put("secondaryUnit", line.secondaryUnit)
                put("secondaryUnitQty", line.secondaryUnitQty)
                put("tertiaryUnit", line.tertiaryUnit)
                put("tertiaryUnitQty", line.tertiaryUnitQty)
            })
        }

        val draft = JSONObject().apply {
            put("party", partyName.text.toString())
            put("paid", paidInput.text.toString())
            put("dateMillis", purchaseDateMillis)
            put("pendingItemName", itemName.text.toString())
            put("pendingQty", qty.text.toString())
            put("pendingRate", rate.text.toString())
            put("lines", linesArray)
        }

        draftPrefs().edit().putString(KEY_DRAFT, draft.toString()).apply()
    }

    private fun clearDraft() {
        draftPrefs().edit().remove(KEY_DRAFT).apply()
    }

    private fun restoreDraftIfAny() {
        val raw = draftPrefs().getString(KEY_DRAFT, null) ?: return
        val draft = try { JSONObject(raw) } catch (e: Exception) { null } ?: return
        if (draftRestored) return
        draftRestored = true
        suppressDraftSave = true

        try {
            val party = draft.optString("party", "")
            if (party.isNotBlank()) {
                partyName.setText(party)
                updateSupplierBalanceDisplay(party)
            }
            val paid = draft.optString("paid", "")
            if (paid.isNotBlank()) paidInput.setText(paid)
            val savedDate = draft.optLong("dateMillis", 0L)
            if (savedDate > 0L) {
                purchaseDateMillis = savedDate
                dateValueText.text = formatDate(purchaseDateMillis)
            }

            val linesArray = draft.optJSONArray("lines")
            if (linesArray != null) {
                for (i in 0 until linesArray.length()) {
                    val o = linesArray.getJSONObject(i)
                    lines.add(
                        PurchaseLine(
                            itemName = o.optString("itemName"),
                            barcode = o.optString("barcode").ifBlank { null },
                            qty = o.optDouble("qty", 0.0),
                            unit = o.optString("unit"),
                            rate = o.optDouble("rate", 0.0),
                            amount = o.optDouble("amount", 0.0),
                            mainUnit = o.optString("mainUnit"),
                            secondaryUnit = o.optString("secondaryUnit"),
                            secondaryUnitQty = o.optDouble("secondaryUnitQty", 0.0),
                            tertiaryUnit = o.optString("tertiaryUnit"),
                            tertiaryUnitQty = o.optDouble("tertiaryUnitQty", 0.0)
                        )
                    )
                }
                renderItemsList()
                updateGrandTotal()
            }

            // Restore whatever was mid-entry in the item-entry row (not yet added as a line).
            val pendingItemName = draft.optString("pendingItemName", "")
            if (pendingItemName.isNotBlank()) {
                itemName.setText(pendingItemName)
                val match = products.find { it.name.equals(pendingItemName, ignoreCase = true) }
                if (match != null) applyPickedProduct(match)
            }
            val pendingQty = draft.optString("pendingQty", "")
            if (pendingQty.isNotBlank()) qty.setText(pendingQty)
            val pendingRate = draft.optString("pendingRate", "")
            if (pendingRate.isNotBlank()) rate.setText(pendingRate)
            updateLineTotal()

            if (lines.isNotEmpty() || pendingItemName.isNotBlank()) {
                Toast.makeText(
                    this,
                    com.grocerypos.v11.util.Loc.t(this, "Restored your unsaved purchase draft", "آپ کا غیر محفوظ شدہ ڈرافٹ بحال کر دیا گیا"),
                    Toast.LENGTH_LONG
                ).show()
            }
        } finally {
            suppressDraftSave = false
        }
    }

    /** Keeps the "Firm Name" card in sync with Settings > Shop Information > Shop Name. */
    private fun loadFirmName() {
        lifecycleScope.launch {
            val savedName = PosDatabase.get(this@PurchaseActivity).appSettingDao().get("shop_name")?.value
            if (!savedName.isNullOrBlank()) firmNameText.text = savedName
        }
    }

    /** Shows the selected supplier's current outstanding balance top-right. */
    private fun updateSupplierBalanceDisplay(name: String) {
        val supplier = suppliers.find { it.name.equals(name, ignoreCase = true) }
        if (supplier == null) {
            supplierBalanceText.text = "Rs 0.00"
            supplierBalanceText.setTextColor(Color.parseColor(textMuted))
            return
        }
        supplierBalanceText.text = "Rs %.2f".format(supplier.balance)
        supplierBalanceText.setTextColor(Color.parseColor(if (supplier.balance > 0) red else teal))
    }

    // ---- UI helpers ----
    private fun premiumCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 16, 22, 16)
        background = strokedBg(border, cardWhite, 16)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 14) }
        applyElevation(this, 2f)
    }

    private fun outlinedBox() = premiumCard()

    private fun innerField() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 12, 18, 12)
        background = strokedBg(border, "#FAFBFC", 12)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) }
    }

    /** Small uppercase muted micro-label. */
    private fun labelRow(label: String) = TextView(this).apply {
        text = label.uppercase()
        textSize = 10.5f
        setTextColor(Color.parseColor(textMuted))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, 6)
        letterSpacing = 0.03f
    }

    private fun pillChip(label: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = label
        textSize = 12.5f
        setTextColor(Color.parseColor(navy))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = roundedBg(cardWhite, 30)
        setPadding(24, 12, 24, 12)
        setOnClickListener { onClick() }
    }

    private fun circleIcon(label: String, colorHex: String, sizeDp: Int, onClick: (() -> Unit)? = null) = TextView(this).apply {
        this.text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        width = px; height = px
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun toggleBilledItems() {
        itemsExpanded = !itemsExpanded
        itemsContainer.visibility = if (itemsExpanded) View.VISIBLE else View.GONE
        billedItemsChevron.text = if (itemsExpanded) "\u25BE" else "\u25B8"
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(com.grocerypos.v11.util.Loc.t(this, "Print", "پرنٹ"))
        popup.menu.add(com.grocerypos.v11.util.Loc.t(this, "Share", "شیئر کریں"))
        popup.setOnMenuItemClickListener { item ->
            val billNo = editBillNo
            if (billNo == null) {
                Toast.makeText(this, "Save the purchase first", Toast.LENGTH_SHORT).show()
            } else {
                openBillPreview(billNo, forSaving = false)
            }
            true
        }
        popup.show()
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
    }

    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }

    private fun ovalBg(colorHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
    }

    /** Adds a soft elevation/shadow to a view that has a rounded background (API 21+). */
    private fun applyElevation(view: View, dp: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.elevation = dp * resources.displayMetrics.density
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }

    private fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange()
    }

    private fun formatDate(millis: Long) = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(millis))

    private fun formatQty(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = purchaseDateMillis }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            purchaseDateMillis = cal.timeInMillis
            dateValueText.text = formatDate(purchaseDateMillis)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ---- Load an existing bill into the form for editing ----
    private fun loadForEdit(bill: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)
            val purchase = db.purchaseDao().findPurchase(bill) ?: return@launch
            val items = db.purchaseDao().itemsForBill(bill)
            originalPurchase = purchase
            originalItems = items

            purchaseDateMillis = purchase.createdAt
            dateValueText.text = formatDate(purchaseDateMillis)

            val supplierName = purchase.supplierId?.let { id ->
                db.supplierDao().all().first().find { it.id == id }?.name
            } ?: ""
            partyName.setText(supplierName)
            updateSupplierBalanceDisplay(supplierName)

            paidInput.setText(if (purchase.paid > 0) Math.round(purchase.paid).toString() else "")

            lines.clear()
            items.forEach { pi ->
                val product = db.productDao().find(pi.barcode)
                lines.add(
                    PurchaseLine(
                        itemName = product?.name ?: pi.barcode,
                        barcode = pi.barcode,
                        qty = pi.qty.toDouble(),
                        unit = pi.unit.ifBlank { product?.unit ?: "" },
                        rate = pi.unitCost,
                        amount = pi.amount,
                        mainUnit = product?.unit ?: "",
                        secondaryUnit = product?.secondaryUnit ?: "",
                        secondaryUnitQty = product?.secondaryUnitQty ?: 0.0,
                        tertiaryUnit = product?.tertiaryUnit ?: "",
                        tertiaryUnitQty = product?.tertiaryUnitQty ?: 0.0
                    )
                )
            }
            renderItemsList()
            updateGrandTotal()
            deleteButton.visibility = View.VISIBLE
        }
    }

    // ---- Data loading ----
    private fun loadSuppliers() {
        lifecycleScope.launch {
            PosDatabase.get(this@PurchaseActivity).supplierDao().all().collectLatest { list ->
                suppliers = list
                partyName.setAdapter(ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_dropdown_item_1line, list.map { it.name }))
                updateSupplierBalanceDisplay(partyName.text.toString().trim())
            }
        }
    }

    private fun loadUnits() {
        lifecycleScope.launch {
            PosDatabase.get(this@PurchaseActivity).unitDao().all().collectLatest { list ->
                allUnits = (listOf("pcs", "kg", "box", "dozen") + list.map { it.name }).distinct()
                if (selectedProduct == null) {
                    unitSpinner.adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, allUnits)
                }
            }
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            PosDatabase.get(this@PurchaseActivity).productDao().all().collectLatest { list ->
                products = list
                itemName.setAdapter(ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_dropdown_item_1line, list.map { it.name }))
            }
        }
    }

    // ---- Item entry logic ----
    // Unit chips now offer up to three tiers: main, secondary, and (if a secondary
    // chain exists) tertiary — mirroring SaleActivity.
    private fun onItemPicked(pickedName: String) {
        val product = products.find { it.name.equals(pickedName, ignoreCase = true) } ?: return
        applyPickedProduct(product)
    }

    /** Shared by onItemPicked() (existing product chosen from the dropdown) and by
     *  openAddProductDialog() (brand-new product just created) — sets up unit chips,
     *  conversion info, and auto-filled rate for whichever product is now selected. */
    private fun applyPickedProduct(product: Product) {
        selectedProduct = product

        val unitOptions = mutableListOf(product.unit)
        if (product.secondaryUnit.isNotBlank() && product.secondaryUnit != product.unit) {
            unitOptions.add(product.secondaryUnit)
            if (product.tertiaryUnit.isNotBlank() && product.tertiaryUnit != product.unit &&
                product.tertiaryUnit != product.secondaryUnit && product.tertiaryUnitQty > 0) {
                unitOptions.add(product.tertiaryUnit)
            }
        }
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitOptions)
        buildUnitChips(unitOptions, product.unit)

        conversionInfo.text = buildString {
            if (unitOptions.contains(product.secondaryUnit) && product.secondaryUnitQty > 0) {
                append("1 ${product.unit} = ${product.secondaryUnitQty} ${product.secondaryUnit}")
            }
            if (unitOptions.contains(product.tertiaryUnit) && product.tertiaryUnitQty > 0) {
                if (isNotEmpty()) append("   •   ")
                append("1 ${product.secondaryUnit} = ${product.tertiaryUnitQty} ${product.tertiaryUnit}")
            }
        }
        conversionInfo.visibility = if (conversionInfo.text.isNotEmpty()) View.VISIBLE else View.GONE

        lastMainQty = 0.0
        lastMainRate = 0.0
        refillAutoRate()
        updateLineTotal()

        // Fast bulk entry: jump straight to Quantity after picking the item.
        qty.setText("")
        qty.requestFocus()
    }

    // ---- Converts an entered quantity (in whichever unit is currently selected) to
    // the product's MAIN unit, and back. Used to auto-convert the Quantity field when
    // the unit chip is switched (e.g. 2 bag -> auto-shows 100 kg if 1 bag = 50 kg). ----
    private fun toMainUnitQty(entered: Double): Double {
        val product = selectedProduct ?: return entered
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        return when {
            chosenUnit == product.tertiaryUnit && product.tertiaryUnit.isNotEmpty() &&
                product.tertiaryUnitQty > 0 && product.secondaryUnitQty > 0 ->
                entered / (product.secondaryUnitQty * product.tertiaryUnitQty)
            chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0 ->
                entered / product.secondaryUnitQty
            else -> entered
        }
    }

    private fun fromMainUnitQty(mainQty: Double, chosenUnit: String): Double {
        val product = selectedProduct ?: return mainQty
        return when {
            chosenUnit == product.tertiaryUnit && product.tertiaryUnit.isNotEmpty() &&
                product.tertiaryUnitQty > 0 && product.secondaryUnitQty > 0 ->
                mainQty * product.secondaryUnitQty * product.tertiaryUnitQty
            chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0 ->
                mainQty * product.secondaryUnitQty
            else -> mainQty
        }
    }

    /** Re-writes the Quantity field to match the newly-selected unit, converted from
     *  whatever was last entered — e.g. typing 2 while on "bag" then switching to "kg"
     *  (1 bag = 50 kg) auto-fills 100 instead of leaving a stale "2" under "kg". */
    private fun refillAutoQty() {
        if (lastMainQty <= 0) return
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: return
        val converted = fromMainUnitQty(lastMainQty, chosenUnit)
        suppressQtyWatcher = true
        qty.setText(formatQty(converted))
        qty.setSelection(qty.text.length)
        suppressQtyWatcher = false
    }

    // ---- Converts an entered "price per currently-selected unit" to a "price per
    // MAIN unit", and back — mirrors toMainUnitQty()/fromMainUnitQty() but inverted
    // (price scales the opposite way qty does: smaller unit -> smaller price). ----
    private fun toMainUnitRate(entered: Double): Double {
        val product = selectedProduct ?: return entered
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        return when {
            chosenUnit == product.tertiaryUnit && product.tertiaryUnit.isNotEmpty() &&
                product.tertiaryUnitQty > 0 && product.secondaryUnitQty > 0 ->
                entered * product.secondaryUnitQty * product.tertiaryUnitQty
            chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0 ->
                entered * product.secondaryUnitQty
            else -> entered
        }
    }

    private fun fromMainUnitRate(mainRate: Double, chosenUnit: String): Double {
        val product = selectedProduct ?: return mainRate
        return when {
            chosenUnit == product.tertiaryUnit && product.tertiaryUnit.isNotEmpty() &&
                product.tertiaryUnitQty > 0 && product.secondaryUnitQty > 0 ->
                mainRate / (product.secondaryUnitQty * product.tertiaryUnitQty)
            chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0 ->
                mainRate / product.secondaryUnitQty
            else -> mainRate
        }
    }

    // ---- Rate adjusts to the chosen unit. Picking the product suggests the main-unit
    // cost as a starting point; but once the user types their OWN rate (e.g. 10000 for
    // 1 bag), lastMainRate takes over so switching units converts proportionally from
    // what was actually entered, instead of snapping back to the product's saved cost.
    // Example: 1 bag = 50 kg, user types 10000 while on "bag" -> switching to "kg"
    // auto-fills 200 (10000 / 50), and switching back to "bag" restores 10000. ----
    private fun refillAutoRate() {
        val product = selectedProduct ?: return
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        val base = if (lastMainRate > 0) lastMainRate else product.cost
        val r = fromMainUnitRate(base, chosenUnit)
        suppressRateWatcher = true
        rate.setText(if (r > 0) "%.2f".format(r) else "")
        suppressRateWatcher = false
    }

    private fun buildUnitChips(options: List<String>, selected: String) {
        unitToggleRow.removeAllViews()
        if (options.size < 2) {
            unitToggleRow.visibility = View.GONE
            return
        }
        unitToggleRow.visibility = View.VISIBLE
        options.forEachIndexed { index, unitLabel ->
            val isSelected = unitLabel == selected
            val chip = TextView(this).apply {
                text = unitLabel
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(28, 12, 28, 12)
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor(teal))
                background = if (isSelected) roundedBg(teal, 30) else strokedBg(teal, cardWhite, 30)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(if (index == 0) 0 else 8, 0, 0, 0) }
                setOnClickListener {
                    unitSpinner.setSelection(options.indexOf(unitLabel))
                    buildUnitChips(options, unitLabel)
                    // FIX: don't auto-change the typed Quantity when the unit chip is
                    // tapped — only Rate/Amount should recalculate (see onItemSelected
                    // above for the full explanation).
                    refillAutoRate()
                    updateLineTotal()
                }
            }
            unitToggleRow.addView(chip)
        }
    }

    private fun updateLineTotal() {
        val q = qty.text.toString().toDoubleOrNull() ?: 0.0
        val r = rate.text.toString().toDoubleOrNull() ?: 0.0
        val amount = Math.round(q * r).toDouble()
        totalAmountText.text = "Total Amount: Rs %.0f".format(amount)
    }

    private fun addItem() {
        val enteredName = itemName.text.toString().trim()
        val q = qty.text.toString().toDoubleOrNull()
        val r = rate.text.toString().toDoubleOrNull()
        val unit = (unitSpinner.selectedItem as? String) ?: "pcs"

        if (enteredName.isEmpty()) { itemName.error = "Required"; return }
        if (q == null || q <= 0) { qty.error = "Enter quantity"; return }
        if (r == null || r < 0) { rate.error = "Enter rate"; return }

        val product = selectedProduct ?: products.find { it.name.equals(enteredName, ignoreCase = true) }
        if (product == null) {
            // Not an existing product — open the quick "Add Product" dialog (prefilled
            // with what was typed) instead of blocking the user with an error.
            openAddProductDialog(enteredName)
            return
        }

        val line = PurchaseLine(
            itemName = product.name,
            barcode = product.barcode,
            qty = q,
            unit = unit,
            rate = r,
            amount = Math.round(q * r).toDouble(),
            mainUnit = product.unit,
            secondaryUnit = product.secondaryUnit,
            secondaryUnitQty = product.secondaryUnitQty,
            tertiaryUnit = product.tertiaryUnit,
            tertiaryUnitQty = product.tertiaryUnitQty
        )
        lines.add(line)
        renderItemsList()
        updateGrandTotal()

        itemName.setText("")
        qty.setText("")
        rate.setText("")
        selectedProduct = null
        lastMainQty = 0.0
        lastMainRate = 0.0
        conversionInfo.visibility = View.GONE
        unitToggleRow.visibility = View.GONE
        totalAmountText.text = "Total Amount: Rs 0"
        itemName.requestFocus()

        if (editBillNo == null) saveDraft()
    }

    /** Renders the numbered "#1, #2, …" Billed Items cards — matches the reference
     *  screenshot: bold item name + amount on top, "Item Subtotal: qty unit x rate = Rs amount"
     *  underneath. The whole section is hidden until at least one line is added. */
    private fun renderItemsList() {
        itemsContainer.removeAllViews()
        billedItemsHeader.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        if (!itemsExpanded) return

        lines.forEachIndexed { index, line ->
            val row = premiumCard()
            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

            val badge = TextView(this).apply {
                text = "#${index + 1}"
                textSize = 11.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(navy))
                background = strokedBg(border, amberBadge, 8)
                setPadding(14, 4, 14, 4)
            }
            topRow.addView(badge)
            topRow.addView(TextView(this).apply {
                text = "  ${line.itemName}"
                textSize = 14.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            topRow.addView(TextView(this).apply {
                text = "Rs %.0f".format(line.amount)
                textSize = 14.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
            })
            topRow.addView(TextView(this).apply {
                text = "  \u2715"
                textSize = 14f
                setTextColor(Color.parseColor(red))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    lines.removeAt(index)
                    renderItemsList()
                    updateGrandTotal()
                    if (editBillNo == null) saveDraft()
                }
            })
            row.addView(topRow)

            row.addView(TextView(this).apply {
                text = "Item Subtotal"
                textSize = 10.5f
                setTextColor(Color.parseColor(textMuted))
                setPadding(0, 6, 0, 2)
            })
            row.addView(TextView(this).apply {
                text = "${formatQty(line.qty)} ${line.unit} x ${line.rate} = Rs %.0f".format(line.amount)
                textSize = 12.5f
                setTextColor(Color.parseColor(textMuted))
            })

            itemsContainer.addView(row)
        }
    }

    /** Total = items subtotal (rounded to the nearest rupee, no paisas).
     *  Due = Total − Paid. */
    private fun updateGrandTotal() {
        val total = Math.round(lines.sumOf { it.amount }).toDouble()
        grandTotalText.text = "Rs %.0f".format(total)

        val paid = Math.round(paidInput.text.toString().toDoubleOrNull() ?: 0.0).toDouble()
        val due = (total - paid).coerceAtLeast(0.0)
        dueAmountText.text = "Rs %.0f".format(due)
        dueAmountText.setTextColor(Color.parseColor(if (due > 0) red else teal))
    }

    // ---- Supplier quick-add ----
    private fun promptAddSupplier() {
        val input = EditText(this).apply { hint = "Supplier name"; setPadding(32, 24, 32, 24) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Add Supplier")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val supplierNameEntered = input.text.toString().trim()
                if (supplierNameEntered.isNotBlank()) {
                    lifecycleScope.launch {
                        val supplier = Supplier(name = supplierNameEntered)
                        PosDatabase.get(this@PurchaseActivity).supplierDao().insert(supplier)
                        partyName.setText(supplierNameEntered)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Quick "Add Product" from within Purchase: lets the user create a brand-new
    // product on the spot — with full Primary/Secondary/Tertiary unit setup — when the
    // item being purchased isn't in the Products list yet, instead of having to leave
    // this screen and set it up in Product Management first. ----
    private fun openAddProductDialog(prefillName: String) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(cardWhite))
        }
        val dialogHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 26, 28, 26)
            background = roundedBg(navy, 0)
        }
        dialogHeader.addView(TextView(this).apply {
            text = "\u2795  " + com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Add New Product", "نئی پروڈکٹ شامل کریں")
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(dialogHeader)

        val scrollableBody = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 26, 28, 8)
        }
        scrollableBody.addView(body)

        fun microLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = 11.5f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }

        // ---- Name ----
        body.addView(microLabel(com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "PRODUCT NAME", "پروڈکٹ کا نام")))
        val nameField = EditText(this).apply {
            setText(prefillName)
            setTextColor(Color.parseColor(textDark))
            background = strokedBg(border, "#FAFBFC", 12)
            setPadding(18, 16, 18, 16)
            textSize = 15f
        }
        body.addView(nameField)
        body.addView(spacer(18))

        // ---- Category ----
        body.addView(microLabel(com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "CATEGORY", "کیٹیگری")))
        val categorySpinnerBox = LinearLayout(this).apply {
            background = strokedBg(border, "#FAFBFC", 12)
            setPadding(14, 2, 14, 2)
        }
        val categorySpinnerDialog = Spinner(this)
        categorySpinnerBox.addView(categorySpinnerDialog)
        body.addView(categorySpinnerBox)
        body.addView(spacer(20))
        lifecycleScope.launch {
            val cats = (listOf("General") + PosDatabase.get(this@PurchaseActivity).categoryDao().all().first().map { it.name }).distinct()
            categorySpinnerDialog.adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, cats)
        }

        // ---- Primary Unit ----
        body.addView(microLabel(com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "PRIMARY UNIT", "بنیادی یونٹ")))
        val primarySpinnerBox = LinearLayout(this).apply {
            background = strokedBg(border, "#FAFBFC", 12); setPadding(14, 2, 14, 2)
        }
        val primarySpinner = Spinner(this)
        primarySpinnerBox.addView(primarySpinner)
        body.addView(primarySpinnerBox)
        body.addView(spacer(20))

        // ---- Secondary Unit (optional) ----
        body.addView(microLabel(com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "SECONDARY UNIT (smaller, optional)", "ثانوی یونٹ (چھوٹی، اختیاری)")))
        val secondarySpinnerBox = LinearLayout(this).apply {
            background = strokedBg(border, "#FAFBFC", 12); setPadding(14, 2, 14, 2)
        }
        val secondarySpinner = Spinner(this)
        secondarySpinnerBox.addView(secondarySpinner)
        body.addView(secondarySpinnerBox)
        body.addView(spacer(16))
        val secQtyBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, "#FAFBFC", 12); setPadding(16, 4, 16, 4)
        }
        secQtyBox.addView(TextView(this).apply { text = "\uD83D\uDD01  "; textSize = 14f })
        val secQtyField = EditText(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "1 Primary = how many Secondary? (e.g. 1 CTN = 50 Outer)", "1 بنیادی = کتنے ثانوی؟ (مثلاً 1 CTN = 50 آؤٹر)")
            setHintTextColor(Color.parseColor(textMuted)); setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        secQtyBox.addView(secQtyField)
        body.addView(secQtyBox)
        body.addView(spacer(20))

        // ---- Tertiary Unit (optional, relative to Secondary) ----
        body.addView(microLabel(com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "TERTIARY UNIT (smallest, optional)", "تیسرا یونٹ (سب سے چھوٹا، اختیاری)")))
        val tertiarySpinnerBox = LinearLayout(this).apply {
            background = strokedBg(border, "#FAFBFC", 12); setPadding(14, 2, 14, 2)
        }
        val tertiarySpinner = Spinner(this)
        tertiarySpinnerBox.addView(tertiarySpinner)
        body.addView(tertiarySpinnerBox)
        body.addView(spacer(16))
        val terQtyBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, "#FAFBFC", 12); setPadding(16, 4, 16, 4)
        }
        terQtyBox.addView(TextView(this).apply { text = "\uD83D\uDD01  "; textSize = 14f })
        val terQtyField = EditText(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "1 Secondary = how many Tertiary? (e.g. 1 Outer = 10 Dabbi)", "1 ثانوی = کتنے تیسرے؟ (مثلاً 1 آؤٹر = 10 ڈبی)")
            setHintTextColor(Color.parseColor(textMuted)); setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        terQtyBox.addView(terQtyField)
        body.addView(terQtyBox)

        content.addView(scrollableBody, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        primarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, allUnits)
        val secondaryOptions = listOf("None") + allUnits
        secondarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, secondaryOptions)
        val tertiaryOptions = listOf("None") + allUnits
        tertiarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tertiaryOptions)

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(28, 18, 28, 26) }
        content.addView(footer)
        val dialog = android.app.AlertDialog.Builder(this).setView(content).create()

        footer.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Cancel", "منسوخ کریں")
            gravity = Gravity.CENTER; textSize = 14f
            setTextColor(Color.parseColor(textMuted)); setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = strokedBg(border, "#FAFBFC", 14)
            setPadding(0, 22, 0, 22)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
            setOnClickListener { dialog.dismiss() }
        })
        footer.addView(TextView(this).apply {
            text = "\u2713  " + com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Save", "محفوظ کریں")
            gravity = Gravity.CENTER; textSize = 14f
            setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(teal, 14)
            setPadding(0, 22, 0, 22)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 0) }
            setOnClickListener {
                val pname = nameField.text.toString().trim()
                if (pname.isEmpty()) { nameField.error = "Required"; return@setOnClickListener }

                val primaryUnit = primarySpinner.selectedItem?.toString() ?: "pcs"
                var secondaryUnit = secondarySpinner.selectedItem?.toString() ?: "None"
                val secondaryQty = secQtyField.text.toString().toDoubleOrNull() ?: 0.0
                var tertiaryUnit = tertiarySpinner.selectedItem?.toString() ?: "None"
                var tertiaryQty = terQtyField.text.toString().toDoubleOrNull() ?: 0.0

                // ---- Numeric validation for the unit-conversion chain (e.g. 1 CTN = 50
                // Outer, 1 Outer = 10 Dabbi). Every tier that's selected needs a positive
                // conversion number and must be distinct from the tiers above it — without
                // this, a blank/zero conversion silently saved as 0, which is what made
                // qty/rate math for that product misbehave later in Purchase/Sale. ----
                if (secondaryUnit != "None") {
                    if (secondaryUnit == primaryUnit) {
                        Toast.makeText(this@PurchaseActivity, "Secondary unit must be different from Primary unit", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (secondaryQty <= 0) {
                        secQtyField.error = "Enter how many $secondaryUnit = 1 $primaryUnit"
                        secQtyField.requestFocus()
                        return@setOnClickListener
                    }
                }
                if (tertiaryUnit != "None") {
                    if (secondaryUnit == "None") {
                        Toast.makeText(this@PurchaseActivity, "Select a Secondary unit before adding a Tertiary unit", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (tertiaryUnit == primaryUnit || tertiaryUnit == secondaryUnit) {
                        Toast.makeText(this@PurchaseActivity, "Tertiary unit must be different from Primary and Secondary units", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (tertiaryQty <= 0) {
                        terQtyField.error = "Enter how many $tertiaryUnit = 1 $secondaryUnit"
                        terQtyField.requestFocus()
                        return@setOnClickListener
                    }
                } else {
                    tertiaryQty = 0.0
                }
                if (secondaryUnit == "None") { tertiaryUnit = "None"; tertiaryQty = 0.0 }

                val newProduct = Product(
                    barcode = "P" + System.currentTimeMillis(),
                    name = pname,
                    category = categorySpinnerDialog.selectedItem?.toString() ?: "General",
                    cost = rate.text.toString().toDoubleOrNull() ?: 0.0,
                    salePrice = 0.0,
                    wholesalePrice = 0.0,
                    stock = 0,
                    openingStock = 0,
                    unit = primaryUnit,
                    secondaryUnit = if (secondaryUnit == "None") "" else secondaryUnit,
                    secondaryUnitQty = secondaryQty,
                    tertiaryUnit = if (tertiaryUnit == "None") "" else tertiaryUnit,
                    tertiaryUnitQty = tertiaryQty
                )
                lifecycleScope.launch {
                    PosDatabase.get(this@PurchaseActivity).productDao().upsert(newProduct)
                    Toast.makeText(this@PurchaseActivity, com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Product added", "پروڈکٹ شامل ہو گئی"), Toast.LENGTH_SHORT).show()

                    itemName.setText(newProduct.name)
                    applyPickedProduct(newProduct)
                    dialog.dismiss()
                }
            }
        })

        dialog.show()
    }

    // ---- Delete (edit mode only): reverses stock + supplier balance, then removes the bill ----
    private fun confirmDeletePurchase() {
        val billNo = editBillNo ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle(com.grocerypos.v11.util.Loc.t(this, "Delete Purchase", "خریداری حذف کریں"))
            .setMessage(com.grocerypos.v11.util.Loc.t(this, "This will remove the bill and reverse its stock and supplier balance effect. Continue?", "یہ بل حذف کر دے گا اور اس کا اسٹاک اور سپلائر بیلنس پر اثر واپس کر دے گا۔ جاری رکھیں؟"))
            .setPositiveButton(com.grocerypos.v11.util.Loc.t(this, "Delete", "حذف کریں")) { _, _ -> deletePurchase(billNo) }
            .setNegativeButton(com.grocerypos.v11.util.Loc.t(this, "Cancel", "منسوخ"), null)
            .show()
    }

    private fun deletePurchase(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)
            val purchase = originalPurchase ?: db.purchaseDao().findPurchase(billNo) ?: return@launch
            val items = originalItems.ifEmpty { db.purchaseDao().itemsForBill(billNo) }

            // FIX: decreaseForce (unguarded) instead of decrease (guarded) — a purchase's
            // stock may have already been partly/fully sold by now, so this must be able
            // to go negative to correctly reverse it, instead of silently no-op'ing.
            items.forEach { db.productDao().decreaseForce(it.barcode, it.qty) }
            val outstanding = purchase.total - purchase.paid
            if (purchase.supplierId != null && outstanding > 0) {
                db.supplierDao().addBalance(purchase.supplierId, -outstanding)
            }
            db.purchaseDao().deleteItems(billNo)
            db.purchaseDao().deletePurchase(billNo)
            db.paymentDao().deleteByReference(billNo)
            // FIX: also remove the Cash Out record this purchase created (see savePurchase()).
            db.cashTransactionDao().deleteByReference(billNo)

            Toast.makeText(this@PurchaseActivity, "Purchase deleted", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ---- Save ----
    private fun savePurchase() {
        // Dismiss the keyboard up front so the bottom Save/Delete bar (which can sit
        // under the soft keyboard on smaller screens) is reachable without the user
        // having to manually tap outside a field first.
        currentFocus?.let { focused ->
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }

        val party = partyName.text.toString().trim()
        if (party.isEmpty()) { partyName.error = "Required"; return }
        if (lines.isEmpty()) {
            Toast.makeText(this, "Add at least one item, or continue without items", Toast.LENGTH_SHORT).show()
        }

        // Amounts are rounded to the nearest rupee (no paisas) throughout.
        val subtotal = lines.sumOf { it.amount }
        val grandTotal = Math.round(subtotal).toDouble().coerceAtLeast(0.0)
        val discount = 0.0
        // No Cash/Credit toggle — it's a credit purchase whenever Amount Paid is less
        // than the total; leaving it blank means fully on credit (0 paid).
        val amountPaid = Math.round(paidInput.text.toString().toDoubleOrNull() ?: 0.0).toDouble().coerceIn(0.0, grandTotal)
        val paymentMethod = "Cash"

        val matchedSupplier = suppliers.find { it.name.equals(party, ignoreCase = true) }
        val supplierId = matchedSupplier?.id

        val billNo = editBillNo ?: genBillNo()

        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)

            val original = originalPurchase
            if (original != null) {
                // FIX: decreaseForce instead of decrease — a purchase's stock may already
                // be partly/fully sold by the time it's edited, so this reversal must be
                // able to go negative rather than silently no-op'ing.
                originalItems.forEach { db.productDao().decreaseForce(it.barcode, it.qty) }
                val originalOutstanding = original.total - original.paid
                if (original.supplierId != null && originalOutstanding > 0) {
                    db.supplierDao().addBalance(original.supplierId, -originalOutstanding)
                }
                db.purchaseDao().deleteItems(billNo)
                db.purchaseDao().deletePurchase(billNo)
                db.paymentDao().deleteByReference(billNo)
                // FIX: also remove the old Cash Out record before re-inserting the updated one below.
                db.cashTransactionDao().deleteByReference(billNo)
            }

            db.purchaseDao().purchase(
                Purchase(
                    billNo = billNo,
                    supplierId = supplierId,
                    total = grandTotal,
                    paid = amountPaid,
                    createdAt = purchaseDateMillis,
                    subtotal = subtotal,
                    discount = discount
                )
            )

            // ---- Convert secondary/tertiary-unit qty & rate to main-unit terms before
            // persisting, mirroring SaleActivity. mainUnitQty()/mainUnitRate() now handle
            // both tiers via the extension functions above. ----
            db.purchaseDao().items(
                lines.map { line ->
                    PurchaseItem(
                        billNo = billNo,
                        barcode = line.barcode ?: "",
                        qty = line.mainUnitQty().roundToInt(),
                        unitCost = line.mainUnitRate(),
                        amount = line.amount,
                        unit = line.unit
                    )
                }
            )

            // FIX: for each line, snapshot the product's stock+cost BEFORE increasing
            // stock, then after increasing, write back a weighted-average cost:
            //   newCost = (oldStock * oldCost + purchasedQty * purchaseRate) / (oldStock + purchasedQty)
            // Without this, Product.cost stayed frozen at whatever was typed once in
            // Add/Edit Product, so Sale's profit calculation didn't reflect real
            // purchase rates over time.
            lines.forEach { line ->
                val barcode = line.barcode ?: return@forEach
                val before = db.productDao().find(barcode)
                val purchasedQty = line.mainUnitQty().roundToInt()

                db.productDao().increase(barcode, purchasedQty)

                if (before != null && purchasedQty > 0) {
                    val oldStock = before.stock
                    val oldCost = before.cost
                    val purchaseRate = line.mainUnitRate()
                    val newCost = if (oldStock <= 0) {
                        purchaseRate
                    } else {
                        ((oldStock * oldCost) + (purchasedQty * purchaseRate)) / (oldStock + purchasedQty)
                    }
                    db.productDao().updateCost(barcode, newCost)
                }
            }

            val outstanding = grandTotal - amountPaid
            if (supplierId != null && outstanding > 0) {
                db.supplierDao().addBalance(supplierId, outstanding)
            }
            if (supplierId != null && amountPaid > 0) {
                db.paymentDao().insert(
                    Payment(
                        reference = billNo,
                        partyType = "supplier",
                        partyId = supplierId,
                        amount = amountPaid,
                        method = paymentMethod,
                        note = if (original != null) "Purchase payment (edited)" else "Purchase payment"
                    )
                )
            }

            // FIX: record the cash that actually left the register for this purchase —
            // previously nothing logged this, so Cash Register / Reports never reflected
            // money paid out to suppliers.
            if (amountPaid > 0) {
                db.cashTransactionDao().insert(
                    CashTransaction(
                        type = "OUT",
                        method = paymentMethod.lowercase(),
                        amount = amountPaid,
                        reason = "Purchase",
                        reference = billNo
                    )
                )
            }

            // Bill is safely persisted now — clear the recovery draft so a future launch
            // doesn't try to restore an already-saved purchase.
            suppressDraftSave = true
            clearDraft()

            editBillNo = billNo

            // Whether this is a new purchase or an update to an existing one, always
            // go to the Bill Preview after Paid Amount is saved.
            Toast.makeText(
                this@PurchaseActivity,
                if (original != null) "Purchase updated" else "Purchase saved",
                Toast.LENGTH_SHORT
            ).show()
            openBillPreview(billNo, forSaving = true, party = party, grandTotal = grandTotal, discount = discount, amountPaid = amountPaid, paymentMethod = paymentMethod)
        }
    }

    // ---- Open the receipt-style Bill Preview. Used both right after Save, and from the
    // 3-dot Print/Share menu for an already-saved bill. ----
    private fun openBillPreview(
        billNo: String,
        forSaving: Boolean,
        party: String = partyName.text.toString().trim(),
        grandTotal: Double = Math.round(lines.sumOf { it.amount }).toDouble(),
        discount: Double = 0.0,
        amountPaid: Double = Math.round(paidInput.text.toString().toDoubleOrNull() ?: 0.0).toDouble(),
        paymentMethod: String = "Cash"
    ) {
        val itemsEncoded = lines.joinToString("\u0002") {
            listOf(it.itemName, formatQty(it.qty), it.unit, it.rate, it.amount).joinToString("\u0003")
        }
        val previewIntent = Intent(this, BillPreviewActivity::class.java).apply {
            putExtra(BillPreviewActivity.EXTRA_TYPE, "purchase")
            putExtra(BillPreviewActivity.EXTRA_REFERENCE, billNo)
            putExtra(BillPreviewActivity.EXTRA_PARTY_NAME, party)
            putExtra(BillPreviewActivity.EXTRA_PARTY_LABEL, "Supplier")
            putExtra(BillPreviewActivity.EXTRA_DATE_MILLIS, purchaseDateMillis)
            putExtra(BillPreviewActivity.EXTRA_SUBTOTAL, grandTotal + discount)
            putExtra(BillPreviewActivity.EXTRA_DISCOUNT, discount)
            putExtra(BillPreviewActivity.EXTRA_TOTAL, grandTotal)
            putExtra(BillPreviewActivity.EXTRA_PAID, amountPaid)
            putExtra(BillPreviewActivity.EXTRA_PAYMENT_METHOD, paymentMethod)
            putExtra(BillPreviewActivity.EXTRA_ITEMS_ENCODED, itemsEncoded)
        }
        startActivity(previewIntent)
        if (forSaving) finish()
    }
}
