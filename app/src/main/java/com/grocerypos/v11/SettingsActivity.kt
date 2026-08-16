package com.grocerypos.v11.ui

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.AppSetting
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.R
import com.grocerypos.v11.User
import com.grocerypos.v11.util.BackupHelper
import com.grocerypos.v11.util.PrinterHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    // ================= NAVY + TEAL + WHITE PALETTE (matches Purchase) =================
    private val bg = "#F4F6F8"
    private val cardBg = "#FFFFFF"
    private val navy = "#0B2545"     // primary actions, active tab, headings
    private val teal = "#0F9B8E"     // secondary actions / accents
    private val red = "#E5484D"      // functional only — logout, restore, disconnected
    private val textDark = "#0B2545"
    private val textGray = "#7C8798"
    private val border = "#E3E8EE"

    private lateinit var currentUsernameField: EditText
    private lateinit var newUsernameField: EditText
    private lateinit var newPasswordField: EditText
    private lateinit var printerStatusText: TextView
    private lateinit var printerStatusDot: TextView
    private lateinit var loginMethodGroup: RadioGroup
    private lateinit var passwordOnlyRadio: RadioButton
    private lateinit var fingerprintOnlyRadio: RadioButton
    private lateinit var bothRadio: RadioButton

    // Shop Information fields
    private lateinit var shopNameField: EditText
    private lateinit var phoneField: EditText
    private lateinit var addressField: EditText
    private lateinit var footerField: EditText
    private lateinit var currencyField: EditText
    private lateinit var taxField: EditText

    private val BT_PERMISSION_REQUEST_CODE = 501

    override fun onCreate(b: Bundle?) {
        // Force-apply the drawer/dialog theme here in code — this guarantees the floating
        // style is used even if the manifest's android:theme override doesn't resolve
        // (build variant issue, manifest merge, etc). Must be called BEFORE super.onCreate().
        setTheme(R.style.Theme_SettingsSheet)
        super.onCreate(b)

        // NOTE: window.setLayout()/setGravity() are called AFTER setContentView() below,
        // wrapped in decorView.post{}. On several OEM skins (Samsung/MIUI/etc.), calling
        // setLayout() before setContentView() gets silently overridden back to full-screen
        // once content is attached — that was the cause of Settings covering the whole
        // dashboard instead of opening as a floating side sheet.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#66000000")))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 40, 22, 22)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER (flat navy, compact) =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 18)
            background = roundedBg(navy, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 14) }
            applyElevation(this, 6f)
        }
        header.addView(TextView(this).apply {
            text = "Settings"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 11f
            setTextColor(Color.parseColor("#9FB4CC"))
            setPadding(0, 3, 0, 0)
        })
        root.addView(header)

        // ================= SHOP INFO =================
        val shopCard = sectionCard("Shop Information")

        shopNameField = plainField("Shop Name")
        phoneField = plainField("Phone")
        addressField = plainField("Address")
        footerField = plainField("Receipt Footer")
        currencyField = plainField("Currency")
        taxField = plainField("Tax %")

        shopCard.addView(fieldBox("Shop Name", shopNameField))
        shopCard.addView(spacer(8))
        shopCard.addView(fieldBox("Phone", phoneField))
        shopCard.addView(spacer(8))
        shopCard.addView(fieldBox("Address", addressField))
        shopCard.addView(spacer(8))
        shopCard.addView(fieldBox("Receipt Footer", footerField))
        shopCard.addView(spacer(8))
        shopCard.addView(fieldBox("Currency", currencyField))
        shopCard.addView(spacer(8))
        shopCard.addView(fieldBox("Tax %", taxField))
        shopCard.addView(spacer(10))

        shopCard.addView(primaryButton("SAVE SETTINGS", navy) { saveShopSettings() })
        root.addView(shopCard)
        root.addView(spacer(10))

        loadShopSettings()

        // ---- Printer Setup (Bluetooth 58mm) ----
        val printerCard = sectionCard("Printer Setup (58mm Bluetooth)")

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, "#FAFBFC", 12)
            setPadding(16, 10, 16, 10)
        }
        printerStatusDot = TextView(this).apply {
            text = "●"
            textSize = 14f
            setTextColor(Color.parseColor(red))
        }
        statusRow.addView(printerStatusDot)
        statusRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(10, 1) })
        printerStatusText = TextView(this).apply {
            text = "No printer selected"
            textSize = 13f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        }
        statusRow.addView(printerStatusText)
        printerCard.addView(statusRow)
        printerCard.addView(spacer(10))
        loadPrinterStatus()

        val printerBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        printerBtnRow.addView(secondaryButton("SELECT PRINTER", navy) { onSelectPrinterClicked() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) }
        })
        printerBtnRow.addView(secondaryButton("TEST PRINT", teal) { onTestPrintClicked() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
        })
        printerCard.addView(printerBtnRow)
        root.addView(printerCard)
        root.addView(spacer(10))

        // ---- Backup & Restore ----
        val backupCard = sectionCard("Backup & Restore")
        backupCard.addView(primaryButton("BACKUP NOW", teal) { onBackupClicked() })
        backupCard.addView(spacer(8))
        backupCard.addView(primaryButton("RESTORE BACKUP", red) { onRestoreClicked() })
        // FIX: plain-language warning sitting right under the button, always visible —
        // not just inside a dialog someone might tap through without reading.
        backupCard.addView(TextView(this).apply {
            text = "⚠️ Restore purani backup laata hai aur is waqt ka sara naya data (jo backup ke baad add hua) permanently mita deta hai. Sirf tab use karein jab aapko waqai purani state par jaana ho."
            textSize = 11f
            setTextColor(Color.parseColor(red))
            setPadding(4, 10, 4, 0)
        })
        root.addView(backupCard)
        root.addView(spacer(10))

        // ---- Security (Login Method) ----
        val securityCard = sectionCard("Security — Login Method")

        loginMethodGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        passwordOnlyRadio = RadioButton(this).apply {
            text = "Password Only"
            setTextColor(Color.parseColor(textDark))
            textSize = 13.5f
        }
        fingerprintOnlyRadio = RadioButton(this).apply {
            text = "Fingerprint Only"
            setTextColor(Color.parseColor(textDark))
            textSize = 13.5f
        }
        bothRadio = RadioButton(this).apply {
            text = "Both (Password + Fingerprint)"
            setTextColor(Color.parseColor(textDark))
            textSize = 13.5f
        }
        loginMethodGroup.addView(passwordOnlyRadio)
        loginMethodGroup.addView(fingerprintOnlyRadio)
        loginMethodGroup.addView(bothRadio)
        securityCard.addView(loginMethodGroup)

        loginMethodGroup.setOnCheckedChangeListener { _, checkedId ->
            val method = when (checkedId) {
                fingerprintOnlyRadio.id -> "fingerprint"
                bothRadio.id -> "both"
                else -> "password"
            }
            lifecycleScope.launch {
                val db = PosDatabase.get(this@SettingsActivity)
                db.appSettingDao().set(AppSetting("login_method", method))
            }
        }

        root.addView(securityCard)
        root.addView(spacer(10))
        loadLoginMethodSetting()

        // ---- Change Username / Password ----
        val loginCard = sectionCard("Change Login (Username / Password)")

        val session = getSharedPreferences("session", MODE_PRIVATE)
        val loggedInUsername = session.getString("username", "") ?: ""

        currentUsernameField = plainField("Current Username").apply {
            setText(loggedInUsername)
            isEnabled = false
            setTextColor(Color.parseColor(textGray))
        }
        newUsernameField = plainField("New Username (blank = keep same)")
        newPasswordField = plainField("New Password (blank = keep same)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        loginCard.addView(fieldBox("Current Username", currentUsernameField, muted = true))
        loginCard.addView(spacer(8))
        loginCard.addView(fieldBox("New Username", newUsernameField))
        loginCard.addView(spacer(8))
        loginCard.addView(passwordFieldBox("New Password", newPasswordField))
        loginCard.addView(spacer(10))
        loginCard.addView(primaryButton("UPDATE LOGIN", navy) { updateLogin(loggedInUsername) })
        root.addView(loginCard)
        root.addView(spacer(10))

        // ---- Language (compact tab pair) ----
        val languageCard = sectionCard("Language")
        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val englishBtn = tabButton("English") {}
        val urduBtn = tabButton("اردو") {}
        fun refreshLanguageButtons() {
            val isUrdu = com.grocerypos.v11.util.Loc.isUrdu(this)
            englishBtn.background = if (!isUrdu) roundedBg(navy, 14) else roundedBg("#EAEDF1", 14)
            englishBtn.setTextColor(if (!isUrdu) Color.WHITE else Color.parseColor(textGray))
            urduBtn.background = if (isUrdu) roundedBg(navy, 14) else roundedBg("#EAEDF1", 14)
            urduBtn.setTextColor(if (isUrdu) Color.WHITE else Color.parseColor(textGray))
        }
        englishBtn.setOnClickListener {
            com.grocerypos.v11.util.Loc.setLanguage(this, "en")
            refreshLanguageButtons()
            recreate()
        }
        urduBtn.setOnClickListener {
            com.grocerypos.v11.util.Loc.setLanguage(this, "ur")
            refreshLanguageButtons()
            recreate()
        }
        englishBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 5, 0) }
        urduBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(5, 0, 0, 0) }
        langRow.addView(englishBtn)
        langRow.addView(urduBtn)
        languageCard.addView(langRow)
        refreshLanguageButtons()
        root.addView(languageCard)
        root.addView(spacer(10))

        // ---- Items (Products / Categories / Units) ----
        val itemsCard = sectionCard("Items")
        itemsCard.addView(secondaryButton("OPEN ITEMS", teal) {
            startActivity(Intent(this@SettingsActivity, ItemsActivity::class.java))
        })
        root.addView(itemsCard)
        root.addView(spacer(10))

        // ---- Users & Account ----
        val usersCard = sectionCard("Users & Account")
        usersCard.addView(secondaryButton("MANAGE USERS", navy) {
            startActivity(Intent(this@SettingsActivity, UserManagementActivity::class.java))
        })
        usersCard.addView(spacer(8))
        usersCard.addView(primaryButton("LOGOUT", red) { doLogout() })
        root.addView(usersCard)
        root.addView(spacer(24))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        // ================= APPLY FLOATING SIDE-SHEET SIZE (must run AFTER setContentView) =================
        // Some OEM skins reset window.setLayout() if it's called before the content view is
        // attached, which is why Settings was opening full-screen instead of as a side sheet.
        // Doing it here — and once more inside decorView.post{} — makes sure it always sticks.
        applyFloatingSheetLayout()
        window.decorView.post { applyFloatingSheetLayout() }
    }

    private fun applyFloatingSheetLayout() {
        val screenWidth = resources.displayMetrics.widthPixels
        window.setGravity(Gravity.START)
        window.setLayout(
            (screenWidth * 0.80).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // windowAnimationStyle from the theme handles slide-in; no extra call needed here.
    }

    private fun doLogout() {
        getSharedPreferences("session", MODE_PRIVATE).edit().clear().apply()
        startActivity(Intent(this@SettingsActivity, LoginActivity::class.java))
        finish()
    }

    // ================= UI HELPERS =================
    private fun sectionCard(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 16, 20, 16)
        background = strokedBg(border, cardBg, 16)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 0) }
        applyElevation(this, 2f)
        addView(sectionLabel(title))
        addView(spacer(2))
    }

    private fun sectionLabel(label: String) = TextView(this).apply {
        text = label
        textSize = 13.5f
        setTextColor(Color.parseColor(textDark))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, 10)
    }

    /** Small uppercase muted micro-label, matching PurchaseActivity's field style. */
    private fun microLabel(label: String) = TextView(this).apply {
        text = label.uppercase()
        textSize = 10f
        setTextColor(Color.parseColor(textGray))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, 4)
        letterSpacing = 0.03f
    }

    private fun plainField(hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Color.parseColor(textGray))
        setTextColor(Color.parseColor(textDark))
        background = null
        textSize = 14.5f
    }

    private fun fieldBox(label: String, field: EditText, muted: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = strokedBg(border, if (muted) "#F1F3F5" else "#FAFBFC", 12)
        setPadding(16, 10, 16, 10)
        addView(microLabel(label))
        (field.parent as? ViewGroup)?.removeView(field)
        field.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(field)
    }

    /** Same as fieldBox but adds a tappable text toggle to show/hide the password. */
    private fun passwordFieldBox(label: String, field: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = strokedBg(border, "#FAFBFC", 12)
        setPadding(16, 10, 16, 10)
        addView(microLabel(label))
        val row = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        (field.parent as? ViewGroup)?.removeView(field)
        field.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(field)

        val toggle = TextView(this@SettingsActivity).apply {
            text = "SHOW"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(teal))
            setPadding(16, 0, 4, 0)
            var visible = false
            setOnClickListener {
                visible = !visible
                field.inputType = if (visible)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                field.setSelection(field.text.length)
                text = if (visible) "HIDE" else "SHOW"
            }
        }
        row.addView(toggle)
        addView(row)
    }

    private fun primaryButton(label: String, colorHex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13.5f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = roundedBg(colorHex, 14)
        setPadding(0, 18, 0, 18)
        setOnClickListener { onClick() }
        applyElevation(this, 2f)
    }

    private fun secondaryButton(label: String, colorHex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.parseColor(colorHex))
        textSize = 12.5f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = strokedBg(colorHex, "#FFFFFF", 14)
        setPadding(0, 16, 0, 16)
        setOnClickListener { onClick() }
    }

    /** Compact segmented-control-style button used for the Language tabs. */
    private fun tabButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12.5f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 12, 0, 12)
        minHeight = 0
        setOnClickListener { onClick() }
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

    // ================= SHOP INFO SAVE/LOAD =================

    private fun loadShopSettings() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            shopNameField.setText(db.appSettingDao().get("shop_name")?.value ?: "")
            phoneField.setText(db.appSettingDao().get("shop_phone")?.value ?: "")
            addressField.setText(db.appSettingDao().get("shop_address")?.value ?: "")
            footerField.setText(db.appSettingDao().get("receipt_footer")?.value ?: "")
            currencyField.setText(db.appSettingDao().get("currency")?.value ?: "")
            taxField.setText(db.appSettingDao().get("tax_percent")?.value ?: "")
        }
    }

    private fun saveShopSettings() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            db.appSettingDao().set(AppSetting("shop_name", shopNameField.text.toString().trim()))
            db.appSettingDao().set(AppSetting("shop_phone", phoneField.text.toString().trim()))
            db.appSettingDao().set(AppSetting("shop_address", addressField.text.toString().trim()))
            db.appSettingDao().set(AppSetting("receipt_footer", footerField.text.toString().trim()))
            db.appSettingDao().set(AppSetting("currency", currencyField.text.toString().trim()))
            db.appSettingDao().set(AppSetting("tax_percent", taxField.text.toString().trim()))
            Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
        }
    }

    // ================= SECURITY (FINGERPRINT TOGGLE) =================

    private fun loadLoginMethodSetting() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            when (db.appSettingDao().get("login_method")?.value ?: "password") {
                "fingerprint" -> fingerprintOnlyRadio.isChecked = true
                "both" -> bothRadio.isChecked = true
                else -> passwordOnlyRadio.isChecked = true
            }
        }
    }

    // ================= PRINTER =================

    private fun loadPrinterStatus() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            val printerName = db.appSettingDao().get("printer_name")?.value
            if (!printerName.isNullOrEmpty()) {
                printerStatusText.text = "Selected: $printerName"
                printerStatusDot.setTextColor(Color.parseColor(teal))
            } else {
                printerStatusText.text = "No printer selected"
                printerStatusDot.setTextColor(Color.parseColor(red))
            }
        }
    }

    private fun onSelectPrinterClicked() {
        if (!PrinterHelper.hasBluetoothPermission(this)) {
            PrinterHelper.requestBluetoothPermission(this, BT_PERMISSION_REQUEST_CODE)
            Toast.makeText(this, "Bluetooth permission dein, phir dobara SELECT PRINTER dabayein", Toast.LENGTH_LONG).show()
            return
        }

        val devices: List<BluetoothDevice> = PrinterHelper.pairedDevices(this)
        if (devices.isEmpty()) {
            Toast.makeText(
                this,
                "Koi paired Bluetooth printer nahi mila. Pehle phone ki Bluetooth Settings se printer ko pair karein.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val names = devices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select 58mm Printer")
            .setItems(names) { _, which ->
                val device = devices[which]
                savePrinter(device.name ?: "Printer", device.address)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun savePrinter(printerName: String, mac: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            db.appSettingDao().set(AppSetting("printer_name", printerName))
            db.appSettingDao().set(AppSetting("printer_mac", mac))
            db.appSettingDao().set(AppSetting("printer_width", "58"))
            printerStatusText.text = "Selected: $printerName"
            printerStatusDot.setTextColor(Color.parseColor(teal))
            Toast.makeText(this@SettingsActivity, "Printer saved: $printerName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onTestPrintClicked() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            val mac = db.appSettingDao().get("printer_mac")?.value
            if (mac.isNullOrEmpty()) {
                Toast.makeText(this@SettingsActivity, "Pehle printer select karein", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val shopName = db.appSettingDao().get("shop_name")?.value ?: "IBTISAAM Kiryana Store"
            val ok = PrinterHelper.testPrint(
                this@SettingsActivity,
                PrinterHelper.PrinterType.BLUETOOTH,
                mac,
                shopName
            )
            Toast.makeText(
                this@SettingsActivity,
                if (ok) "Test print bhej diya" else "Print fail ho gaya. Printer on hai aur range mein hai check karein.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ================= BACKUP / RESTORE =================

    private fun onBackupClicked() {
        val file = BackupHelper.backupNow(this)
        if (file != null) {
            Toast.makeText(this, "Backup ho gaya: ${file.name}", Toast.LENGTH_LONG).show()
            BackupHelper.shareBackup(this, file)
        } else {
            Toast.makeText(this, "Backup fail ho gaya", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * FIX: the list now shows a human-readable date/time (and marks the most
     * recent one) instead of the raw filename, so it's obvious at a glance
     * exactly which point in time each backup would roll you back to.
     */
    private fun onRestoreClicked() {
        val backups = BackupHelper.listBackups(this)
        if (backups.isEmpty()) {
            Toast.makeText(this, "Koi backup nahi mila", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        val labels = backups.mapIndexed { index, file ->
            val dateLabel = fmt.format(java.util.Date(file.lastModified()))
            if (index == 0) "$dateLabel  (most recent)" else dateLabel
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Backup to Restore")
            .setItems(labels) { _, which ->
                confirmRestore(backups[which], labels[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * FIX: this used to be a single tap-through "Restore" button, which made it
     * very easy to accidentally wipe out everything entered after the chosen
     * backup was taken. It now:
     *  - spells out in plain language exactly what will be lost
     *  - requires the person to actively check "I understand" before the
     *    Restore button becomes tappable, so it can't be dismissed on autopilot
     */
    private fun confirmRestore(file: java.io.File, dateLabel: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        container.addView(TextView(this).apply {
            text = "Ye backup ($dateLabel) is waqt ke current data ko OVERWRITE kar dega.\n\n" +
                "Iska matlab: is backup ke baad add ki gayi tamam sales, purchases, aur baaki entries HAMESHA ke liye mit jayengi. Ye wapis nahi hoga."
            textSize = 13.5f
            setTextColor(Color.parseColor(textDark))
        })
        val checkBox = CheckBox(this).apply {
            text = "Mujhe samajh aa gaya, aage badhein"
            setTextColor(Color.parseColor(textDark))
            setPadding(0, 28, 0, 0)
        }
        container.addView(checkBox)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Restore Backup?")
            .setView(container)
            .setPositiveButton("Restore", null) // set below so we can disable-until-checked
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveBtn.isEnabled = false
            positiveBtn.setTextColor(Color.parseColor(red))
            checkBox.setOnCheckedChangeListener { _, isChecked -> positiveBtn.isEnabled = isChecked }
            positiveBtn.setOnClickListener {
                val ok = BackupHelper.restore(this, file)
                if (ok) {
                    Toast.makeText(this, "Restore ho gaya. App ko dobara open karein.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Restore fail ho gaya", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // ================= LOGIN =================

    private fun updateLogin(currentUsername: String) {
        if (currentUsername.isEmpty()) {
            Toast.makeText(this, "Login session nahi mila, dobara login karein", Toast.LENGTH_SHORT).show()
            return
        }
        val newUsername = newUsernameField.text.toString().trim()
        val newPassword = newPasswordField.text.toString()

        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            val user = db.userDao().find(currentUsername)
            if (user == null) {
                Toast.makeText(this@SettingsActivity, "User nahi mila", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val finalUsername = if (newUsername.isNotEmpty()) newUsername else user.username
            val finalPassword = if (newPassword.isNotEmpty()) newPassword else user.passwordHash

            db.userDao().upsert(
                User(
                    username = finalUsername,
                    displayName = user.displayName,
                    role = user.role,
                    passwordHash = finalPassword,
                    active = true
                )
            )

            if (finalUsername != currentUsername) {
                db.userDao().delete(currentUsername)
                getSharedPreferences("session", MODE_PRIVATE).edit()
                    .putString("username", finalUsername).apply()
                currentUsernameField.setText(finalUsername)
            }

            Toast.makeText(this@SettingsActivity, "Login updated", Toast.LENGTH_SHORT).show()
            newUsernameField.text.clear()
            newPasswordField.text.clear()
        }
    }
}
