package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
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

class SaleActivity : AppCompatActivity() {

    private val bg = "#F4F3FB"
    private val green = "#2E7D32"
    private val blue = "#1565C0"
    private val red = "#C62828"
    private val border = "#DDDDDD"

    private lateinit var dateValueText: TextView
    private lateinit var customerName: AutoCompleteTextView
    private lateinit var cashBtn: Button
    private lateinit var creditBtn: Button
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
    private lateinit var paymentSection: LinearLayout
    private lateinit var paidInput: EditText
    private lateinit var paymentMethodSpinner: Spinner

    private var customers = listOf<Customer>()
    private var products = listOf<Product>()
    private val lines = mutableListOf<SaleLine>()
    private var selectedProduct: Product? = null
    private var isCashSale = true
    private var saleDateMillis = System.currentTimeMillis()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 56, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER =================
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 18)
        }
        headerRow.addView(TextView(this).apply {
            text = "New Sale"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = "Hold"
            textSize = 13f
            setTextColor(Color.parseColor(blue))
            setPadding(0, 0, 20, 0)
            setOnClickListener { holdBill() }
        })
        headerRow.addView(TextView(this).apply {
            text = "Recall"
            textSize = 13f
            setTextColor(Color.parseColor(blue))
            setOnClickListener { openRecallDialog() }
        })
        root.addView(headerRow)

        // ================= DATE =================
        val dateBox = outlinedBox()
        dateBox.setOnClickListener { openDatePicker() }
        dateBox.addView(TextView(this).apply { text = "Date"; textSize = 12f; setTextColor(Color.GRAY) })
        val dateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dateValueText = TextView(this).apply {
            text = formatDate(saleDateMillis)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        dateRow.addView(dateValueText)
        dateRow.addView(TextView(this).apply { text = "▾"; textSize = 15f; setTextColor(Color.parseColor(blue)) })
        dateBox.addView(dateRow)
        root.addView(dateBox)
        root.addView(spacer(12))

        // ================= FIRM NAME =================
        val firmBox = outlinedBox()
        val firmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        firmRow.addView(TextView(this).apply { text = "Firm Name:  "; textSize = 13f; setTextColor(Color.GRAY) })
        firmRow.addView(TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        firmBox.addView(firmRow)
        root.addView(firmBox)
        root.addView(spacer(16))

        // ================= CUSTOMER NAME =================
        val custBox = outlinedBox()
        val custRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        customerName = AutoCompleteTextView(this).apply {
            hint = "Customer Name (Walk-in)"
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        custRow.addView(customerName)
        custRow.addView(TextView(this).apply {
            text = "+"
            textSize = 20f
            setTextColor(Color.parseColor(blue))
            setPadding(20, 0, 4, 0)
            setOnClickListener { promptAddCustomer() }
        })
        custBox.addView(custRow)
        root.addView(custBox)
        root.addView(spacer(12))

        // ---- Cash / Credit ----
        val cashCreditToggle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cashBtn = Button(this).apply {
            text = "CASH"; textSize = 10f; setTextColor(Color.WHITE)
            background = roundedBg(green, 16)
            setPadding(14, 4, 14, 4); minWidth = 0; minHeight = 0
            setOnClickListener { setSaleMode(true) }
        }
        creditBtn = Button(this).apply {
            text = "CREDIT"; textSize = 10f; setTextColor(Color.parseColor("#9E9E9E"))
            background = roundedBg("#EEEEEE", 16)
            setPadding(14, 4, 14, 4); minWidth = 0; minHeight = 0
            setOnClickListener { setSaleMode(false) }
        }
        cashCreditToggle.addView(cashBtn)
        cashCreditToggle.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(8, 1) })
        cashCreditToggle.addView(creditBtn)
        root.addView(cashCreditToggle)
        root.addView(spacer(16))

        // ================= SALE TYPE =================
        val saleTypeBox = outlinedBox()
        saleTypeBox.addView(TextView(this).apply { text = "Sale Type"; textSize = 12f; setTextColor(Color.GRAY) })
        saleTypeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Retail", "Wholesale"))
        }
        saleTypeBox.addView(saleTypeSpinner)
        root.addView(saleTypeBox)
        root.addView(spacer(16))

        // ================= "Add Items" trigger =================
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
            text = "  Add Items"
            textSize = 14f
            setTextColor(Color.parseColor(blue))
        }
        addItemsRow.addView(addItemsTrigger)
        addItemsBox.addView(addItemsRow)
        addItemsBox.setOnClickListener { toggleItemEntry() }
        root.addView(addItemsBox)
        root.addView(spacer(14))

        // ================= ITEM ENTRY (collapsible) =================
        itemEntrySection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val itemBox = outlinedBox()
        itemName = AutoCompleteTextView(this).apply { hint = "Item Name (type to search)"; background = null }
        itemBox.addView(itemName)
        itemEntrySection.addView(itemBox)
        itemEntrySection.addView(spacer(10))

        val qtyUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qtyBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,6,0) }
        }
        qty = EditText(this).apply {
            hint = "Quantity"
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        qtyBox.addView(qty)
        val unitBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6,0,0,0) }
        }
        unitSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
        }
        unitBox.addView(unitSpinner)
        qtyUnitRow.addView(qtyBox)
        qtyUnitRow.addView(unitBox)
        itemEntrySection.addView(qtyUnitRow)
        itemEntrySection.addView(spacer(10))

        val rateBox = outlinedBox()
        unitPrice = EditText(this).apply {
            hint = "Rate (auto-filled, editable)"
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        rateBox.addView(unitPrice)
        itemEntrySection.addView(rateBox)
        itemEntrySection.addView(spacer(12))

        itemEntrySection.addView(Button(this).apply {
            text = "+  ADD ITEM"
            setTextColor(Color.WHITE)
            background = roundedBg(blue, 14)
            setOnClickListener { addItem() }
        })
        root.addView(itemEntrySection)
        root.addView(spacer(14))

        // ================= BILL ITEMS =================
        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        // ================= SUBTOTAL / DISCOUNT / TOTAL =================
        val subtotalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(4, 6, 4, 6) }
        subtotalRow.addView(TextView(this).apply {
            text = "Subtotal"; textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        subtotalText = TextView(this).apply { text = "Rs 0.00"; textSize = 14f }
        subtotalRow.addView(subtotalText)
        root.addView(subtotalRow)

        val discountBox = outlinedBox().apply { setPadding(16, 2, 16, 2) }
        discountInput = EditText(this).apply {
            hint = "Discount (Rs)"
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        discountBox.addView(discountInput)
        root.addView(discountBox)
        discountInput.addTextChangedListener(simpleWatcher { updateTotals() })

        val totalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(4, 8, 4, 4) }
        totalRow.addView(TextView(this).apply {
            text = "Total Amount"; textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        totalText = TextView(this).apply {
            text = "Rs 0.00"; textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        totalRow.addView(totalText)
        root.addView(totalRow)
        root.addView(spacer(10))

        // ================= PAYMENT (compact) =================
        paymentSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val payRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val methodBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,6,0) }
            setPadding(16, 4, 16, 4)
        }
        paymentMethodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cash", "Bank"))
        }
        methodBox.addView(paymentMethodSpinner)
        val paidBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6,0,0,0) }
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
        root.addView(spacer(14))

        // ================= SAVE =================
        root.addView(Button(this).apply {
            text = "SAVE SALE"
            setTextColor(Color.WHITE)
            textSize = 15f
            background = roundedBg(green, 14)
            setPadding(0, 22, 0, 22)
            setOnClickListener { saveSale() }
        })
        root.addView(spacer(40))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadCustomers()
        loadProducts()
        setSaleMode(true)

        itemName.setOnItemClickListener { _, _, position, _ ->
            val name = itemName.adapter.getItem(position).toString()
            onItemPicked(name)
        }
        itemName.addTextChangedListener(simpleWatcher {
            val match = products.find { it.name.equals(itemName.text.toString().trim(), ignoreCase = true) }
            if (match == null) {
                selectedProduct = null
                unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
            }
        })
        unitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { refillAutoPrice() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        saleTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { refillAutoPrice() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ================= UI helpers =================
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
        addItemsTrigger.text = if (itemEntrySection.visibility == View.VISIBLE) "  Hide Item Entry" else "  Add Items"
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
        val cal = Calendar.getInstance().apply { timeInMillis = saleDateMillis }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            saleDateMillis = cal.timeInMillis
            dateValueText.text = formatDate(saleDateMillis)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ================= Cash / Credit toggle =================
    private fun setSaleMode(cash: Boolean) {
        isCashSale = cash
        if (cash) {
            cashBtn.background = roundedBg(green, 16); cashBtn.setTextColor(Color.WHITE)
            creditBtn.background = roundedBg("#EEEEEE", 16); creditBtn.setTextColor(Color.parseColor("#9E9E9E"))
            paymentSection.visibility = View.VISIBLE
        } else {
            creditBtn.background = roundedBg(red, 16); creditBtn.setTextColor(Color.WHITE)
            cashBtn.background = roundedBg("#EEEEEE", 16); cashBtn.setTextColor(Color.parseColor("#9E9E9E"))
            paymentSection.visibility = View.GONE
        }
    }

    // ================= Data loading =================
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

    private fun promptAddCustomer() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("New Customer")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) lifecycleScope.launch {
                    PosDatabase.get(this@SaleActivity).customerDao().insert(Customer(name = v))
                    Toast.makeText(this@SaleActivity, "Customer added", Toast.LENGTH_SHORT).show()
                    customerName.setText(v)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= Item selection & auto price =================
    private fun onItemPicked(name: String) {
        val product = products.find { it.name.equals(name, ignoreCase = true) } ?: return
        selectedProduct = product
        val unitChoices = if (product.secondaryUnit.isNotEmpty())
            listOf(product.unit, product.secondaryUnit) else listOf(product.unit)
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitChoices)
        refillAutoPrice()
    }

    private fun refillAutoPrice() {
        val product = selectedProduct ?: return
        val isWholesale = saleTypeSpinner.selectedItem?.toString() == "Wholesale"
        val basePrice = if (isWholesale) product.wholesalePrice else product.salePrice
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        val isSecondary = chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0
        val price = if (isSecondary) basePrice / product.secondaryUnitQty else basePrice
        unitPrice.setText(if (price > 0) "%.2f".format(price) else "")
    }

    // ================= Add item to bill =================
    private fun addItem() {
        val n = itemName.text.toString().trim()
        val q = qty.text.toString().toDoubleOrNull() ?: 0.0
        val price = unitPrice.text.toString().toDoubleOrNull() ?: 0.0
        val product = products.find { it.name.equals(n, ignoreCase = true) }

        if (product == null) {
            Toast.makeText(this, "Ye item product list mein nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        if (q <= 0) {
            Toast.makeText(this, "Quantity theek se likhen", Toast.LENGTH_SHORT).show()
            return
        }

        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        val isSecondary = chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0
        val mainUnitQtyEquivalent = if (isSecondary) q / product.secondaryUnitQty else q

        if (product.stock < mainUnitQtyEquivalent) {
            Toast.makeText(this, "Stock kam hai (available: ${product.stock} ${product.unit})", Toast.LENGTH_SHORT).show()
            return
        }

        val amount = q * price
        lines.add(
            SaleLine(
                barcode = product.barcode,
                itemName = product.name,
                qty = q,
                unit = chosenUnit,
                unitPrice = price,
                cost = product.cost,
                amount = amount,
                mainUnit = product.unit,
                secondaryUnit = product.secondaryUnit,
                secondaryUnitQty = product.secondaryUnitQty
            )
        )
        renderItemsList()
        updateTotals()

        itemName.text.clear(); qty.text.clear(); unitPrice.text.clear()
        selectedProduct = null
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
        itemName.requestFocus()
    }

    private fun renderItemsList() {
        itemsContainer.removeAllViews()
        lines.forEachIndexed { index, line ->
            itemsContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
                background = strokedBg(border, "#FFFFFF", 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 10) }

                val top = LinearLayout(this@SaleActivity).apply { orientation = LinearLayout.HORIZONTAL }
                top.addView(TextView(this@SaleActivity).apply {
                    text = line.itemName; textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                top.addView(TextView(this@SaleActivity).apply {
                    text = "Rs %.2f".format(line.amount)
                    setTextColor(Color.parseColor(green))
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(top)
                addView(TextView(this@SaleActivity).apply {
                    text = "Qty: ${line.qty} ${line.unit}  •  Rate: ${line.unitPrice}"
                    textSize = 13f
                    setTextColor(Color.GRAY)
                })
                addView(TextView(this@SaleActivity).apply {
                    text = "Remove"
                    textSize = 12f
                    setTextColor(Color.parseColor(red))
                    setPadding(0, 8, 0, 0)
                    setOnClickListener {
                        lines.removeAt(index)
                        renderItemsList()
                        updateTotals()
                    }
                })
            })
        }
    }

    private fun updateTotals() {
        val subtotal = lines.sumOf { it.amount }
        val discount = discountInput.text.toString().toDoubleOrNull() ?: 0.0
        subtotalText.text = "Rs %.2f".format(subtotal)
        val total = (subtotal - discount).coerceAtLeast(0.0)
        totalText.text = "Rs %.2f".format(total)
        if (isCashSale) paidInput.setText("%.2f".format(total))
    }

    // ================= Save =================
    private fun saveSale() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "Kam az kam ek item add karen", Toast.LENGTH_SHORT).show()
            return
        }
        val enteredCustomer = customerName.text.toString().trim()
        if (!isCashSale && enteredCustomer.isEmpty()) {
            Toast.makeText(this, "Credit sale ke liye Customer zaroori hai", Toast.LENGTH_SHORT).show()
            return
        }

        val subtotal = lines.sumOf { it.amount }
        val discount = discountInput.text.toString().toDoubleOrNull() ?: 0.0
        val total = (subtotal - discount).coerceAtLeast(0.0)
        val paid = if (isCashSale) (paidInput.text.toString().toDoubleOrNull() ?: total) else 0.0
        val method = if (isCashSale) (paymentMethodSpinner.selectedItem?.toString() ?: "Cash") else "credit"
        var customer = customers.find { it.name.equals(enteredCustomer, ignoreCase = true) }
        val saleType = if (saleTypeSpinner.selectedItem?.toString() == "Wholesale") "wholesale" else "retail"
        val invoice = "INV" + System.currentTimeMillis().toString()

        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)

            if (customer == null && enteredCustomer.isNotEmpty()) {
                val newId = db.customerDao().insert(Customer(name = enteredCustomer))
                customer = Customer(id = newId, name = enteredCustomer)
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
                    paymentMethod = method.lowercase(),
                    saleType = saleType,
                    createdAt = saleDateMillis
                )
            )

            val saleItems = lines.map {
                val isSecondary = it.secondaryUnit.isNotEmpty() && it.unit == it.secondaryUnit && it.secondaryUnitQty > 0
                val mainUnitQty = if (isSecondary) it.qty / it.secondaryUnitQty else it.qty
                val unitPricePerMainUnit = if (isSecondary) it.unitPrice * it.secondaryUnitQty else it.unitPrice
                SaleItem(
                    invoice = invoice,
                    barcode = it.barcode,
                    product = it.itemName,
                    qty = mainUnitQty.roundToInt(),
                    unitPrice = unitPricePerMainUnit,
                    cost = it.cost,
                    amount = it.amount
                )
            }
            db.saleDao().items(saleItems)

            for (line in lines) {
                val isSecondary = line.secondaryUnit.isNotEmpty() && line.unit == line.secondaryUnit && line.secondaryUnitQty > 0
                val mainUnitQty = if (isSecondary) line.qty / line.secondaryUnitQty else line.qty
                db.productDao().decrease(line.barcode, mainUnitQty.roundToInt())
            }

            if (customer != null && paid < total) {
                db.customerDao().addBalance(customer!!.id, total - paid)
            }

            if (paid > 0) {
                db.cashTransactionDao().insert(
                    CashTransaction(
                        type = "IN",
                        method = method.lowercase(),
                        amount = paid,
                        reason = "Sale",
                        reference = invoice
                    )
                )
            }

            Toast.makeText(this@SaleActivity, "Sale saved: $invoice", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ================= Hold Bill =================
    private fun holdBill() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "Add items pehle, phir hold karen", Toast.LENGTH_SHORT).show()
            return
        }
        val holdId = "HOLD" + System.currentTimeMillis().toString()
        val payload = encodeHold()

        lifecycleScope.launch {
            PosDatabase.get(this@SaleActivity).heldDao().hold(HeldBill(holdId = holdId, payload = payload))
            Toast.makeText(this@SaleActivity, "Bill hold ho gayi", Toast.LENGTH_SHORT).show()
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

        val itemsPart = lines.joinToString("\u0002") {
            listOf(it.barcode, it.itemName, it.qty, it.unit, it.unitPrice, it.cost, it.amount, it.mainUnit, it.secondaryUnit, it.secondaryUnitQty)
                .joinToString("\u0003")
        }
        return header + "\u0004" + itemsPart
    }

    private fun decodeHold(payload: String) {
        val parts = payload.split("\u0004")
        if (parts.isEmpty()) return
        val header = parts[0].split("\u0001")
        if (header.size >= 4) {
            customerName.setText(header[0])
            val saleTypeIndex = if (header[1] == "Wholesale") 1 else 0
            saleTypeSpinner.setSelection(saleTypeIndex)
            setSaleMode(header[2] != "CREDIT")
            discountInput.setText(header[3])
        }

        lines.clear()
        if (parts.size > 1 && parts[1].isNotEmpty()) {
            parts[1].split("\u0002").forEach { row ->
                val f = row.split("\u0003")
                if (f.size >= 10) {
                    lines.add(
                        SaleLine(
                            barcode = f[0],
                            itemName = f[1],
                            qty = f[2].toDoubleOrNull() ?: 0.0,
                            unit = f[3],
                            unitPrice = f[4].toDoubleOrNull() ?: 0.0,
                            cost = f[5].toDoubleOrNull() ?: 0.0,
                            amount = f[6].toDoubleOrNull() ?: 0.0,
                            mainUnit = f[7],
                            secondaryUnit = f[8],
                            secondaryUnitQty = f[9].toDoubleOrNull() ?: 0.0
                        )
                    )
                }
            }
        }
        renderItemsList()
        updateTotals()
    }

    private fun clearAll() {
        lines.clear()
        renderItemsList()
        customerName.text.clear()
        discountInput.text.clear()
        itemName.text.clear(); qty.text.clear(); unitPrice.text.clear()
        selectedProduct = null
        setSaleMode(true)
        subtotalText.text = "Rs 0.00"
        totalText.text = "Rs 0.00"
        paidInput.text.clear()
        saleDateMillis = System.currentTimeMillis()
        dateValueText.text = formatDate(saleDateMillis)
    }

    // ================= Recall Bill dialog =================
    private fun openRecallDialog() {
        lifecycleScope.launch {
            val held = PosDatabase.get(this@SaleActivity).heldDao().all().first()

            val content = LinearLayout(this@SaleActivity).apply { orientation = LinearLayout.VERTICAL }
            val dialogHeader = LinearLayout(this@SaleActivity).apply {
                setPadding(28, 24, 28, 24)
                setBackgroundColor(Color.parseColor(green))
            }
            dialogHeader.addView(TextView(this@SaleActivity).apply {
                text = "Held Bills"
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            content.addView(dialogHeader)

            val list = LinearLayout(this@SaleActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 16, 24, 16)
            }

            if (held.isEmpty()) {
                list.addView(TextView(this@SaleActivity).apply {
                    text = "Koi held bill nahi hai"
                    setTextColor(Color.GRAY)
                    setPadding(8, 20, 8, 20)
                })
            }

            val dialog = AlertDialog.Builder(this@SaleActivity).setView(content).create()
            val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

            for (h in held) {
                val itemCount = h.payload.split("\u0004").getOrNull(1)?.split("\u0002")?.filter { it.isNotEmpty() }?.size ?: 0
                val row = LinearLayout(this@SaleActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(16, 16, 16, 16)
                    background = strokedBg(border, "#FFFFFF", 10)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 10) }
                }
                val info = LinearLayout(this@SaleActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                info.addView(TextView(this@SaleActivity).apply {
                    text = "$itemCount items"; textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                info.addView(TextView(this@SaleActivity).apply {
                    text = fmt.format(Date(h.createdAt))
                    textSize = 12f
                    setTextColor(Color.GRAY)
                })
                row.addView(info)
                row.addView(TextView(this@SaleActivity).apply {
                    text = "RECALL"
                    textSize = 12f
                    setTextColor(Color.parseColor(blue))
                    setPadding(20, 0, 20, 0)
                    setOnClickListener {
                        decodeHold(h.payload)
                        lifecycleScope.launch {
                            PosDatabase.get(this@SaleActivity).heldDao().delete(h)
                        }
                        dialog.dismiss()
                    }
                })
                row.addView(TextView(this@SaleActivity).apply {
                    text = "✕"
                    textSize = 14f
                    setTextColor(Color.parseColor(red))
                    setPadding(16, 0, 8, 0)
                    setOnClickListener {
                        lifecycleScope.launch {
                            PosDatabase.get(this@SaleActivity).heldDao().delete(h)
                            Toast.makeText(this@SaleActivity, "Held bill hata di", Toast.LENGTH_SHORT).show()
                        }
                        dialog.dismiss()
                    }
                })
                list.addView(row)
            }
            content.addView(list)

            content.addView(Button(this@SaleActivity).apply {
                text = "Close"
                setOnClickListener { dialog.dismiss() }
            })

            dialog.show()
        }
    }
}
