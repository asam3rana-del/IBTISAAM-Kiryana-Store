
package com.grocerypos.v11.ui

import android.app.AlertDialog
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
    val secondaryUnitQty: Double
)

private fun SaleLine.isSecondary(): Boolean =
    secondaryUnit.isNotEmpty() && unit == secondaryUnit && secondaryUnitQty > 0

private fun SaleLine.mainUnitQty(): Double =
    if (isSecondary()) qty / secondaryUnitQty else qty

private fun SaleLine.mainUnitPrice(): Double =
    if (isSecondary()) unitPrice * secondaryUnitQty else unitPrice

class SaleActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_INVOICE = "invoice"
        private const val PREFS_NAME = "sale_draft_prefs"
        private const val KEY_DRAFT = "draft_json"
    }
    private val bg = "#F4F6F8"
    private val cardBg = "#FFFFFF"
    private val navy = "#0F9B8E"
    private val teal = "#0B2545"
    private val green = "#1FA971"
    private val red = "#E5484D"
    private val textDark = "#0B2545"
    private val textGray = "#7C8798"
    private val border = "#E3E8EE"
    private lateinit var dateValueText: TextView
    private lateinit var customerName: AutoCompleteTextView
    private lateinit var saleTypeSpinner: Spinner
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var unitPrice: EditText
    private lateinit var itemsContainer: LinearLayout
    private lateinit var subtotalText: TextView
    private lateinit var discountInput: EditText
    private lateinit var totalText: TextView
    private lateinit var dueText: TextView
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 56, 24, 24); setBackgroundColor(Color.parseColor(bg)) }
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(22, 20, 22, 18); background = roundedBg(navy, 20)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 18) }
        }
        dateValueText = TextView(this).apply { text = formatDate(saleDateMillis); setTextColor(Color.WHITE); setOnClickListener { openDatePicker() } }
        firmNameText = TextView(this).apply { text = "IBTISAAM Kiryana Store"; textSize = 20f; setTextColor(Color.WHITE) }
        partyBalanceText = TextView(this).apply { text = "Balance: Rs 0.00"; visibility = View.GONE }
        headerCard.addView(dateValueText); headerCard.addView(firmNameText); headerCard.addView(partyBalanceText)
        root.addView(headerCard)

        customerName = AutoCompleteTextView(this).apply { hint = "Customer Name" }
        root.addView(customerName)
        saleTypeSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Retail", "Wholesale")) }
        root.addView(saleTypeSpinner)
        itemName = AutoCompleteTextView(this).apply { hint = "Item Name" }
        qty = EditText(this).apply { hint = "Qty"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        unitSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("pcs")) }
        unitPrice = EditText(this).apply { hint = "Rate"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        root.addView(itemName); root.addView(qty); root.addView(unitSpinner); root.addView(unitPrice)
        val addBtn = Button(this).apply { text = "ADD ITEM"; setOnClickListener { addItem() } }
        root.addView(addBtn)
        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)
        subtotalText = TextView(this).apply { text = "Rs 0.00" }
        discountInput = EditText(this).apply { hint = "Discount" }
        totalText = TextView(this).apply { text = "Rs 0.00" }
        dueText = TextView(this).apply { text = "Rs 0.00" }
        paidInput = EditText(this).apply { hint = "Paid" }
        root.addView(subtotalText); root.addView(discountInput); root.addView(totalText); root.addView(dueText); root.addView(paidInput)
        saveButton = Button(this).apply { text = "SAVE SALE"; setOnClickListener { saveSale() } }
        deleteButton = Button(this).apply { text = "DELETE"; visibility = if (editInvoice != null) View.VISIBLE else View.GONE; setOnClickListener { confirmDeleteSale() } }
        root.addView(saveButton); root.addView(deleteButton)
        setContentView(ScrollView(this).apply { addView(root) })
        loadCustomers(); loadProducts()
        editInvoice?.let { loadForEdit(it) }
        itemName.setOnItemClickListener { _, _, pos, _ -> onItemPicked(itemName.adapter.getItem(pos).toString()) }
        if (editInvoice == null) restoreDraftIfAny()
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
            }
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
    private fun onItemPicked(name: String) {
        val product = products.find { it.name.equals(name, ignoreCase = true) } ?: return
        selectedProduct = product
        val unitChoices = mutableListOf(product.unit)
        if (product.secondaryUnit.isNotEmpty()) unitChoices.add(product.secondaryUnit)
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitChoices)
        lastMainPrice = 0.0; refillAutoPrice()
    }
    private fun toMainUnitPrice(entered: Double): Double {
        val product = selectedProduct ?: return entered
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        return if (chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0) entered * product.secondaryUnitQty else entered
    }
    private fun fromMainUnitPrice(mainPrice: Double, chosenUnit: String): Double {
        val product = selectedProduct ?: return mainPrice
        return if (chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0) mainPrice / product.secondaryUnitQty else mainPrice
    }
    private fun refillAutoPrice() {
        val product = selectedProduct ?: return
        val isWholesale = saleTypeSpinner.selectedItem?.toString() == "Wholesale"
        val basePrice = if (isWholesale) product.wholesalePrice else product.salePrice
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        val base = if (lastMainPrice > 0) lastMainPrice else basePrice
        val price = fromMainUnitPrice(base, chosenUnit)
        suppressPriceWatcher = true; unitPrice.setText(if (price > 0) "%.2f".format(price) else ""); suppressPriceWatcher = false
    }
    private fun addItem() {
        val n = itemName.text.toString().trim(); val q = qty.text.toString().toDoubleOrNull() ?: 0.0; val price = unitPrice.text.toString().toDoubleOrNull() ?: 0.0
        val product = products.find { it.name.equals(n, ignoreCase = true) } ?: return
        if (q <= 0) return
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        val amount = q * price
        lines.add(SaleLine(product.barcode, product.name, q, chosenUnit, price, product.cost, amount, product.unit, product.secondaryUnit, product.secondaryUnitQty))
        renderItemsList(); updateTotals()
        itemName.text.clear(); qty.text.clear(); unitPrice.text.clear(); selectedProduct = null
    }
    private fun renderItemsList() {
        itemsContainer.removeAllViews()
        lines.forEachIndexed { index, line ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 16); background = strokedBg(border, cardBg, 14) }
            row.addView(TextView(this@SaleActivity).apply { text = "${line.itemName} - ${line.qty} ${line.unit} x ${line.unitPrice} = ${line.amount}" })
            row.addView(TextView(this@SaleActivity).apply { text = "Remove"; setOnClickListener { lines.removeAt(index); renderItemsList(); updateTotals() } })
            itemsContainer.addView(row)
        }
    }
    private fun updateTotals() {
        val subtotal = lines.sumOf { it.amount }
        val discount = discountInput.text.toString().toDoubleOrNull() ?: 0.0
        subtotalText.text = "Rs %.2f".format(subtotal)
        val total = (subtotal - discount).coerceAtLeast(0.0)
        totalText.text = "Rs %.2f".format(total)
        val paid = paidInput.text.toString().toDoubleOrNull() ?: 0.0
        dueText.text = "Rs %.2f".format((total - paid).coerceAtLeast(0.0))
    }
    private fun draftPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun saveDraft() {
        if (suppressDraftSave) return
        try {
            val arr = JSONArray()
            lines.forEach { line -> arr.put(JSONObject().apply { put("barcode", line.barcode); put("itemName", line.itemName); put("qty", line.qty); put("unit", line.unit); put("unitPrice", line.unitPrice); put("cost", line.cost); put("amount", line.amount); put("mainUnit", line.mainUnit); put("secondaryUnit", line.secondaryUnit); put("secondaryUnitQty", line.secondaryUnitQty) }) }
            val draft = JSONObject().apply { put("customer", customerName.text.toString()); put("lines", arr) }
            draftPrefs().edit().putString(KEY_DRAFT, draft.toString()).apply()
        } catch (_: Exception) {}
    }
    private fun clearDraft() { draftPrefs().edit().remove(KEY_DRAFT).apply() }
    private fun restoreDraftIfAny() {
        val raw = draftPrefs().getString(KEY_DRAFT, null) ?: return
        try {
            val draft = JSONObject(raw)
            val arr = draft.optJSONArray("lines") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                lines.add(SaleLine(o.optString("barcode"), o.optString("itemName"), o.optDouble("qty", 0.0), o.optString("unit"), o.optDouble("unitPrice", 0.0), o.optDouble("cost", 0.0), o.optDouble("amount", 0.0), o.optString("mainUnit"), o.optString("secondaryUnit"), o.optDouble("secondaryUnitQty", 0.0)))
            }
            renderItemsList(); updateTotals()
        } catch (_: Exception) {}
    }
    private fun loadForEdit(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            originalSale = sale; originalItems = items
            customerName.setText(db.customerDao().all().first().find { it.id == sale.customerId }?.name ?: "")
            lines.clear()
            items.forEach { si ->
                val product = db.productDao().find(si.barcode)
                lines.add(SaleLine(si.barcode, si.product, si.qty.toDouble(), product?.unit ?: "", si.unitPrice, si.cost, si.amount, product?.unit ?: "", product?.secondaryUnit ?: "", product?.secondaryUnitQty ?: 0.0))
            }
            renderItemsList(); updateTotals()
        }
    }
    private fun saveSale() {
        if (lines.isEmpty()) return
        val total = lines.sumOf { it.amount }
        val paid = paidInput.text.toString().toDoubleOrNull() ?: 0.0
        val invoice = editInvoice ?: ("INV" + System.currentTimeMillis().toString())
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)
            db.saleDao().sale(Sale(invoice = invoice, customerId = null, subtotal = total, discount = 0.0, tax = 0.0, total = total, paid = paid, paymentMethod = "cash", saleType = "retail", createdAt = saleDateMillis))
            db.saleDao().items(lines.map { SaleItem(invoice = invoice, barcode = it.barcode, product = it.itemName, qty = it.mainUnitQty().roundToInt(), unitPrice = it.mainUnitPrice(), cost = it.cost, amount = it.amount) })
            lines.forEach { db.productDao().decrease(it.barcode, it.mainUnitQty().roundToInt()) }
            clearDraft(); finish()
        }
    }
    private fun confirmDeleteSale() {
        AlertDialog.Builder(this).setTitle("Delete Sale").setPositiveButton("Delete") { _, _ ->
            lifecycleScope.launch {
                val db = PosDatabase.get(this@SaleActivity)
                val sale = db.saleDao().findSale(editInvoice!!) ?: return@launch
                db.saleDao().itemsForInvoice(editInvoice!!).forEach { db.productDao().increase(it.barcode, it.qty) }
                db.saleDao().deleteItems(editInvoice!!); db.saleDao().deleteSale(editInvoice!!); finish()
            }
        }.setNegativeButton("Cancel", null).show()
    }
    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = radius.toFloat() }
    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(fillHex)); setStroke(2, Color.parseColor(strokeHex)); cornerRadius = radius.toFloat() }
}
