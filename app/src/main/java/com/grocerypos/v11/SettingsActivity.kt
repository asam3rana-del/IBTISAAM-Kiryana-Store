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

class SettingsActivity : AppCompatActivity() {

    // ================= PREMIUM COLOR PALETTE (matches Sale / Product / Purchase) =================
    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val green = "#1FA971"
    private val greenDark = "#158A5A"
    private val red = "#E5484D"
    private val redDark = "#C93A3E"
    private val blue = "#2F6FED"
    private val amber = "#F5A524"
    private val amberDark = "#D6890E"
    private val purple = "#8B5CF6"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

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
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 26, 22)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            ).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
            applyElevation(this, 10f)
        }
        header.addView(circleIcon("⚙️", "#5C4DFF", 42))
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = "Settings"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 11.5f
            setTextColor(Color.parseColor("#D8D3FF"))
            setPadding(0, 4, 0, 0)
        })
        header.addView(headerCol)
        root.addView(header)

        // ================= SHOP INFO =================
        val shopCard = sectionCard("🏪", "Shop Information")

        shopNameField = EditText(this).apply {
            hint = "Shop Name"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
        }
        phoneField = EditText(this).apply {
            hint = "Phone"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
        }
        addressField = EditText(this).apply {
            hint = "Address"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
        }
        footerField = EditText(this).apply {
            hint = "Receipt Footer"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
        }
        currencyField = EditText(this).apply {
            hint = "Currency"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
        }
        taxField = EditText(this).apply {
            hint = "Tax %"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
        }

        shopCard.addView(fieldBox("🏬", shopNameField))
        shopCard.addView(spacer(10))
        shopCard.addView(fieldBox("📞", phoneField))
        shopCard.addView(spacer(10))
        shopCard.addView(fieldBox("📍", addressField))
        shopCard.addView(spacer(10))
        shopCard.addView(fieldBox("🧾", footerField))
        shopCard.addView(spacer(10))
        shopCard.addView(fieldBox("💱", currencyField))
        shopCard.addView(spacer(10))
        shopCard.addView(fieldBox("📊", taxField))
        shopCard.addView(spacer(10))

        shopCard.addView(primaryButton("💾  SAVE SETTINGS", primary, primaryDark) { saveShopSettings() })
        root.addView(shopCard)
        root.addView(spacer(18))

        loadShopSettings()

        // ---- Printer Setup (Bluetooth 58mm) ----
        val printerCard = sectionCard("🖨️", "Printer Setup (58mm Bluetooth)")

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, "#FAFAFF", 14)
            setPadding(18, 14, 18, 14)
        }
        printerStatusDot = TextView(this).apply {
            text = "●"
            textSize = 16f
            setTextColor(Color.parseColor(red))
        }
        statusRow.addView(printerStatusDot)
        statusRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(12, 1) })
        printerStatusText = TextView(this).apply {
            text = "No printer selected"
            textSize = 13.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        }
        statusRow.addView(printerStatusText)
        printerCard.addView(statusRow)
        printerCard.addView(spacer(14))
        loadPrinterStatus()

        val printerBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        printerBtnRow.addView(secondaryButton("🔍  SELECT PRINTER", blue) { onSelectPrinterClicked() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) }
        })
        printerBtnRow.addView(secondaryButton("🖨️  TEST PRINT", green) { onTestPrintClicked() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
        })
        printerCard.addView(printerBtnRow)
        root.addView(printerCard)
        root.addView(spacer(18))

        // ---- Backup & Restore ----
        val backupCard = sectionCard("💾", "Backup & Restore")
        backupCard.addView(primaryButton("⬆  BACKUP NOW", green, greenDark) { onBackupClicked() })
        backupCard.addView(spacer(12))
        backupCard.addView(primaryButton("⬇  RESTORE BACKUP", amber, amberDark) { onRestoreClicked() })
        root.addView(backupCard)
        root.addView(spacer(18))

        // ---- Security (Login Method) ----
        val securityCard = sectionCard("🔐", "Security — Login Method")

        loginMethodGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        passwordOnlyRadio = RadioButton(this).apply {
            text = "🔑  Password Only"
            setTextColor(Color.parseColor(textDark))
        }
        fingerprintOnlyRadio = RadioButton(this).apply {
            text = "👆  Fingerprint Only"
            setTextColor(Color.parseColor(textDark))
        }
        bothRadio = RadioButton(this).apply {
            text = "🔑👆  Both (Password + Fingerprint)"
            setTextColor(Color.parseColor(textDark))
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
        root.addView(spacer(18))
        loadLoginMethodSetting()

        // ---- Change Username / Password ----
        val loginCard = sectionCard("🔑", "Change Login (Username / Password)")

        val session = getSharedPreferences("session", MODE_PRIVATE)
        val loggedInUsername = session.getString("username", "") ?: ""

        currentUsernameField = EditText(this).apply {
            hint = "Current Username"
            setText(loggedInUsername)
            isEnabled = false
            setTextColor(Color.parseColor(textGray))
            background = null
        }
        newUsernameField = EditText(this).apply {
            hint = "New Username (blank = keep same)"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
        }
        newPasswordField = EditText(this).apply {
            hint = "New Password (blank = keep same)"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        loginCard.addView(fieldBox("👤", currentUsernameField, muted = true))
        loginCard.addView(spacer(10))
        loginCard.addView(fieldBox("✏️", newUsernameField))
        loginCard.addView(spacer(10))
        loginCard.addView(passwordFieldBox(newPasswordField))
        loginCard.addView(spacer(14))
        loginCard.addView(primaryButton("✓  UPDATE LOGIN", primary, primaryDark) { updateLogin(loggedInUsername) })
        root.addView(loginCard)
        root.addView(spacer(18))

        // ---- Language ----
        val languageCard = sectionCard("🌐", "Language")
        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val englishBtn = secondaryButton("English", primary) {}
        val urduBtn = secondaryButton("اردو", primary) {}
        fun activeLangBg() = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
        ).apply { cornerRadius = 16f }
        fun refreshLanguageButtons() {
            val isUrdu = com.grocerypos.v11.util.Loc.isUrdu(this)
            englishBtn.background = if (!isUrdu) activeLangBg() else strokedBg(primary, "#FFFFFF", 16)
            englishBtn.setTextColor(if (!isUrdu) Color.WHITE else Color.parseColor(primary))
            urduBtn.background = if (isUrdu) activeLangBg() else strokedBg(primary, "#FFFFFF", 16)
            urduBtn.setTextColor(if (isUrdu) Color.WHITE else Color.parseColor(primary))
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
        englishBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) }
        urduBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
        langRow.addView(englishBtn)
        langRow.addView(urduBtn)
        languageCard.addView(langRow)
        refreshLanguageButtons()
        root.addView(languageCard)
        root.addView(spacer(18))

        // ---- Items (Products / Categories / Units) ----
        val itemsCard = sectionCard("🗃️", "Items")
        itemsCard.addView(secondaryButton("🗃️  OPEN ITEMS", amber) {
            startActivity(Intent(this@SettingsActivity, ItemsActivity::class.java))
        })
        root.addView(itemsCard)
        root.addView(spacer(18))

        // ---- Users & Account ----
        val usersCard = sectionCard("👥", "Users & Account")
        usersCard.addView(secondaryButton("👥  MANAGE USERS", blue) {
            startActivity(Intent(this@SettingsActivity, UserManagementActivity::class.java))
        })
        usersCard.addView(spacer(12))
        usersCard.addView(primaryButton("🚪  LOGOUT", red, redDark) { doLogout() })
        root.addView(usersCard)
        root.addView(spacer(30))

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
    private fun sectionCard(icon: String, title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 22, 24, 22)
        background = strokedBg(border, cardBg, 18)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        applyElevation(this, 3f)
        addView(sectionLabel(icon, title))
        addView(spacer(4))
    }

    private fun sectionLabel(icon: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 12)
        addView(TextView(this@SettingsActivity).apply { text = "$icon  "; textSize = 15f })
        addView(TextView(this@SettingsActivity).apply {
            text = label
            textSize = 14.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun fieldBox(icon: String, field: EditText, muted: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = strokedBg(border, if (muted) "#F1F0F7" else "#FAFAFF", 12)
        setPadding(18, 4, 18, 4)
        addView(TextView(this@SettingsActivity).apply { text = "$icon  "; textSize = 14f })
        (field.parent as? ViewGroup)?.removeView(field)
        field.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(field)
    }

    /** Same as fieldBox but adds a tappable eye icon to show/hide the password text. */
    private fun passwordFieldBox(field: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = strokedBg(border, "#FAFAFF", 12)
        setPadding(18, 4, 18, 4)
        addView(TextView(this@SettingsActivity).apply { text = "🔒  "; textSize = 14f })
        (field.parent as? ViewGroup)?.removeView(field)
        field.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(field)

        val toggle = TextView(this@SettingsActivity).apply {
            text = "👁"
            textSize = 16f
            setPadding(16, 0, 8, 0)
            var visible = false
            setOnClickListener {
                visible = !visible
                field.inputType = if (visible)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                field.setSelection(field.text.length)
                text = if (visible) "🙈" else "👁"
            }
        }
        addView(toggle)
    }

    private fun primaryButton(label: String, colorHex: String, colorDarkHex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14.5f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor(colorHex), Color.parseColor(colorDarkHex))
        ).apply { cornerRadius = 16f }
        setPadding(0, 24, 0, 24)
        setOnClickListener { onClick() }
        applyElevation(this, 4f)
    }

    private fun secondaryButton(label: String, colorHex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.parseColor(colorHex))
        textSize = 13.5f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = strokedBg(colorHex, "#FFFFFF", 16)
        setPadding(0, 22, 0, 22)
        setOnClickListener { onClick() }
    }

    private fun circleIcon(label: String, colorHex: String, sizeDp: Int) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        width = px; height = px
    }

    private fun ovalBg(colorHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
    }

    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.4 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
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
                printerStatusDot.setTextColor(Color.parseColor(green))
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
            printerStatusDot.setTextColor(Color.parseColor(green))
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

    private fun onRestoreClicked() {
        val backups = BackupHelper.listBackups(this)
        if (backups.isEmpty()) {
            Toast.makeText(this, "Koi backup nahi mila", Toast.LENGTH_SHORT).show()
            return
        }
        val names = backups.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Backup to Restore")
            .setItems(names) { _, which ->
                confirmRestore(backups[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRestore(file: java.io.File) {
        AlertDialog.Builder(this)
            .setTitle("Restore Backup?")
            .setMessage("Ye current data ko overwrite kar dega: ${file.name}. Aage badhein?")
            .setPositiveButton("Restore") { _, _ ->
                val ok = BackupHelper.restore(this, file)
                if (ok) {
                    Toast.makeText(this, "Restore ho gaya. App ko dobara open karein.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Restore fail ho gaya", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
