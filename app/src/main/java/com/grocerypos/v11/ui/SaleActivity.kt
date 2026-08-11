package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class SaleLine(
    val barcode: String,
    val itemName: String,
    val qty: Int,
    val unit: String,
    val unitPrice: Double,
    val cost: Double,
    val amount: Double
)

class SaleActivity : AppCompatActivity() {

    // ---- palette ----
    private val colorBg = Color.parseColor("#F4F6F5")
    private val colorCard = Color.parseColor("#FFFFFF")
    private val colorBorder = Color.parseColor("#E3E7E5")
    private val colorPrimary = Color.parseColor("#2E7D32")
    private val colorAccent = Color.parseColor("#1565C0")
    private val colorHold = Color.parseColor("#EF6C00")
    private val colorDanger = Color.parseColor("#C62828")
    private val colorMuted = Color.parseColor("#6B7280")

    private lateinit var saleModeGroup: RadioGroup
    private lateinit var cashRadio: RadioButton
    private lateinit var creditRadio: RadioButton
    private lateinit var customerName: AutoCompleteTextView

    private lateinit var saleTypeSpinner: Spinner
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var unitPrice: EditText
    private lateinit var itemsTable: TableLayout
    private lateinit var subtotalText: TextView
    private lateinit var discountInput: EditText
    private lateinit var totalText: TextView
    private lateinit var paidInput: EditText
    private lateinit var paymentMethodSpinner: Spinner
    private lateinit var dateText: TextView

    private val firmName = "Ibtisaam Traders"
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private var saleCalendar: Calendar = Calendar.getInstance()

    private var customers = listOf<Customer>()
    private var products = listOf<Product>()
    private val lines = mutableListOf<SaleLine>()
    private var isCreditSale = false

