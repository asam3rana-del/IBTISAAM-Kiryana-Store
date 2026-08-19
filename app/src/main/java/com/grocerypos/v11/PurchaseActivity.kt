package com.grocerypos.v11.ui

import android.app.DatePickerDialog
import android.app.Dialog
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
        private const val PREFS_UNIT = "unit_preset_prefs"
        private const val KEY_LAST_PRIMARY = "last_primary"
        private const val KEY_LAST_SECONDARY = "last_secondary"
        private const val KEY_LAST_SEC_QTY = "last_sec_qty"
        private const val KEY_LAST_TERTIARY = "last_tertiary"
        private const val KEY_LAST_TER_QTY = "last_ter_qty"
    }

    private val bg = "#F4F6F8"
    private val cardWhite = "#FFFFFF"
    private val navy = "#0F9B8E"
    private val teal = "#0B2545"
    private val textDark = "#0B2545"
    private val textMuted = "#7C8798"
    private val border = "#E3E8EE"
    private val red = "#E5484D"
    private val billedBlue = "#90CAF9"
    private val billedBlueDark = "#42A5F5"

    private lateinit var dateValueText: TextView
    private lateinit var firmNameText: TextView
    private lateinit var supplierBalanceText: TextView
    private lateinit var partyName: AutoCompleteTextView
    private lateinit var itemEntrySection: LinearLayout
    private lateinit var addItemsTrigger: TextView
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
    private lateinit var billedItemsHeader: LinearLayout
    private lateinit var billedItemsChevron: TextView

    private var suppliers = listOf<Supplier>()
    private var products = listOf<Product>()
    private var allUnits = listOf("pcs", "kg", "Pao", "gram", "g", "box", "dozen", "carton", "ctn", "outer", "dabbi", "Ctn", "Box", "Nos", "Bkt", "Kg")
    private val lines = mutableListOf<PurchaseLine>()
    private var purchaseDateMillis = System.currentTimeMillis()
    private var itemsExpanded = true
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
            text = if (editBillNo!= null) com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Edit Purchase", "خریداری میں ترمیم") else com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Purchase", "خریداری")
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
            text = "\u2795 " + com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Add Items", "آئٹم شامل کریں")
            textSize = 14.5f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { openAddItemNewWindow() }
        }
        addItemHeaderRow.addView(addItemsTrigger)
        addItemHeaderRow.addView(TextView(this).apply {
            text = "\uD83D\uDCF7 Scan"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(teal, 30)
            setPadding(22, 12, 22, 12)
            setOnClickListener { billScanLauncher.launch(Intent(this@PurchaseActivity, BillScanActivity::class.java)) }
        })
        itemEntrySection.addView(addItemHeaderRow)
        root.addView(itemEntrySection)

        billedItemsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 14, 20, 14)
            background = roundedBg(billedBlue, 12)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 4, 0, 0) }
            visibility = View.GONE
            setOnClickListener { toggleBilledItems() }
        }
        val checkCircle = TextView(this).apply {
            text = "✓"
            textSize = 12f
            setTextColor(Color.parseColor(billedBlueDark))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            val px = (22 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(px, px)
        }
        billedItemsHeader.addView(checkCircle)
        billedItemsHeader.addView(TextView(this).apply {
            text = " " + com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "Billed Items", "بل شدہ آئٹمز")
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(12,0,0,0) }
        })
        billedItemsChevron = TextView(this).apply { text = "▼"; textSize = 12f; setTextColor(Color.WHITE) }
        billedItemsHeader.addView(billedItemsChevron)
        root.addView(billedItemsHeader)

        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 0) }
        root.addView(itemsContainer)
        root.addView(spacer(14))

        val totalCard = premiumCard().apply { orientation = LinearLayout.VERTICAL; setPadding(22, 18, 22, 18) }
        val totalRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        totalRow1.addView(TextView(this).apply { text = "Total Disc: 0.00"; textSize = 11f; setTextColor(Color.parseColor(textMuted)); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        totalRow1.addView(TextView(this).apply { text = "Total Tax Amt: 0.00"; textSize = 11f; setTextColor(Color.parseColor(textMuted)); gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        totalCard.addView(totalRow1)
        val totalRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,6,0,0) }
        val qtyText = TextView(this).apply { text = "Total Qty:0.0"; textSize = 11f; setTextColor(Color.parseColor(textMuted)); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        grandTotalText = TextView(this).apply { text = "Subtotal: 0.00"; textSize = 11f; setTextColor(Color.parseColor(textMuted)); gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        totalRow2.addView(qtyText)
        totalRow2.addView(grandTotalText)
        totalCard.addView(totalRow2)
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
            text = if (editBillNo!= null) com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "UPDATE PURCHASE", "خریداری اپ ڈیٹ کریں") else com.grocerypos.v11.util.Loc.t(this@PurchaseActivity, "SAVE PURCHASE", "خریداری محفوظ کریں")
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
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(red, 16)
            setPadding(0, 28, 0, 28)
            visibility = if (editBillNo!= null) View.VISIBLE else View.GONE
        }
        val saveDeleteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveDeleteRow.addView(saveButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) })
        saveDeleteRow.addView(deleteButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply { setMargins(8, 0, 0, 0) })
        root.addView(saveDeleteRow)
        root.addView(spacer(30))

        scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            addView(root)
        }

        setContentView(scrollArea)

        loadSuppliers()
        loadUnits()
        loadProducts()
        loadFirmName()
        editBillNo?.let { loadForEdit(it) }

        partyName.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) partyName.showDropDown() }
        partyName.setOnItemClickListener { _, _, position, _ -> updateSupplierBalanceDisplay(partyName.adapter.getItem(position).toString()) }
        partyName.addTextChangedListener(simpleWatcher {
            updateSupplierBalanceDisplay(partyName.text.toString().trim())
            if (partyName.text.length >= 1) partyName.showDropDown()
        })

        paidInput.addTextChangedListener(simpleWatcher { updateGrandTotal() })
        saveButton.setOnClickListener { savePurchase() }
        deleteButton.setOnClickListener { confirmDeletePurchase() }

        if (editBillNo == null) restoreDraftIfAny()
    }

    // ================== UPDATED FAST ENTRY WITH TOGGLE + 1st 2nd 3rd BELOW ITEM NAME ==================
    private fun openAddItemNewWindow() {
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        val scrollView = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
            setPadding(24, 24, 24, 24)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 20)
        }
        header.addView(TextView(this).apply {
            text = "← Add Item - Fast Entry"
            textSize = 17f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.parseColor(textMuted))
            setPadding(20, 0, 0, 0)
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(header)

        // Item Name Box
        val itemBox = innerField()
        itemBox.addView(labelRow("Item Name * (1ST / 2ND / 3RD UNIT AUTO)"))
        val itemNameInput = AutoCompleteTextView(this).apply {
            hint = "Type to search..."
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            threshold = 1
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setAdapter(ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_dropdown_item_1line, products.map { it.name }))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        itemBox.addView(itemNameInput)
        root.addView(itemBox)

        // Toggle for New Product
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 12, 8, 12)
            background = roundedBg(cardWhite, 12)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,12) }
        }
        toggleRow.addView(TextView(this).apply {
            text = "New Product Add?"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(textDark))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val newProductToggle = Switch(this).apply {
            isChecked = false
        }
        toggleRow.addView(newProductToggle)
        root.addView(toggleRow)

        // New Product Details - Hidden by default, shows below Item Name after save
        val newProductBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            background = strokedBg(border, "#FFF3E0", 12)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,12) }
        }
        newProductBox.addView(TextView(this).apply {
            text = "NEW PRODUCT UNITS (1st / 2nd / 3rd) - Keyboard se fill karo"
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(teal))
            setPadding(0,0,0,12)
        })

        // 1st Unit
        val firstUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val firstUnitNameBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,6,0) } }
        firstUnitNameBox.addView(labelRow("1st Unit Name *"))
        val firstUnitNameInput = EditText(this).apply {
            hint = "e.g. ctn"
            textSize = 14f
            background = null
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        firstUnitNameBox.addView(firstUnitNameInput)
        firstUnitRow.addView(firstUnitNameBox)
        newProductBox.addView(firstUnitRow)

        // 2nd Unit
        val secondUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val secUnitNameBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,6,0) } }
        secUnitNameBox.addView(labelRow("2nd Unit Name"))
        val secUnitNameInput = EditText(this).apply {
            hint = "e.g. box"
            textSize = 14f
            background = null
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        secUnitNameBox.addView(secUnitNameInput)
        val secQtyBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6,0,0,0) } }
        secQtyBox.addView(labelRow("2nd Qty (1st me kitne?)"))
        val secQtyInput = EditText(this).apply {
            hint = "e.g. 12"
            textSize = 14f
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        secQtyBox.addView(secQtyInput)
        secondUnitRow.addView(secUnitNameBox)
        secondUnitRow.addView(secQtyBox)
        newProductBox.addView(secondUnitRow)

        // 3rd Unit
        val thirdUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val thirdUnitNameBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,6,0) } }
        thirdUnitNameBox.addView(labelRow("3rd Unit Name"))
        val thirdUnitNameInput = EditText(this).apply {
            hint = "e.g. pcs"
            textSize = 14f
            background = null
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        thirdUnitNameBox.addView(thirdUnitNameInput)
        val thirdQtyBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6,0,0,0) } }
        thirdQtyBox.addView(labelRow("3rd Qty (2nd me kitne?)"))
        val thirdQtyInput = EditText(this).apply {
            hint = "e.g. 10"
            textSize = 14f
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        thirdQtyBox.addView(thirdQtyInput)
        thirdUnitRow.addView(thirdUnitNameBox)
        thirdUnitRow.addView(thirdQtyBox)
        newProductBox.addView(thirdUnitRow)

        // Save New Product Button
        val saveNewProductBtn = Button(this).apply {
            text = "SAVE NEW PRODUCT UNITS (Enter)"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBg(navy, 10)
            isFocusable = true
        }
        newProductBox.addView(saveNewProductBtn)
        root.addView(newProductBox)

        // Auto unit info for existing products
        val primaryUnitInfo = TextView(this).apply {
            text = "1st: - | 2nd: - | 3rd: -"
            textSize = 11f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = View.GONE
            setPadding(4, 8, 4, 8)
            background = roundedBg("#E8F5E9", 8)
        }
        root.addView(primaryUnitInfo)

        val livePreview = TextView(this).apply {
            text = "Live: 0 Ctn x 0 = Rs 0"
            textSize = 12f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(12, 12, 12, 12)
            background = roundedBg("#F5F8FF", 10)
        }
        root.addView(livePreview)

        val qtyUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qtyBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } }
        qtyBox.addView(labelRow("Quantity *"))
        val qtyInput = EditText(this).apply {
            hint = "0"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = null
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        qtyBox.addView(qtyInput)
        val unitBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) } }
        unitBox.addView(labelRow("Unit * (1st/2nd/3rd)"))
        val unitSpinner = Spinner(this)
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, allUnits)
        unitBox.addView(unitSpinner)
        qtyUnitRow.addView(qtyBox)
        qtyUnitRow.addView(unitBox)
        root.addView(qtyUnitRow)

        val tierBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = strokedBg(border, "#FFF8E1", 10)
            visibility = View.GONE
        }
        val secondaryInfo = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor(textDark)) }
        val tertiaryInfo = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor(textDark)) }
        tierBox.addView(secondaryInfo)
        tierBox.addView(tertiaryInfo)
        root.addView(tierBox)

        val rateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rateBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } }
        rateBox.addView(labelRow("Rate (Per Unit) *"))
        val rateInput = EditText(this).apply {
            hint = "Per Unit Price"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = null
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        rateBox.addView(rateInput)
        val totalLotBox = innerField().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
            background = strokedBg(teal, "#E0F2F1", 12)
        }
        totalLotBox.addView(labelRow("Total Lot Price * (ACTIVE)"))
        val totalLotInput = EditText(this).apply {
            hint = "e.g. 5000"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = null
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        totalLotBox.addView(totalLotInput)
        rateRow.addView(rateBox)
        rateRow.addView(totalLotBox)
        root.addView(rateRow)

        val totalAmountTextLocal = TextView(this).apply {
            text = "Total Amount: Rs 0.00"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(teal))
            setPadding(6, 10, 0, 10)
        }
        root.addView(totalAmountTextLocal)

        var selectedProductLocal: Product? = null
        var isUpdatingLotRate = false
        var tempNewProductUnits: Map<String, String>? = null

        fun updatePreview() {
            val q = qtyInput.text.toString().toDoubleOrNull()?: 0.0
            val r = rateInput.text.toString().toDoubleOrNull()?: 0.0
            val unit = unitSpinner.selectedItem?.toString()?: "Ctn"
            livePreview.text = "Live: ${formatQty(q)} $unit x $r = Rs ${"%.0f".format(q * r)}"
            totalAmountTextLocal.text = "Total Amount: Rs ${"%.0f".format(q * r)}"
        }

        // Toggle listener
        newProductToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                newProductBox.visibility = View.VISIBLE
                primaryUnitInfo.visibility = View.GONE
                tierBox.visibility = View.GONE
                firstUnitNameInput.requestFocus()
            } else {
                newProductBox.visibility = View.GONE
                tempNewProductUnits = null
            }
        }

        // New Product Save via Keyboard Chain
        fun saveNewProductUnitsToTemp() {
            val u1 = firstUnitNameInput.text.toString().trim().ifEmpty { "pcs" }
            val u2 = secUnitNameInput.text.toString().trim()
            val q2 = secQtyInput.text.toString().toDoubleOrNull()?: 0.0
            val u3 = thirdUnitNameInput.text.toString().trim()
            val q3 = thirdQtyInput.text.toString().toDoubleOrNull()?: 0.0

            tempNewProductUnits = mapOf("u1" to u1, "u2" to u2, "q2" to q2.toString(), "u3" to u3, "q3" to q3.toString())

            val unitOptions = mutableListOf<String>()
            unitOptions.add(u1)
            if (u2.isNotBlank()) unitOptions.add(u2)
            if (u3.isNotBlank()) unitOptions.add(u3)
            unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitOptions.distinct())

            primaryUnitInfo.text = "NEW -> 1st: $u1 | 2nd: ${u2.ifBlank { "-" }} ($q2) | 3rd: ${u3.ifBlank { "-" }} ($q3) - SAVED"
            primaryUnitInfo.visibility = View.VISIBLE
            tierBox.visibility = View.VISIBLE
            secondaryInfo.text = if (u2.isNotBlank()) "1 $u1 = $q2 $u2" else ""
            tertiaryInfo.text = if (u3.isNotBlank()) "1 $u2 = $q3 $u3" else ""

            Toast.makeText(this, "Units saved below Item Name: $u1 > $u2 > $u3", Toast.LENGTH_SHORT).show()
            qtyInput.requestFocus()
        }

        saveNewProductBtn.setOnClickListener { saveNewProductUnitsToTemp() }

        // Keyboard chain for new product box
        firstUnitNameInput.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_NEXT) { secUnitNameInput.requestFocus(); true } else false }
        secUnitNameInput.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_NEXT) { secQtyInput.requestFocus(); true } else false }
        secQtyInput.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_NEXT) { thirdUnitNameInput.requestFocus(); true } else false }
        thirdUnitNameInput.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_NEXT) { thirdQtyInput.requestFocus(); true } else false }
        thirdQtyInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_NEXT) {
                saveNewProductUnitsToTemp()
                true
            } else false
        }

        itemNameInput.setOnItemClickListener { _, _, pos, _ ->
            val picked = itemNameInput.adapter.getItem(pos).toString()
            val product = products.find { it.name == picked }
            selectedProductLocal = product
            if (product!= null) {
                newProductToggle.isChecked = false
                newProductBox.visibility = View.GONE
                primaryUnitInfo.text = "1st: ${product.unit} | 2nd: ${product.secondaryUnit.ifBlank { "-" }} (${formatQty(product.secondaryUnitQty)}) | 3rd: ${product.tertiaryUnit.ifBlank { "-" }} (${formatQty(product.tertiaryUnitQty)})"
                primaryUnitInfo.visibility = View.VISIBLE
                val unitOptions = mutableListOf(product.unit)
                if (product.secondaryUnit.isNotBlank()) unitOptions.add(product.secondaryUnit)
                if (product.tertiaryUnit.isNotBlank()) unitOptions.add(product.tertiaryUnit)
                unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, (unitOptions + allUnits).distinct())
                if (product.secondaryUnit.isNotBlank() || product.tertiaryUnit.isNotBlank()) {
                    tierBox.visibility = View.VISIBLE
                    secondaryInfo.text = if (product.secondaryUnit.isNotBlank()) "1 ${product.unit} = ${formatQty(product.secondaryUnitQty)} ${product.secondaryUnit} (2nd Unit)" else ""
                    tertiaryInfo.text = if (product.tertiaryUnit.isNotBlank()) "1 ${product.secondaryUnit} = ${formatQty(product.tertiaryUnitQty)} ${product.tertiaryUnit} (3rd Unit)" else ""
                }
                if (product.cost > 0) rateInput.setText(product.cost.toInt().toString())
            }
            qtyInput.requestFocus()
            updatePreview()
        }

        itemNameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                if (newProductToggle.isChecked && newProductBox.visibility == View.VISIBLE) {
                    firstUnitNameInput.requestFocus()
                } else {
                    qtyInput.requestFocus()
                }
                true
            } else false
        }
        qtyInput.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_NEXT) { rateInput.requestFocus(); true } else false }
        rateInput.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_NEXT) { totalLotInput.requestFocus(); true } else false }

        qtyInput.addTextChangedListener(simpleWatcher { updatePreview() })
        rateInput.addTextChangedListener(simpleWatcher {
            if (isUpdatingLotRate) return@simpleWatcher
            val q = qtyInput.text.toString().toDoubleOrNull()?: 0.0
            val r = rateInput.text.toString().toDoubleOrNull()?: 0.0
            if (q > 0 && r > 0) {
                isUpdatingLotRate = true
                totalLotInput.setText("%.0f".format(q * r))
                isUpdatingLotRate = false
            }
            updatePreview()
        })
        totalLotInput.addTextChangedListener(simpleWatcher {
            if (isUpdatingLotRate) return@simpleWatcher
            val totalLot = totalLotInput.text.toString().toDoubleOrNull()?: 0.0
            val q = qtyInput.text.toString().toDoubleOrNull()?: 0.0
            if (q > 0 && totalLot > 0) {
                isUpdatingLotRate = true
                rateInput.setText("%.2f".format(totalLot / q))
                isUpdatingLotRate = false
            }
            updatePreview()
        })

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 20, 0, 0) }
        val addAnotherBtn = Button(this).apply {
            text = "Add & Another (NEXT)"
            background = strokedBg(border, cardWhite, 14)
            setTextColor(Color.parseColor(textDark))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val addBtn = Button(this).apply {
            text = "ADD TO BILL"
            setTextColor(Color.WHITE)
            background = roundedBg(teal, 14)
        }
        btnRow.addView(addAnotherBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) })
        btnRow.addView(addBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 0) })
        root.addView(btnRow)

        fun addItemToBill(closeAfter: Boolean) {
            val enteredName = itemNameInput.text.toString().trim()
            val q = qtyInput.text.toString().toDoubleOrNull()
            val r = rateInput.text.toString().toDoubleOrNull()
            val unit = (unitSpinner.selectedItem as? String)?: "pcs"
            if (enteredName.isEmpty() || q == null || q <= 0 || r == null || r < 0) {
                Toast.makeText(this@PurchaseActivity, "Name, Qty, Rate required", Toast.LENGTH_SHORT).show()
                return
            }

            // New Product create if toggle ON
            var productToSave: Product? = null
            if (newProductToggle.isChecked) {
                val u1 = tempNewProductUnits?.get("u1")?: firstUnitNameInput.text.toString().trim().ifEmpty { unit }
                val u2 = tempNewProductUnits?.get("u2")?: secUnitNameInput.text.toString().trim()
                val q2 = tempNewProductUnits?.get("q2")?.toDoubleOrNull()?: secQtyInput.text.toString().toDoubleOrNull()?: 0.0
                val u3 = tempNewProductUnits?.get("u3")?: thirdUnitNameInput.text.toString().trim()
                val q3 = tempNewProductUnits?.get("q3")?.toDoubleOrNull()?: thirdQtyInput.text.toString().toDoubleOrNull()?: 0.0

                productToSave = Product(
                    barcode = "P" + System.currentTimeMillis(),
                    name = enteredName,
                    category = "General",
                    cost = r,
                    salePrice = 0.0,
                    wholesalePrice = 0.0,
                    stock = 0,
                    openingStock = 0,
                    unit = u1,
                    secondaryUnit = u2,
                    secondaryUnitQty = q2,
                    tertiaryUnit = u3,
                    tertiaryUnitQty = q3
                )
                lifecycleScope.launch {
                    PosDatabase.get(this@PurchaseActivity).productDao().upsert(productToSave)
                    loadProducts()
                }
            }

            val product = selectedProductLocal?: products.find { it.name.equals(enteredName, ignoreCase = true) }?: productToSave
            val line = PurchaseLine(
                itemName = product?.name?: enteredName,
                barcode = product?.barcode,
                qty = q,
                unit = unit,
                rate = r,
                amount = Math.round(q * r).toDouble(),
                mainUnit = product?.unit?: tempNewProductUnits?.get("u1")?: unit,
                secondaryUnit = product?.secondaryUnit?: tempNewProductUnits?.get("u2")?: "",
                secondaryUnitQty = product?.secondaryUnitQty?: tempNewProductUnits?.get("q2")?.toDoubleOrNull()?: 0.0,
                tertiaryUnit = product?.tertiaryUnit?: tempNewProductUnits?.get("u3")?: "",
                tertiaryUnitQty = product?.tertiaryUnitQty?: tempNewProductUnits?.get("q3")?.toDoubleOrNull()?: 0.0
            )
            lines.add(line)
            renderItemsList()
            updateGrandTotal()
            if (closeAfter) dialog.dismiss() else {
                itemNameInput.setText("")
                qtyInput.setText("")
                rateInput.setText("")
                totalLotInput.setText("")
                firstUnitNameInput.setText("")
                secUnitNameInput.setText("")
                secQtyInput.setText("")
                thirdUnitNameInput.setText("")
                thirdQtyInput.setText("")
                selectedProductLocal = null
                tempNewProductUnits = null
                primaryUnitInfo.visibility = View.GONE
                tierBox.visibility = View.GONE
                newProductToggle.isChecked = false
                newProductBox.visibility = View.GONE
                itemNameInput.requestFocus()
                Toast.makeText(this@PurchaseActivity, "#${lines.size} added - $unit (1st/2nd/3rd)", Toast.LENGTH_SHORT).show()
            }
        }

        totalLotInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE) {
                addAnotherBtn.requestFocus()
                addAnotherBtn.performClick()
                true
            } else false
        }

        addBtn.setOnClickListener { addItemToBill(true) }
        addAnotherBtn.setOnClickListener { addItemToBill(false) }

        scrollView.addView(root)
        dialog.setContentView(scrollView)
        dialog.show()
        itemNameInput.requestFocus()
    }

    // ================== BILLED ITEMS WITH #1,2,3,4,5,6 ==================
    private fun renderItemsList() {
        itemsContainer.removeAllViews()
        billedItemsHeader.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        if (!itemsExpanded) {
            itemsExpanded = true
            itemsContainer.visibility = View.VISIBLE
            billedItemsChevron.text = "▼"
        }
        lines.forEachIndexed { index, line ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 18, 28, 18)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F5F5F5"))
                    setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor(border))
                    cornerRadius = 14 * resources.displayMetrics.density
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
            }
            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val badge = TextView(this).apply {
                text = "#${index + 1}" // FIXED: #1,2,3,4,5,6 se
                textSize = 10.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(textMuted))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor(border))
                    cornerRadius = 6 * resources.displayMetrics.density
                }
                setPadding(12, 4, 12, 4)
            }
            topRow.addView(badge)
            topRow.addView(TextView(this).apply {
                text = " ${line.itemName}"
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6,0,0,0) }
                maxLines = 1
            })
            topRow.addView(TextView(this).apply {
                text = "Rs ${"%,.0f".format(line.amount)}"
                textSize = 13.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
            })
            topRow.addView(TextView(this).apply {
                text = " ✕"
                textSize = 16f
                setTextColor(Color.parseColor(red))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(12, 0, 0, 0)
                setOnClickListener {
                    lines.removeAt(index)
                    renderItemsList()
                    updateGrandTotal()
                    if (editBillNo == null) saveDraft()
                    Toast.makeText(this@PurchaseActivity, "Item #${index+1} deleted", Toast.LENGTH_SHORT).show()
                }
            })
            row.addView(topRow)

            val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 0) }
            bottomRow.addView(TextView(this).apply {
                text = "Item Subtotal"
                textSize = 11.5f
                setTextColor(Color.parseColor(textMuted))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            bottomRow.addView(TextView(this).apply {
                text = "${formatQty(line.qty)} ${line.unit} x ${line.rate.toInt()} = Rs ${"%,.0f".format(line.amount)}"
                textSize = 11.5f
                setTextColor(Color.parseColor(textMuted))
            })
            row.addView(bottomRow)

            if (line.secondaryUnit.isNotEmpty() || line.tertiaryUnit.isNotEmpty()) {
                val tierInfo = TextView(this).apply {
                    text = buildString {
                        append("${line.mainUnit}")
                        if (line.secondaryUnit.isNotEmpty()) append(" > ${line.secondaryUnit} (${line.secondaryUnitQty})")
                        if (line.tertiaryUnit.isNotEmpty()) append(" > ${line.tertiaryUnit} (${line.tertiaryUnitQty})")
                    }
                    textSize = 10f
                    setTextColor(Color.parseColor(teal))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 6, 0, 0)
                }
                row.addView(tierInfo)
            }
            row.setOnClickListener { openAddItemNewWindowForEdit(index) }
            itemsContainer.addView(row)
        }

        if (lines.isNotEmpty()) {
            val deleteAllRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, 8, 0, 0)
            }
            deleteAllRow.addView(TextView(this).apply {
                text = "🗑 Delete All Billed Items"
                textSize = 12f
                setTextColor(Color.parseColor(red))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(12, 8, 12, 8)
                background = strokedBg(border, "#FFEBEE", 8)
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@PurchaseActivity)
                       .setTitle("Delete All?")
                       .setMessage("Sari billed items delete karni hain? Ye undo nahi hoga.")
                       .setPositiveButton("Yes, Delete All") { _, _ ->
                            lines.clear()
                            renderItemsList()
                            updateGrandTotal()
                            if (editBillNo == null) saveDraft()
                            Toast.makeText(this@PurchaseActivity, "All billed items deleted", Toast.LENGTH_SHORT).show()
                        }
                       .setNegativeButton("Cancel", null)
                       .show()
                }
            })
            itemsContainer.addView(deleteAllRow)
        }
    }

    //... rest same functions as before (loadForEdit, loadSuppliers etc.)...
    private fun getUnitPrefs() = getSharedPreferences(PREFS_UNIT, Context.MODE_PRIVATE)
    private fun getLastUnitPreset(): Map<String, String> {
        val p = getUnitPrefs()
        return mapOf(
            "primary" to (p.getString(KEY_LAST_PRIMARY, "kg")?: "kg"),
            "secondary" to (p.getString(KEY_LAST_SECONDARY, "Pao")?: "Pao"),
            "secQty" to (p.getString(KEY_LAST_SEC_QTY, "4")?: "4"),
            "tertiary" to (p.getString(KEY_LAST_TERTIARY, "gram")?: "gram"),
            "terQty" to (p.getString(KEY_LAST_TER_QTY, "250")?: "250")
        )
    }
    override fun onPause() { super.onPause(); if (editBillNo == null &&!suppressDraftSave) saveDraft() }
    private fun draftPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun saveDraft() {
        val hasContent = lines.isNotEmpty() || partyName.text.toString().isNotBlank()
        if (!hasContent) { clearDraft(); return }
        val linesArray = JSONArray()
        lines.forEach { line -> linesArray.put(JSONObject().apply {
            put("itemName", line.itemName); put("barcode", line.barcode?: ""); put("qty", line.qty); put("unit", line.unit); put("rate", line.rate); put("amount", line.amount)
            put("mainUnit", line.mainUnit); put("secondaryUnit", line.secondaryUnit); put("secondaryUnitQty", line.secondaryUnitQty)
            put("tertiaryUnit", line.tertiaryUnit); put("tertiaryUnitQty", line.tertiaryUnitQty)
        }) }
        val draft = JSONObject().apply { put("party", partyName.text.toString()); put("paid", paidInput.text.toString()); put("dateMillis", purchaseDateMillis); put("lines", linesArray) }
        draftPrefs().edit().putString(KEY_DRAFT, draft.toString()).apply()
    }
    private fun clearDraft() { draftPrefs().edit().remove(KEY_DRAFT).apply() }
    private fun restoreDraftIfAny() {
        val raw = draftPrefs().getString(KEY_DRAFT, null)?: return
        val draft = try { JSONObject(raw) } catch (e: Exception) { null }?: return
        if (draftRestored) return
        draftRestored = true
        suppressDraftSave = true
        try {
            val party = draft.optString("party", ""); if (party.isNotBlank()) { partyName.setText(party); updateSupplierBalanceDisplay(party) }
            val paid = draft.optString("paid", ""); if (paid.isNotBlank()) paidInput.setText(paid)
            val savedDate = draft.optLong("dateMillis", 0L); if (savedDate > 0L) { purchaseDateMillis = savedDate; dateValueText.text = formatDate(purchaseDateMillis) }
            val linesArray = draft.optJSONArray("lines")
            if (linesArray!= null) {
                for (i in 0 until linesArray.length()) {
                    try {
                        val o = linesArray.getJSONObject(i)
                        lines.add(PurchaseLine(o.optString("itemName"), o.optString("barcode").ifBlank { null }, o.optDouble("qty", 0.0), o.optString("unit"), o.optDouble("rate", 0.0), o.optDouble("amount", 0.0), o.optString("mainUnit"), o.optString("secondaryUnit"), o.optDouble("secondaryUnitQty", 0.0), o.optString("tertiaryUnit"), o.optDouble("tertiaryUnitQty", 0.0)))
                    } catch (e: Exception) { Log.e("PurchaseActivity", "restoreDraftIfAny: skipping bad line $i", e) }
                }
                renderItemsList(); updateGrandTotal()
            }
        } finally { suppressDraftSave = false }
    }
    private fun loadFirmName() { lifecycleScope.launch { val savedName = PosDatabase.get(this@PurchaseActivity).appSettingDao().get("shop_name")?.value; if (!savedName.isNullOrBlank()) firmNameText.text = savedName } }
    private fun updateSupplierBalanceDisplay(name: String) {
        val supplier = suppliers.find { it.name.equals(name, ignoreCase = true) }
        if (supplier == null) { supplierBalanceText.text = "Rs 0.00"; supplierBalanceText.setTextColor(Color.parseColor(textMuted)); return }
        supplierBalanceText.text = "Rs %.2f".format(supplier.balance)
        supplierBalanceText.setTextColor(Color.parseColor(if (supplier.balance > 0) red else teal))
    }
    private fun premiumCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(22, 16, 22, 16); background = strokedBg(border, cardWhite, 16)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 14) }; applyElevation(this, 2f)
    }
    private fun innerField() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(18, 12, 18, 12); background = strokedBg(border, "#FAFBFC", 12)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
    }
    private fun labelRow(label: String) = TextView(this).apply { text = label.uppercase(); textSize = 10.5f; setTextColor(Color.parseColor(textMuted)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 6); letterSpacing = 0.03f }
    private fun pillChip(label: String, onClick: () -> Unit) = TextView(this).apply { this.text = label; textSize = 12.5f; setTextColor(Color.parseColor(navy)); setTypeface(typeface, android.graphics.Typeface.BOLD); background = roundedBg(cardWhite, 30); setPadding(24, 12, 24, 12); setOnClickListener { onClick() } }
    private fun circleIcon(label: String, colorHex: String, sizeDp: Int, onClick: (() -> Unit)? = null) = TextView(this).apply { this.text = label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = ovalBg(colorHex); val px = (sizeDp * resources.displayMetrics.density).toInt(); width = px; height = px; if (onClick!= null) setOnClickListener { onClick() } }
    private fun toggleBilledItems() { itemsExpanded =!itemsExpanded; itemsContainer.visibility = if (itemsExpanded) View.VISIBLE else View.GONE; billedItemsChevron.text = if (itemsExpanded) "▼" else "▶" }
    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(com.grocerypos.v11.util.Loc.t(this, "Print", "پرنٹ")); popup.menu.add(com.grocerypos.v11.util.Loc.t(this, "Share", "شیئر کریں"))
        popup.setOnMenuItemClickListener { val billNo = editBillNo; if (billNo == null) { Toast.makeText(this, "Save the purchase first", Toast.LENGTH_SHORT).show() } else { openBillPreview(billNo, forSaving = false) }; true }; popup.show()
    }
    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = radius.toFloat() }
    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(fillHex)); setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex)); cornerRadius = radius.toFloat() }
    private fun ovalBg(colorHex: String) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(colorHex)) }
    private fun applyElevation(view: View, dp: Float) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { view.elevation = dp * resources.displayMetrics.density; view.outlineProvider = ViewOutlineProvider.BACKGROUND } }
    private fun spacer(heightDp: Int) = View(this).apply { val px = (heightDp * resources.displayMetrics.density).toInt(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px) }
    private fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun afterTextChanged(s: Editable?) = onChange() }
    private fun formatDate(millis: Long) = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(millis))
    private fun formatQty(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    private fun openDatePicker() { val cal = Calendar.getInstance().apply { timeInMillis = purchaseDateMillis }; DatePickerDialog(this, { _, y, m, d -> cal.set(y, m, d); purchaseDateMillis = cal.timeInMillis; dateValueText.text = formatDate(purchaseDateMillis) }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show() }
    private fun hideKeyboard() { currentFocus?.let { focused -> val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager; imm?.hideSoftInputFromWindow(focused.windowToken, 0); focused.clearFocus() } }
    private fun loadForEdit(bill: String) {
        lifecycleScope.launch {
            try {
                val db = PosDatabase.get(this@PurchaseActivity); val purchase = db.purchaseDao().findPurchase(bill)?: return@launch; val items = db.purchaseDao().itemsForBill(bill)
                originalPurchase = purchase; originalItems = items; purchaseDateMillis = purchase.createdAt; dateValueText.text = formatDate(purchaseDateMillis)
                val supplierName = purchase.supplierId?.let { id -> withTimeoutOrNull(8000) { db.supplierDao().all().first().find { it.id == id }?.name } }?: ""; partyName.setText(supplierName); updateSupplierBalanceDisplay(supplierName)
                paidInput.setText(if (purchase.paid > 0) Math.round(purchase.paid).toString() else ""); lines.clear()
                items.forEach { pi -> val product = try { db.productDao().find(pi.barcode) } catch (e: Exception) { null }; lines.add(PurchaseLine(product?.name?: pi.barcode, pi.barcode, pi.qty.toDouble(), pi.unit.ifBlank { product?.unit?: "" }, pi.unitCost, pi.amount, product?.unit?: "", product?.secondaryUnit?: "", product?.secondaryUnitQty?: 0.0, product?.tertiaryUnit?: "", product?.tertiaryUnitQty?: 0.0)) }
                renderItemsList(); updateGrandTotal(); deleteButton.visibility = View.VISIBLE
            } catch (e: Exception) { Log.e("PurchaseActivity", "loadForEdit failed", e); Toast.makeText(this@PurchaseActivity, "Could not load purchase", Toast.LENGTH_LONG).show() }
        }
    }
    private fun loadSuppliers() { lifecycleScope.launch { PosDatabase.get(this@PurchaseActivity).supplierDao().all().collectLatest { list -> suppliers = list; partyName.setAdapter(android.widget.ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_dropdown_item_1line, list.map { it.name })); updateSupplierBalanceDisplay(partyName.text.toString().trim()) } } }
    private fun loadUnits() { lifecycleScope.launch { PosDatabase.get(this@PurchaseActivity).unitDao().all().collectLatest { list -> allUnits = (listOf("pcs", "kg", "Pao", "gram", "g", "box", "dozen", "carton", "ctn", "outer", "dabbi", "Ctn", "Box", "Nos", "Bkt", "Kg") + list.map { it.name }).distinct() } } }
    private fun loadProducts() { lifecycleScope.launch { PosDatabase.get(this@PurchaseActivity).productDao().all().collectLatest { list -> products = list } } }
    private fun openAddItemNewWindowForEdit(editIndex: Int) {
        if (editIndex < 0 || editIndex >= lines.size) return
        val existing = lines[editIndex]; val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(bg)); setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply { text = "← Edit Item #${editIndex+1}"; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); setOnClickListener { dialog.dismiss() }; setPadding(0,20,0,20) })
        val nameInput = EditText(this).apply { setText(existing.itemName) }; val qtyInput = EditText(this).apply { setText(formatQty(existing.qty)); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }; val unitSpinner = Spinner(this).apply { adapter = android.widget.ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, allUnits); setSelection(allUnits.indexOf(existing.unit).coerceAtLeast(0)) }; val rateInput = EditText(this).apply { setText(existing.rate.toString()); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        root.addView(nameInput); root.addView(qtyInput); root.addView(unitSpinner); root.addView(rateInput)
        val saveBtn = Button(this).apply { text = "UPDATE"; background = roundedBg(teal, 14); setTextColor(Color.WHITE) }
        saveBtn.setOnClickListener { val q = qtyInput.text.toString().toDoubleOrNull()?: existing.qty; val r = rateInput.text.toString().toDoubleOrNull()?: existing.rate; lines[editIndex] = existing.copy(itemName = nameInput.text.toString(), qty = q, unit = unitSpinner.selectedItem.toString(), rate = r, amount = q*r); renderItemsList(); updateGrandTotal(); dialog.dismiss() }
        root.addView(saveBtn); dialog.setContentView(root); dialog.show()
    }
    private fun updateGrandTotal() {
        val total = Math.round(lines.sumOf { it.amount }).toDouble(); grandTotalText.text = "Subtotal: %.2f".format(total); val paid = Math.round(paidInput.text.toString().toDoubleOrNull()?: 0.0).toDouble(); val due = (total - paid).coerceAtLeast(0.0); dueAmountText.text = "Rs %.0f".format(due); dueAmountText.setTextColor(Color.parseColor(if (due > 0) red else teal))
        if (paid == 0.0 && total > 0) { paidWarningText.visibility = View.VISIBLE; paidWarningText.text = "⚠️ Paid khali hai - Ye Rs %.0f Udhaar jayega".format(due) } else { paidWarningText.visibility = View.GONE }
    }
    private fun promptAddSupplier() { val input = EditText(this).apply { hint = "Supplier name"; setPadding(32, 24, 32, 24) }; android.app.AlertDialog.Builder(this).setTitle("Add Supplier").setView(input).setPositiveButton("Add") { _, _ -> val name = input.text.toString().trim(); if (name.isNotBlank()) { lifecycleScope.launch { val s = Supplier(name = name); PosDatabase.get(this@PurchaseActivity).supplierDao().insert(s); partyName.setText(name) } } }.setNegativeButton("Cancel", null).show() }
    private fun handleScannedItems(json: String) {
        val arr = try { JSONArray(json) } catch (e: Exception) { return }; lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity); var matchedCount = 0
            for (i in 0 until arr.length()) { val o = try { arr.getJSONObject(i) } catch (e: Exception) { continue }; val scannedName = o.optString("name").trim(); val scannedQty = o.optDouble("qty", 0.0); val scannedRate = o.optDouble("rate", 0.0); if (scannedName.isEmpty() || scannedQty <= 0) continue; var product = products.find { it.name.equals(scannedName, ignoreCase = true) }?: products.find { it.name.contains(scannedName, ignoreCase = true) || scannedName.contains(it.name, ignoreCase = true) }; if (product == null) { val newProduct = Product(barcode = "P" + System.currentTimeMillis() + i, name = scannedName, category = "General", cost = scannedRate, salePrice = 0.0, wholesalePrice = 0.0, stock = 0, openingStock = 0, unit = "pcs", secondaryUnit = "", secondaryUnitQty = 0.0, tertiaryUnit = "", tertiaryUnitQty = 0.0); db.productDao().upsert(newProduct); product = newProduct }; lines.add(PurchaseLine(product.name, product.barcode, scannedQty, product.unit, scannedRate, Math.round(scannedQty * scannedRate).toDouble(), product.unit, product.secondaryUnit, product.secondaryUnitQty, product.tertiaryUnit, product.tertiaryUnitQty)); matchedCount++ }
            if (matchedCount > 0) { renderItemsList(); updateGrandTotal(); if (editBillNo == null) saveDraft() }; Toast.makeText(this@PurchaseActivity, "$matchedCount items added from scan", Toast.LENGTH_LONG).show()
        }
    }
    private fun confirmDeletePurchase() { val billNo = editBillNo?: return; android.app.AlertDialog.Builder(this).setTitle("Delete Purchase").setMessage("This will remove the bill and reverse its stock and supplier balance effect. Continue?").setPositiveButton("Delete") { _, _ -> deletePurchase(billNo) }.setNegativeButton("Cancel", null).show() }
    private fun deletePurchase(billNo: String) { lifecycleScope.launch { val db = PosDatabase.get(this@PurchaseActivity); val purchase = originalPurchase?: db.purchaseDao().findPurchase(billNo)?: return@launch; val items = originalItems.ifEmpty { db.purchaseDao().itemsForBill(billNo) }; items.forEach { db.productDao().decreaseForce(it.barcode, it.qty.toInt()) }; val outstanding = purchase.total - purchase.paid; if (purchase.supplierId!= null && outstanding > 0) { db.supplierDao().addBalance(purchase.supplierId, -outstanding) }; db.purchaseDao().deleteItems(billNo); db.purchaseDao().deletePurchase(billNo); db.paymentDao().deleteByReference(billNo); db.cashTransactionDao().deleteByReference(billNo); Toast.makeText(this@PurchaseActivity, "Purchase deleted", Toast.LENGTH_SHORT).show(); finish() } }
    private fun savePurchase() {
        hideKeyboard(); val party = partyName.text.toString().trim(); if (party.isEmpty()) { partyName.error = "Required"; return }; if (lines.isEmpty()) { Toast.makeText(this, "Add at least one item", Toast.LENGTH_SHORT).show(); return }; val subtotal = lines.sumOf { it.amount }; val grandTotal = Math.round(subtotal).toDouble().coerceAtLeast(0.0); val paidText = paidInput.text.toString().trim(); val isPaidEmpty = paidText.isEmpty() || paidText.toDoubleOrNull() == 0.0
        if (isPaidEmpty && grandTotal > 0) { android.app.AlertDialog.Builder(this).setTitle("Confirm Credit Purchase").setMessage("You have not entered Paid Amount.\nTotal: Rs %.0f\n\nThis bill will be saved as CREDIT (Udhaar).\nSupplier balance will increase.\n\nAre you sure?".format(grandTotal)).setPositiveButton("Yes, Save as Credit") { _, _ -> proceedSave(party, grandTotal) }.setNegativeButton("Enter Payment") { dialog, _ -> dialog.dismiss() }.show(); return }; proceedSave(party, grandTotal)
    }
    private fun proceedSave(party: String, grandTotal: Double) {
        val discount = 0.0; val amountPaid = Math.round(paidInput.text.toString().toDoubleOrNull()?: 0.0).toDouble().coerceIn(0.0, grandTotal); val paymentMethod = "Cash"; val matchedSupplier = suppliers.find { it.name.equals(party, ignoreCase = true) }; var supplierId = matchedSupplier?.id; val billNo = editBillNo?: genBillNo()
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity); if (supplierId == null && party.isNotEmpty()) { supplierId = db.supplierDao().insert(Supplier(name = party)) }; val original = originalPurchase; if (original!= null) { originalItems.forEach { db.productDao().decreaseForce(it.barcode, it.qty.toInt()) }; val originalOutstanding = original.total - original.paid; if (original.supplierId!= null && originalOutstanding > 0) { db.supplierDao().addBalance(original.supplierId, -originalOutstanding) }; db.purchaseDao().deleteItems(billNo); db.purchaseDao().deletePurchase(billNo); db.paymentDao().deleteByReference(billNo); db.cashTransactionDao().deleteByReference(billNo) }
            db.purchaseDao().purchase(Purchase(billNo = billNo, supplierId = supplierId, total = grandTotal, paid = amountPaid, createdAt = purchaseDateMillis, subtotal = lines.sumOf { it.amount }, discount = discount)); db.purchaseDao().items(lines.map { line -> PurchaseItem(billNo = billNo, barcode = line.barcode?: "", qty = line.mainUnitQty(), unitCost = line.mainUnitRate(), amount = line.amount, unit = line.unit) })
            lines.forEach { line -> val barcode = line.barcode?: return@forEach; val before = db.productDao().find(barcode); val purchasedQty = line.mainUnitQty().roundToInt(); db.productDao().increase(barcode, purchasedQty); if (before!= null && purchasedQty > 0) { val oldStock = before.stock; val oldCost = before.cost; val purchaseRate = line.mainUnitRate(); val newCost = if (oldStock <= 0) { purchaseRate } else { ((oldStock * oldCost) + (purchasedQty * purchaseRate)) / (oldStock + purchasedQty).toDouble() }; db.productDao().updateCost(barcode, newCost) } }
            val outstanding = grandTotal - amountPaid; if (supplierId!= null && outstanding > 0) { db.supplierDao().addBalance(supplierId!!, outstanding) }; if (supplierId!= null && amountPaid > 0) { db.paymentDao().insert(Payment(reference = billNo, partyType = "supplier", partyId = supplierId, amount = amountPaid, method = paymentMethod, note = if (original!= null) "Purchase payment (edited)" else "Purchase payment")) }; if (amountPaid > 0) { db.cashTransactionDao().insert(CashTransaction(type = "OUT", method = paymentMethod.lowercase(), amount = amountPaid, reason = "Purchase", reference = billNo)) }; suppressDraftSave = true; clearDraft(); editBillNo = billNo; Toast.makeText(this@PurchaseActivity, if (original!= null) "Purchase updated" else "Purchase saved", Toast.LENGTH_SHORT).show(); openBillPreview(billNo, forSaving = true, party = party, grandTotal = grandTotal, discount = discount, amountPaid = amountPaid, paymentMethod = paymentMethod)
        }
    }
    private fun openBillPreview(billNo: String, forSaving: Boolean, party: String = partyName.text.toString().trim(), grandTotal: Double = Math.round(lines.sumOf { it.amount }).toDouble(), discount: Double = 0.0, amountPaid: Double = Math.round(paidInput.text.toString().toDoubleOrNull()?: 0.0).toDouble(), paymentMethod: String = "Cash") {
        val itemsEncoded = lines.joinToString("\u0002") { listOf(it.itemName, formatQty(it.qty), it.unit, it.rate, it.amount).joinToString("\u0003") }
        val previewIntent = Intent(this, BillPreviewActivity::class.java).apply { putExtra(BillPreviewActivity.EXTRA_TYPE, "purchase"); putExtra(BillPreviewActivity.EXTRA_REFERENCE, billNo); putExtra(BillPreviewActivity.EXTRA_PARTY_NAME, party); putExtra(BillPreviewActivity.EXTRA_PARTY_LABEL, "Supplier"); putExtra(BillPreviewActivity.EXTRA_DATE_MILLIS, purchaseDateMillis); putExtra(BillPreviewActivity.EXTRA_SUBTOTAL, grandTotal + discount); putExtra(BillPreviewActivity.EXTRA_DISCOUNT, discount); putExtra(BillPreviewActivity.EXTRA_TOTAL, grandTotal); putExtra(BillPreviewActivity.EXTRA_PAID, amountPaid); putExtra(BillPreviewActivity.EXTRA_PAYMENT_METHOD, paymentMethod); putExtra(BillPreviewActivity.EXTRA_ITEMS_ENCODED, itemsEncoded) }; startActivity(previewIntent); if (forSaving) finish()
    }
}
