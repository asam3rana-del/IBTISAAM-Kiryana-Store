package com.grocerypos.v11.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    val secondaryUnitQty: Double
)

private fun genBillNo(): String = "PUR" + System.currentTimeMillis()

class PurchaseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BILL_NO = "billNo"
    }

    private val bg = "#F4F3FB"
    private val orange = "#EF6C00"
    private val blue = "#1565C0"
    private val green = "#2E7D32"
    private val border = "#DDDDDD"

    private lateinit var dateValueText: TextView
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
    private lateinit var itemsContainer: LinearLayout
    private lateinit var grandTotalText: TextView
    private lateinit var cashBtn: Button
    private lateinit var creditBtn: Button
    private lateinit var paymentSection: LinearLayout
    private lateinit var paidInput: EditText
    private lateinit var paymentMethodSpinner: Spinner
    private lateinit var saveButton: Button
    private var isCashPurchase = true

    private var suppliers = listOf<Supplier>()
    private var products = listOf<Product>()
    private var allUnits = listOf("pcs", "kg", "box", "dozen")
    private val lines = mutableListOf<PurchaseLine>()
    private var purchaseDateMillis = System.currentTimeMillis()
    private var selectedProduct: Product? = null

    private var editBillNo: String? = null
    private var originalPurchase: Purchase? = null
    private var originalItems: List<PurchaseItem> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        editBillNo = intent.getStringExtra(EXTRA_BILL_NO)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER =================
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 18)
            addView(TextView(this@PurchaseActivity).apply {
                text = if (editBillNo != null) "Edit Purchase" else "Purchase"
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@PurchaseActivity).apply {
                text = "History"
                textSize = 13f
                setTextColor(Color.parseColor(blue))
                setOnClickListener {
                    startActivity(Intent(this@PurchaseActivity, PurchaseHistoryActivity::class.java))
                }
            })
        })

        // ================= DATE (outlined box, left aligned) =================
        val dateBox = outlinedBox()
        dateBox.setOnClickListener { openDatePicker() }
        dateBox.addView(TextView(this).apply { text = "Date"; textSize = 12f; setTextColor(Color.GRAY) })
        val dateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dateValueText = TextView(this).apply {
            text = formatDate(purchaseDateMillis)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        dateRow.addView(dateValueText)
        dateRow.addView(TextView(this).apply { text = "▾"; textSize = 15f; setTextColor(Color.parseColor(blue)) })
        dateBox.addView(dateRow)
        root.addView(dateBox)
        root.addView(spacer(0))

        // ================= FIRM NAME (static display row, chevron for visual match only) =================
        val firmBox = outlinedBox().apply { setPadding(22, 14, 22, 14) }
        val firmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        firmRow.addView(TextView(this).apply { text = "Firm Name:  "; textSize = 13f; setTextColor(Color.GRAY) })
        firmRow.addView(TextView(this).apply {
            // TODO: replace with your actual store/firm name, or bind to a firms table if you support more than one
            text = "IBTISAAM Kiryana Store"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        firmRow.addView(TextView(this).apply { text = "▾"; textSize = 15f; setTextColor(Color.GRAY) })
        firmBox.addView(firmRow)
        root.addView(firmBox)
        root.addView(spacer(16))

        // ================= PARTY NAME (outlined box, + inline) =================
        val partyBox = outlinedBox()
        val partyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        partyName = AutoCompleteTextView(this).apply {
            hint = "Party Name (Supplier) *"
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        partyRow.addView(partyName)
        partyRow.addView(TextView(this).apply {
            text = "+"
            textSize = 20f
            setTextColor(Color.parseColor(blue))
            setPadding(20, 0, 4, 0)
            setOnClickListener { promptAddSupplier() }
        })
        partyBox.addView(partyRow)
        root.addView(partyBox)
        root.addView(spacer(12))

        // ---- Cash / Credit (compact, under Party box) ----
        val cashCreditToggle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cashBtn = Button(this).apply {
            text = "CASH"; textSize = 11f; setTextColor(Color.WHITE)
            background = roundedBg(orange, 20)
            setPadding(20, 6, 20, 6); minWidth = 0; minHeight = 0
            setOnClickListener { setPurchaseMode(true) }
        }
        creditBtn = Button(this).apply {
            text = "CREDIT"; textSize = 11f; setTextColor(Color.parseColor("#9E9E9E"))
            background = roundedBg("#EEEEEE", 20)
            setPadding(20, 6, 20, 6); minWidth = 0; minHeight = 0
            setOnClickListener { setPurchaseMode(false) }
        }
        cashCreditToggle.addView(cashBtn)
        cashCreditToggle.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(8, 1) })
        cashCreditToggle.addView(creditBtn)
        root.addView(cashCreditToggle)
        root.addView(spacer(16))

        // ================= "Add Items (Optional)" trigger =================
        val addItemsBox = outlinedBox()
        val addItemsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addItemsRow.addView(TextView(this).apply {
            text = "+"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = ovalBg(blue)
            width = (30 * resources.displayMetrics.density).toInt()
            height = (30 * resources.displayMetrics.density).toInt()
        })
        addItemsTrigger = TextView(this).apply {
            text = "  Add Items (Optional)"
            textSize = 14f
            setTextColor(Color.parseColor(blue))
        }
        addItemsRow.addView(addItemsTrigger)
        addItemsBox.addView(addItemsRow)
        addItemsBox.setOnClickListener { toggleItemEntry() }
        root.addView(addItemsBox)
        root.addView(spacer(14))

        // ================= ITEM ENTRY (collapsible) =================
        itemEntrySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val itemBox = outlinedBox()
        itemName = AutoCompleteTextView(this).apply { hint = "Item Name"; background = null }
        itemBox.addView(itemName)
        itemEntrySection.addView(itemBox)

        // Unit / Secondary Unit toggle — shows which unit this line is being
        // purchased in as soon as a known product is picked.
        unitToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        itemEntrySection.addView(unitToggleRow)
        itemEntrySection.addView(spacer(10))

        val qtyUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qtyBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) }
        }
        qty = EditText(this).apply {
            hint = "Quantity"
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        qtyBox.addView(qty)
        val unitBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
        }
        unitSpinner = Spinner(this)
        unitBox.addView(unitSpinner)
        qtyUnitRow.addView(qtyBox)
        qtyUnitRow.addView(unitBox)
        itemEntrySection.addView(qtyUnitRow)
        itemEntrySection.addView(spacer(10))

        val rateBox = outlinedBox()
        rate = EditText(this).apply {
            hint = "Rate (Price/Unit)"
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        rateBox.addView(rate)
        itemEntrySection.addView(rateBox)

        conversionInfo = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor(blue))
            setPadding(4, 10, 0, 0)
            visibility = View.GONE
        }
        itemEntrySection.addView(conversionInfo)

        totalAmountText = TextView(this).apply {
            text = "Total Amount: Rs 0.00"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(4, 10, 0, 10)
        }
        itemEntrySection.addView(totalAmountText)

        val watcher = simpleWatcher { updateLineTotal() }
        qty.addTextChangedListener(watcher)
        rate.addTextChangedListener(watcher)

        itemEntrySection.addView(Button(this).apply {
            text = "ADD ITEM"
            setTextColor(Color.WHITE)
            background = roundedBg(blue, 14)
            setOnClickListener { addItem() }
        })
        root.addView(itemEntrySection)
        root.addView(spacer(14))

        // ================= ITEMS LIST =================
        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        // ================= GRAND TOTAL =================
        val totalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 10, 4, 4)
        }
        totalRow.addView(TextView(this).apply {
            text = "Total Amount"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        grandTotalText = TextView(this).apply {
            text = "Rs 0.00"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        totalRow.addView(grandTotalText)
        root.addView(totalRow)
        root.addView(spacer(6))

        // ================= PAYMENT (compact, pulled up right under grand total) =================
        paymentSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val payRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val methodBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) }
            setPadding(16, 4, 16, 4)
        }
        paymentMethodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cash", "Bank"))
        }
        methodBox.addView(paymentMethodSpinner)
        val paidBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
            setPadding(16, 4, 16, 4)
        }
        paidInput = EditText(this).apply {
            hint = "Amount Paid"
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        paidBox.addView(paidInput)
        payRow.addView(methodBox)
        payRow.addView(paidBox)
        paymentSection.addView(payRow)
        root.addView(paymentSection)
        root.addView(spacer(10))

        // ================= SAVE (fixed bottom bar, not inside the scroll) =================
        saveButton = Button(this).apply {
            text = if (editBillNo != null) "UPDATE PURCHASE" else "SAVE PURCHASE"
            setTextColor(Color.WHITE)
            textSize = 15f
            background = roundedBg(green, 14)
            setPadding(0, 22, 0, 22)
            setOnClickListener { savePurchase() }
        }

        val scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(root)
        }

        val saveBar = LinearLayout(this).apply {
            setPadding(24, 10, 24, 18)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            addView(saveButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        setPurchaseMode(true)
        editBillNo?.let { loadForEdit(it) }

        itemName.setOnItemClickListener { _, _, position, _ ->
            val name = itemName.adapter.getItem(position).toString()
            onItemPicked(name)
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
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { updateLineTotal() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ---- UI helpers ----
    private fun outlinedBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 16, 22, 16)
        background = strokedBg(border, "#FFFFFF", 10)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) }
    }

    private fun toggleItemEntry() {
        itemEntrySection.visibility = if (itemEntrySection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        addItemsTrigger.text = if (itemEntrySection.visibility == View.VISIBLE) "  Hide Item Entry" else "  Add Items (Optional)"
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

    private fun setPurchaseMode(cash: Boolean) {
        isCashPurchase = cash
        if (cash) {
            cashBtn.background = roundedBg(orange, 20); cashBtn.setTextColor(Color.WHITE)
            creditBtn.background = roundedBg("#EEEEEE", 20); creditBtn.setTextColor(Color.parseColor("#9E9E9E"))
            paymentSection.visibility = View.VISIBLE
        } else {
            creditBtn.background = roundedBg("#C62828", 20); creditBtn.setTextColor(Color.WHITE)
            cashBtn.background = roundedBg("#EEEEEE", 20); cashBtn.setTextColor(Color.parseColor("#9E9E9E"))
            paymentSection.visibility = View.GONE
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

            setPurchaseMode(purchase.paid > 0)
            paidInput.setText(purchase.paid.toString())

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
                        secondaryUnitQty = product?.secondaryUnitQty ?: 0.0
                    )
                )
            }
            renderItemsList()
            updateGrandTotal()
            if (lines.isNotEmpty()) {
                itemEntrySection.visibility = View.VISIBLE
                addItemsTrigger.text = "  Hide Item Entry"
            }
        }
    }

    // ---- Data loading ----
    private fun loadSuppliers() {
        lifecycleScope.launch {
            PosDatabase.get(this@PurchaseActivity).supplierDao().all().collectLatest { list ->
                suppliers = list
                partyName.setAdapter(ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_dropdown_item_1line, list.map { it.name }))
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
    private fun onItemPicked(name: String) {
        val product = products.find { it.name.equals(name, ignoreCase = true) } ?: return
        selectedProduct = product
        rate.setText(product.cost.toString())

        val unitOptions = mutableListOf(product.unit)
        if (product.secondaryUnit.isNotBlank() && product.secondaryUnit != product.unit) {
            unitOptions.add(product.secondaryUnit)
        }
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitOptions)
        buildUnitChips(unitOptions, product.unit)

        if (unitOptions.size > 1 && product.secondaryUnitQty > 0) {
            conversionInfo.text = "1 ${product.unit} = ${product.secondaryUnitQty} ${product.secondaryUnit}"
            conversionInfo.visibility = View.VISIBLE
        } else {
            conversionInfo.visibility = View.GONE
        }
        updateLineTotal()
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
                setPadding(28, 12, 28, 12)
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor(blue))
                background = if (isSelected) roundedBg(blue, 18) else strokedBg(blue, "#FFFFFF", 18)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(if (index == 0) 0 else 8, 0, 0, 0) }
                setOnClickListener {
                    unitSpinner.setSelection(options.indexOf(unitLabel))
                    buildUnitChips(options, unitLabel)
                    updateLineTotal()
                }
            }
            unitToggleRow.addView(chip)
        }
    }

    private fun updateLineTotal() {
        val q = qty.text.toString().toDoubleOrNull() ?: 0.0
        val r = rate.text.toString().toDoubleOrNull() ?: 0.0
        val amount = q * r
        totalAmountText.text = "Total Amount: Rs %.2f".format(amount)
    }

    private fun addItem() {
        val name = itemName.text.toString().trim()
        val q = qty.text.toString().toDoubleOrNull()
        val r = rate.text.toString().toDoubleOrNull()
        val unit = (unitSpinner.selectedItem as? String) ?: "pcs"

        if (name.isEmpty()) { itemName.error = "Required"; return }
        if (q == null || q <= 0) { qty.error = "Enter quantity"; return }
        if (r == null || r < 0) { rate.error = "Enter rate"; return }

        // Purchase items are stored against a product barcode, so the item must
        // already exist in Products. Add it there first if it's new.
        val product = selectedProduct ?: products.find { it.name.equals(name, ignoreCase = true) }
        if (product == null) {
            itemName.error = "Select an existing product, or add it in Products first"
            return
        }

        val line = PurchaseLine(
            itemName = product.name,
            barcode = product.barcode,
            qty = q,
            unit = unit,
            rate = r,
            amount = q * r,
            mainUnit = product.unit,
            secondaryUnit = product.secondaryUnit,
            secondaryUnitQty = product.secondaryUnitQty
        )
        lines.add(line)
        renderItemsList()
        updateGrandTotal()

        // reset entry fields for next item
        itemName.setText("")
        qty.setText("")
        rate.setText("")
        selectedProduct = null
        conversionInfo.visibility = View.GONE
        unitToggleRow.visibility = View.GONE
        totalAmountText.text = "Total Amount: Rs 0.00"
        itemName.requestFocus()
    }

    private fun renderItemsList() {
        itemsContainer.removeAllViews()
        lines.forEachIndexed { index, line ->
            val row = outlinedBox().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = "${line.itemName}\n${line.qty} ${line.unit} x Rs ${line.rate}"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "Rs %.2f".format(line.amount)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(12, 0, 12, 0)
            })
            row.addView(TextView(this).apply {
                text = "✕"
                textSize = 15f
                setTextColor(Color.parseColor("#C62828"))
                setPadding(12, 0, 4, 0)
                setOnClickListener {
                    lines.removeAt(index)
                    renderItemsList()
                    updateGrandTotal()
                }
            })
            itemsContainer.addView(row)
        }
    }

    private fun updateGrandTotal() {
        val total = lines.sumOf { it.amount }
        grandTotalText.text = "Rs %.2f".format(total)
    }

    // ---- Supplier quick-add ----
    private fun promptAddSupplier() {
        val input = EditText(this).apply { hint = "Supplier name"; setPadding(32, 24, 32, 24) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Add Supplier")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    lifecycleScope.launch {
                        // TODO: confirm Supplier constructor matches your SupplierDao insert signature
                        val supplier = Supplier(name = name)
                        PosDatabase.get(this@PurchaseActivity).supplierDao().insert(supplier)
                        partyName.setText(name)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Save ----
    private fun savePurchase() {
        val party = partyName.text.toString().trim()
        if (party.isEmpty()) { partyName.error = "Required"; return }
        if (lines.isEmpty()) {
            Toast.makeText(this, "Add at least one item, or continue without items", Toast.LENGTH_SHORT).show()
        }

        val grandTotal = lines.sumOf { it.amount }
        val amountPaid = if (isCashPurchase) (paidInput.text.toString().toDoubleOrNull() ?: grandTotal) else 0.0
        val paymentMethod = (paymentMethodSpinner.selectedItem as? String) ?: "Cash"

        // Resolve to an existing supplier's id (case-insensitive match on the typed
        // name). If none matches, supplierId stays null — Reports/Suppliers treat
        // a null supplierId purchase as a plain "Cash Purchase".
        val matchedSupplier = suppliers.find { it.name.equals(party, ignoreCase = true) }
        val supplierId = matchedSupplier?.id

        val billNo = editBillNo ?: genBillNo()

        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)

            // Editing an existing bill: undo its old stock/balance effect and clear
            // its old rows before writing the updated version back under the same billNo.
            val original = originalPurchase
            if (original != null) {
                originalItems.forEach { db.productDao().decrease(it.barcode, it.qty) }
                val originalOutstanding = original.total - original.paid
                if (original.supplierId != null && originalOutstanding > 0) {
                    db.supplierDao().addBalance(original.supplierId, -originalOutstanding)
                }
                db.purchaseDao().deleteItems(billNo)
                db.purchaseDao().deletePurchase(billNo)
                db.paymentDao().deleteByReference(billNo)
            }

            db.purchaseDao().purchase(
                Purchase(
                    billNo = billNo,
                    supplierId = supplierId,
                    total = grandTotal,
                    paid = amountPaid,
                    createdAt = purchaseDateMillis,
                    subtotal = grandTotal,
                    discount = 0.0
                )
            )

            db.purchaseDao().items(
                lines.map { line ->
                    PurchaseItem(
                        billNo = billNo,
                        barcode = line.barcode ?: "",
                        qty = line.qty.roundToInt(),
                        unitCost = line.rate,
                        amount = line.amount,
                        unit = line.unit
                    )
                }
            )

            // Restock each item.
            lines.forEach { line ->
                line.barcode?.let { db.productDao().increase(it, line.qty.roundToInt()) }
            }

            // Track outstanding balance / payment for credit or partial-paid purchases.
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

            Toast.makeText(
                this@PurchaseActivity,
                if (original != null) "Purchase updated" else "Purchase saved",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
}
