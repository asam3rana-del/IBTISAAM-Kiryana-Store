package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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

data class SaleLine(
    val barcode: String,
    val itemName: String,
    val qty: Double,
    val unit: String,
    val unitPrice: Double,
    val cost: Double,
    val amount: Double,
    val mainUnit: String,
    val secondaryUnit: String,
    val secondaryUnitQty: Double,
    val tertiaryUnit: String = "",
    val tertiaryUnitQty: Double = 0.0
)

private fun SaleLine.isSecondary() =
    secondaryUnit.isNotBlank() && unit == secondaryUnit && secondaryUnitQty > 0

private fun SaleLine.isTertiary() =
    tertiaryUnit.isNotBlank() && unit == tertiaryUnit &&
        tertiaryUnitQty > 0 && secondaryUnitQty > 0

private fun SaleLine.mainUnitQty() = when {
    isTertiary() -> qty / (secondaryUnitQty * tertiaryUnitQty)
    isSecondary() -> qty / secondaryUnitQty
    else -> qty
}

private fun SaleLine.mainUnitPrice() = when {
    isTertiary() -> unitPrice * secondaryUnitQty * tertiaryUnitQty
    isSecondary() -> unitPrice * secondaryUnitQty
    else -> unitPrice
}

class SaleActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INVOICE = "invoice"
        private const val PREFS_NAME = "sale_draft_prefs"
        private const val KEY_DRAFT = "draft_json"
    }

    private val bg = "#F4F6F8"
    private val white = "#FFFFFF"
    private val navy = "#0B2545"
    private val navy2 = "#16345E"
    private val teal = "#0F9B8E"
    private val red = "#E5484D"
    private val green = "#1FA971"
    private val amber = "#F5A524"
    private val textDark = "#0B2545"
    private val textMuted = "#7C8798"
    private val border = "#E3E8EE"

    private lateinit var dateValueText: TextView
    private lateinit var firmNameText: TextView
    private lateinit var customerName: AutoCompleteTextView
    private lateinit var cashBtn: Button
    private lateinit var creditBtn: Button
    private lateinit var saleTypeSpinner: Spinner
    private lateinit var addItemsTrigger: TextView
    private lateinit var itemEntrySection: LinearLayout
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitButton: TextView
    private lateinit var unitPrice: EditText
    private lateinit var itemsContainer: LinearLayout
    private lateinit var subtotalText: TextView
    private lateinit var discountInput: EditText
    private lateinit var totalText: TextView
    private lateinit var paymentSection: LinearLayout
    private lateinit var paidInput: EditText
    private lateinit var paymentMethodSpinner: Spinner
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button
    private lateinit var selectedUnitText: TextView
    private lateinit var conversionInfo: TextView

    private var customers = listOf<Customer>()
    private var products = listOf<Product>()
    private var allUnits = listOf(
        "pcs", "kg", "box", "dozen", "carton", "ctn", "outer", "dabbi",
        "gram", "g", "ml", "litre", "liter", "pao", "quintal", "ton", "gross"
    )

    private val lines = mutableListOf<SaleLine>()
    private var selectedProduct: Product? = null
    private var selectedUnit = "pcs"
    private var isCashSale = true
    private var saleDateMillis = System.currentTimeMillis()

    private var editInvoice: String? = null
    private var originalSale: Sale? = null
    private var originalItems: List<SaleItem> = emptyList()

    private var lastMainPrice = 0.0
    private var suppressPriceWatcher = false

    private var suppressDraftSave = false
    private var draftRestored = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editInvoice = intent.getStringExtra(EXTRA_INVOICE)
        buildUi()
        loadCustomers()
        loadProducts()
        loadUnits()
        loadFirmName()
        editInvoice?.let { loadForEdit(it) }
        if (editInvoice == null) restoreDraftIfAny()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 40, 24, 50)
            setBackgroundColor(Color.parseColor(bg))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 18, 24)
            background = gradientBg(navy, navy2, 20)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 16) }
            applyElevation(this, 7f)
        }
        val hcol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        hcol.addView(TextView(this).apply {
            text = if (editInvoice == null)
                tr("New Sale", "نئی سیل")
            else tr("Edit Sale", "سیل میں ترمیم")
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        hcol.addView(TextView(this).apply {
            text = "SALES · CUSTOMER BILLING"
            textSize = 10.5f
            setTextColor(Color.parseColor("#A7B4CC"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 4, 0, 0)
        })
        header.addView(hcol)
        header.addView(pill("History") {
            startActivity(Intent(this, SaleHistoryActivity::class.java))
        })
        header.addView(space(8, horizontal = true))
        header.addView(pill("Hold") { holdBill() })
        header.addView(space(8, horizontal = true))
        header.addView(pill("Recall") { openRecallDialog() })
        root.addView(header)

        val date = card()
        date.addView(label(tr("Date", "تاریخ")))
        val dateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dateValueText = TextView(this).apply {
            text = formatDate(saleDateMillis)
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        dateRow.addView(dateValueText)
        dateRow.addView(TextView(this).apply {
            text = "›"
            textSize = 20f
            setTextColor(Color.parseColor(teal))
        })
        date.addView(dateRow)
        date.setOnClickListener { openDatePicker() }
        root.addView(date)

        val firm = card()
        firm.addView(label(tr("Firm Name", "فرم کا نام")))
        firmNameText = TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 14.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        }
        firm.addView(firmNameText)
        root.addView(firm)

        val customer = card()
        customer.addView(label(tr("Customer", "کسٹمر")))
        val crow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        customerName = AutoCompleteTextView(this).apply {
            hint = tr("Customer Name (Walk-in)", "کسٹمر کا نام (واک ان)")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            threshold = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        crow.addView(customerName)
        crow.addView(circle("+", teal, 32) { promptAddCustomer() })
        customer.addView(crow)
        root.addView(customer)

        val mode = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cashBtn = modeButton("CASH", true) { setSaleMode(true) }
        creditBtn = modeButton("CREDIT", false) { setSaleMode(false) }
        mode.addView(cashBtn, LinearLayout.LayoutParams(0, -2, 1f))
        mode.addView(space(8, true), LinearLayout.LayoutParams(8, 1))
        mode.addView(creditBtn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(mode)
        root.addView(space(14))

        val type = card()
        type.addView(label(tr("Sale Type", "سیل کی قسم")))
        saleTypeSpinner = Spinner(this)
        saleTypeSpinner.adapter = spinnerAdapter(listOf("Retail", "Wholesale"))
        type.addView(saleTypeSpinner)
        root.addView(type)

        val addBox = card()
        addItemsTrigger = TextView(this).apply {
            text = "＋  " + tr("Add Items", "آئٹمز شامل کریں")
            textSize = 15f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        addBox.addView(addItemsTrigger)
        addBox.setOnClickListener { toggleItemEntry() }
        root.addView(addBox)

        itemEntrySection = card().apply {
            setPadding(18, 18, 18, 18)
        }

        val itemBox = inner()
        itemBox.addView(label(tr("Item Name", "آئٹم کا نام")))
        val itemRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        itemName = AutoCompleteTextView(this).apply {
            hint = tr("Type to search…", "تلاش کے لیے لکھیں…")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            threshold = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        itemRow.addView(itemName)
        itemRow.addView(circle("+", teal, 30) { promptAddProduct(itemName.text.toString().trim()) })
        itemBox.addView(itemRow)
        itemEntrySection.addView(itemBox)
        itemEntrySection.addView(space(8))

        val qtyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qbox = inner().apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 6, 0) }
        }
        qbox.addView(label(tr("Quantity", "مقدار")))
        qty = EditText(this).apply {
            hint = "0"
            setTextColor(Color.parseColor(textDark))
            setHintTextColor(Color.parseColor(textMuted))
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        qbox.addView(qty)
        val ubox = inner().apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 0, 0, 0) }
        }
        ubox.addView(label(tr("Unit", "یونٹ")))
        val urow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        selectedUnitText = TextView(this).apply {
            text = "pcs"
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        unitButton = TextView(this).apply {
            text = "📏"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = ovalBg(teal)
            val p = dp(34)
            width = p; height = p
            setOnClickListener { openUnitDialog() }
        }
        urow.addView(selectedUnitText)
        urow.addView(unitButton)
        ubox.addView(urow)
        qtyRow.addView(qbox)
        qtyRow.addView(ubox)
        itemEntrySection.addView(qtyRow)
        itemEntrySection.addView(space(8))

        val rateBox = inner()
        rateBox.addView(label(tr("Rate / Unit", "ریٹ / یونٹ")))
        unitPrice = EditText(this).apply {
            hint = tr("Auto-filled, editable", "خودکار، قابل ترمیم")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        rateBox.addView(unitPrice)
        itemEntrySection.addView(rateBox)

        conversionInfo = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(12, 10, 12, 10)
            background = strokedBg("#CDEEEC", "#EFFBFA", 10)
            visibility = View.GONE
        }
        itemEntrySection.addView(conversionInfo)
        itemEntrySection.addView(space(10))

        val addButton = Button(this).apply {
            text = tr("＋ ADD ITEM", "＋ آئٹم شامل کریں")
            isAllCaps = false
            textSize = 14.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = gradientBg(teal, "#0C8F8A", 14)
            setPadding(0, 24, 0, 24)
            setOnClickListener { addItem() }
        }
        itemEntrySection.addView(addButton)
        root.addView(itemEntrySection)

        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        val sub = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6, 10, 6, 8)
        }
        sub.addView(TextView(this).apply {
            text = tr("Subtotal", "سب ٹوٹل")
            textSize = 14f
            setTextColor(Color.parseColor(textMuted))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        subtotalText = TextView(this).apply {
            text = "Rs 0.00"
            textSize = 14f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        }
        sub.addView(subtotalText)
        root.addView(sub)

        val disc = inner()
        discountInput = EditText(this).apply {
            hint = tr("Discount (Rs)", "رعایت (روپے)")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        disc.addView(discountInput)
        root.addView(disc)

        val totalCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 20, 22, 20)
            background = gradientBg(teal, "#0C8F8A", 16)
            applyElevation(this, 5f)
        }
        totalCard.addView(TextView(this).apply {
            text = tr("TOTAL AMOUNT", "کل رقم")
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        totalText = TextView(this).apply {
            text = "Rs 0.00"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        totalCard.addView(totalText)
        root.addView(totalCard)
        root.addView(space(12))

        paymentSection = card()
        val payRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val methodBox = inner().apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 6, 0) }
        }
        methodBox.addView(label(tr("Method", "طریقہ")))
        paymentMethodSpinner = Spinner(this)
        paymentMethodSpinner.adapter = spinnerAdapter(listOf("Cash", "Bank"))
        methodBox.addView(paymentMethodSpinner)
        val paidBox = inner().apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 0, 0, 0) }
        }
        paidBox.addView(label(tr("Amount Paid", "ادا شدہ رقم")))
        paidInput = EditText(this).apply {
            hint = "0.00"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        paidBox.addView(paidInput)
        payRow.addView(methodBox)
        payRow.addView(paidBox)
        paymentSection.addView(payRow)
        root.addView(paymentSection)
        root.addView(space(16))

        saveButton = Button(this).apply {
            text = if (editInvoice == null) tr("SAVE SALE", "سیل محفوظ کریں")
            else tr("UPDATE SALE", "سیل اپ ڈیٹ کریں")
            isAllCaps = false
            textSize = 15.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = gradientBg(navy, navy2, 16)
            setPadding(0, 27, 0, 27)
            setOnClickListener { saveSale() }
        }
        deleteButton = Button(this).apply {
            text = tr("DELETE", "حذف کریں")
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(red, 16)
            setPadding(0, 27, 0, 27)
            visibility = if (editInvoice == null) View.GONE else View.VISIBLE
            setOnClickListener { confirmDeleteSale() }
        }
        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveRow.addView(saveButton, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 6, 0) })
        saveRow.addView(deleteButton, LinearLayout.LayoutParams(0, -2, .75f).apply { setMargins(6, 0, 0, 0) })
        root.addView(saveRow)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        customerName.setOnItemClickListener { _, _, p, _ ->
            customerName.setText(customerName.adapter.getItem(p).toString())
        }
        itemName.setOnItemClickListener { _, _, p, _ ->
            onItemPicked(itemName.adapter.getItem(p).toString())
            qty.requestFocus()
        }
        itemName.addTextChangedListener(watcher {
            val match = products.find { it.name.equals(itemName.text.toString().trim(), true) }
            if (match == null) clearSelectedProduct()
        })
        qty.addTextChangedListener(watcher { })
        unitPrice.addTextChangedListener(watcher {
            if (!suppressPriceWatcher) {
                lastMainPrice = toMainPrice(unitPrice.text.toString().toDoubleOrNull() ?: 0.0)
            }
        })
        discountInput.addTextChangedListener(watcher { updateTotals() })
        saleTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                lastMainPrice = 0.0
                refillAutoPrice()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun openUnitDialog() {
        val product = selectedProduct
        if (product == null) {
            Toast.makeText(this, tr("Select an item first", "پہلے آئٹم منتخب کریں"), Toast.LENGTH_SHORT).show()
            return
        }

        val options = mutableListOf(product.unit.ifBlank { "pcs" })
        if (product.secondaryUnit.isNotBlank() && product.secondaryUnit !in options)
            options.add(product.secondaryUnit)
        if (product.tertiaryUnit.isNotBlank() && product.tertiaryUnit !in options)
            options.add(product.tertiaryUnit)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(white))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 24, 26, 22)
            background = gradientBg(navy, navy2, 0)
        }
        head.addView(TextView(this).apply {
            text = "📏  " + tr("Select Sale Unit", "سیل یونٹ منتخب کریں")
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        head.addView(TextView(this).apply {
            text = product.name
            textSize = 12f
            setTextColor(Color.parseColor("#B9C7D9"))
            setPadding(0, 5, 0, 0)
        })
        content.addView(head)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 10)
        }

        val chain = buildConversionText(product)
        if (chain.isNotBlank()) {
            body.addView(TextView(this).apply {
                text = chain
                textSize = 12f
                setTextColor(Color.parseColor(teal))
                setTypeface(typeface, Typeface.BOLD)
                background = strokedBg("#CDEEEC", "#EFFBFA", 12)
                setPadding(16, 14, 16, 14)
            })
            body.addView(space(14))
        }

        body.addView(TextView(this).apply {
            text = tr("Choose the unit you want to sell in", "جس یونٹ میں سیل کرنی ہے وہ منتخب کریں")
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
            setPadding(2, 0, 2, 10)
        })

        var dialog: AlertDialog? = null
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        options.forEach { unit ->
            val selected = unit.equals(selectedUnit, true)
            val row = TextView(this).apply {
                text = if (selected) "✓  $unit" else "     $unit"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (selected) Color.WHITE else Color.parseColor(textDark))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 17, 20, 17)
                background = if (selected) roundedBg(teal, 14)
                else strokedBg(border, "#FAFBFC", 14)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, 0, 0, 8)
                }
                setOnClickListener {
                    selectedUnit = unit
                    selectedUnitText.text = unit
                    updateConversionInfo(product)
                    refillAutoPrice()
                    dialog?.dismiss()
                }
            }
            buttons.addView(row)
        }
        body.addView(buttons)
        content.addView(body)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(22, 8, 22, 22)
        }
        val close = TextView(this).apply {
            text = tr("Cancel", "منسوخ کریں")
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(textMuted))
            background = strokedBg(border, "#FAFBFC", 14)
            setPadding(0, 18, 0, 18)
        }
        footer.addView(close, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(footer)

        dialog = AlertDialog.Builder(this).setView(content).create()
        close.setOnClickListener { dialog?.dismiss() }
        dialog.show()
    }

    private fun buildConversionText(p: Product): String = buildString {
        if (p.secondaryUnit.isNotBlank() && p.secondaryUnitQty > 0) {
            append("1 ${p.unit} = ${formatQty(p.secondaryUnitQty)} ${p.secondaryUnit}")
        }
        if (p.tertiaryUnit.isNotBlank() && p.tertiaryUnitQty > 0 && p.secondaryUnit.isNotBlank()) {
            if (isNotEmpty()) append("   •   ")
            append("1 ${p.secondaryUnit} = ${formatQty(p.tertiaryUnitQty)} ${p.tertiaryUnit}")
        }
    }

    private fun updateConversionInfo(p: Product) {
        val text = buildConversionText(p)
        conversionInfo.text = text
        conversionInfo.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun onItemPicked(name: String) {
        val p = products.find { it.name.equals(name, true) } ?: return
        selectedProduct = p
        selectedUnit = p.unit.ifBlank { "pcs" }
        selectedUnitText.text = selectedUnit
        lastMainPrice = 0.0
        updateConversionInfo(p)
        refillAutoPrice()
    }

    private fun clearSelectedProduct() {
        selectedProduct = null
        selectedUnit = "pcs"
        selectedUnitText.text = "pcs"
        conversionInfo.visibility = View.GONE
    }

    private fun toMainQty(value: Double): Double {
        val p = selectedProduct ?: return value
        return when {
            selectedUnit == p.tertiaryUnit && p.tertiaryUnitQty > 0 && p.secondaryUnitQty > 0 ->
                value / (p.secondaryUnitQty * p.tertiaryUnitQty)
            selectedUnit == p.secondaryUnit && p.secondaryUnitQty > 0 ->
                value / p.secondaryUnitQty
            else -> value
        }
    }

    private fun toMainPrice(value: Double): Double {
        val p = selectedProduct ?: return value
        return when {
            selectedUnit == p.tertiaryUnit && p.tertiaryUnitQty > 0 && p.secondaryUnitQty > 0 ->
                value * p.secondaryUnitQty * p.tertiaryUnitQty
            selectedUnit == p.secondaryUnit && p.secondaryUnitQty > 0 ->
                value * p.secondaryUnitQty
            else -> value
        }
    }

    private fun fromMainPrice(value: Double): Double {
        val p = selectedProduct ?: return value
        return when {
            selectedUnit == p.tertiaryUnit && p.tertiaryUnitQty > 0 && p.secondaryUnitQty > 0 ->
                value / (p.secondaryUnitQty * p.tertiaryUnitQty)
            selectedUnit == p.secondaryUnit && p.secondaryUnitQty > 0 ->
                value / p.secondaryUnitQty
            else -> value
        }
    }

    private fun refillAutoPrice() {
        val p = selectedProduct ?: return
        val wholesale = saleTypeSpinner.selectedItem?.toString() == "Wholesale"
        val base = if (lastMainPrice > 0) lastMainPrice
        else if (wholesale) p.wholesalePrice else p.salePrice
        val value = fromMainPrice(base)
        suppressPriceWatcher = true
        unitPrice.setText(if (value > 0) "%.2f".format(value) else "")
        suppressPriceWatcher = false
    }

    private fun addItem() {
        val name = itemName.text.toString().trim()
        val q = qty.text.toString().toDoubleOrNull() ?: 0.0
        val price = unitPrice.text.toString().toDoubleOrNull() ?: 0.0
        val p = products.find { it.name.equals(name, true) }

        if (p == null) {
            Toast.makeText(this, tr("Item is not in Product list", "یہ آئٹم پروڈکٹ لسٹ میں نہیں ہے"), Toast.LENGTH_SHORT).show()
            return
        }
        if (q <= 0) {
            qty.error = tr("Enter quantity", "مقدار لکھیں")
            return
        }
        if (price < 0) {
            unitPrice.error = tr("Invalid rate", "غلط ریٹ")
            return
        }

        val needed = toMainQty(q)
        val already = lines.filter { it.barcode == p.barcode }.sumOf { it.mainUnitQty() }
        val available = p.stock - already

        if (available < needed - 0.000001) {
            Toast.makeText(
                this,
                "${tr("Stock short", "اسٹاک کم ہے")}: ${formatQty(available.coerceAtLeast(0.0))} ${p.unit}",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        lines.add(
            SaleLine(
                barcode = p.barcode,
                itemName = p.name,
                qty = q,
                unit = selectedUnit,
                unitPrice = price,
                cost = p.cost,
                amount = q * price,
                mainUnit = p.unit,
                secondaryUnit = p.secondaryUnit,
                secondaryUnitQty = p.secondaryUnitQty,
                tertiaryUnit = p.tertiaryUnit,
                tertiaryUnitQty = p.tertiaryUnitQty
            )
        )
        renderItems()
        updateTotals()
        itemName.text.clear()
        qty.text.clear()
        unitPrice.text.clear()
        clearSelectedProduct()
        if (editInvoice == null) saveDraft()
    }

    private fun renderItems() {
        itemsContainer.removeAllViews()
        lines.forEachIndexed { index, line ->
            val row = card().apply { setPadding(18, 15, 18, 15) }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = "${index + 1}. ${line.itemName}"
                textSize = 14.5f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            top.addView(TextView(this).apply {
                text = "Rs %.2f".format(line.amount)
                textSize = 14.5f
                setTextColor(Color.parseColor(teal))
                setTypeface(typeface, Typeface.BOLD)
            })
            row.addView(top)
            row.addView(TextView(this).apply {
                text = "${formatQty(line.qty)} ${line.unit} × Rs ${"%.2f".format(line.unitPrice)}"
                textSize = 12.5f
                setTextColor(Color.parseColor(textMuted))
                setPadding(0, 6, 0, 0)
            })
            row.addView(TextView(this).apply {
                text = "✕  " + tr("Remove", "ہٹائیں")
                textSize = 12f
                setTextColor(Color.parseColor(red))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 10, 0, 0)
                setOnClickListener {
                    lines.removeAt(index)
                    renderItems()
                    updateTotals()
                    if (editInvoice == null) saveDraft()
                }
            })
            itemsContainer.addView(row)
        }
    }

    private fun updateTotals() {
        val subtotal = lines.sumOf { it.amount }
        val discount = (discountInput.text.toString().toDoubleOrNull() ?: 0.0)
            .coerceIn(0.0, subtotal)
        val total = (subtotal - discount).coerceAtLeast(0.0)
        subtotalText.text = "Rs %.2f".format(subtotal)
        totalText.text = "Rs %.2f".format(total)
        if (isCashSale) {
            paidInput.setText("%.2f".format(total))
        }
    }

    private fun saveSale() {
        if (lines.isEmpty()) {
            Toast.makeText(this, tr("Add at least one item", "کم از کم ایک آئٹم شامل کریں"), Toast.LENGTH_SHORT).show()
            return
        }

        val enteredCustomer = customerName.text.toString().trim()
        if (!isCashSale && enteredCustomer.isBlank()) {
            Toast.makeText(this, tr("Customer is required for credit sale", "ادھار سیل کے لیے کسٹمر ضروری ہے"), Toast.LENGTH_SHORT).show()
            return
        }

        val subtotal = lines.sumOf { it.amount }
        val discount = (discountInput.text.toString().toDoubleOrNull() ?: 0.0).coerceIn(0.0, subtotal)
        val total = (subtotal - discount).coerceAtLeast(0.0)
        val paid = if (isCashSale)
            (paidInput.text.toString().toDoubleOrNull() ?: total).coerceIn(0.0, total)
        else 0.0
        val method = if (isCashSale)
            paymentMethodSpinner.selectedItem?.toString()?.lowercase() ?: "cash"
        else "credit"
        val saleType = if (saleTypeSpinner.selectedItem?.toString() == "Wholesale") "wholesale" else "retail"
        val invoice = editInvoice ?: "INV${System.currentTimeMillis()}"

        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)

            // Validate against current stock BEFORE changing the old bill.
            val oldQtyByBarcode = if (originalSale != null) {
                originalItems.groupBy { it.barcode }.mapValues { it.value.sumOf { x -> x.qty.toDouble() } }
            } else emptyMap()

            val needed = lines.groupBy { it.barcode }
                .mapValues { (_, group) -> group.sumOf { it.mainUnitQty() } }

            for ((barcode, newQty) in needed) {
                val current = db.productDao().find(barcode)
                val oldQty = oldQtyByBarcode[barcode] ?: 0.0
                val availableAfterReleasingOld = (current?.stock ?: 0) + oldQty
                if (current == null || availableAfterReleasingOld < newQty) {
                    Toast.makeText(
                        this@SaleActivity,
                        "\"${current?.name ?: barcode}\" ${tr("has insufficient stock", "کا اسٹاک کم ہے")}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
            }

            var customer = customers.find { it.name.equals(enteredCustomer, true) }
            if (customer == null && enteredCustomer.isNotBlank()) {
                val id = db.customerDao().insert(Customer(name = enteredCustomer))
                customer = Customer(id = id, name = enteredCustomer)
            }

            // Reverse old edit effects only after validation succeeds.
            originalSale?.let { old ->
                originalItems.forEach { db.productDao().increase(it.barcode, it.qty) }
                val outstanding = (old.total - old.paid).coerceAtLeast(0.0)
                if (old.customerId != null && outstanding > 0)
                    db.customerDao().addBalance(old.customerId, -outstanding)
                db.saleDao().deleteItems(invoice)
                db.saleDao().deleteSale(invoice)
                db.cashTransactionDao().deleteByReference(invoice)
            }

            db.saleDao().sale(
                Sale(
                    invoice = invoice,
                    customerId = customer?.id,
                    subtotal = subtotal,
                    discount = discount,
                    tax = 0.0,
                    total = total,
                    paid = paid,
                    paymentMethod = method,
                    saleType = saleType,
                    createdAt = saleDateMillis
                )
            )

            db.saleDao().items(
                lines.map {
                    SaleItem(
                        invoice = invoice,
                        barcode = it.barcode,
                        product = it.itemName,
                        qty = it.mainUnitQty().roundToInt(),
                        unitPrice = it.mainUnitPrice(),
                        cost = it.cost,
                        amount = it.amount
                    )
                }
            )

            for (line in lines)
                db.productDao().decrease(line.barcode, line.mainUnitQty().roundToInt())

            if (customer != null && paid < total)
                db.customerDao().addBalance(customer.id, total - paid)

            if (paid > 0) {
                db.cashTransactionDao().insert(
                    CashTransaction(
                        type = "IN",
                        method = method,
                        amount = paid,
                        reason = "Sale",
                        reference = invoice
                    )
                )
            }

            suppressDraftSave = true
            clearDraft()
            Toast.makeText(
                this@SaleActivity,
                if (originalSale == null) tr("Sale saved", "سیل محفوظ ہو گئی")
                else tr("Sale updated", "سیل اپ ڈیٹ ہو گئی"),
                Toast.LENGTH_SHORT
            ).show()

            val encoded = lines.joinToString("\u0002") {
                listOf(it.itemName, formatQty(it.qty), it.unit, it.unitPrice, it.amount)
                    .joinToString("\u0003")
            }

            startActivity(Intent(this@SaleActivity, BillPreviewActivity::class.java).apply {
                putExtra(BillPreviewActivity.EXTRA_TYPE, "sale")
                putExtra(BillPreviewActivity.EXTRA_REFERENCE, invoice)
                putExtra(BillPreviewActivity.EXTRA_PARTY_NAME, customer?.name ?: enteredCustomer)
                putExtra(BillPreviewActivity.EXTRA_PARTY_LABEL, "Customer")
                putExtra(BillPreviewActivity.EXTRA_DATE_MILLIS, saleDateMillis)
                putExtra(BillPreviewActivity.EXTRA_SUBTOTAL, subtotal)
                putExtra(BillPreviewActivity.EXTRA_DISCOUNT, discount)
                putExtra(BillPreviewActivity.EXTRA_TOTAL, total)
                putExtra(BillPreviewActivity.EXTRA_PAID, paid)
                putExtra(BillPreviewActivity.EXTRA_PAYMENT_METHOD, method)
                putExtra(BillPreviewActivity.EXTRA_ITEMS_ENCODED, encoded)
            })
            finish()
        }
    }

    private fun confirmDeleteSale() {
        val invoice = editInvoice ?: return
        AlertDialog.Builder(this)
            .setTitle(tr("Delete Sale", "سیل حذف کریں"))
            .setMessage(tr(
                "This will reverse stock, customer balance and cash entry. Continue?",
                "یہ اسٹاک، کسٹمر بیلنس اور کیش انٹری واپس کرے گا۔ جاری رکھیں؟"
            ))
            .setPositiveButton(tr("Delete", "حذف کریں")) { _, _ -> deleteSale(invoice) }
            .setNegativeButton(tr("Cancel", "منسوخ کریں"), null)
            .show()
    }

    private fun deleteSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)
            val sale = originalSale ?: db.saleDao().findSale(invoice) ?: return@launch
            val items = originalItems.ifEmpty { db.saleDao().itemsForInvoice(invoice) }

            items.forEach { db.productDao().increase(it.barcode, it.qty) }
            val outstanding = (sale.total - sale.paid).coerceAtLeast(0.0)
            if (sale.customerId != null && outstanding > 0)
                db.customerDao().addBalance(sale.customerId, -outstanding)

            db.saleDao().deleteItems(invoice)
            db.saleDao().deleteSale(invoice)
            db.cashTransactionDao().deleteByReference(invoice)

            Toast.makeText(this@SaleActivity, tr("Sale deleted", "سیل حذف ہو گئی"), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun holdBill() {
        if (lines.isEmpty()) {
            Toast.makeText(this, tr("Add items before Hold", "پہلے آئٹمز شامل کریں"), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val id = "HOLD${System.currentTimeMillis()}"
            PosDatabase.get(this@SaleActivity).heldDao()
                .hold(HeldBill(holdId = id, payload = encodeHold()))
            Toast.makeText(this@SaleActivity, tr("Bill held", "بل ہولڈ ہو گیا"), Toast.LENGTH_SHORT).show()
            clearAll()
        }
    }

    private fun encodeHold(): String {
        val header = listOf(
            customerName.text.toString(),
            saleTypeSpinner.selectedItem?.toString() ?: "Retail",
            if (isCashSale) "CASH" else "CREDIT",
            discountInput.text.toString()
        ).joinToString("\u0001")

        val items = lines.joinToString("\u0002") {
            listOf(
                it.barcode, it.itemName, it.qty, it.unit, it.unitPrice, it.cost,
                it.amount, it.mainUnit, it.secondaryUnit, it.secondaryUnitQty,
                it.tertiaryUnit, it.tertiaryUnitQty
            ).joinToString("\u0003")
        }
        return "$header\u0004$items"
    }

    private fun decodeHold(payload: String) {
        val parts = payload.split("\u0004")
        val h = parts.firstOrNull()?.split("\u0001") ?: return
        if (h.size >= 4) {
            customerName.setText(h[0])
            saleTypeSpinner.setSelection(if (h[1] == "Wholesale") 1 else 0)
            setSaleMode(h[2] != "CREDIT")
            discountInput.setText(h[3])
        }

        lines.clear()
        parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.split("\u0002")?.forEach { row ->
            val f = row.split("\u0003")
            if (f.size >= 10) {
                lines.add(
                    SaleLine(
                        f[0], f[1],
                        f[2].toDoubleOrNull() ?: 0.0,
                        f[3],
                        f[4].toDoubleOrNull() ?: 0.0,
                        f[5].toDoubleOrNull() ?: 0.0,
                        f[6].toDoubleOrNull() ?: 0.0,
                        f[7], f[8],
                        f[9].toDoubleOrNull() ?: 0.0,
                        f.getOrNull(10) ?: "",
                        f.getOrNull(11)?.toDoubleOrNull() ?: 0.0
                    )
                )
            }
        }
        renderItems()
        updateTotals()
        if (editInvoice == null) saveDraft()
    }

    private fun openRecallDialog() {
        lifecycleScope.launch {
            val held = PosDatabase.get(this@SaleActivity).heldDao().all().first()
            val list = LinearLayout(this@SaleActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 10, 20, 10)
            }
            if (held.isEmpty()) {
                list.addView(TextView(this@SaleActivity).apply {
                    text = tr("No held bills", "کوئی ہولڈ بل نہیں")
                    textSize = 14f
                    setTextColor(Color.parseColor(textMuted))
                    setPadding(10, 25, 10, 25)
                })
            }
            val dialog = AlertDialog.Builder(this@SaleActivity)
                .setTitle(tr("Held Bills", "ہولڈ بلز"))
                .setView(list)
                .setNegativeButton(tr("Close", "بند کریں"), null)
                .create()

            held.forEach { h ->
                val row = LinearLayout(this@SaleActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(14, 14, 14, 14)
                    background = strokedBg(border, "#FAFBFC", 12)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                        setMargins(0, 0, 0, 8)
                    }
                }
                val info = TextView(this@SaleActivity).apply {
                    val count = h.payload.split("\u0004").getOrNull(1)
                        ?.split("\u0002")?.count { it.isNotBlank() } ?: 0
                    text = "$count items\n" +
                        SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                            .format(Date(h.createdAt))
                    textSize = 13f
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                row.addView(info)
                row.addView(TextView(this@SaleActivity).apply {
                    text = "RECALL"
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    background = roundedBg(teal, 22)
                    setPadding(18, 10, 18, 10)
                    setOnClickListener {
                        decodeHold(h.payload)
                        lifecycleScope.launch {
                            PosDatabase.get(this@SaleActivity).heldDao().delete(h)
                        }
                        dialog.dismiss()
                    }
                })
                list.addView(row)
            }
            dialog.show()
        }
    }

    private fun loadForEdit(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            originalSale = sale
            originalItems = items

            saleDateMillis = sale.createdAt
            dateValueText.text = formatDate(saleDateMillis)

            val custs = db.customerDao().all().first()
            val name = sale.customerId?.let { id -> custs.find { it.id == id }?.name } ?: ""
            customerName.setText(name)

            saleTypeSpinner.setSelection(if (sale.saleType == "wholesale") 1 else 0)
            setSaleMode(!sale.paymentMethod.equals("credit", true))
            discountInput.setText(if (sale.discount > 0) "%.2f".format(sale.discount) else "")
            paidInput.setText(if (sale.paid > 0) "%.2f".format(sale.paid) else "")
            paymentMethodSpinner.setSelection(
                if (sale.paymentMethod.equals("bank", true)) 1 else 0
            )

            val prodList = db.productDao().all().first()
            lines.clear()
            items.forEach { si ->
                val p = prodList.find { it.barcode == si.barcode }
                lines.add(
                    SaleLine(
                        si.barcode,
                        si.product,
                        si.qty.toDouble(),
                        p?.unit ?: "pcs",
                        si.unitPrice,
                        si.cost,
                        si.amount,
                        p?.unit ?: "pcs",
                        p?.secondaryUnit ?: "",
                        p?.secondaryUnitQty ?: 0.0,
                        p?.tertiaryUnit ?: "",
                        p?.tertiaryUnitQty ?: 0.0
                    )
                )
            }
            renderItems()
            updateTotals()
            deleteButton.visibility = View.VISIBLE
        }
    }

    private fun loadCustomers() {
        lifecycleScope.launch {
            PosDatabase.get(this@SaleActivity).customerDao().all().collectLatest {
                customers = it
                customerName.setAdapter(
                    ArrayAdapter(
                        this@SaleActivity,
                        android.R.layout.simple_dropdown_item_1line,
                        it.map { c -> c.name }
                    )
                )
            }
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            PosDatabase.get(this@SaleActivity).productDao().all().collectLatest {
                products = it
                itemName.setAdapter(
                    ArrayAdapter(
                        this@SaleActivity,
                        android.R.layout.simple_dropdown_item_1line,
                        it.map { p -> p.name }
                    )
                )
            }
        }
    }

    private fun loadUnits() {
        lifecycleScope.launch {
            PosDatabase.get(this@SaleActivity).unitDao().all().collectLatest {
                allUnits = (
                    allUnits + it.map { u -> u.name }
                ).distinct()
            }
        }
    }

    private fun loadFirmName() {
        lifecycleScope.launch {
            val n = PosDatabase.get(this@SaleActivity)
                .appSettingDao().get("shop_name")?.value
            if (!n.isNullOrBlank()) firmNameText.text = n
        }
    }

    private fun promptAddCustomer() {
        val input = EditText(this).apply {
            hint = tr("Customer name", "کسٹمر کا نام")
            setPadding(30, 20, 30, 20)
        }
        AlertDialog.Builder(this)
            .setTitle(tr("New Customer", "نیا کسٹمر"))
            .setView(input)
            .setPositiveButton(tr("Add", "شامل کریں")) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotBlank()) lifecycleScope.launch {
                    PosDatabase.get(this@SaleActivity)
                        .customerDao().insert(Customer(name = n))
                    customerName.setText(n)
                }
            }
            .setNegativeButton(tr("Cancel", "منسوخ کریں"), null)
            .show()
    }

    private fun promptAddProduct(prefill: String) {
        Toast.makeText(
            this,
            tr("Add this item from Product screen", "یہ آئٹم Product screen سے شامل کریں"),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setSaleMode(cash: Boolean) {
        isCashSale = cash
        if (cash) {
            cashBtn.background = roundedBg(teal, 22)
            cashBtn.setTextColor(Color.WHITE)
            creditBtn.background = roundedBg("#EEF0F7", 22)
            creditBtn.setTextColor(Color.parseColor(textMuted))
            paymentSection.visibility = View.VISIBLE
        } else {
            creditBtn.background = roundedBg(red, 22)
            creditBtn.setTextColor(Color.WHITE)
            cashBtn.background = roundedBg("#EEF0F7", 22)
            cashBtn.setTextColor(Color.parseColor(textMuted))
            paymentSection.visibility = View.GONE
        }
    }

    private fun toggleItemEntry() {
        val show = itemEntrySection.visibility != View.VISIBLE
        itemEntrySection.visibility = if (show) View.VISIBLE else View.GONE
        addItemsTrigger.text =
            if (show) "⌃  " + tr("Hide Item Entry", "آئٹم انٹری چھپائیں")
            else "＋  " + tr("Add Items", "آئٹمز شامل کریں")
    }

    private fun clearAll() {
        lines.clear()
        renderItems()
        customerName.text.clear()
        discountInput.text.clear()
        itemName.text.clear()
        qty.text.clear()
        unitPrice.text.clear()
        paidInput.text.clear()
        clearSelectedProduct()
        saleDateMillis = System.currentTimeMillis()
        dateValueText.text = formatDate(saleDateMillis)
        setSaleMode(true)
        clearDraft()
    }

    private fun saveDraft() {
        if (suppressDraftSave) return
        val has = lines.isNotEmpty() ||
            customerName.text.isNotBlank() ||
            itemName.text.isNotBlank() ||
            qty.text.isNotBlank() ||
            unitPrice.text.isNotBlank()
        if (!has) {
            clearDraft()
            return
        }

        val arr = JSONArray()
        lines.forEach {
            arr.put(JSONObject().apply {
                put("barcode", it.barcode)
                put("itemName", it.itemName)
                put("qty", it.qty)
                put("unit", it.unit)
                put("unitPrice", it.unitPrice)
                put("cost", it.cost)
                put("amount", it.amount)
                put("mainUnit", it.mainUnit)
                put("secondaryUnit", it.secondaryUnit)
                put("secondaryUnitQty", it.secondaryUnitQty)
                put("tertiaryUnit", it.tertiaryUnit)
                put("tertiaryUnitQty", it.tertiaryUnitQty)
            })
        }

        val draft = JSONObject().apply {
            put("customer", customerName.text.toString())
            put("saleType", saleTypeSpinner.selectedItem?.toString() ?: "Retail")
            put("cash", isCashSale)
            put("discount", discountInput.text.toString())
            put("paid", paidInput.text.toString())
            put("dateMillis", saleDateMillis)
            put("pendingItem", itemName.text.toString())
            put("pendingQty", qty.text.toString())
            put("pendingPrice", unitPrice.text.toString())
            put("pendingUnit", selectedUnit)
            put("lines", arr)
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DRAFT, draft.toString()).apply()
    }

    private fun restoreDraftIfAny() {
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DRAFT, null) ?: return
        if (draftRestored) return
        val o = try { JSONObject(raw) } catch (_: Exception) { return }
        draftRestored = true
        suppressDraftSave = true
        try {
            customerName.setText(o.optString("customer", ""))
            saleTypeSpinner.setSelection(if (o.optString("saleType") == "Wholesale") 1 else 0)
            setSaleMode(o.optBoolean("cash", true))
            discountInput.setText(o.optString("discount", ""))
            paidInput.setText(o.optString("paid", ""))
            val d = o.optLong("dateMillis", 0)
            if (d > 0) {
                saleDateMillis = d
                dateValueText.text = formatDate(d)
            }

            val arr = o.optJSONArray("lines")
            if (arr != null) for (i in 0 until arr.length()) {
                val x = arr.getJSONObject(i)
                lines.add(
                    SaleLine(
                        x.optString("barcode"),
                        x.optString("itemName"),
                        x.optDouble("qty"),
                        x.optString("unit"),
                        x.optDouble("unitPrice"),
                        x.optDouble("cost"),
                        x.optDouble("amount"),
                        x.optString("mainUnit"),
                        x.optString("secondaryUnit"),
                        x.optDouble("secondaryUnitQty"),
                        x.optString("tertiaryUnit"),
                        x.optDouble("tertiaryUnitQty")
                    )
                )
            }
            renderItems()
            updateTotals()
            val pending = o.optString("pendingItem", "")
            if (pending.isNotBlank()) {
                itemName.setText(pending)
                products.find { it.name.equals(pending, true) }?.let { onItemPicked(it.name) }
            }
            qty.setText(o.optString("pendingQty", ""))
            unitPrice.setText(o.optString("pendingPrice", ""))
            selectedUnit = o.optString("pendingUnit", selectedUnit)
            selectedUnitText.text = selectedUnit
        } finally {
            suppressDraftSave = false
        }
    }

    private fun clearDraft() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_DRAFT).apply()
    }

    override fun onPause() {
        super.onPause()
        if (editInvoice == null && !suppressDraftSave) saveDraft()
    }

    private fun openDatePicker() {
        val c = Calendar.getInstance().apply { timeInMillis = saleDateMillis }
        DatePickerDialog(
            this,
            { _, y, m, d ->
                c.set(y, m, d)
                saleDateMillis = c.timeInMillis
                dateValueText.text = formatDate(saleDateMillis)
            },
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH),
            c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 16, 20, 16)
        background = strokedBg(border, white, 16)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 12) }
        applyElevation(this, 2f)
    }

    private fun inner() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 11, 16, 11)
        background = strokedBg(border, "#FAFBFC", 13)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 8) }
    }

    private fun label(s: String) = TextView(this).apply {
        text = s.uppercase()
        textSize = 10.5f
        setTextColor(Color.parseColor(textMuted))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, 6)
        letterSpacing = .04f
    }

    private fun pill(textValue: String, action: () -> Unit) = TextView(this).apply {
        text = textValue
        textSize = 11.5f
        setTextColor(Color.parseColor(navy))
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = roundedBg(white, 30)
        setPadding(18, 11, 18, 11)
        setOnClickListener { action() }
    }

    private fun modeButton(textValue: String, active: Boolean, action: () -> Unit) =
        Button(this).apply {
            text = textValue
            isAllCaps = false
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 14, 0, 14)
            background = roundedBg(if (active) teal else "#EEF0F7", 22)
            setTextColor(if (active) Color.WHITE else Color.parseColor(textMuted))
            setOnClickListener { action() }
        }

    private fun circle(t: String, color: String, sizeDp: Int, action: (() -> Unit)? = null) =
        TextView(this).apply {
            text = t
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = ovalBg(color)
            val p = dp(sizeDp)
            width = p; height = p
            action?.let { setOnClickListener { it() } }
        }

    private fun spinnerAdapter(values: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)

    private fun roundedBg(hex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(hex))
        cornerRadius = dp(radius).toFloat()
    }

    private fun strokedBg(stroke: String, fill: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fill))
        setStroke((1.2f * resources.displayMetrics.density).toInt(), Color.parseColor(stroke))
        cornerRadius = dp(radius).toFloat()
    }

    private fun gradientBg(start: String, end: String, radius: Int) =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(start), Color.parseColor(end))
        ).apply { cornerRadius = dp(radius).toFloat() }

    private fun ovalBg(hex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(hex))
    }

    private fun applyElevation(v: View, value: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            v.elevation = value * resources.displayMetrics.density
            v.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }

    private fun watcher(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { action() }
    }

    private fun space(dpValue: Int, horizontal: Boolean = false) = View(this).apply {
        layoutParams = if (horizontal)
            LinearLayout.LayoutParams(dp(dpValue), 1)
        else
            LinearLayout.LayoutParams(-1, dp(dpValue))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun formatDate(millis: Long) =
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(millis))

    private fun formatQty(v: Double) =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.3f".format(v).trimEnd('0').trimEnd('.')

    private fun tr(en: String, ur: String) =
        com.grocerypos.v11.util.Loc.t(this, en, ur)
}