    // currently selected item in "Add Item" - drives which units + price apply
    private var selectedProduct: Product? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            setPadding(24, 28, 24, 40)
        }

        // ---- topbar: firm name + calendar date picker ----
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 22, 28, 22)
            background = GradientDrawable().apply { setColor(colorPrimary); cornerRadius = 20f }
        }
        val topBarText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBarText.addView(TextView(this).apply {
            text = firmName
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        topBarText.addView(TextView(this).apply {
            text = "New Sale"
            textSize = 13f
            setTextColor(Color.parseColor("#DCEDC8"))
        })
        topBar.addView(topBarText)

        dateText = TextView(this).apply {
            text = "\uD83D\uDCC5 " + dateFormat.format(saleCalendar.time)
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(20, 12, 20, 12)
            background = GradientDrawable().apply { setColor(Color.parseColor("#33FFFFFF")); cornerRadius = 14f }
            setOnClickListener { openDatePicker() }
        }
        topBar.addView(dateText)

        root.addView(topBar)
        root.addView(spacerView(20))

        // ---- top actions: Hold / Recall ----
        val topActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        topActions.addView(outlineButton("Hold Bill", colorHold) { holdCurrentBill() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = 12 }
        })
        topActions.addView(outlineButton("Recall Bill", colorAccent) { showRecallDialog() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(card { addView(topActions) })

        // ---- customer section ----
        root.addView(card {
            addView(sectionTitle("Customer"))

            saleModeGroup = RadioGroup(this@SaleActivity).apply { orientation = LinearLayout.HORIZONTAL }
            cashRadio = RadioButton(this@SaleActivity).apply { text = "Cash Sale"; isChecked = true }
            creditRadio = RadioButton(this@SaleActivity).apply { text = "Credit Sale"; setPadding(40, 0, 0, 0) }
            saleModeGroup.addView(cashRadio)
            saleModeGroup.addView(creditRadio)
            addView(saleModeGroup)

            customerName = AutoCompleteTextView(this@SaleActivity).apply {
                hint = "Customer Name (optional for cash, type or pick)"
                threshold = 1
            }
            addView(customerName)
            addView(smallLink("+ Add New Customer") { promptAddCustomer() })

            saleModeGroup.setOnCheckedChangeListener { _, checkedId ->
                isCreditSale = checkedId == creditRadio.id
                if (isCreditSale) {
                    customerName.hint = "Customer Name (required for credit)"
                    Toast.makeText(this@SaleActivity, "Credit Sale - customer select ya add karen", Toast.LENGTH_SHORT).show()
                } else {
                    customerName.hint = "Customer Name (optional for cash)"
                    Toast.makeText(this@SaleActivity, "Cash Sale - customer optional hai", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // ---- sale type ----
        root.addView(card {
            addView(sectionTitle("Sale Type"))
            saleTypeSpinner = Spinner(this@SaleActivity).apply {
                adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Retail", "Wholesale"))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        applyUnitPrice()
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            addView(saleTypeSpinner)
        })

        // ---- add item ----
        root.addView(card {
            addView(sectionTitle("Add Item"))

            itemName = AutoCompleteTextView(this@SaleActivity).apply { hint = "Item Name (type to search)" }
            addView(itemName)
            itemName.setOnItemClickListener { _, _, position, _ ->
                val matches = products.filter { it.name.contains(itemName.text.toString(), ignoreCase = true) }
                if (position < matches.size) {
                    val p = matches[position]
                    selectedProduct = p
                    val opts = mutableListOf(p.unit)
                    if (p.secondaryUnit.isNotBlank() && p.secondaryUnitQty > 0) opts.add(p.secondaryUnit)
                    unitSpinner.adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, opts)
                    unitSpinner.setSelection(0)
                    applyUnitPrice()
                }
            }
            // also clear the stale product link if the user edits the text after picking one
            itemName.addTextChangedListener(simpleWatcher {
                val p = selectedProduct
                if (p != null && !itemName.text.toString().equals(p.name, ignoreCase = true)) {
                    selectedProduct = null
                }
            })

            val qtyRow = LinearLayout(this@SaleActivity).apply { orientation = LinearLayout.HORIZONTAL }
            qty = EditText(this@SaleActivity).apply {
                hint = "Quantity"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = 12 }
            }
            unitSpinner = Spinner(this@SaleActivity).apply {
                adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Unit"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        applyUnitPrice()
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            qtyRow.addView(qty); qtyRow.addView(unitSpinner)
            addView(qtyRow)

            unitPrice = EditText(this@SaleActivity).apply {
                hint = "Unit Price (auto-filled, editable)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            addView(unitPrice)

            addView(filledButton("ADD ITEM", colorPrimary) { addItem() })
        })

        // ---- bill items ----
        root.addView(card {
            addView(sectionTitle("Bill Items"))
            itemsTable = TableLayout(this@SaleActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addView(itemsTable)
        })

        // ---- totals ----
        root.addView(card {
            subtotalText = TextView(this@SaleActivity).apply { text = "Subtotal: Rs 0.00"; textSize = 16f }
            addView(subtotalText)

            discountInput = EditText(this@SaleActivity).apply {
                hint = "Discount (Rs)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            addView(discountInput)
            discountInput.addTextChangedListener(simpleWatcher { updateTotals() })

            totalText = TextView(this@SaleActivity).apply {
                text = "Total: Rs 0.00"
                textSize = 20f
                setTextColor(colorPrimary)
                setPadding(0, 8, 0, 0)
            }
            addView(totalText)
        })

        // ---- payment ----
        root.addView(card {
            addView(sectionTitle("Payment"))
            paymentMethodSpinner = Spinner(this@SaleActivity).apply {
                adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cash", "Bank"))
            }
            addView(paymentMethodSpinner)

            paidInput = EditText(this@SaleActivity).apply {
                hint = "Amount Paid"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            addView(paidInput)
        })

        root.addView(filledButton("SAVE SALE", colorPrimary) { saveSale() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 140).also { it.topMargin = 8 }
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(colorBg); addView(root) })

        loadCustomers()
        loadProducts()
    }

    // ---------- small UI helpers ----------

    private fun card(builder: LinearLayout.() -> Unit): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = GradientDrawable().apply {
                setColor(colorCard)
                cornerRadius = 22f
                setStroke(2, colorBorder)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 20 }
        }
        c.builder()
        return c
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(colorMuted)
        setPadding(0, 0, 0, 10)
    }

    private fun spacerView(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px)
    }

    private fun filledButton(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(color); cornerRadius = 16f }
        setOnClickListener { onClick() }
    }

    private fun outlineButton(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(color)
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16f; setStroke(3, color) }
        setOnClickListener { onClick() }
    }

    private fun smallLink(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(colorAccent)
        setPadding(0, 12, 0, 4)
        setOnClickListener { onClick() }
    }

    private fun simpleWatcher(onChange: () -> Unit) = object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { onChange() }
        override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, e: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    // ---------- data loading ----------

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

    private fun openDatePicker() {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                saleCalendar.set(Calendar.YEAR, year)
                saleCalendar.set(Calendar.MONTH, month)
                saleCalendar.set(Calendar.DAY_OF_MONTH, day)
                dateText.text = "\uD83D\uDCC5 " + dateFormat.format(saleCalendar.time)
            },
            saleCalendar.get(Calendar.YEAR),
            saleCalendar.get(Calendar.MONTH),
            saleCalendar.get(Calendar.DAY_OF_MONTH)
        ).show()
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
                    customerName.setText(v)
                    Toast.makeText(this@SaleActivity, "Customer added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- item add / render (repeatable without redoing customer / sale type) ----------

    // Recomputes unit price whenever the chosen unit or Retail/Wholesale sale type changes.
    // Base salePrice/wholesalePrice are stored per PRIMARY unit; secondary-unit price = base / secondaryUnitQty.
    private fun applyUnitPrice() {
        val p = selectedProduct ?: return
        val isWholesale = saleTypeSpinner.selectedItem?.toString() == "Wholesale"
        val baseRate = if (isWholesale) p.wholesalePrice else p.salePrice
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: p.unit

        val rate = when {
            chosenUnit == p.unit -> baseRate
            chosenUnit == p.secondaryUnit && p.secondaryUnitQty > 0 -> baseRate / p.secondaryUnitQty
            else -> baseRate
        }
        unitPrice.setText(if (rate == rate.toLong().toDouble()) rate.toLong().toString() else "%.2f".format(rate))
    }

    private fun addItem() {
        val n = itemName.text.toString().trim()
        val q = qty.text.toString().toIntOrNull() ?: 0
        val unit = unitSpinner.selectedItem?.toString() ?: (selectedProduct?.unit ?: "pcs")
        val price = unitPrice.text.toString().toDoubleOrNull() ?: 0.0
        val product = selectedProduct ?: products.find { it.name.equals(n, ignoreCase = true) }

        if (product == null) {
            Toast.makeText(this, "Ye item product list mein nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        if (q <= 0) {
            Toast.makeText(this, "Quantity theek se likhen", Toast.LENGTH_SHORT).show()
            return
        }
        if (product.stock < q) {
            Toast.makeText(this, "Stock kam hai (available: ${product.stock})", Toast.LENGTH_SHORT).show()
            return
        }

        val amount = q * price
        lines.add(SaleLine(product.barcode, product.name, q, unit, price, product.cost, amount))
        renderItems()

        // clear only the item-entry fields so the next item can be added immediately
        itemName.text.clear(); qty.text.clear(); unitPrice.text.clear()
        selectedProduct = null
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Unit"))
        itemName.requestFocus()
        updateTotals()
    }

    private fun tableCell(text: String, bold: Boolean = false, color: Int = Color.parseColor("#212121"), weight: Float = 1f) =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(8, 10, 8, 10)
            layoutParams = TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        }

    private fun renderItems() {
        itemsTable.removeAllViews()
        if (lines.isEmpty()) return

        itemsTable.addView(TableRow(this).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#EEF2F1")); cornerRadius = 8f }
            addView(tableCell("Item", bold = true, color = colorMuted, weight = 2.4f))
            addView(tableCell("Qty", bold = true, color = colorMuted, weight = 1.1f))
            addView(tableCell("Rate", bold = true, color = colorMuted, weight = 1.2f))
            addView(tableCell("Amount", bold = true, color = colorMuted, weight = 1.3f))
            addView(tableCell("", weight = 0.5f))
        })

        lines.forEachIndexed { index, line ->
            itemsTable.addView(TableRow(this).apply {
                setPadding(0, 4, 0, 4)
                addView(tableCell(line.itemName, weight = 2.4f))
                addView(tableCell("${line.qty} ${line.unit}", weight = 1.1f))
                addView(tableCell("%.2f".format(line.unitPrice), weight = 1.2f))
                addView(tableCell("%.2f".format(line.amount), weight = 1.3f))
                addView(TextView(this@SaleActivity).apply {
                    text = "X"
                    setTextColor(colorDanger)
                    textSize = 13f
                    setPadding(8, 10, 8, 10)
                    layoutParams = TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.5f)
                    setOnClickListener {
                        lines.removeAt(index)
                        renderItems()
                        updateTotals()
                    }
                })
            })
        }
    }

    private fun updateTotals() {
        val subtotal = lines.sumOf { it.amount }
        val discount = discountInput.text.toString().toDoubleOrNull() ?: 0.0
        subtotalText.text = "Subtotal: Rs %.2f".format(subtotal)
        totalText.text = "Total: Rs %.2f".format((subtotal - discount).coerceAtLeast(0.0))
    }

    // ---------- save (stock / invoice logic unchanged from original) ----------

    private fun saveSale() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "Kam az kam ek item add karen", Toast.LENGTH_SHORT).show()
            return
        }

        val typedName = customerName.text.toString().trim()
        val customer = customers.find { it.name.equals(typedName, ignoreCase = true) }

        if (isCreditSale) {
            if (typedName.isEmpty()) {
                Toast.makeText(this, "Credit sale ke liye customer name zaroori hai", Toast.LENGTH_SHORT).show()
                return
            }
            if (customer == null) {
                Toast.makeText(this, "Ye customer list mein nahi hai, pehle '+ Add New Customer' se add karen", Toast.LENGTH_LONG).show()
                return
            }
        }

        val subtotal = lines.sumOf { it.amount }
        val discount = discountInput.text.toString().toDoubleOrNull() ?: 0.0
        val total = (subtotal - discount).coerceAtLeast(0.0)
        val paid = paidInput.text.toString().toDoubleOrNull() ?: total
        val method = paymentMethodSpinner.selectedItem?.toString() ?: "Cash"
        val saleType = if (saleTypeSpinner.selectedItem?.toString() == "Wholesale") "wholesale" else "retail"
        val invoice = "INV" + System.currentTimeMillis().toString()

        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)

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
                    saleType = saleType
                )
            )

            val saleItems = lines.map {
                SaleItem(
                    invoice = invoice,
                    barcode = it.barcode,
                    product = "${it.itemName} (${it.unit})",
                    qty = it.qty,
                    unitPrice = it.unitPrice,
                    cost = it.cost,
                    amount = it.amount
                )
            }
            db.saleDao().items(saleItems)

            for (line in lines) {
                db.productDao().decrease(line.barcode, line.qty)
            }

            if (customer != null && paid < total) {
                db.customerDao().addBalance(customer.id, total - paid)
            }

            db.cashTransactionDao().insert(
                CashTransaction(
                    type = "IN",
                    method = method.lowercase(),
                    amount = paid,
                    reason = "Sale",
                    reference = invoice
                )
            )

            Toast.makeText(this@SaleActivity, "Sale saved: $invoice", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ---------- Hold Bill / Recall (stored locally, does NOT touch stock/invoice tables) ----------

    private fun heldBillsPrefs() = getSharedPreferences("held_bills_v11", MODE_PRIVATE)

    private fun readHeldBills(): JSONArray =
        JSONArray(heldBillsPrefs().getString("bills", "[]"))

    private fun writeHeldBills(arr: JSONArray) {
        heldBillsPrefs().edit().putString("bills", arr.toString()).apply()
    }

    private fun holdCurrentBill() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "Hold karne ke liye kam az kam ek item add karen", Toast.LENGTH_SHORT).show()
            return
        }

        val obj = JSONObject()
        obj.put("customerName", customerName.text.toString())
        obj.put("isCredit", isCreditSale)
        obj.put("saleType", saleTypeSpinner.selectedItem?.toString() ?: "Retail")
        obj.put("paymentMethod", paymentMethodSpinner.selectedItem?.toString() ?: "Cash")
        obj.put("discount", discountInput.text.toString())
        obj.put("paid", paidInput.text.toString())
        obj.put("createdAt", System.currentTimeMillis())

        val linesArr = JSONArray()
        lines.forEach {
            val lo = JSONObject()
            lo.put("barcode", it.barcode)
            lo.put("itemName", it.itemName)
            lo.put("qty", it.qty)
            lo.put("unit", it.unit)
            lo.put("unitPrice", it.unitPrice)
            lo.put("cost", it.cost)
            lo.put("amount", it.amount)
            linesArr.put(lo)
        }
        obj.put("lines", linesArr)

        val bills = readHeldBills()
        bills.put(obj)
        writeHeldBills(bills)

        Toast.makeText(this, "Bill hold ho gaya", Toast.LENGTH_SHORT).show()
        clearForNewSale()
    }

    private fun clearForNewSale() {
        lines.clear()
        renderItems()
        customerName.text.clear()
        discountInput.text.clear()
        paidInput.text.clear()
        cashRadio.isChecked = true
        updateTotals()
    }

    private fun showRecallDialog() {
        val bills = readHeldBills()
        if (bills.length() == 0) {
            Toast.makeText(this, "Koi held bill nahi hai", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = (0 until bills.length()).map { i ->
            val bObj = bills.getJSONObject(i)
            val name = bObj.optString("customerName").ifBlank { "Walk-in" }
            val itemCount = bObj.getJSONArray("lines").length()
            val mode = if (bObj.optBoolean("isCredit")) "Credit" else "Cash"
            "$name - $itemCount items ($mode)"
        }

        AlertDialog.Builder(this)
            .setTitle("Held Bills")
            .setItems(labels.toTypedArray()) { _, which -> recallBill(which) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun recallBill(index: Int) {
        val bills = readHeldBills()
        val bObj = bills.getJSONObject(index)

        clearForNewSale()

        customerName.setText(bObj.optString("customerName"))
        isCreditSale = bObj.optBoolean("isCredit")
        if (isCreditSale) creditRadio.isChecked = true else cashRadio.isChecked = true

        val saleTypeVal = bObj.optString("saleType", "Retail")
        val stAdapter = saleTypeSpinner.adapter as ArrayAdapter<String>
        val stPos = (0 until stAdapter.count).find { stAdapter.getItem(it) == saleTypeVal } ?: 0
        saleTypeSpinner.setSelection(stPos)

        val pmVal = bObj.optString("paymentMethod", "Cash")
        val pmAdapter = paymentMethodSpinner.adapter as ArrayAdapter<String>
        val pmPos = (0 until pmAdapter.count).find { pmAdapter.getItem(it) == pmVal } ?: 0
        paymentMethodSpinner.setSelection(pmPos)

        discountInput.setText(bObj.optString("discount"))
        paidInput.setText(bObj.optString("paid"))

        val linesArr = bObj.getJSONArray("lines")
        for (i in 0 until linesArr.length()) {
            val lo = linesArr.getJSONObject(i)
            lines.add(
                SaleLine(
                    barcode = lo.getString("barcode"),
                    itemName = lo.getString("itemName"),
                    qty = lo.getInt("qty"),
                    unit = lo.optString("unit", "Pcs"),
                    unitPrice = lo.getDouble("unitPrice"),
                    cost = lo.getDouble("cost"),
                    amount = lo.getDouble("amount")
                )
            )
        }
        renderItems()
        updateTotals()

        bills.remove(index)
        writeHeldBills(bills)

        Toast.makeText(this, "Bill recall ho gaya", Toast.LENGTH_SHORT).show()
    }
}
