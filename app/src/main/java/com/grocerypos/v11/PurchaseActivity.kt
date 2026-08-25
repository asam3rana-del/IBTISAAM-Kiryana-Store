package com.grocerypos.v11.ui

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.grocerypos.v11.*
import com.grocerypos.v11.util.ThemeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
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

private suspend fun genBillNo(db: PosDatabase): String {
    val prefix = "PUR-" + SimpleDateFormat("MMMyy", Locale.getDefault()).format(Date()) + "-"
    val existing = db.purchaseDao().allPurchases().map { it.billNo }.toHashSet()
    var seqNum = existing.count { it.startsWith(prefix) } + 1
    var candidate = prefix + seqNum.toString().padStart(4, '0')
    while (existing.contains(candidate)) {
        seqNum++
        candidate = prefix + seqNum.toString().padStart(4, '0')
    }
    return candidate
}

class PurchaseActivity : ThemedActivity() {

    companion object {
        const val EXTRA_BILL_NO = "billNo"
        private const val PREFS_NAME = "purchase_draft_prefs"
        private const val KEY_DRAFT = "draft_json"
        private const val TAG = "PurchaseActivity"
    }

    // ---------- Premium palette ----------
    private var bg = "#F5F7FA"
    private var cardWhite = "#FFFFFF"
    private var textDark = "#111827"
    private var textMuted = "#8892A0"
    private var border = "#E7EAF0"
    private var red = "#E5484D"
    private var fieldFill = "#FAFBFD"

    private val navy = "#101B33"
    private val navyLight = "#1C2C4F"
    private val teal = "#0EA5A0"
    private val gold = "#C9A24B"
    private val amberBadge = "#F4F1E8"
    private val successGreen = "#1E9E6B"

    private fun loadThemePrefs() {
        val p = ThemeManager.palette(this)
        bg = p.bg
        cardWhite = p.cardWhite
        textDark = p.textDark
        textMuted = p.textMuted
        border = p.border
        red = p.red
        fieldFill = p.fieldFill
    }

    private fun toggleTheme() {
        ThemeManager.toggleDarkMode(this)
        recreate()
    }

    private lateinit var dateValueText: TextView
    private lateinit var firmNameText: TextView
    private lateinit var supplierBalanceText: TextView
    private lateinit var partyBox: LinearLayout
    private lateinit var partyName: AutoCompleteTextView
    private lateinit var itemEntrySection: LinearLayout
    private lateinit var addItemsTrigger: TextView
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var unitToggleRow: LinearLayout
    private lateinit var rate: EditText
    private lateinit var totalLotPrice: EditText
    private lateinit var conversionInfo: TextView
    private lateinit var totalAmountText: TextView
    private lateinit var addItemButton: Button
    private lateinit var billedItemsHeader: LinearLayout
    private lateinit var billedItemsSummaryText: TextView
    private lateinit var billedItemsChevron: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var grandTotalText: TextView
    private lateinit var paymentSection: LinearLayout
    private lateinit var paidInput: EditText
    private lateinit var dueAmountText: TextView
    private lateinit var paidWarningText: TextView
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button
    private lateinit var overflowButton: TextView
    private lateinit var scrollArea: ScrollView

    private var suppliers = listOf<Supplier>()
    private var products = listOf<Product>()
    private var allUnits = listOf("pcs", "kg", "box", "dozen", "carton", "ctn", "outer", "dabbi")
    private val lines = mutableListOf<PurchaseLine>()
    private var purchaseDateMillis = System.currentTimeMillis()
    private var selectedProduct: Product? = null

    private var lastMainQty: Double = 0.0
    private var suppressQtyWatcher = false
    private var lastMainRate: Double = 0.0
    private var suppressRateWatcher = false
    private var suppressTotalLotWatcher = false
    private var editBillNo: String? = null
    private var originalPurchase: Purchase? = null
    private var originalItems: List<PurchaseItem> = emptyList()
    private var suppressDraftSave = false
    private var draftRestored = false

    private var isSaving = false

    private var scrollTargetView: View? = null
    private var scrollAlignTop: Boolean = true

