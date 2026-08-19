package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
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

private fun SaleLine.isSecondary(): Boolean =
    secondaryUnit.isNotEmpty() && unit == secondaryUnit && secondaryUnitQty > 0

private fun SaleLine.isTertiary(): Boolean =
    tertiaryUnit.isNotEmpty() && unit == tertiaryUnit && tertiaryUnitQty > 0 && secondaryUnitQty > 0

private fun SaleLine.mainUnitQty(): Double = when {
    isTertiary() -> qty / (secondaryUnitQty * tertiaryUnitQty)
    isSecondary() -> qty / secondaryUnitQty
    else -> qty
}

private fun SaleLine.mainUnitPrice(): Double = when {
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

    // ---- COLOR SWAP: Navy <-> Teal ----
    private val bg = "#F4F6F8"
    private val cardBg = "#FFFFFF"
    private val navy = "#0F9B8E" // Pehle Teal tha, ab Header Teal ho gaya (SWAP)
    private val teal = "#0B2545" // Pehle Navy tha, ab Buttons Navy honge (SWAP)
    private val green = "#1FA971"
    private val red = "#E5484D"
    private val amber = "#F5A524"
    private val textDark = "#0B2545"
    private val textGray = "#7C8798"
    private val border = "#E3E8EE"

    private lateinit var dateValueText: TextView
    private lateinit var customerName: AutoCompleteTextView
    private lateinit var saleTypeSpinner: Spinner
    private lateinit var addItemsTrigger: TextView
    private lateinit var itemEntrySection: LinearLayout
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var unitPrice: EditText
    private lateinit var itemsContainer: LinearLayout
    private lateinit var subtotalText: TextView
    private lateinit var discountInput: EditText
    private lateinit var totalText: TextView
    private lateinit var dueText: TextView
    private lateinit var totalCard: LinearLayout
    private lateinit var dueCard: LinearLayout
    private lateinit var paidInput: EditText
    private lateinit var firmNameText: TextView
    private lateinit var partyBalanceText: TextView
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button

    private var customers = listOf<Customer>()
    private var products = listOf<Product>()
    private val lines = mutableListOf<SaleLine>()
    private var selectedProduct: Product? = null
    private var saleDateMillis = System.currentTimeMillis()

    private var editInvoice: String? = null
    private var originalSale: Sale? = null
    private var originalItems: List<SaleItem> = emptyList()

    private var lastMainPrice: Double = 0.0
    private var suppressPriceWatcher = false
    private var suppressDraftSave = false
    private var draftRestored = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        editInvoice = intent.getStringExtra(EXTRA_INVOICE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 56, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER - NEW DESIGN =================
        // Date left corner me, Firm Name center me bara
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 18)
            background = roundedBg(navy, 20) // Ab Teal color hai (swap)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 18) }
            applyElevation(this, 6f)
        }

        // Top Row: Date (Left) + History/Hold/Recall (Right)
        val headerTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dateChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#FFFFFF30", 20)
            setPadding(14, 8, 14, 8)
            setOnClickListener { openDatePicker() }
        }
        dateValueText = TextView(this).apply {
            text = formatDate(saleDateMillis)
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        dateChip.addView(dateValueText)
        dateChip.addView(TextView(this).apply { text = " \u203A"; setTextColor(Color.WHITE) })

        val headerActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        headerActions.addView(pillChip("History") { startActivity(Intent(this@SaleActivity, SaleHistoryActivity::class.java)) })
        headerActions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(6, 1) })
        headerActions.addView(pillChip("Hold") { holdBill() })
        headerActions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(6, 1) })
        headerActions.addView(pillChip("Recall") { openRecallDialog() })

        headerTopRow.addView(dateChip)
        headerTopRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        headerTopRow.addView(headerActions)
        headerCard.addView(headerTopRow)

        headerCard.addView(spacer(14))

        // Center Firm Name - Bara Size
        firmNameText = TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.02f
        }
        headerCard.addView(firmNameText)

        // Party Balance - Firm ke neche left side
        partyBalanceText = TextView(this).apply {
            text = "Balance: Rs 0.00"
            textSize = 11.5f
            setTextColor(Color.parseColor("#D1FFF8"))
            gravity = Gravity.START
            setPadding(0, 10, 0, 0)
            visibility = View.GONE
        }
        headerCard.addView(partyBalanceText)

        root.addView(headerCard)

        // ================= CUSTOMER NAME =================
        val custBox = outlinedBox()
        custBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Customer", "کسٹمر")))
        val custRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        customerName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Customer Name (Walk-in)", "کسٹمر کا نام (واک ان)")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            threshold = 0 // 0 pe hi list show hogi
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            // Click par list show karo
            setOnClickListener { if (adapter!= null) showDropDown() }
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showDropDown() }
        }
        custRow.addView(customerName)
        custRow.addView(circleIcon("+", navy, 30) { promptAddCustomer() })
        custBox.addView(custRow)
        root.addView(custBox)
        root.addView(spacer(14))

        // ================= SALE TYPE =================
        val saleTypeBox = outlinedBox()
        saleTypeBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Sale Type", "سیل کی قسم")))
        saleTypeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Retail", "Wholesale"))
        }
        saleTypeBox.addView(saleTypeSpinner)
        root.addView(saleTypeBox)
        root.addView(spacer(16))

        // ================= "Add Items" trigger =================
        val addItemsBox = outlinedBox()
        val addItemsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addItemsRow.addView(circleIcon("+", navy, 30))
        addItemsTrigger = TextView(this).apply {
            text = " " + com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Add Items", "آئٹمز شامل کریں")
            textSize = 14.5f
            setTextColor(Color.parseColor(navy))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addItemsRow.addView(addItemsTrigger)
        addItemsBox.addView(addItemsRow)
        addItemsBox.setOnClickListener { toggleItemEntry() }
        root.addView(addItemsBox)
        root.addView(spacer(14))

        // ================= ITEM ENTRY (collapsible) =================
        itemEntrySection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val itemBox = outlinedBox()
        itemBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Item Name", "آئٹم کا نام")))
        itemName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Type to search…", "تلاش کے لیے لکھیں…")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
        }
        itemBox.addView(itemName)
        itemEntrySection.addView(itemBox)
        itemEntrySection.addView(spacer(10))

        val qtyUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qtyBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,6,0) }
        }
        qtyBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Quantity", "مقدار")))
        qty = EditText(this).apply {
            hint = "0"; setHintTextColor(Color.parseColor(textGray)); setTextColor(Color.parseColor(textDark)); background = null; textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        qtyBox.addView(qty)
        val unitBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6,0,0,0) }
        }
        unitBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Unit", "یونٹ")))
        unitSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
        }
        unitBox.addView(unitSpinner)
        qtyUnitRow.addView(qtyBox)
        qtyUnitRow.addView(unitBox)
        itemEntrySection.addView(qtyUnitRow)
        itemEntrySection.addView(spacer(10))

        val rateBox = outlinedBox()
        rateBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Rate", "ریٹ")))
        unitPrice = EditText(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Auto-filled, editable", "خودکار، قابل ترمیم")
            setHintTextColor(Color.parseColor(textGray)); setTextColor(Color.parseColor(textDark)); background = null; textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        rateBox.addView(unitPrice)
        itemEntrySection.addView(rateBox)
        itemEntrySection.addView(spacer(14))

        itemEntrySection.addView(Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "ADD ITEM", "آئٹم شامل کریں")
            setTextColor(Color.WHITE); textSize = 14f; isAllCaps = false; setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(navy, 14); setPadding(0, 24, 0, 24); setOnClickListener { addItem() }; applyElevation(this, 3f)
        })
        root.addView(itemEntrySection)
        root.addView(spacer(16))

        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        // ================= SUBTOTAL / DISCOUNT + PAID (LEFT SMALL) =================
        val subtotalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(6, 8, 6, 8) }
        subtotalRow.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Subtotal", "سب ٹوٹل"); textSize = 14f; setTextColor(Color.parseColor(textGray))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        subtotalText = TextView(this).apply { text = "Rs 0.00"; textSize = 14f; setTextColor(Color.parseColor(textDark)) }
        subtotalRow.addView(subtotalText)
        root.addView(subtotalRow)

        // Discount (Small Left) + Paid Amount (Right)
        val discPaidRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val discountBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f).apply { setMargins(0,0,6,0) }
            setPadding(18, 4, 18, 4)
        }
        discountBox.addView(labelRow("DISC."))
        discountInput = EditText(this).apply {
            hint = "0"; setHintTextColor(Color.parseColor(textGray)); setTextColor(Color.parseColor(textDark)); background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 14f
        }
        discountBox.addView(discountInput)
        val paidBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f).apply { setMargins(6,0,0,0) }
            setPadding(18, 4, 18, 4)
        }
        paidBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Paid Amount", "ادا شدہ رقم")))
        paidInput = EditText(this).apply {
            hint = "0.00"; setHintTextColor(Color.parseColor(textGray)); setTextColor(Color.parseColor(textDark)); background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 14f
        }
        paidBox.addView(paidInput)
        discPaidRow.addView(discountBox)
        discPaidRow.addView(paidBox)
        root.addView(discPaidRow)
        discountInput.addTextChangedListener(simpleWatcher { updateTotals() })
        paidInput.addTextChangedListener(simpleWatcher { updateTotals() })
        root.addView(spacer(12))

        // Total Amount Card
        totalCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(22, 20, 22, 20)
            background = roundedBg(teal, 16); // Ab Navy color hai (swap)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            applyElevation(this, 6f)
        }
        totalCard.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Total Amount", "کل رقم"); textSize = 15.5f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        totalText = TextView(this).apply { text = "Rs 0.00"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        totalCard.addView(totalText)
        root.addView(totalCard)
        root.addView(spacer(10))

        // Due Amount Card - Is se pata chalega Cash/Credit
        dueCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(22, 16, 22, 16)
            background = strokedBg(border, "#FFF3F3", 16)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        dueCard.addView(TextView(this).apply {
            text = "Due Amount (Udhar)"; textSize = 13.5f; setTextColor(Color.parseColor(red))
            setTypeface(typeface, android.graphics.Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        dueText = TextView(this).apply { text = "Rs 0.00"; textSize = 16f; setTextColor(Color.parseColor(red)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        dueCard.addView(dueText)
        root.addView(dueCard)
        root.addView(spacer(18))

        // SAVE + DELETE
        saveButton = Button(this).apply {
            text = if (editInvoice!= null) com.grocerypos.v11.util.Loc.t(this@SaleActivity, "UPDATE SALE", "سیل اپ ڈیٹ کریں") else com.grocerypos.v11.util.Loc.t(this@SaleActivity, "SAVE SALE", "سیل محفوظ کریں")
            setTextColor(Color.WHITE); textSize = 15.5f; isAllCaps = false; setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(teal, 16); setPadding(0, 26, 0, 26); setOnClickListener { saveSale() }; applyElevation(this, 8f)
        }
        deleteButton = Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "DELETE", "حذف کریں"); setTextColor(Color.WHITE); textSize = 15.5f; isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD); background = roundedBg(red, 16); setPadding(0, 26, 0, 26)
            visibility = if (editInvoice!= null) View.VISIBLE else View.GONE; setOnClickListener { confirmDeleteSale() }; applyElevation(this, 8f)
        }
        val saveDeleteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveDeleteRow.addView(saveButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) })
        saveDeleteRow.addView(deleteButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply { setMargins(8, 0, 0, 0) })
        root.addView(saveDeleteRow)
        root.addView(spacer(40))

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(bg)); addView(root) })

        loadCustomers()
        loadProducts()
        loadFirmName()
        editInvoice?.let { loadForEdit(it) }

        itemName.setOnItemClickListener { _, _, position, _ -> onItemPicked(itemName.adapter.getItem(position).toString()) }
        itemName.addTextChangedListener(simpleWatcher {
            val match = products.find { it.name.equals(itemName.text.toString().trim(), ignoreCase = true) }
            if (match == null) {
                selectedProduct = null
                unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
            }
        })
        unitPrice.addTextChangedListener(simpleWatcher {
            if (!suppressPriceWatcher) {
                val entered = unitPrice.text.toString().toDoubleOrNull()?: 0.0
                lastMainPrice = toMainUnitPrice(entered)
            }
        })
        unitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { refillAutoPrice() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        saleTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                lastMainPrice = 0.0; refillAutoPrice()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        if (editInvoice == null) restoreDraftIfAny()
    }

    override fun onPause() {
        super.onPause()
        if (editInvoice == null &&!suppressDraftSave) saveDraft()
    }

    private fun draftPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun saveDraft() {
        val hasContent = lines.isNotEmpty() || customerName.text.toString().isNotBlank() || itemName.text.toString().isNotBlank() || qty.text.toString().isNotBlank() || unitPrice.text.toString().isNotBlank()
        if (!hasContent) { clearDraft(); return }
        val linesArray = JSONArray()
        lines.forEach { line ->
            linesArray.put(JSONObject().apply {
                put("barcode", line.barcode); put("itemName", line.itemName); put("qty", line.qty); put("unit", line.unit)
                put("unitPrice", line.unitPrice); put("cost", line.cost); put("amount", line.amount); put("mainUnit", line.mainUnit)
                put("secondaryUnit", line.secondaryUnit); put("secondaryUnitQty", line.secondaryUnitQty); put("tertiaryUnit", line.tertiaryUnit); put("tertiaryUnitQty", line.tertiaryUnitQty)
            })
        }
        val draft = JSONObject().apply {
            put("customer", customerName.text.toString()); put("saleType", saleTypeSpinner.selectedItem?.toString()?: "Retail")
            put("discount", discountInput.text.toString()); put("paid", paidInput.text.toString()); put("dateMillis", saleDateMillis)
            put("pendingItemName", itemName.text.toString()); put("pendingQty", qty.text.toString()); put("pendingPrice", unitPrice.text.toString()); put("lines", linesArray)
        }
        draftPrefs().edit().putString(KEY_DRAFT, draft.toString()).apply()
    }
    private fun clearDraft() { draftPrefs().edit().remove(KEY_DRAFT).apply() }
    private fun restoreDraftIfAny() {
        val raw = draftPrefs().getString(KEY_DRAFT, null)?: return
        val draft = try { JSONObject(raw) } catch (e: Exception) { null }?: return
        if (draftRestored) return
        draftRestored = true; suppressDraftSave = true
        try {
            val customer = draft.optString("customer", ""); if (customer.isNotBlank()) customerName.setText(customer)
            val saleType = draft.optString("saleType", "Retail"); saleTypeSpinner.setSelection(if (saleType == "Wholesale") 1 else 0)
            val discount = draft.optString("discount", ""); if (discount.isNotBlank()) discountInput.setText(discount)
            val paid = draft.optString("paid", ""); if (paid.isNotBlank()) paidInput.setText(paid)
            val savedDate = draft.optLong("dateMillis", 0L); if (savedDate > 0L) { saleDateMillis = savedDate; dateValueText.text = formatDate(saleDateMillis) }
            val linesArray = draft.optJSONArray("lines")
            if (linesArray!= null) {
                for (i in 0 until linesArray.length()) {
                    val o = linesArray.getJSONObject(i)
                    lines.add(SaleLine(o.optString("barcode"), o.optString("itemName"), o.optDouble("qty", 0.0), o.optString("unit"), o.optDouble("unitPrice", 0.0), o.optDouble("cost", 0.0), o.optDouble("amount", 0.0), o.optString("mainUnit"), o.optString("secondaryUnit"), o.optDouble("secondaryUnitQty", 0.0), o.optString("tertiaryUnit"), o.optDouble("tertiaryUnitQty", 0.0)))
                }
                renderItemsList(); updateTotals()
            }
        } finally { suppressDraftSave = false }
    }

    private fun loadFirmName() {
        lifecycleScope.launch {
            val savedName = PosDatabase.get(this@SaleActivity).appSettingDao().get("shop_name")?.value
            if (!savedName.isNullOrBlank()) firmNameText.text = savedName
        }
    }

    private fun loadForEdit(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)
            val sale = db.saleDao().findSale(invoice)?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            originalSale = sale; originalItems = items
            saleDateMillis = sale.createdAt; dateValueText.text = formatDate(saleDateMillis)
            val custList = db.customerDao().all().first()
            val custName = sale.customerId?.let { id -> custList.find { it.id == id }?.name }?: ""
            customerName.setText(custName)
            saleTypeSpinner.setSelection(if (sale.saleType == "wholesale") 1 else 0)
            discountInput.setText(if (sale.discount > 0) "%.2f".format(sale.discount) else "")
            paidInput.setText(if (sale.paid > 0) "%.2f".format(sale.paid) else "")
            val prodList = db.productDao().all().first()
            lines.clear()
            items.forEach { si ->
                val product = prodList.find { it.barcode == si.barcode }
                lines.add(SaleLine(si.barcode, si.product, si.qty.toDouble(), product?.unit?: "", si.unitPrice, si.cost, si.amount, product?.unit?: "", product?.secondaryUnit?: "", product?.secondaryUnitQty?: 0.0, product?.tertiaryUnit?: "", product?.tertiaryUnitQty?: 0.0))
            }
            renderItemsList(); updateTotals(); deleteButton.visibility = View.VISIBLE
        }
    }

    private fun outlinedBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(22, 16, 22, 16); background = strokedBg(border, cardBg, 16)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
        applyElevation(this, 2f)
    }
    private fun labelRow(label: String) = TextView(this).apply {
        text = label.uppercase(); textSize = 10.5f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 6); letterSpacing = 0.03f
    }
    private fun pillChip(label: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = label; textSize = 11f; setTextColor(Color.parseColor(teal)); setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = roundedBg(cardBg, 30); setPadding(18, 10, 18, 10); setOnClickListener { onClick() }
    }
    private fun circleIcon(text: String, colorHex: String, sizeDp: Int, onClick: (() -> Unit)? = null) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt(); width = px; height = px; if (onClick!= null) setOnClickListener { onClick() }
    }
    private fun toggleItemEntry() {
        itemEntrySection.visibility = if (itemEntrySection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        addItemsTrigger.text = if (itemEntrySection.visibility == View.VISIBLE) " Hide Item Entry" else " Add Items"
    }
    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = radius.toFloat() }
    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex)); setStroke((1.4 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex)); cornerRadius = radius.toFloat()
    }
    private fun ovalBg(colorHex: String) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(colorHex)) }
    private fun applyElevation(view: View, dp: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { view.elevation = dp * resources.displayMetrics.density; view.outlineProvider = ViewOutlineProvider.BACKGROUND }
    }
    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }
    private fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange()
    }
    private fun formatDate(millis: Long) = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(millis))
    private fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = saleDateMillis }
        DatePickerDialog(this, { _, y, m, d -> cal.set(y, m, d); saleDateMillis = cal.timeInMillis; dateValueText.text = formatDate(saleDateMillis) }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun loadCustomers() {
        lifecycleScope.launch {
            PosDatabase.get(this@SaleActivity).customerDao().all().collectLatest { list ->
                customers = list
                customerName.setAdapter(ArrayAdapter(this@SaleActivity, android.R.layout.simple_dropdown_item_1line, list.map { it.name }))
                // Customer select par balance show karo
                customerName.setOnItemClickListener { _, _, position, _ ->
                    val selectedName = customerName.adapter.getItem(position).toString()
                    val cust = customers.find { it.name == selectedName }
                    if (cust!= null) {
                        partyBalanceText.text = "Balance: Rs %.2f".format(cust.balance)
                        partyBalanceText.visibility = View.VISIBLE
                    }
                    onCustomerPicked(selectedName)
                }
            }
        }
    }
    private fun onCustomerPicked(name: String) {
        // Agar manually type kiya to bhi balance dhoondo
        val cust = customers.find { it.name.equals(name, ignoreCase = true) }
        if (cust!= null) {
            partyBalanceText.text = "Balance: Rs %.2f ${if (cust.balance > 0) "(Udhar)" else ""}"
            partyBalanceText.visibility = View.VISIBLE
        } else {
            partyBalanceText.visibility = View.GONE
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            PosDatabase.get(this@SaleActivity).productDao().all().collectLatest { list ->
                products = list
                itemName.setAdapter(ArrayAdapter(this@SaleActivity, android.R.layout.simple_dropdown_item_1line, list.map { it.name }))
            }
        }
    }
    private fun promptAddCustomer() {
        val input = EditText(this).apply { setPadding(32, 24, 32, 24) }
        AlertDialog.Builder(this).setTitle("New Customer").setView(input)
           .setPositiveButton("Add") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) lifecycleScope.launch {
                    PosDatabase.get(this@SaleActivity).customerDao().insert(Customer(name = v))
                    Toast.makeText(this@SaleActivity, "Customer added", Toast.LENGTH_SHORT).show()
                    customerName.setText(v)
                    partyBalanceText.text = "Balance: Rs 0.00"; partyBalanceText.visibility = View.VISIBLE
                }
            }.setNegativeButton("Cancel", null).show()
    }
    private fun onItemPicked(name: String) {
        val product = products.find { it.name.equals(name, ignoreCase = true) }?: return
        selectedProduct = product
        val unitChoices = mutableListOf(product.unit)
        if (product.secondaryUnit.isNotEmpty()) {
            unitChoices.add(product.secondaryUnit)
            if (product.tertiaryUnit.isNotEmpty() && product.tertiaryUnitQty > 0) unitChoices.add(product.tertiaryUnit)
        }
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitChoices)
        lastMainPrice = 0.0; refillAutoPrice()
    }
    private fun toMainUnitPrice(entered: Double): Double {
        val product = selectedProduct?: return entered
        val chosenUnit = unitSpinner.selectedItem?.toString()?: product.unit
        return when {
            chosenUnit == product.tertiaryUnit && product.tertiaryUnit.isNotEmpty() && product.tertiaryUnitQty > 0 && product.secondaryUnitQty > 0 -> entered * product.secondaryUnitQty * product.tertiaryUnitQty
            chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0 -> entered * product.secondaryUnitQty
            else -> entered
        }
    }
    private fun fromMainUnitPrice(mainPrice: Double, chosenUnit: String): Double {
        val product = selectedProduct?: return mainPrice
        return when {
            chosenUnit == product.tertiaryUnit && product.tertiaryUnit.isNotEmpty() && product.tertiaryUnitQty > 0 && product.secondaryUnitQty > 0 -> mainPrice / (product.secondaryUnitQty * product.tertiaryUnitQty)
            chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0 -> mainPrice / product.secondaryUnitQty
            else -> mainPrice
        }
    }
    private fun refillAutoPrice() {
        val product = selectedProduct?: return
        val isWholesale = saleTypeSpinner.selectedItem?.toString() == "Wholesale"
        val basePrice = if (isWholesale) product.wholesalePrice else product.salePrice
        val chosenUnit = unitSpinner.selectedItem?.toString()?: product.unit
        val base = if (lastMainPrice > 0) lastMainPrice else basePrice
        val price = fromMainUnitPrice(base, chosenUnit)
        suppressPriceWatcher = true; unitPrice.setText(if (price > 0) "%.2f".format(price) else ""); suppressPriceWatcher = false
    }
    private fun addItem() {
        val n = itemName.text.toString().trim(); val q = qty.text.toString().toDoubleOrNull()?: 0.0; val price = unitPrice.text.toString().toDoubleOrNull()?: 0.0
        val product = products.find { it.name.equals(n, ignoreCase = true) }
        if (product == null) { Toast.makeText(this, "Ye item product list mein nahi hai", Toast.LENGTH_SHORT).show(); return }
        if (q <= 0) { Toast.makeText(this, "Quantity theek se likhen", Toast.LENGTH_SHORT).show(); return }
        val chosenUnit = unitSpinner.selectedItem?.toString()?: product.unit
        val mainUnitQtyEquivalent = when {
            chosenUnit == product.tertiaryUnit && product.tertiaryUnit.isNotEmpty() && product.tertiaryUnitQty > 0 && product.secondaryUnitQty > 0 -> q / (product.secondaryUnitQty * product.tertiaryUnitQty)
            chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0 -> q / product.secondaryUnitQty
            else -> q
        }
        val alreadyInCart = lines.filter { it.barcode == product.barcode }.sumOf { it.mainUnitQty() }
        val availableForThisAdd = product.stock - alreadyInCart
        if (availableForThisAdd < mainUnitQtyEquivalent) {
            Toast.makeText(this, "Stock kam hai (available: ${formatQty(availableForThisAdd.coerceAtLeast(0.0))} ${product.unit})", Toast.LENGTH_SHORT).show(); return
        }
        val amount = q * price
        lines.add(SaleLine(product.barcode, product.name, q, chosenUnit, price, product.cost, amount, product.unit, product.secondaryUnit, product.secondaryUnitQty, product.tertiaryUnit, product.tertiaryUnitQty))
        renderItemsList(); updateTotals()
        itemName.text.clear(); qty.text.clear(); unitPrice.text.clear(); selectedProduct = null; lastMainPrice = 0.0
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("pcs")); itemName.requestFocus()
        if (editInvoice == null) saveDraft()
    }
    private fun renderItemsList() {
        itemsContainer.removeAllViews()
        lines.forEachIndexed { index, line ->
            itemsContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 16); background = strokedBg(border, cardBg, 14)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }; applyElevation(this, 2f)
                val top = LinearLayout(this@SaleActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(TextView(this@SaleActivity).apply {
                    text = line.itemName; textSize = 15f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                top.addView(TextView(this@SaleActivity).apply {
                    text = "Rs %.2f".format(line.amount); setTextColor(Color.parseColor(navy)); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(top)
                addView(TextView(this@SaleActivity).apply {
                    text = "Qty: ${line.qty} ${line.unit} • Rate: ${line.unitPrice}"; textSize = 12.5f; setTextColor(Color.parseColor(textGray)); setPadding(0, 6, 0, 0)
                })
                addView(TextView(this@SaleActivity).apply {
                    text = "\u2715 " + com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Remove", "ہٹائیں"); textSize = 12f; setTextColor(Color.parseColor(red))
                    setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 10, 0, 0)
                    setOnClickListener { lines.removeAt(index); renderItemsList(); updateTotals(); if (editInvoice == null) saveDraft() }
                })
            })
        }
    }
    private fun updateTotals() {
        val subtotal = lines.sumOf { it.amount }
        val discount = discountInput.text.toString().toDoubleOrNull()?: 0.0
        subtotalText.text = "Rs %.2f".format(subtotal)
        val total = (subtotal - discount).coerceAtLeast(0.0)
        totalText.text = "Rs %.2f".format(total)
        val paid = paidInput.text.toString().toDoubleOrNull()?: 0.0
        val due = (total - paid).coerceAtLeast(0.0)
        dueText.text = "Rs %.2f".format(due)
        // Agar paid kam hai to Due red rahega, agar full paid to green
        if (due <= 0.01) {
            dueCard.background = strokedBg(border, "#E8FFF3", 16)
            dueText.setTextColor(Color.parseColor(green))
        } else {
            dueCard.background = strokedBg(border, "#FFF3F3", 16)
            dueText.setTextColor(Color.parseColor(red))
        }
    }
    private fun saveSale() {
        if (lines.isEmpty()) { Toast.makeText(this, "Kam az kam ek item add karen", Toast.LENGTH_SHORT).show(); return }
        val enteredCustomer = customerName.text.toString().trim()
        val subtotal = lines.sumOf { it.amount }
        val discount = (discountInput.text.toString().toDoubleOrNull()?: 0.0).coerceIn(0.0, subtotal)
        val total = (subtotal - discount).coerceAtLeast(0.0)
        val paid = paidInput.text.toString().toDoubleOrNull()?: 0.0
        // Cash/Credit auto-detect: Paid < Total = Credit, else Cash
        val method = if (paid < total - 0.01) "credit" else "cash"
        var customer = customers.find { it.name.equals(enteredCustomer, ignoreCase = true) }
        val saleType = if (saleTypeSpinner.selectedItem?.toString() == "Wholesale") "wholesale" else "retail"
        val invoice = editInvoice?: ("INV" + System.currentTimeMillis().toString())
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)
            val original = originalSale
            if (original!= null) {
                originalItems.forEach { db.productDao().increase(it.barcode, it.qty) }
                val originalOutstanding = original.total - original.paid
                if (original.customerId!= null && originalOutstanding > 0) db.customerDao().addBalance(original.customerId, -originalOutstanding)
                db.saleDao().deleteItems(invoice); db.saleDao().deleteSale(invoice); db.cashTransactionDao().deleteByReference(invoice)
            }
            val neededByBarcode = lines.groupBy { it.barcode }.mapValues { (_, group) -> group.sumOf { it.mainUnitQty() } }
            for ((barcode, needed) in neededByBarcode) {
                val current = db.productDao().find(barcode)
                if (current == null || current.stock < needed) {
                    Toast.makeText(this@SaleActivity, "Stock badal gaya hai — \"${current?.name?: barcode}\" mein sirf ${current?.stock?: 0} available hai.", Toast.LENGTH_LONG).show(); return@launch
                }
            }
            if (customer == null && enteredCustomer.isNotEmpty()) {
                val newId = db.customerDao().insert(Customer(name = enteredCustomer)); customer = Customer(id = newId, name = enteredCustomer)
            }
            db.saleDao().sale(Sale(invoice = invoice, customerId = customer?.id, subtotal = subtotal, discount = discount, tax = 0.0, total = total, paid = paid, paymentMethod = method, saleType = saleType, createdAt = saleDateMillis))
            val saleItems = lines.map { SaleItem(invoice = invoice, barcode = it.barcode, product = it.itemName, qty = it.mainUnitQty().roundToInt(), unitPrice = it.mainUnitPrice(), cost = it.cost, amount = it.amount) }
            db.saleDao().items(saleItems)
            for (line in lines) { db.productDao().decrease(line.barcode, line.mainUnitQty().roundToInt()) }
            if (customer!= null && paid < total) db.customerDao().addBalance(customer!!.id, total - paid)
            if (paid > 0) db.cashTransactionDao().insert(CashTransaction(type = "IN", method = method, amount = paid, reason = "Sale", reference = invoice))
            suppressDraftSave = true; clearDraft(); editInvoice = invoice
            Toast.makeText(this@SaleActivity, if (original!= null) "Sale updated" else "Sale saved", Toast.LENGTH_SHORT).show()
            val itemsEncoded = lines.joinToString("\u0002") { listOf(it.itemName, formatQty(it.qty), it.unit, it.unitPrice, it.amount).joinToString("\u0003") }
            val previewIntent = Intent(this@SaleActivity, BillPreviewActivity::class.java).apply {
                putExtra(BillPreviewActivity.EXTRA_TYPE, "sale"); putExtra(BillPreviewActivity.EXTRA_REFERENCE, invoice)
                putExtra(BillPreviewActivity.EXTRA_PARTY_NAME, customer?.name?: enteredCustomer); putExtra(BillPreviewActivity.EXTRA_PARTY_LABEL, "Customer")
                putExtra(BillPreviewActivity.EXTRA_DATE_MILLIS, saleDateMillis); putExtra(BillPreviewActivity.EXTRA_SUBTOTAL, subtotal)
                putExtra(BillPreviewActivity.EXTRA_DISCOUNT, discount); putExtra(BillPreviewActivity.EXTRA_TOTAL, total)
                putExtra(BillPreviewActivity.EXTRA_PAID, paid); putExtra(BillPreviewActivity.EXTRA_PAYMENT_METHOD, method); putExtra(BillPreviewActivity.EXTRA_ITEMS_ENCODED, itemsEncoded)
            }
            startActivity(previewIntent); finish()
        }
    }
    private fun confirmDeleteSale() {
        val invoice = editInvoice?: return
        AlertDialog.Builder(this).setTitle(com.grocerypos.v11.util.Loc.t(this, "Delete Sale", "سیل حذف کریں"))
           .setMessage(com.grocerypos.v11.util.Loc.t(this, "This will remove the bill and reverse its stock and customer balance effect. Continue?", "یہ بل حذف کر دے گا اور اس کا اسٹاک اور کسٹمر بیلنس پر اثر واپس کر دے گا۔ جاری رکھیں؟"))
           .setPositiveButton(com.grocerypos.v11.util.Loc.t(this, "Delete", "حذف کریں")) { _, _ -> deleteSale(invoice) }.setNegativeButton(com.grocerypos.v11.util.Loc.t(this, "Cancel", "منسوخ"), null).show()
    }
    private fun deleteSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)
            val sale = originalSale?: db.saleDao().findSale(invoice)?: return@launch
            val items = originalItems.ifEmpty { db.saleDao().itemsForInvoice(invoice) }
            items.forEach { db.productDao().increase(it.barcode, it.qty) }
            val outstanding = sale.total - sale.paid
            if (sale.customerId!= null && outstanding > 0) db.customerDao().addBalance(sale.customerId, -outstanding)
            db.saleDao().deleteItems(invoice); db.saleDao().deleteSale(invoice); db.cashTransactionDao().deleteByReference(invoice)
            Toast.makeText(this@SaleActivity, "Sale deleted", Toast.LENGTH_SHORT).show(); finish()
        }
    }
    private fun formatQty(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    private fun holdBill() {
        if (lines.isEmpty()) { Toast.makeText(this, "Add items pehle, phir hold karen", Toast.LENGTH_SHORT).show(); return }
        val holdId = "HOLD" + System.currentTimeMillis().toString(); val payload = encodeHold()
        lifecycleScope.launch {
            PosDatabase.get(this@SaleActivity).heldDao().hold(HeldBill(holdId = holdId, payload = payload))
            Toast.makeText(this@SaleActivity, "Bill hold ho gayi", Toast.LENGTH_SHORT).show(); clearAll()
        }
    }
    private fun encodeHold(): String {
        val header = listOf(customerName.text.toString(), saleTypeSpinner.selectedItem?.toString()?: "Retail", discountInput.text.toString(), paidInput.text.toString()).joinToString("\u0001")
        val itemsPart = lines.joinToString("\u0002") { listOf(it.barcode, it.itemName, it.qty, it.unit, it.unitPrice, it.cost, it.amount, it.mainUnit, it.secondaryUnit, it.secondaryUnitQty, it.tertiaryUnit, it.tertiaryUnitQty).joinToString("\u0003") }
        return header + "\u0004" + itemsPart
    }
    private fun decodeHold(payload: String) {
        val parts = payload.split("\u0004"); if (parts.isEmpty()) return
        val header = parts[0].split("\u0001")
        if (header.size >= 4) {
            customerName.setText(header[0]); val saleTypeIndex = if (header[1] == "Wholesale") 1 else 0; saleTypeSpinner.setSelection(saleTypeIndex)
            discountInput.setText(header[2]); paidInput.setText(header[3])
        }
        lines.clear()
        if (parts.size > 1 && parts[1].isNotEmpty()) {
            parts[1].split("\u0002").forEach { row ->
                val f = row.split("\u0003")
                if (f.size >= 10) {
                    lines.add(SaleLine(f[0], f[1], f[2].toDoubleOrNull()?: 0.0, f[3], f[4].toDoubleOrNull()?: 0.0, f[5].toDoubleOrNull()?: 0.0, f[6].toDoubleOrNull()?: 0.0, f[7], f[8], f[9].toDoubleOrNull()?: 0.0, f.getOrNull(10)?: "", f.getOrNull(11)?.toDoubleOrNull()?: 0.0))
                }
            }
        }
        renderItemsList(); updateTotals(); if (editInvoice == null) saveDraft()
    }
    private fun clearAll() {
        lines.clear(); renderItemsList(); customerName.text.clear(); discountInput.text.clear(); itemName.text.clear(); qty.text.clear(); unitPrice.text.clear()
        selectedProduct = null; lastMainPrice = 0.0; subtotalText.text = "Rs 0.00"; totalText.text = "Rs 0.00"; dueText.text = "Rs 0.00"; paidInput.text.clear()
        saleDateMillis = System.currentTimeMillis(); dateValueText.text = formatDate(saleDateMillis); partyBalanceText.visibility = View.GONE; clearDraft()
    }
    private fun openRecallDialog() {
        lifecycleScope.launch {
            val held = PosDatabase.get(this@SaleActivity).heldDao().all().first()
            val content = LinearLayout(this@SaleActivity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(cardBg)) }
            val dialogHeader = LinearLayout(this@SaleActivity).apply { setPadding(28, 26, 28, 26); background = roundedBg(navy, 0) }
            dialogHeader.addView(TextView(this@SaleActivity).apply { text = "Held Bills"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            content.addView(dialogHeader)
            val list = LinearLayout(this@SaleActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 18, 24, 16) }
            if (held.isEmpty()) list.addView(TextView(this@SaleActivity).apply { text = "Koi held bill nahi hai"; setTextColor(Color.parseColor(textGray)); setPadding(8, 20, 8, 20) })
            val dialog = AlertDialog.Builder(this@SaleActivity).setView(content).create()
            val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            for (h in held) {
                val itemCount = h.payload.split("\u0004").getOrNull(1)?.split("\u0002")?.filter { it.isNotEmpty() }?.size?: 0
                val row = LinearLayout(this@SaleActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(18, 16, 18, 16)
                    background = strokedBg(border, "#F7F8FC", 14); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
                }
                row.addView(circleIcon("\u23F8", amber, 30)); row.addView(View(this@SaleActivity).apply { layoutParams = LinearLayout.LayoutParams(12, 1) })
                val info = LinearLayout(this@SaleActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
                info.addView(TextView(this@SaleActivity).apply { text = "$itemCount items"; textSize = 15f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
                info.addView(TextView(this@SaleActivity).apply { text = fmt.format(Date(h.createdAt)); textSize = 12f; setTextColor(Color.parseColor(textGray)) })
                row.addView(info)
                row.addView(TextView(this@SaleActivity).apply {
                    text = "RECALL"; textSize = 12f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD); background = roundedBg(teal, 20); setPadding(22, 10, 22, 10)
                    setOnClickListener { decodeHold(h.payload); lifecycleScope.launch { PosDatabase.get(this@SaleActivity).heldDao().delete(h) }; dialog.dismiss() }
                })
                row.addView(View(this@SaleActivity).apply { layoutParams = LinearLayout.LayoutParams(10, 1) })
                row.addView(TextView(this@SaleActivity).apply {
                    text = "\u2715"; textSize = 14f; setTextColor(Color.WHITE); background = ovalBg(red); gravity = Gravity.CENTER
                    val px = (26 * resources.displayMetrics.density).toInt(); width = px; height = px
                    setOnClickListener { lifecycleScope.launch { PosDatabase.get(this@SaleActivity).heldDao().delete(h); Toast.makeText(this@SaleActivity, "Held bill hata di", Toast.LENGTH_SHORT).show() }; dialog.dismiss() }
                })
                list.addView(row)
            }
            content.addView(list)
            content.addView(Button(this@SaleActivity).apply { text = "Close"; isAllCaps = false; setTextColor(Color.parseColor(textGray)); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { dialog.dismiss() } })
            dialog.show()
        }
    }
}
