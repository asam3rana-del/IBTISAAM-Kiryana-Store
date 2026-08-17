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

    // ================= PALETTE =================
    private val bg = "#F4F6F8"
    private val cardBg = "#FFFFFF"
    private val navy = "#0B2545"
    private val headerBlue = "#1565C0"     // drawer header background
    private val teal = "#0F9B8E"
    private val red = "#E5484D"
    private val badgeRed = "#E53950"
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
    private lateinit var noPasswordRadio: RadioButton

    // Shop Information fields
    private lateinit var shopNameField: EditText
    private lateinit var phoneField: EditText
    private lateinit var addressField: EditText
    private lateinit var footerField: EditText
    private lateinit var currencyField: EditText
    private lateinit var taxField: EditText

    private val BT_PERMISSION_REQUEST_CODE = 501

    override fun onCreate(b: Bundle?) {
        setTheme(R.style.Theme_SettingsSheet)
        super.onCreate(b)

        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#66000000")))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= DRAWER-STYLE HEADER =================
        root.addView(buildHeader())

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
            setBackgroundColor(Color.parseColor(cardBg))
        }

        // ---- Parties (real) ----
        list.addView(menuRow("👥", "Parties", showNew = true, showChevron = true) {
            startActivity(Intent(this@SettingsActivity, PartyDashboardActivity::class.java))
        })

        // ---- Items (real) ----
        list.addView(menuRow("📋", "Items", showNew = true) {
            startActivity(Intent(this@SettingsActivity, ItemsActivity::class.java))
        })

        // ---- Business Dashboard (no screen yet) ----
        list.addView(menuRow("⊞", "Business Dashboard") {
            comingSoon("Business Dashboard")
        })

        // ---- Reports (real) ----
        list.addView(menuRow("📈", "Reports") {
            startActivity(Intent(this@SettingsActivity, ReportsActivity::class.java))
        })

        // ---- Sale (real) ----
        list.addView(menuRow("🧾", "Sale", showChevron = true) {
            startActivity(Intent(this@SettingsActivity, SaleActivity::class.java))
        })

        // ---- Purchase (real) ----
        list.addView(menuRow("🛒", "Purchase", showChevron = true) {
            startActivity(Intent(this@SettingsActivity, PurchaseActivity::class.java))
        })

        // ---- Expense (no screen yet — reflection fallback kept in case it's added later) ----
        list.addView(menuRow("💼", "Expense", trailingText = "+") {
            tryOpenActivity("com.grocerypos.v11.ui.ExpenseActivity", "Expense")
        })

        // ---- Cash & Bank (real) ----
        list.addView(menuRow("🏦", "Cash & Bank", showChevron = true) {
            startActivity(Intent(this@SettingsActivity, CashActivity::class.java))
        })

        // ---- My Online Store (no screen yet) ----
        list.addView(menuRow("🏬", "My Online Store", showNew = true) {
            comingSoon("My Online Store")
        })

        list.addView(divider())

        // ---- Sync & Share (no screen yet) ----
        list.addView(menuRow("🔄", "Sync & Share") {
            comingSoon("Sync & Share")
        })

        // ---- Settings (expandable — holds all the real settings sections) ----
        val settingsContent = buildSettingsContent()
        settingsContent.visibility = View.GONE
        val settingsRow = expandableMenuRow("⚙️", "Settings", showNew = true, target = settingsContent)
        list.addView(settingsRow)
        list.addView(settingsContent)

        // ---- Backup/Restore (expandable) ----
        val backupContent = buildBackupContent()
        backupContent.visibility = View.GONE
        val backupRow = expandableMenuRow(
            "🗄️", "Backup/Restore",
            subtitle = "Auto backup not enabled.",
            target = backupContent
        )
        list.addView(backupRow)
        list.addView(backupContent)

        list.addView(divider())

        // ---- Plans & Pricing (no screen yet) ----
        list.addView(menuRow("🏷️", "Plans & Pricing") {
            comingSoon("Plans & Pricing")
        })

        list.addView(divider())

        // ---- Logout ----
        list.addView(menuRow("🚪", "Logout", textColorHex = red) {
            doLogout()
        })

        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(list)
        })

        setContentView(root)

        applyFloatingSheetLayout()
        window.decorView.post { applyFloatingSheetLayout() }
    }

    private fun comingSoon(label: String) {
        Toast.makeText(this, "$label — Coming Soon", Toast.LENGTH_SHORT).show()
    }

    /** Opens the given activity by class name if it exists in the app; falls back to a "Coming Soon" toast otherwise.
     *  Kept only for features that don't have a real screen yet (e.g. Expense) — anything confirmed to exist
     *  (Parties, Items, Reports, Sale, Purchase, Cash & Bank) is launched directly above instead. */
    private fun tryOpenActivity(activityClassName: String, label: String) {
        try {
            val clazz = Class.forName(activityClassName)
            startActivity(Intent(this, clazz))
        } catch (e: ClassNotFoundException) {
            comingSoon(label)
        }
    }

    private fun applyFloatingSheetLayout() {
        val screenWidth = resources.displayMetrics.widthPixels
        window.setGravity(Gravity.START)
        window.setLayout(
            (screenWidth * 0.80).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun doLogout() {
        getSharedPreferences("session", MODE_PRIVATE).edit().clear().apply()
        startActivity(Intent(this@SettingsActivity, LoginActivity::class.java))
        finish()
    }

    // ================= HEADER =================
    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(headerBlue))
            setPadding(20, 44, 20, 22)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconCircle = FrameLayout(this).apply {
            val px = (52 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(px, px)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }
        iconCircle.addView(TextView(this).apply {
            text = "🏪"
            textSize = 24f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        topRow.addView(iconCircle)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 0, 0)
        }
        textCol.addView(TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = "Point of Sale"
            textSize = 12f
            setTextColor(Color.parseColor("#C9DDF5"))
            setPadding(0, 2, 0, 0)
        })
        topRow.addView(textCol)
        header.addView(topRow)

        return header
    }

    // ================= MENU ROW HELPERS =================

    /** A simple, non-expanding menu row: icon + label (+ optional NEW badge / chevron / trailing text). */
    private fun menuRow(
        icon: String,
        label: String,
        showNew: Boolean = false,
        showChevron: Boolean = false,
        trailingText: String? = null,
        textColorHex: String = textDark,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 22, 20, 22)
            isClickable = true
            isFocusable = true
        }
        row.addView(TextView(this).apply {
            text = icon
            textSize = 18f
            setPadding(0, 0, 24, 0)
        })
        row.addView(TextView(this).apply {
            text = label
            textSize = 14.5f
            setTextColor(Color.parseColor(textColorHex))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (showNew) {
            row.addView(newBadge())
            row.addView(spacerH(10))
        }
        if (trailingText != null) {
            row.addView(TextView(this).apply {
                text = trailingText
                textSize = 18f
                setTextColor(Color.parseColor(textGray))
            })
        } else if (showChevron) {
            row.addView(TextView(this).apply {
                text = "⌄"
                textSize = 16f
                setTextColor(Color.parseColor(textGray))
            })
        }
        row.setOnClickListener { onClick() }
        return row
    }

    /** A menu row with an optional subtitle line that expands/collapses a target view when tapped. */
    private fun expandableMenuRow(
        icon: String,
        label: String,
        showNew: Boolean = false,
        subtitle: String? = null,
        target: View
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 22, 20, 22)
            isClickable = true
            isFocusable = true
        }
        row.addView(TextView(this).apply {
            text = icon
            textSize = 18f
            setPadding(0, 0, 24, 0)
        })

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = label
            textSize = 14.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        })
        if (subtitle != null) {
            textCol.addView(TextView(this).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.parseColor(textGray))
                setPadding(0, 2, 0, 0)
            })
        }
        row.addView(textCol)

        if (showNew) {
            row.addView(newBadge())
            row.addView(spacerH(10))
        }

        val chevron = TextView(this).apply {
            text = "⌄"
            textSize = 16f
            setTextColor(Color.parseColor(textGray))
        }
        row.addView(chevron)

        row.setOnClickListener {
            val expanding = target.visibility != View.VISIBLE
            target.visibility = if (expanding) View.VISIBLE else View.GONE
            chevron.text = if (expanding) "⌃" else "⌄"
        }
        return row
    }

    private fun newBadge() = TextView(this).apply {
        text = "NEW"
        textSize = 9.5f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(12, 4, 12, 4)
        background = roundedBg(badgeRed, 20)
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * resources.displayMetrics.density).toInt())
        setBackgroundColor(Color.parseColor(border))
    }

    private fun spacerH(widthDp: Int) = View(this).apply {
        val px = (widthDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(px, 1)
    }

    // ================= EXPANDABLE "SETTINGS" CONTENT (all the original sections) =================
    private fun buildSettingsContent(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 4, 16, 16)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ---- Shop Info ----
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
        container.addView(shopCard)
        container.addView(spacer(10))
        loadShopSettings()

        // ---- Printer ----
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
        statusRow.addView(spacerH(10))
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
        container.addView(printerCard)
        container.addView(spacer(10))

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
        // "No Password" — app opens straight to the dashboard, no login screen at all
        // (see LoginActivity.onCreate — it checks this setting before building any UI).
        noPasswordRadio = RadioButton(this).apply {
            text = "No Password (App won't lock)"
            setTextColor(Color.parseColor(textDark))
            textSize = 13.5f
        }
        loginMethodGroup.addView(passwordOnlyRadio)
        loginMethodGroup.addView(fingerprintOnlyRadio)
        loginMethodGroup.addView(bothRadio)
        loginMethodGroup.addView(noPasswordRadio)
        securityCard.addView(loginMethodGroup)

        loginMethodGroup.setOnCheckedChangeListener { _, checkedId ->
            val method = when (checkedId) {
                fingerprintOnlyRadio.id -> "fingerprint"
                bothRadio.id -> "both"
                noPasswordRadio.id -> "none"
                else -> "password"
            }
            lifecycleScope.launch {
                val db = PosDatabase.get(this@SettingsActivity)
                db.appSettingDao().set(AppSetting("login_method", method))
                com.grocerypos.v11.AppLock.updateCachedLoginMethod(method)
            }
        }
        container.addView(securityCard)
        container.addView(spacer(10))
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
        container.addView(loginCard)
        container.addView(spacer(10))

        // ---- Language ----
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
        container.addView(languageCard)
        container.addView(spacer(10))

        // ---- Users ----
        val usersCard = sectionCard("Users & Account")
        usersCard.addView(secondaryButton("MANAGE USERS", navy) {
            startActivity(Intent(this@SettingsActivity, UserManagementActivity::class.java))
        })
        container.addView(usersCard)

        return container
    }

    // ================= EXPANDABLE "BACKUP/RESTORE" CONTENT =================
    private fun buildBackupContent(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 4, 16, 16)
            setBackgroundColor(Color.parseColor(bg))
        }
        val backupCard = sectionCard("Backup & Restore")
        backupCard.addView(primaryButton("BACKUP NOW", teal) { onBackupClicked() })
        backupCard.addView(spacer(8))
        backupCard.addView(primaryButton("RESTORE BACKUP", red) { onRestoreClicked() })
        backupCard.addView(TextView(this).apply {
            text = "⚠️ Restore purani backup laata hai aur is waqt ka sara naya data (jo backup ke baad add hua) permanently mita deta hai. Sirf tab use karein jab aapko waqai purani state par jaana ho."
            textSize = 11f
            setTextColor(Color.parseColor(red))
            setPadding(4, 10, 4, 0)
        })
        container.addView(backupCard)
        return container
    }

    // ================= UI HELPERS =================
    private fun sectionCard(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 16, 20, 16)
        background = strokedBg(border, cardBg, 16)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
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

    // ================= SECURITY (LOGIN METHOD) =================
    private fun loadLoginMethodSetting() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            when (db.appSettingDao().get("login_method")?.value ?: "password") {
                "fingerprint" -> fingerprintOnlyRadio.isChecked = true
                "both" -> bothRadio.isChecked = true
                "none" -> noPasswordRadio.isChecked = true
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
            .setPositiveButton("Restore", null)
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
