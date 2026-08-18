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
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.*
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

    private val bg = "#F4F6F8"
    private val cardWhite = "#FFFFFF"
    private val navy = "#0F9B8E"
    private val teal = "#0B2545"
    private val textDark = "#0B2545"
    private val textMuted = "#7C8798"
    private val border = "#E3E8EE"
    private val red = "#E5484D"
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
    private lateinit var totalLotPrice: EditText
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
    private var itemsExpanded = true

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

    private val billScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val json = result.data?.getStringExtra(BillScanActivity.RESULT_ITEMS_JSON)
            if (!json.isNullOrBlank()) handleScannedItems(json)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        editBillNo = intent.getStringExtra(EXTRA_BILL_NO)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 80)
            setBackgroundColor(Color.parseColor(bg))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 22, 22)
            background = roundedBg(navy, 20)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
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
        header.addView(spacer(8).apply { layoutParams = LinearLayout.LayoutParams((8 * resources.displayMetrics.density).toInt(), 1) })
        overflowButton = TextView(this).apply {
            text = "\u22EE"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(18, 8, 6, 8)
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
        dateChip.addView(TextView(this).apply { text = " \u203A"; textSize = 13f; setTextColor(Color.parseColor(teal)) })
        topRow.addView(dateChip)
        root.addView(topRow)

        val firmBox = premiumCard().apply { setPadding(20, 14, 20, 14) }
        val firmCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        firmCol.addView(TextView(this).apply { text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Firm Name", "فرم کا نام"); textSize = 10f; setTextColor(Color.parseColor(textMuted)) })
        firmNameText = TextView(this).apply { text = "IBTISAAM Kiryana Store"; textSize = 13f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        firmCol.addView(firmNameText)
        firmCol.addView(TextView(this).apply { text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Party Balance", "پارٹی بیلنس"); textSize = 10f; setTextColor(Color.parseColor(textMuted)); setPadding(0, 8, 0, 0) })
        supplierBalanceText = TextView(this).apply { text = "Rs 0.00"; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor(textMuted)) }
        firmCol.addView(supplierBalanceText)
        firmBox.addView(firmCol)
        root.addView(firmBox)

        val partyBox = premiumCard()
        partyBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Party / Supplier", "پارٹی / سپلائر")))
        val partyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        partyName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Party Name (Supplier) *", "پارٹی کا نام (سپلائر) *")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            threshold = 1
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        partyRow.addView(partyName)
        partyRow.addView(circleIcon("+", teal, 30) { promptAddSupplier() })
        partyBox.addView(partyRow)
        root.addView(partyBox)
        root.addView(spacer(18))

        itemEntrySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 20)
            background = strokedBg(border, cardWhite, 18)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
            applyElevation(this, 2f)
        }

        val addItemHeaderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 14) }
        addItemsTrigger = TextView(this).apply {
            text = "\u2795  " + com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Add Item", "آئٹم شامل کریں")
            textSize = 14.5f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        addItemHeaderRow.addView(addItemsTrigger)
        addItemHeaderRow.addView(TextView(this).apply {
            text = "\uD83D\uDCF7  " + com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Scan Bill", "بل اسکین کریں")
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(teal, 30)
            setPadding(22, 12, 22, 12)
            setOnClickListener { billScanLauncher.launch(Intent(this@PurchaseActivity, BillScanActivity::class.java)) }
        })
        itemEntrySection.addView(addItemHeaderRow)

        val itemBox = innerField()
        itemBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Item Name", "آئٹم کا نام")))
        val itemNameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        itemName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Type to search…", "تلاش کے لیے لکھیں…")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
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
            textSize = 15f
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
            textSize = 15f
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
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        totalLotBox.addView(totalLotPrice)
        rateRow.addView(rateBox)
        rateRow.addView(totalLotBox)
        itemEntrySection.addView(rateRow)

        conversionInfo = TextView(this).apply { text = ""; textSize = 12f; setTextColor(Color.parseColor(teal)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(6, 10, 0, 0); visibility = View.GONE }
        itemEntrySection.addView(conversionInfo)

        totalAmountText = TextView(this).apply { text = "Total Amount: Rs 0.00"; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor(teal)); setPadding(6, 10, 0, 10) }
        itemEntrySection.addView(totalAmountText)

        addItemButton = Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "ADD ITEM", "آئٹم شامل کریں")
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(teal, 14)
            setPadding(0, 24, 0, 24)
        }
        itemEntrySection.addView(addItemButton)
        root.addView(itemEntrySection)

        billedItemsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 14, 20, 14)
            background = roundedBg(teal, 12)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 4, 0, 0) }
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
        billedItemsChevron = TextView(this).apply { text = "\u25BE"; textSize = 15f; setTextColor(Color.WHITE) }
        billedItemsHeader.addView(billedItemsChevron)
        root.addView(billedItemsHeader)

        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 0) }
        root.addView(itemsContainer)
        root.addView(spacer(14))

        val totalCard = premiumCard().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(22, 18, 22, 18) }
        totalCard.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Total Amount", "کل رقم").uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        grandTotalText = TextView(this).apply { text = "Rs 0"; textSize = 19f; setTextColor(Color.parseColor(teal)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        totalCard.addView(grandTotalText)
        root.addView(totalCard)

        paymentSection = premiumCard().apply { orientation = LinearLayout.VERTICAL; setPadding(22, 18, 22, 18) }
        val paidRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        paidRow.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Paid Amount", "ادا شدہ رقم").uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        paidRow.addView(TextView(this).apply { text = "Rs "; textSize = 16f; setTextColor(Color.parseColor(navy)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        paidInput = EditText(this).apply {
            hint = "0"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(navy))
            background = null
            textSize = 19f
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

        val dueCard = premiumCard().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(20, 16, 20, 16) }
        dueCard.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Due Amount", "باقی رقم").uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        dueAmountText = TextView(this).apply { text = "Rs 0"; textSize = 17f; setTextColor(Color.parseColor(navy)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        dueCard.addView(dueAmountText)
        root.addView(dueCard)
        root.addView(spacer(14))

        saveButton = Button(this).apply {
            text = if (editBillNo != null) com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "UPDATE PURCHASE", "خریداری اپ ڈیٹ کریں") else com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "SAVE PURCHASE", "خریداری محفوظ کریں")
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(navy, 16)
            setPadding(0, 28, 0, 28)
            applyElevation(this, 4f)
        }
        deleteButton = Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "DELETE", "حذف کریں")
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            setTypeface(typeface