    private val billScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val json = result.data?.getStringExtra(BillScanActivity.RESULT_ITEMS_JSON)
            if (!json.isNullOrBlank()) handleScannedItems(json)
        }
    }

    private fun safeLaunch(label: String, block: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "safeLaunch[$label] failed", e)
                Toast.makeText(this@PurchaseActivity, "Error in $label: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        com.grocerypos.v11.util.CrashHandler.install(this)
        com.grocerypos.v11.util.CrashHandler.getLastCrash(this)?.let { crashText -> showCrashDialog(crashText) }
        try {
            loadThemePrefs()
            editBillNo = intent.getStringExtra(EXTRA_BILL_NO)
            buildUi()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: fatal error building Purchase screen", e)
            Toast.makeText(this, "Purchase screen error: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun showCrashDialog(crashText: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Pichli baar app crash hui thi")
            .setMessage(if (crashText.length > 3000) crashText.take(3000) + "\n\n…(truncated, use Share for full text)" else crashText)
            .setPositiveButton("Share") { _, _ ->
                startActivity(Intent.createChooser(com.grocerypos.v11.util.CrashHandler.shareIntent(crashText), "Share crash log"))
                com.grocerypos.v11.util.CrashHandler.clearLastCrash(this)
            }
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash", crashText))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss") { _, _ -> com.grocerypos.v11.util.CrashHandler.clearLastCrash(this) }
            .setCancelable(false)
            .show()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 80)
            setBackgroundColor(Color.parseColor(bg))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 30, 22, 26)
            background = gradientBg(navy, navyLight, cornerBottom = 26)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(-24, 0, -24, 16) }
            applyElevation(this, 8f)
        }
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = if (editBillNo != null) com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Edit Purchase", "خریداری میں ترمیم") else com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Purchase", "خریداری")
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.01f
        })
        headerCol.addView(TextView(this).apply {
            text = "STOCK IN · SUPPLIER BILLING"
            textSize = 10.5f
            setTextColor(Color.parseColor("#A7B4CC"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, 5, 0, 0)
        })
        header.addView(headerCol)
        header.addView(pillChip("History") {
            startActivity(Intent(this@PurchaseActivity, PurchaseHistoryActivity::class.java))
        })
        header.addView(spacer(8).apply { layoutParams = LinearLayout.LayoutParams((8 * resources.displayMetrics.density).toInt(), 1) })
        header.addView(TextView(this).apply {
            text = if (ThemeManager.isDarkMode(this@PurchaseActivity)) "☀️" else "🌙"
            textSize = 15f
            gravity = Gravity.CENTER
            background = ovalBg("#22FFFFFF")
            val px = (34 * resources.displayMetrics.density).toInt(); width = px; height = px
            setOnClickListener { toggleTheme() }
        })
        header.addView(spacer(8).apply { layoutParams = LinearLayout.LayoutParams((8 * resources.displayMetrics.density).toInt(), 1) })
        overflowButton = TextView(this).apply {
            text = "\u22EE"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = ovalBg("#22FFFFFF")
            val px = (34 * resources.displayMetrics.density).toInt(); width = px; height = px
            setOnClickListener { showOverflowMenu(it) }
        }
        header.addView(overflowButton)
        root.addView(header)

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 14) }
        }
        val dateChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 12, 22, 12)
            background = strokedBg(border, cardWhite, 30)
            applyElevation(this, 1.5f)
            setOnClickListener { openDatePicker() }
        }
        dateChip.addView(TextView(this).apply { text = "\uD83D\uDCC5  "; textSize = 13f })
        dateValueText = TextView(this).apply {
            text = formatDate(purchaseDateMillis)
            textSize = 13f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        dateChip.addView(dateValueText)
        dateChip.addView(TextView(this).apply { text = "  \u203A"; textSize = 13f; setTextColor(Color.parseColor(teal)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        topRow.addView(dateChip)
        root.addView(topRow)

        val firmBox = premiumCard().apply { setPadding(22, 16, 22, 16) }
        val firmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val firmCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        firmCol.addView(TextView(this).apply { text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Firm Name", "فرم کا نام").uppercase(); textSize = 9.5f; setTextColor(Color.parseColor(textMuted)); setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.05f })
        firmNameText = TextView(this).apply { text = "IBTISAAM Kiryana Store"; textSize = 13.5f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 2, 0, 0) }
        firmCol.addView(firmNameText)
        firmRow.addView(firmCol)
        val balCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        balCol.addView(TextView(this).apply { text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Party Balance", "پارٹی بیلنس").uppercase(); textSize = 9.5f; setTextColor(Color.parseColor(textMuted)); setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.05f })
        supplierBalanceText = TextView(this).apply { text = "Rs 0.00"; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor(textMuted)); setPadding(0, 2, 0, 0) }
        balCol.addView(supplierBalanceText)
        firmRow.addView(balCol)
        firmBox.addView(firmRow)
        root.addView(firmBox)

        partyBox = premiumCard()
        partyBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Party / Supplier", "پارٹی / سپلائر")))
        val partyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        partyName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Party Name (Supplier) *", "پارٹی کا نام (سپلائر) *")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15.5f
            threshold = 1
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        partyRow.addView(partyName)
        partyRow.addView(circleIcon("+", teal, 32) { promptAddSupplier() })
        partyBox.addView(partyRow)
        root.addView(partyBox)
        root.addView(spacer(18))

        itemEntrySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 22, 24, 22)
            background = strokedBg(border, cardWhite, 20)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
            applyElevation(this, 3f)
        }

        val addItemHeaderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 16) }
        addItemsTrigger = TextView(this).apply {
            text = "\u2795  " + com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Add Item", "آئٹم شامل کریں")
            textSize = 15f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        addItemHeaderRow.addView(addItemsTrigger)
        itemEntrySection.addView(addItemHeaderRow)

        val itemBox = innerField()
        itemBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Item Name", "آئٹم کا نام")))
        val itemNameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        itemName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Type to search…", "تلاش کے لیے لکھیں…")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15.5f
            threshold = 1
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        itemNameRow.addView(itemName)
        itemNameRow.addView(circleIcon("+", teal, 30) { openAddProductDialog(itemName.text.toString().trim()) })
        itemBox.addView(itemNameRow)
        itemEntrySection.addView(itemBox)

        unitToggleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        itemEntrySection.addView(unitToggleRow)
        itemEntrySection.addView(spacer(10))

        val qtyUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qtyBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } }
        qtyBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Quantity", "مقدار")))
        qty = EditText(this).apply {
            hint = "0"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15.5f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        qtyBox.addView(qty)
        val unitBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) } }
        unitBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Unit", "یونٹ")))
        unitSpinner = Spinner(this)
        unitBox.addView(unitSpinner)
        qtyUnitRow.addView(qtyBox)
        qtyUnitRow.addView(unitBox)
        itemEntrySection.addView(qtyUnitRow)
        itemEntrySection.addView(spacer(10))

        val rateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rateBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } }
        rateBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Rate (Per Unit)", "ریٹ")))
        rate = EditText(this).apply {
            hint = "Price / Unit"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15.5f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        rateBox.addView(rate)

        val totalLotBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) } }
        totalLotBox.addView(labelRow("Total Lot Price"))
        totalLotPrice = EditText(this).apply {
            hint = "e.g. 5000 for 2 Ctn"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15.5f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        totalLotBox.addView(totalLotPrice)
        rateRow.addView(rateBox)
        rateRow.addView(totalLotBox)
        itemEntrySection.addView(rateRow)

        conversionInfo = TextView(this).apply {
            text = ""; textSize = 12f; setTextColor(Color.parseColor(teal)); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(16, 10, 16, 10); visibility = View.GONE
            background = strokedBg("#CDEEEC", "#EFFBFA", 10)
        }
        itemEntrySection.addView(conversionInfo)
        itemEntrySection.addView(spacer(6))

        totalAmountText = TextView(this).apply { text = "Total Amount: Rs 0.00"; textSize = 14.5f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor(navy)); setPadding(6, 8, 0, 10) }
        itemEntrySection.addView(totalAmountText)

        addItemButton = Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "ADD ITEM", "آئٹم شامل کریں")
            setTextColor(Color.WHITE)
            textSize = 14.5f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = gradientBg(teal, "#0C8F8A", cornerBottom = 14, cornerTop = 14)
            setPadding(0, 26, 0, 26)
            applyElevation(this, 3f)
        }
        itemEntrySection.addView(addItemButton)
        root.addView(itemEntrySection)

        billedItemsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 16, 22, 16)
            background = gradientBg(navy, navyLight, cornerBottom = 14, cornerTop = 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 4, 0, 0) }
            visibility = View.GONE
            applyElevation(this, 2f)
            setOnClickListener { showBilledItemsDialog() }
        }
        billedItemsSummaryText = TextView(this).apply {
            text = "\uD83D\uDCCB  " + com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Billed Items", "بل شدہ آئٹمز")
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        billedItemsHeader.addView(billedItemsSummaryText)
        billedItemsChevron = TextView(this).apply { text = "\u203A"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        billedItemsHeader.addView(billedItemsChevron)
        root.addView(billedItemsHeader)
        root.addView(spacer(14))

        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 0) }

        val totalCard = premiumCard().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(24, 20, 24, 20); background = strokedBg(border, fieldFill, 18) }
        totalCard.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Total Amount", "کل رقم").uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        grandTotalText = TextView(this).apply { text = "Rs 0"; textSize = 21f; setTextColor(Color.parseColor(navy)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        totalCard.addView(grandTotalText)
        root.addView(totalCard)

        paymentSection = premiumCard().apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 20) }
        val paidRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        paidRow.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Paid Amount", "ادا شدہ رقم").uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        paidRow.addView(TextView(this).apply { text = "Rs "; textSize = 17f; setTextColor(Color.parseColor(successGreen)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        paidInput = EditText(this).apply {
            hint = "0"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(successGreen))
            background = null
            textSize = 20f
            gravity = Gravity.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minWidth = (120 * resources.displayMetrics.density).toInt()
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        paidRow.addView(paidInput)
        paymentSection.addView(paidRow)
        paidWarningText = TextView(this).apply {
            text = "⚠️ Paid khali hai - Ye Udhaar me jayega"
            textSize = 11f
            setTextColor(Color.parseColor(red))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 0)
            visibility = View.GONE
        }
        paymentSection.addView(paidWarningText)
        root.addView(paymentSection)

        val dueCard = premiumCard().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(22, 18, 22, 18); background = strokedBg(border, fieldFill, 18) }
        dueCard.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Due Amount", "باقی رقم").uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        dueAmountText = TextView(this).apply { text = "Rs 0"; textSize = 18f; setTextColor(Color.parseColor(navy)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        dueCard.addView(dueAmountText)
        root.addView(dueCard)
        root.addView(spacer(220))

        saveButton = Button(this).apply {
            text = if (editBillNo != null) com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "UPDATE PURCHASE", "خریداری اپ ڈیٹ کریں") else com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "SAVE PURCHASE", "خریداری محفوظ کریں")
            setTextColor(Color.WHITE)
            textSize = 15.5f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = gradientBg(navy, navyLight, cornerBottom = 16, cornerTop = 16)
            setPadding(0, 30, 0, 30)
            applyElevation(this, 5f)
        }
        deleteButton = Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "DELETE", "حذف کریں")
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(red, 16)
            setPadding(0, 30, 0, 30)
            applyElevation(this, 3f)
            visibility = if (editBillNo != null) View.VISIBLE else View.GONE
        }
        val saveDeleteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveDeleteRow.addView(saveButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) })
        saveDeleteRow.addView(deleteButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply { setMargins(8, 0, 0, 0) })
        root.addView(saveDeleteRow)
        root.addView(spacer(30))

        scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }

        setContentView(scrollArea)

        loadSuppliers()
        loadUnits()
        loadProducts()
        loadFirmName()
        editBillNo?.let { loadForEdit(it) }

        partyName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { itemName.requestFocus(); safeShowDropDown(itemName); true } else false
        }

        itemName.setOnItemClickListener { _, _, position, _ ->
            onItemPicked(itemName.adapter.getItem(position).toString())
            qty.requestFocus()
        }
        itemName.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                safeShowDropDown(itemName)
                scrollTargetView = itemEntrySection
                scrollAlignTop = true
                scrollArea.post { scrollToShowView(itemEntrySection, true) }
            }
        }
        itemName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { qty.requestFocus(); true } else false
        }
        itemName.addTextChangedListener(simpleWatcher {
            if (itemName.hasFocus() && itemName.text.length >= 1) safeShowDropDown(itemName)
            val match = products.find { it.name.equals(itemName.text.toString().trim(), ignoreCase = true) }
            if (match == null) {
                selectedProduct = null
                unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, allUnits)
                conversionInfo.visibility = View.GONE
                unitToggleRow.visibility = View.GONE
            }
        })

        qty.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { rate.requestFocus(); true } else false
        }
        qty.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                scrollTargetView = itemEntrySection
                scrollAlignTop = true
                scrollArea.post { scrollToShowView(itemEntrySection, true) }
            }
        }
        rate.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { totalLotPrice.requestFocus(); true } else false
        }
        rate.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                rate.post { rate.selectAll() }
                scrollTargetView = itemEntrySection
                scrollAlignTop = true
                scrollArea.post { scrollToShowView(itemEntrySection, true) }
            }
        }
        totalLotPrice.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { addItem(); true } else false
        }
        totalLotPrice.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                scrollTargetView = itemEntrySection
                scrollAlignTop = true
                scrollArea.post { scrollToShowView(itemEntrySection, true) }
            }
        }

        addItemButton.setOnClickListener { addItem() }
        saveButton.setOnClickListener { savePurchase() }
        deleteButton.setOnClickListener { confirmDeletePurchase() }

        unitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { refillAutoRate(); updateLineTotal() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        partyName.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                safeShowDropDown(partyName)
                scrollTargetView = partyBox
                scrollAlignTop = true
                scrollArea.post { scrollToShowView(partyBox, true) }
            }
        }
        partyName.setOnItemClickListener { _, _, position, _ -> updateSupplierBalanceDisplay(partyName.adapter.getItem(position).toString()) }
        partyName.addTextChangedListener(simpleWatcher {
            updateSupplierBalanceDisplay(partyName.text.toString().trim())
            if (partyName.hasFocus() && partyName.text.length >= 1) safeShowDropDown(partyName)
        })

        qty.addTextChangedListener(simpleWatcher {
            if (!suppressQtyWatcher) { lastMainQty = toMainUnitQty(qty.text.toString().toDoubleOrNull() ?: 0.0) }
            updateLineTotal()
        })
        rate.addTextChangedListener(simpleWatcher {
            if (suppressRateWatcher) return@simpleWatcher
            lastMainRate = toMainUnitRate(rate.text.toString().toDoubleOrNull() ?: 0.0)
            val q = qty.text.toString().toDoubleOrNull() ?: 0.0
            val r = rate.text.toString().toDoubleOrNull() ?: 0.0
            if (q > 0 && r > 0) {
                suppressTotalLotWatcher = true
                totalLotPrice.setText("%.2f".format(q * r).trimEnd('0').trimEnd('.'))
                suppressTotalLotWatcher = false
            }
            updateLineTotal()
        })
        totalLotPrice.addTextChangedListener(simpleWatcher {
            if (suppressTotalLotWatcher) return@simpleWatcher
            val totalLot = totalLotPrice.text.toString().toDoubleOrNull() ?: 0.0
            val q = qty.text.toString().toDoubleOrNull() ?: 0.0
            if (q > 0 && totalLot > 0) {
                val newRate = totalLot / q
                suppressRateWatcher = true
                rate.setText("%.2f".format(newRate).trimEnd('0').trimEnd('.'))
                suppressRateWatcher = false
                lastMainRate = toMainUnitRate(newRate)
                updateLineTotal()
            }
        })
        paidInput.addTextChangedListener(simpleWatcher { updateGrandTotal() })

        paidInput.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                scrollTargetView = paymentSection
                scrollAlignTop = false
                scrollArea.post { scrollToShowView(paymentSection, false) }
            }
        }
        scrollArea.viewTreeObserver.addOnGlobalLayoutListener {
            scrollTargetView?.let { scrollToShowView(it, scrollAlignTop) }
        }

        if (editBillNo == null) restoreDraftIfAny()
    }

    private fun scrollToShowView(target: View, alignTop: Boolean) {
        if (!::scrollArea.isInitialized) return
        val visibleFrame = Rect()
        scrollArea.getWindowVisibleDisplayFrame(visibleFrame)
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val top = location[1]
        val bottom = top + target.height
        val extraPadding = (24 * resources.displayMetrics.density).toInt()
        if (alignTop) {
            scrollArea.scrollBy(0, top - visibleFrame.top - extraPadding)
        } else {
            when {
                bottom > visibleFrame.bottom -> scrollArea.scrollBy(0, (bottom - visibleFrame.bottom) + extraPadding)
                top < visibleFrame.top -> scrollArea.scrollBy(0, top - visibleFrame.top - extraPadding)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (editBillNo == null && !suppressDraftSave) saveDraft()
    }

    private fun draftPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun saveDraft() {
        try {
            val hasContent = lines.isNotEmpty() || partyName.text.toString().isNotBlank() || itemName.text.toString().isNotBlank() || qty.text.toString().isNotBlank() || rate.text.toString().isNotBlank()
            if (!hasContent) { clearDraft(); return }
            val linesArray = JSONArray()
            lines.forEach { line ->
                linesArray.put(JSONObject().apply {
                    put("itemName", line.itemName); put("barcode", line.barcode ?: ""); put("qty", line.qty); put("unit", line.unit); put("rate", line.rate); put("amount", line.amount)
                    put("mainUnit", line.mainUnit); put("secondaryUnit", line.secondaryUnit); put("secondaryUnitQty", line.secondaryUnitQty)
                    put("tertiaryUnit", line.tertiaryUnit); put("tertiaryUnitQty", line.tertiaryUnitQty)
                })
            }
            val draft = JSONObject().apply {
                put("party", partyName.text.toString()); put("paid", paidInput.text.toString()); put("dateMillis", purchaseDateMillis)
                put("pendingItemName", itemName.text.toString()); put("pendingQty", qty.text.toString()); put("pendingRate", rate.text.toString()); put("lines", linesArray)
            }
            draftPrefs().edit().putString(KEY_DRAFT, draft.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveDraft failed", e)
        }
    }
    private fun clearDraft() { draftPrefs().edit().remove(KEY_DRAFT).apply() }
    private fun restoreDraftIfAny() {
        try {
            val raw = draftPrefs().getString(KEY_DRAFT, null) ?: return
            val draft = try { JSONObject(raw) } catch (e: Exception) { null } ?: return
            if (draftRestored) return
            draftRestored = true
            suppressDraftSave = true
            try {
                val party = draft.optString("party", ""); if (party.isNotBlank()) { partyName.setText(party); updateSupplierBalanceDisplay(party) }
                val paid = draft.optString("paid", ""); if (paid.isNotBlank()) paidInput.setText(paid)
                val savedDate = draft.optLong("dateMillis", 0L); if (savedDate > 0L) { purchaseDateMillis = savedDate; dateValueText.text = formatDate(purchaseDateMillis) }
                val linesArray = draft.optJSONArray("lines")
                if (linesArray != null) {
                    for (i in 0 until linesArray.length()) {
                        try {
                            val o = linesArray.getJSONObject(i)
                            lines.add(PurchaseLine(o.optString("itemName"), o.optString("barcode").ifBlank { null }, o.optDouble("qty", 0.0), o.optString("unit"), o.optDouble("rate", 0.0), o.optDouble("amount", 0.0), o.optString("mainUnit"), o.optString("secondaryUnit"), o.optDouble("secondaryUnitQty", 0.0), o.optString("tertiaryUnit"), o.optDouble("tertiaryUnitQty", 0.0)))
                        } catch (e: Exception) { Log.e(TAG, "restoreDraftIfAny: skipping bad line $i", e) }
                    }
                    renderItemsList(); updateGrandTotal()
                }
                val pendingItemName = draft.optString("pendingItemName", ""); if (pendingItemName.isNotBlank()) { itemName.setText(pendingItemName); val match = products.find { it.name.equals(pendingItemName, ignoreCase = true) }; if (match != null) applyPickedProduct(match) }
                val pendingQty = draft.optString("pendingQty", ""); if (pendingQty.isNotBlank()) qty.setText(pendingQty)
                val pendingRate = draft.optString("pendingRate", ""); if (pendingRate.isNotBlank()) rate.setText(pendingRate)
                updateLineTotal()
            } finally { suppressDraftSave = false }
        } catch (e: Exception) {
            Log.e(TAG, "restoreDraftIfAny failed - clearing corrupt draft", e)
            try { clearDraft() } catch (ignored: Exception) {}
            suppressDraftSave = false
        }
    }

    private fun loadFirmName() = safeLaunch("loadFirmName") {
        val savedName = PosDatabase.get(this@PurchaseActivity).appSettingDao().get("shop_name")?.value
        if (!savedName.isNullOrBlank()) firmNameText.text = savedName
    }
    private fun updateSupplierBalanceDisplay(name: String) {
        val supplier = suppliers.find { it.name.equals(name, ignoreCase = true) }
        if (supplier == null) { supplierBalanceText.text = "Rs 0.00"; supplierBalanceText.setTextColor(Color.parseColor(textMuted)); return }
        supplierBalanceText.text = "Rs %.2f".format(supplier.balance)
        supplierBalanceText.setTextColor(Color.parseColor(if (supplier.balance > 0) red else successGreen))
    }

    private fun premiumCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 18, 22, 18)
        background = strokedBg(border, cardWhite, 18)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 14) }
        applyElevation(this, 3f)
    }
    private fun innerField() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 13, 18, 13)
        background = strokedBg(border, fieldFill, 14)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
    }
    private fun labelRow(label: String) = TextView(this).apply {
        text = label.uppercase(); textSize = 10.5f; setTextColor(Color.parseColor(textMuted)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 6); letterSpacing = 0.05f
    }
    private fun pillChip(label: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = label; textSize = 12.5f; setTextColor(Color.parseColor(navy)); setTypeface(typeface, android.graphics.Typeface.BOLD); background = roundedBg(cardWhite, 30); setPadding(24, 13, 24, 13); setOnClickListener { onClick() }
    }
    private fun circleIcon(label: String, colorHex: String, sizeDp: Int, onClick: (() -> Unit)? = null) = TextView(this).apply {
        this.text = label; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = ovalBg(colorHex); val px = (sizeDp * resources.displayMetrics.density).toInt(); width = px; height = px; if (onClick != null) setOnClickListener { onClick() }
    }

    private fun showBilledItemsDialog() {
        if (lines.isEmpty()) return
        (itemsContainer.parent as? ViewGroup)?.removeView(itemsContainer)
        val wrapper = ScrollView(this).apply {
            setPadding(20, 10, 20, 4)
            addView(itemsContainer)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(com.grocerypos.v11.util.Loc.t(this, "Billed Items", "بل شدہ آئٹمز"))
            .setView(wrapper)
            .setPositiveButton(com.grocerypos.v11.util.Loc.t(this, "Close", "بند کریں"), null)
            .setOnDismissListener {
                paidInput.requestFocus()
                paidInput.post {
                    paidInput.selectAll()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(paidInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    scrollTargetView = paymentSection
                    scrollAlignTop = false
                    scrollArea.post { scrollToShowView(paymentSection, false) }
                }
            }
            .show()
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(com.grocerypos.v11.util.Loc.t(this, "Print", "پرنٹ"))
        popup.menu.add(com.grocerypos.v11.util.Loc.t(this, "Share", "شیئر کریں"))
        popup.setOnMenuItemClickListener {
            val billNo = editBillNo
            if (billNo == null) { Toast.makeText(this, "Save the purchase first", Toast.LENGTH_SHORT).show() } else { openBillPreview(billNo, forSaving = false) }
            true
        }
        popup.show()
    }
    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = radius.toFloat() }
    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(fillHex)); setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex)); cornerRadius = radius.toFloat() }
    private fun ovalBg(colorHex: String) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(colorHex)) }
    private fun gradientBg(startHex: String, endHex: String, cornerTop: Int = 0, cornerBottom: Int = 0) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
    ).apply {
        val density = resources.displayMetrics.density
        cornerRadii = floatArrayOf(
            cornerTop * density, cornerTop * density,
            cornerTop * density, cornerTop * density,
            cornerBottom * density, cornerBottom * density,
            cornerBottom * density, cornerBottom * density
        )
    }
    private fun applyElevation(view: View, dp: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { view.elevation = dp * resources.displayMetrics.density; view.outlineProvider = ViewOutlineProvider.BACKGROUND }
    }
    private fun spacer(heightDp: Int) = View(this).apply { val px = (heightDp * resources.displayMetrics.density).toInt(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px) }
    private fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange()
    }
    private fun safeShowDropDown(view: AutoCompleteTextView) {
        if (!view.isAttachedToWindow) return
        try {
            view.showDropDown()
        } catch (e: Exception) {
            Log.e(TAG, "safeShowDropDown failed for ${view.hint}", e)
        }
    }

    private fun formatDate(millis: Long) = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(millis))
    private fun formatQty(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    private fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = purchaseDateMillis }
        DatePickerDialog(this, { _, y, m, d -> cal.set(y, m, d); purchaseDateMillis = cal.timeInMillis; dateValueText.text = formatDate(purchaseDateMillis) }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }
    private fun hideKeyboard() {
        currentFocus?.let { focused -> val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager; imm?.hideSoftInputFromWindow(focused.windowToken, 0); focused.clearFocus() }
    }
    private fun loadForEdit(bill: String) = safeLaunch("loadForEdit") {
        val db = PosDatabase.get(this@PurchaseActivity)
        val purchase = db.purchaseDao().findPurchase(bill) ?: return@safeLaunch
        val items = db.purchaseDao().itemsForBill(bill)
        originalPurchase = purchase; originalItems = items
        purchaseDateMillis = purchase.createdAt; dateValueText.text = formatDate(purchaseDateMillis)
        val supplierName = purchase.supplierId?.let { id -> withTimeoutOrNull(8000) { db.supplierDao().all().first().find { it.id == id }?.name } } ?: ""
        partyName.setText(supplierName); updateSupplierBalanceDisplay(supplierName)
        paidInput.setText(if (purchase.paid > 0) Math.round(purchase.paid).toString() else "")
        lines.clear()
        items.forEach { pi ->
            val product = try { db.productDao().find(pi.barcode) } catch (e: Exception) { Log.e(TAG, "loadForEdit: product lookup failed for ${pi.barcode}", e); null }
            lines.add(PurchaseLine(product?.name ?: pi.barcode, pi.barcode, pi.qty, pi.unit.ifBlank { product?.unit ?: "" }, pi.unitCost, pi.amount, product?.unit ?: "", product?.secondaryUnit ?: "", product?.secondaryUnitQty ?: 0.0, product?.tertiaryUnit ?: "", product?.tertiaryUnitQty ?: 0.0))
        }
        renderItemsList(); updateGrandTotal(); deleteButton.visibility = View.VISIBLE
    }
    private fun loadSuppliers() = safeLaunch("loadSuppliers") {
        PosDatabase.get(this@PurchaseActivity).supplierDao().all().collectLatest { list ->
            suppliers = list
            partyName.setAdapter(ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_dropdown_item_1line, list.map { it.name }))
            updateSupplierBalanceDisplay(partyName.text.toString().trim())
        }
    }
    private fun loadUnits() = safeLaunch("loadUnits") {
        PosDatabase.get(this@PurchaseActivity).unitDao().all().collectLatest { list ->
            allUnits = (listOf("pcs", "kg", "box", "dozen", "carton", "ctn", "outer", "dabbi") + list.map { it.name }).distinct()
            if (selectedProduct == null) { unitSpinner.adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, allUnits) }
        }
    }
    private fun loadProducts() = safeLaunch("loadProducts") {
        PosDatabase.get(this@PurchaseActivity).productDao().all().collectLatest { list ->
            products = list
            itemName.setAdapter(ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_dropdown_item_1line, list.map { it.name }))
        }
    }
    private fun onItemPicked(pickedName: String) {
        val product = products.find { it.name.equals(pickedName, ignoreCase = true) } ?: return
        applyPickedProduct(product)
    }
    private fun applyPickedProduct(product: Product) {
        selectedProduct = product
        val unitOptions = mutableListOf(product.unit)
        if (product.secondaryUnit.isNotBlank() && product.secondaryUnit != product.unit) {
            unitOptions.add(product.secondaryUnit)
            if (product.tertiaryUnit.isNotBlank() && product.tertiaryUnit != product.unit && product.tertiaryUnit != product.secondaryUnit && product.tertiaryUnitQty > 0) { unitOptions.add(product.tertiaryUnit) }
        }
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitOptions)
        buildUnitChips(unitOptions, product.unit)
        conversionInfo.text = buildString {
            if (unitOptions.contains(product.secondaryUnit) && product.secondaryUnitQty > 0) { append("1 ${product.unit} = ${product.secondaryUnitQty} ${product.secondaryUnit}") }
            if (unitOptions.contains(product.tertiaryUnit) && product.tertiaryUnitQty > 0) { if (isNotEmpty()) append("   •   "); append("1 ${product.secondaryUnit} = ${product.tertiaryUnitQty} ${product.tertiaryUnit}") }
        }
        conversionInfo.visibility = if (conversionInfo.text.isNotEmpty()) View.VISIBLE else View.GONE
        lastMainQty = 0.0; lastMainRate = 0.0; refillAutoRate(); updateLineTotal()
        qty.setText(""); totalLotPrice.setText(""); qty.requestFocus()
    }
    private fun toMainUnitQty(entered: Double): Double {
        val product = selectedProduct ?: return entered
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        return when {
            chosenUnit == product.tertiaryUnit && product.tertiaryUnit.isNotEmpty() && product.tertiaryUnitQty > 0 && product.secondaryUnitQty > 0 -> entered / (product.secondaryUnitQty * product.tertiaryUnitQty)
            chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0 -> entered / product.secondaryUnitQty
            else -> entered
        }
    }
    private fun fromMainUnitRate(mainRate: Double, chosenUnit: String): Double {
        val product = selectedProduct ?: return mainRate
        return when {
            chosenUnit == product.tertiaryUnit && product.tertiaryUnit.isNotEmpty() && product.tertiaryUnitQty > 0 && product.secondaryUnitQty > 0 -> mainRate / (product.secondaryUnitQty * product.tertiaryUnitQty)
            chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0 -> mainRate / product.secondaryUnitQty
            else -> mainRate
        }
    }
    private fun toMainUnitRate(entered: Double): Double {
        val product = selectedProduct ?: return entered
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        return when {
            chosenUnit == product.tertiaryUnit && product.tertiaryUnit.isNotEmpty() && product.tertiaryUnitQty > 0 && product.secondaryUnitQty > 0 -> entered * product.secondaryUnitQty * product.tertiaryUnitQty
            chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0 -> entered * product.secondaryUnitQty
            else -> entered
        }
    }
    private fun refillAutoRate() {
        val product = selectedProduct ?: return
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        val base = if (lastMainRate > 0) lastMainRate else product.cost
        val r = fromMainUnitRate(base, chosenUnit)
        suppressRateWatcher = true
        rate.setText(if (r > 0) "%.2f".format(r) else "")
        suppressRateWatcher = false
        if (r > 0) rate.post { rate.selectAll() }
    }
    private fun buildUnitChips(options: List<String>, selected: String) {
        unitToggleRow.removeAllViews()
        if (options.size < 2) { unitToggleRow.visibility = View.GONE; return }
        unitToggleRow.visibility = View.VISIBLE
        options.forEachIndexed { index, unitLabel ->
            val isSelected = unitLabel == selected
            val chip = TextView(this).apply {
                text = unitLabel; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(28, 13, 28, 13)
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor(teal))
                background = if (isSelected) roundedBg(teal, 30) else strokedBg(teal, cardWhite, 30)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(if (index == 0) 0 else 8, 0, 0, 0) }
                setOnClickListener { unitSpinner.setSelection(options.indexOf(unitLabel)); buildUnitChips(options, unitLabel); refillAutoRate(); updateLineTotal() }
            }
            unitToggleRow.addView(chip)
        }
    }
    private fun updateLineTotal() {
        val q = qty.text.toString().toDoubleOrNull() ?: 0.0
        val r = rate.text.toString().toDoubleOrNull() ?: 0.0
        totalAmountText.text = "Total Amount: Rs %.0f".format(Math.round(q * r).toDouble())
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
        if (product == null) { openAddProductDialog(enteredName); return }
        val line = PurchaseLine(product.name, product.barcode, q, unit, r, Math.round(q * r).toDouble(), product.unit, product.secondaryUnit, product.secondaryUnitQty, product.tertiaryUnit, product.tertiaryUnitQty)
        lines.add(line); renderItemsList(); updateGrandTotal()
        itemName.setText(""); qty.setText(""); rate.setText(""); totalLotPrice.setText("")
        selectedProduct = null; lastMainQty = 0.0; lastMainRate = 0.0
        conversionInfo.visibility = View.GONE; unitToggleRow.visibility = View.GONE
        totalAmountText.text = "Total Amount: Rs 0"
        itemName.requestFocus()
        if (editBillNo == null) saveDraft()
        if (lines.isNotEmpty() && paidInput.text.toString().isBlank()) {
            paidWarningText.visibility = View.VISIBLE
            paymentSection.background = strokedBg("#FF9800", "#FFF8E1", 18)
            paymentSection.postDelayed({ paymentSection.background = strokedBg(border, cardWhite, 18) }, 2000)
        }
    }
    private fun normalizeUnitName(u: String) = u.trim().lowercase()
    private fun standardUnitQty(fromUnit: String, toUnit: String): Double? {
        val f = normalizeUnitName(fromUnit); val t = normalizeUnitName(toUnit)
        val gramNames = setOf("gram", "grams", "g", "gm"); val pieceNames = setOf("pcs", "pc", "piece", "pieces"); val mlNames = setOf("ml", "milliliter", "millilitre"); val kgNames = setOf("kg", "kgs", "kilogram", "kilograms"); val litreNames = setOf("litre", "liter", "l", "ltr"); val paoNames = setOf("pao", "pav")
        return when {
            f == "dozen" && t in pieceNames -> 12.0
            f == "gross" && t == "dozen" -> 12.0
            f == "gross" && t in pieceNames -> 144.0
            f in kgNames && t in gramNames -> 1000.0
            f in litreNames && t in mlNames -> 1000.0
            f == "quintal" && t in kgNames -> 100.0
            f == "ton" && t in kgNames -> 1000.0
            f in kgNames && t in paoNames -> 4.0
            f in paoNames && t in gramNames -> 250.0
            f == "pao" && t == "gram" -> 250.0
            else -> null
        }
    }
    private fun trimNum(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    private fun promptAddUnitInline(onAdded: (String) -> Unit) {
        hideKeyboard(); val input = EditText(this).apply { setPadding(32, 24, 32, 24) }
        android.app.AlertDialog.Builder(this).setTitle(com.grocerypos.v11.util.Loc.t(this, "New Unit", "نیا یونٹ")).setView(input).setPositiveButton(com.grocerypos.v11.util.Loc.t(this, "Add", "شامل کریں")) { _, _ ->
            val v = input.text.toString().trim()
            if (v.isNotEmpty()) safeLaunch("addUnit") { PosDatabase.get(this@PurchaseActivity).unitDao().insert(UnitType(v)); Toast.makeText(this@PurchaseActivity, com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Unit added", "یونٹ شامل ہو گیا"), Toast.LENGTH_SHORT).show(); onAdded(v) }
        }.setNegativeButton(com.grocerypos.v11.util.Loc.t(this, "Cancel", "منسوخ کریں"), null).show()
    }
    private fun handleScannedItems(json: String) {
        val arr = try { JSONArray(json) } catch (e: Exception) { Log.e(TAG, "handleScannedItems: bad json", e); return }
        safeLaunch("handleScannedItems") {
            val db = PosDatabase.get(this@PurchaseActivity); var matchedCount = 0
            for (i in 0 until arr.length()) {
                val o = try { arr.getJSONObject(i) } catch (e: Exception) { Log.e(TAG, "handleScannedItems: skipping bad item $i", e); continue }
                val scannedName = o.optString("name").trim(); val scannedQty = o.optDouble("qty", 0.0); val scannedRate = o.optDouble("rate", 0.0)
                if (scannedName.isEmpty() || scannedQty <= 0) continue
                var product = products.find { it.name.equals(scannedName, ignoreCase = true) } ?: products.find { it.name.contains(scannedName, ignoreCase = true) || scannedName.contains(it.name, ignoreCase = true) }
                if (product == null) {
                    val newProduct = Product(barcode = "P" + System.currentTimeMillis() + i, name = scannedName, category = "General", cost = scannedRate, salePrice = 0.0, wholesalePrice = 0.0, stock = 0, openingStock = 0, unit = "pcs", secondaryUnit = "", secondaryUnitQty = 0.0, tertiaryUnit = "", tertiaryUnitQty = 0.0)
                    db.productDao().upsert(newProduct); product = newProduct
                }
                lines.add(PurchaseLine(product.name, product.barcode, scannedQty, product.unit, scannedRate, Math.round(scannedQty * scannedRate).toDouble(), product.unit, product.secondaryUnit, product.secondaryUnitQty, product.tertiaryUnit, product.tertiaryUnitQty)); matchedCount++
            }
            if (matchedCount > 0) { renderItemsList(); updateGrandTotal(); if (editBillNo == null) saveDraft() }
            Toast.makeText(this@PurchaseActivity, "$matchedCount items added from scan", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderItemsList() {
        itemsContainer.removeAllViews()
        billedItemsHeader.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        val totalSoFar = lines.sumOf { it.amount }
        billedItemsSummaryText.text = "\uD83D\uDCCB  " + com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Billed Items", "بل شدہ آئٹمز") +
            "  (${lines.size})  ·  Rs %.0f".format(totalSoFar)
        lines.forEachIndexed { index, line ->
            val row = premiumCard(); val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val badge = TextView(this).apply { text = "#${index + 1}"; textSize = 11.5f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor(gold)); background = strokedBg("#E7DCB8", amberBadge, 8); setPadding(14, 5, 14, 5) }
            topRow.addView(badge)
            topRow.addView(TextView(this).apply { text = "  ${line.itemName}"; textSize = 14.5f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor(textDark)); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            topRow.addView(TextView(this).apply { text = "Rs %.0f".format(line.amount); textSize = 14.5f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor(navy)) })
            topRow.addView(TextView(this).apply { text = "  \u2715"; textSize = 14f; setTextColor(Color.parseColor(red)); setTypeface(typeface, android.graphics.Typeface.BOLD); setOnClickListener { lines.removeAt(index); renderItemsList(); updateGrandTotal(); if (editBillNo == null) saveDraft() } })
            row.addView(topRow)
            row.addView(TextView(this).apply { text = "${formatQty(line.qty)} ${line.unit} x ${line.rate} = Rs %.0f".format(line.amount); textSize = 12.5f; setTextColor(Color.parseColor(textMuted)); setPadding(0, 6, 0, 0) })
            itemsContainer.addView(row)
        }
    }
    private fun updateGrandTotal() {
        val total = Math.round(lines.sumOf { it.amount }).toDouble(); grandTotalText.text = "Rs %.0f".format(total)
        val paid = Math.round(paidInput.text.toString().toDoubleOrNull() ?: 0.0).toDouble()
        val due = (total - paid).coerceAtLeast(0.0)
        dueAmountText.text = "Rs %.0f".format(due)
        dueAmountText.setTextColor(Color.parseColor(if (due > 0) red else successGreen))
        if (paid == 0.0 && total > 0) { paidWarningText.visibility = View.VISIBLE; paidWarningText.text = "⚠️ Paid khali hai - Ye Rs %.0f Udhaar jayega".format(due) }
        else { paidWarningText.visibility = View.GONE }
    }
    private fun promptAddSupplier() {
        val input = EditText(this).apply { hint = "Supplier name"; setPadding(32, 24, 32, 24) }
        android.app.AlertDialog.Builder(this).setTitle("Add Supplier").setView(input).setPositiveButton("Add") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isNotBlank()) { safeLaunch("addSupplier") { val s = Supplier(name = name); PosDatabase.get(this@PurchaseActivity).supplierDao().insert(s); partyName.setText(name) } }
        }.setNegativeButton("Cancel", null).show()
    }
    private fun openAddProductDialog(prefillName: String) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(cardWhite)) }
        val dialogHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(28, 26, 28, 26); background = gradientBg(navy, navyLight) }
        dialogHeader.addView(TextView(this).apply { text = "\u2795  Add New Product"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        content.addView(dialogHeader)
        val scrollableBody = ScrollView(this); val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 26, 28, 8) }; scrollableBody.addView(body)
        fun microLabel(text: String) = TextView(this).apply { this.text = text; textSize = 11.5f; setTextColor(Color.parseColor(textMuted)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 8); letterSpacing = 0.04f }
        body.addView(microLabel("PRODUCT NAME"))
        val nameField = EditText(this).apply { setText(prefillName); setTextColor(Color.parseColor(textDark)); background = strokedBg(border, fieldFill, 14); setPadding(18, 16, 18, 16); textSize = 15f }
        body.addView(nameField); body.addView(spacer(18))
        body.addView(microLabel("CATEGORY"))
        val categorySpinnerBox = LinearLayout(this).apply { background = strokedBg(border, fieldFill, 14); setPadding(14, 2, 14, 2) }; val categorySpinnerDialog = Spinner(this); categorySpinnerBox.addView(categorySpinnerDialog); body.addView(categorySpinnerBox); body.addView(spacer(20))
        safeLaunch("loadCategoriesForDialog") { val cats = (listOf("General") + PosDatabase.get(this@PurchaseActivity).categoryDao().all().first().map { it.name }).distinct(); categorySpinnerDialog.adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, cats) }
        body.addView(microLabel("PRIMARY UNIT"))
        val primaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val primarySpinnerBox = LinearLayout(this).apply { background = strokedBg(border, fieldFill, 14); setPadding(14, 2, 14, 2); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val primarySpinner = Spinner(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        primarySpinnerBox.addView(primarySpinner); primaryRow.addView(primarySpinnerBox); primaryRow.addView(spacer(8).apply { layoutParams = LinearLayout.LayoutParams((10 * resources.displayMetrics.density).toInt(), 1) })
        primaryRow.addView(circleIcon("+", teal, 34) { promptAddUnitInline { newUnit -> allUnits = (allUnits + newUnit).distinct(); primarySpinner.adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, allUnits); primarySpinner.setSelection(allUnits.indexOf(newUnit)) } })
        body.addView(primaryRow); body.addView(spacer(20))
        body.addView(microLabel("SECONDARY UNIT"))
        val secondaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val secondarySpinnerBox = LinearLayout(this).apply { background = strokedBg(border, fieldFill, 14); setPadding(14, 2, 14, 2); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val secondarySpinner = Spinner(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        secondarySpinnerBox.addView(secondarySpinner); secondaryRow.addView(secondarySpinnerBox); secondaryRow.addView(spacer(8).apply { layoutParams = LinearLayout.LayoutParams((10 * resources.displayMetrics.density).toInt(), 1) })
        secondaryRow.addView(circleIcon("+", teal, 34) { promptAddUnitInline { newUnit -> allUnits = (allUnits + newUnit).distinct(); val opts = listOf("None") + allUnits; secondarySpinner.adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, opts); secondarySpinner.setSelection(opts.indexOf(newUnit)) } })
        body.addView(secondaryRow); body.addView(spacer(16))
        val secQtyBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = strokedBg(border, fieldFill, 14); setPadding(16, 4, 16, 4) }
        val secQtyField = EditText(this).apply { hint = "1 Primary = how many Secondary?"; setHintTextColor(Color.parseColor(textMuted)); setTextColor(Color.parseColor(textDark)); background = null; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        secQtyField.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) secQtyField.post { secQtyField.selectAll() } }
        secQtyBox.addView(secQtyField); body.addView(secQtyBox); body.addView(spacer(20))
        body.addView(microLabel("TERTIARY UNIT"))
        val tertiaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tertiarySpinnerBox = LinearLayout(this).apply { background = strokedBg(border, fieldFill, 14); setPadding(14, 2, 14, 2); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val tertiarySpinner = Spinner(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        tertiarySpinnerBox.addView(tertiarySpinner); tertiaryRow.addView(tertiarySpinnerBox); tertiaryRow.addView(spacer(8).apply { layoutParams = LinearLayout.LayoutParams((10 * resources.displayMetrics.density).toInt(), 1) })
        tertiaryRow.addView(circleIcon("+", teal, 34) { promptAddUnitInline { newUnit -> allUnits = (allUnits + newUnit).distinct(); val opts = listOf("None") + allUnits; tertiarySpinner.adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, opts); tertiarySpinner.setSelection(opts.indexOf(newUnit)) } })
        body.addView(tertiaryRow); body.addView(spacer(16))
        val terQtyBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = strokedBg(border, fieldFill, 14); setPadding(16, 4, 16, 4) }
        val terQtyField = EditText(this).apply { hint = "1 Secondary = how many Tertiary?"; setHintTextColor(Color.parseColor(textMuted)); setTextColor(Color.parseColor(textDark)); background = null; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        terQtyField.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) terQtyField.post { terQtyField.selectAll() } }
        terQtyBox.addView(terQtyField); body.addView(terQtyBox)
        content.addView(scrollableBody, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        primarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, allUnits)
        val secondaryOptions = listOf("None") + allUnits; secondarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, secondaryOptions)
        val tertiaryOptions = listOf("None") + allUnits; tertiarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tertiaryOptions)
        fun autoFillSecondaryQty() {
            val p = primarySpinner.selectedItem?.toString() ?: return; val s = secondarySpinner.selectedItem?.toString() ?: return; if (s == "None") return
            val std = standardUnitQty(p, s) ?: return
            secQtyField.setText(trimNum(std))
            secQtyField.post { secQtyField.selectAll() }
        }
        fun autoFillTertiaryQty() {
            val s = secondarySpinner.selectedItem?.toString() ?: return; val t = tertiarySpinner.selectedItem?.toString() ?: return; if (s == "None" || t == "None") return
            val std = standardUnitQty(s, t) ?: return
            terQtyField.setText(trimNum(std))
            terQtyField.post { terQtyField.selectAll() }
        }
        primarySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { autoFillSecondaryQty() } override fun onNothingSelected(p: AdapterView<*>?) {} }
        secondarySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { autoFillSecondaryQty(); autoFillTertiaryQty() } override fun onNothingSelected(p: AdapterView<*>?) {} }
        tertiarySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { autoFillTertiaryQty() } override fun onNothingSelected(p: AdapterView<*>?) {} }
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(28, 18, 28, 26) }; content.addView(footer)
        val dialog = android.app.AlertDialog.Builder(this).setView(content).create()
        footer.addView(TextView(this).apply { text = "Cancel"; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.parseColor(textMuted)); setTypeface(typeface, android.graphics.Typeface.BOLD); background = strokedBg(border, fieldFill, 14); setPadding(0, 22, 0, 22); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }; setOnClickListener { dialog.dismiss() } })
        footer.addView(TextView(this).apply {
            text = "Save"; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD); background = gradientBg(teal, "#0C8F8A", cornerBottom = 14, cornerTop = 14); setPadding(0, 22, 0, 22); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 0) }
            setOnClickListener {
                val pname = nameField.text.toString().trim(); if (pname.isEmpty()) { nameField.error = "Required"; return@setOnClickListener }
                val primaryUnit = primarySpinner.selectedItem?.toString() ?: "pcs"; var secondaryUnit = secondarySpinner.selectedItem?.toString() ?: "None"; val secondaryQty = secQtyField.text.toString().toDoubleOrNull() ?: 0.0; var tertiaryUnit = tertiarySpinner.selectedItem?.toString() ?: "None"; var tertiaryQty = terQtyField.text.toString().toDoubleOrNull() ?: 0.0
                if (secondaryUnit != "None" && secondaryUnit == primaryUnit) { Toast.makeText(this@PurchaseActivity, "Secondary must be different", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                if (secondaryUnit != "None" && secondaryQty <= 0) { secQtyField.error = "Enter qty"; return@setOnClickListener }
                if (tertiaryUnit != "None" && secondaryUnit == "None") { Toast.makeText(this@PurchaseActivity, "Select Secondary first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                if (secondaryUnit == "None") { tertiaryUnit = "None"; tertiaryQty = 0.0 }
                val newProduct = Product(barcode = "P" + System.currentTimeMillis(), name = pname, category = categorySpinnerDialog.selectedItem?.toString() ?: "General", cost = rate.text.toString().toDoubleOrNull() ?: 0.0, salePrice = 0.0, wholesalePrice = 0.0, stock = 0, openingStock = 0, unit = primaryUnit, secondaryUnit = if (secondaryUnit == "None") "" else secondaryUnit, secondaryUnitQty = secondaryQty, tertiaryUnit = if (tertiaryUnit == "None") "" else tertiaryUnit, tertiaryUnitQty = tertiaryQty)
                safeLaunch("saveNewProduct") { PosDatabase.get(this@PurchaseActivity).productDao().upsert(newProduct); Toast.makeText(this@PurchaseActivity, "Product added", Toast.LENGTH_SHORT).show(); itemName.setText(newProduct.name); applyPickedProduct(newProduct); dialog.dismiss() }
            }
        })
        dialog.show()
    }
    private fun confirmDeletePurchase() {
        val billNo = editBillNo ?: return
        android.app.AlertDialog.Builder(this).setTitle("Delete Purchase").setMessage("This will remove the bill and reverse its stock and supplier balance effect. Continue?").setPositiveButton("Delete") { _, _ -> deletePurchase(billNo) }.setNegativeButton("Cancel", null).show()
    }

    private suspend fun reverseStockAndCostForItems(db: PosDatabase, items: List<PurchaseItem>) {
        items.forEach { pi ->
            val product = db.productDao().find(pi.barcode) ?: return@forEach
            val factor = product.smallestUnitFactor()
            val smallestQty = product.toSmallestUnits(pi.qty, pi.unit.ifBlank { product.unit }).roundToInt()
            if (smallestQty <= 0) return@forEach

            val currentCostPerSmallest = if (factor > 0) product.cost / factor else product.cost
            val currentStock = product.stock
            val newStock = currentStock - smallestQty

            val totalValueBefore = currentStock * currentCostPerSmallest
            val totalValueAfterRemoval = (totalValueBefore - pi.amount).coerceAtLeast(0.0)

            val newCostPerSmallest = if (newStock > 0) totalValueAfterRemoval / newStock else 0.0

            db.productDao().decreaseForce(pi.barcode, smallestQty)
            db.productDao().updateCost(pi.barcode, newCostPerSmallest * factor)
        }
    }

    private fun deletePurchase(billNo: String) = safeLaunch("deletePurchase") {
        val db = PosDatabase.get(this@PurchaseActivity)
        val purchase = originalPurchase ?: db.purchaseDao().findPurchase(billNo) ?: return@safeLaunch
        val items = originalItems.ifEmpty { db.purchaseDao().itemsForBill(billNo) }
        reverseStockAndCostForItems(db, items)
        val outstanding = purchase.total - purchase.paid
        if (purchase.supplierId != null && outstanding > 0) { db.supplierDao().addBalance(purchase.supplierId, -outstanding) }
        db.purchaseDao().deleteItems(billNo); db.purchaseDao().deletePurchase(billNo); db.paymentDao().deleteByReference(billNo); db.cashTransactionDao().deleteByReference(billNo)
        SyncQueueHelper.enqueue(db, "purchase", "purchase:$billNo", "delete", org.json.JSONObject().apply { put("billNo", billNo) }.toString())
        SyncQueueHelper.trigger(this@PurchaseActivity)
        Toast.makeText(this@PurchaseActivity, "Purchase deleted", Toast.LENGTH_SHORT).show(); finish()
    }
    private fun savePurchase() {
        if (isSaving) return

        hideKeyboard()
        val party = partyName.text.toString().trim()
        if (party.isEmpty()) { partyName.error = "Required"; return }
        if (lines.isEmpty()) { Toast.makeText(this, "Add at least one item", Toast.LENGTH_SHORT).show(); return }
        val subtotal = lines.sumOf { it.amount }
        val grandTotal = Math.round(subtotal).toDouble().coerceAtLeast(0.0)
        val paidText = paidInput.text.toString().trim()
        val isPaidEmpty = paidText.isEmpty() || paidText.toDoubleOrNull() == 0.0
        if (isPaidEmpty && grandTotal > 0) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Confirm Credit Purchase")
                .setMessage("You have not entered Paid Amount.\nTotal: Rs %.0f\n\nThis bill will be saved as CREDIT (Udhaar).\nSupplier balance will increase.\n\nAre you sure?".format(grandTotal))
                .setPositiveButton("Yes, Save as Credit") { _, _ -> proceedSave(party, grandTotal) }
                .setNegativeButton("Enter Payment") { dialog, _ -> dialog.dismiss(); scrollArea.post { scrollArea.smoothScrollTo(0, paymentSection.top); paidInput.requestFocus() } }
                .show()
            return
        }
        proceedSave(party, grandTotal)
    }

    private fun proceedSave(party: String, grandTotal: Double) {
        isSaving = true
        saveButton.isEnabled = false
        safeLaunch("proceedSave") {
            try {
                val discount = 0.0
                val amountPaid = Math.round(paidInput.text.toString().toDoubleOrNull() ?: 0.0).toDouble().coerceIn(0.0, grandTotal)
                val paymentMethod = "Cash"
                val matchedSupplier = suppliers.find { it.name.equals(party, ignoreCase = true) }
                var supplierId = matchedSupplier?.id
                val db = PosDatabase.get(this@PurchaseActivity)
                val billNo = editBillNo ?: genBillNo(db)
                // FIX (Phase 1 - Data Safety): everything below (supplier insert, reversal
                // of the original purchase on edit, purchase+items insert, stock/cost
                // update per line, supplier balance update, payment + cash transaction
                // insert) now runs inside one Room transaction instead of as separate
                // sequential writes — a crash/kill partway through previously could leave
                // stock, cost, and supplier balance out of sync with the purchase record.
                val original = originalPurchase
                db.withTransaction {
                if (supplierId == null && party.isNotEmpty()) { supplierId = db.supplierDao().insert(Supplier(name = party)) }
                if (original != null) {
                    reverseStockAndCostForItems(db, originalItems)
                    val originalOutstanding = original.total - original.paid
                    if (original.supplierId != null && originalOutstanding > 0) { db.supplierDao().addBalance(original.supplierId, -originalOutstanding) }
                    db.purchaseDao().deleteItems(billNo); db.purchaseDao().deletePurchase(billNo); db.paymentDao().deleteByReference(billNo); db.cashTransactionDao().deleteByReference(billNo)
                }
                val purchaseRecord = Purchase(billNo = billNo, supplierId = supplierId, total = grandTotal, paid = amountPaid, createdAt = purchaseDateMillis, subtotal = lines.sumOf { it.amount }, discount = discount)
                db.purchaseDao().purchase(purchaseRecord)
                val purchaseItems = lines.map { line -> PurchaseItem(billNo = billNo, barcode = line.barcode ?: "", qty = line.qty, unitCost = line.rate, amount = line.amount, unit = line.unit) }
                db.purchaseDao().items(purchaseItems)
                SyncQueueHelper.enqueue(
                    db, "purchase", SyncQueueHelper.purchaseEntityId(purchaseRecord), if (original != null) "update" else "create",
                    SyncQueueHelper.purchaseJson(purchaseRecord, purchaseItems.size)
                )
                lines.forEach { line ->
                    val barcode = line.barcode ?: return@forEach
                    val before = db.productDao().find(barcode) ?: return@forEach
                    val purchasedSmallest = before.toSmallestUnits(line.qty, line.unit).roundToInt()
                    db.productDao().increase(barcode, purchasedSmallest)
                    if (purchasedSmallest > 0) {
                        val oldStockSmallest = before.stock
                        val factor = before.smallestUnitFactor()
                        val oldCostPerSmallest = if (factor > 0) before.cost / factor else before.cost
                        val purchaseRatePerSmallest = line.amount / purchasedSmallest
                        val newCostPerSmallest = if (oldStockSmallest <= 0) purchaseRatePerSmallest
                            else ((oldStockSmallest * oldCostPerSmallest) + (purchasedSmallest * purchaseRatePerSmallest)) / (oldStockSmallest + purchasedSmallest).toDouble()
                        db.productDao().updateCost(barcode, newCostPerSmallest * factor)
                    }
                }
                val outstanding = grandTotal - amountPaid
                if (supplierId != null && outstanding > 0) { db.supplierDao().addBalance(supplierId!!, outstanding) }
                if (supplierId != null && amountPaid > 0) {
                    val payment = Payment(reference = billNo, partyType = "supplier", partyId = supplierId, amount = amountPaid, method = paymentMethod, note = if (original != null) "Purchase payment (edited)" else "Purchase payment")
                    db.paymentDao().insert(payment)
                    SyncQueueHelper.enqueue(db, "payment", SyncQueueHelper.paymentEntityId(payment), "create", SyncQueueHelper.paymentJson(payment))
                }
                if (amountPaid > 0) {
                    val cashTx = CashTransaction(type = "OUT", method = paymentMethod.lowercase(), amount = amountPaid, reason = "Purchase", reference = billNo)
                    val cashTxId = db.cashTransactionDao().insert(cashTx)
                    val savedCashTx = cashTx.copy(id = cashTxId)
                    SyncQueueHelper.enqueue(db, "cash_transaction", SyncQueueHelper.cashTransactionEntityId(savedCashTx), "create", SyncQueueHelper.cashTransactionJson(savedCashTx))
                }
                } // end db.withTransaction
                suppressDraftSave = true; clearDraft(); editBillNo = billNo
                SyncQueueHelper.trigger(this@PurchaseActivity)
                Toast.makeText(this@PurchaseActivity, if (original != null) "Purchase updated" else "Purchase saved", Toast.LENGTH_SHORT).show()
                openBillPreview(billNo, forSaving = true, party = party, grandTotal = grandTotal, discount = discount, amountPaid = amountPaid, paymentMethod = paymentMethod)
            } finally {
                isSaving = false
                saveButton.isEnabled = true
            }
        }
    }

    private fun openBillPreview(billNo: String, forSaving: Boolean, party: String = partyName.text.toString().trim(), grandTotal: Double = Math.round(lines.sumOf { it.amount }).toDouble(), discount: Double = 0.0, amountPaid: Double = Math.round(paidInput.text.toString().toDoubleOrNull() ?: 0.0).toDouble(), paymentMethod: String = "Cash") {
        val itemsEncoded = lines.joinToString("\u0002") { listOf(it.itemName, formatQty(it.qty), it.unit, it.rate, it.amount).joinToString("\u0003") }
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
        startActivity(previewIntent); if (forSaving) finish()
    }
}
