package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class SaleLine(
    val barcode: String,
    val itemName: String,
    val qty: Int,
    val unitPrice: Double,
    val cost: Double,
    val amount: Double
)

class SaleActivity : AppCompatActivity() {

    private lateinit var customerSpinner: Spinner
    private lateinit var saleTypeSpinner: Spinner
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitPrice: EditText
    private lateinit var itemsContainer: LinearLayout
    private lateinit var subtotalText: TextView
    private lateinit var discountInput: EditText
    private lateinit var totalText: TextView
    private lateinit var paidInput: EditText
    private lateinit var paymentMethodSpinner: Spinner

    private var customers = listOf<Customer>()
    private var products = listOf<Product>()
    private val lines = mutableListOf<SaleLine>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
        }

        root.addView(TextView(this).apply { text = "New Sale"; textSize = 22f })

        customerSpinner = Spinner(this)
        root.addView(labeled("Customer", customerSpinner))
        root.addView(smallButton("+ Add New Customer") { promptAddCustomer() })

        saleTypeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Retail", "Wholesale"))
        }
        root.addView(labeled("Sale Type", saleTypeSpinner))

        root.addView(TextView(this).apply { text = "\nAdd Item"; textSize = 18f })
        itemName = AutoCompleteTextView(this).apply { hint = "Item Name (type to search)" }
        root.addView(itemName)
        itemName.setOnItemClickListener { _, _, position, _ ->
            val matches = products.filter { it.name.contains(itemName.text.toString(), ignoreCase = true) }
            if (position < matches.size) {
                val p = matches[position]
                val isWholesale = saleTypeSpinner.selectedItem?.toString() == "Wholesale"
                unitPrice.setText((if (isWholesale) p.wholesalePrice else p.salePrice).toString())
            }
        }

        qty = EditText(this).apply { hint = "Quantity" }
        unitPrice = EditText(this).apply { hint = "Unit Price (auto-filled, editable)" }
        root.addView(qty); root.addView(unitPrice)

        root.addView(Button(this).apply {
            text = "ADD ITEM"
            setOnClickListener { addItem() }
        })

        root.addView(TextView(this).apply { text = "\nBill Items"; textSize = 18f })
        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        subtotalText = TextView(this).apply { text = "Subtotal: Rs 0.00"; textSize = 16f }
        root.addView(subtotalText)

        discountInput = EditText(this).apply { hint = "Discount (Rs)" }
        root.addView(discountInput)

        totalText = TextView(this).apply { text = "Total: Rs 0.00"; textSize = 18f }
        root.addView(totalText)

        discountInput.addTextChangedListener(simpleWatcher { updateTotals() })

        paymentMethodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cash", "Bank"))
        }
        root.addView(labeled("Payment Method", paymentMethodSpinner))

        paidInput = EditText(this).apply { hint = "Amount Paid" }
        root.addView(paidInput)

        root.addView(Button(this).apply {
            text = "SAVE SALE"
            setOnClickListener { saveSale() }
        })

        setContentView(ScrollView(this).apply { addView(root) })

        loadCustomers()
        loadProducts()
    }

    private fun simpleWatcher(onChange: () -> Unit) = object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { onChange() }
        override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, e: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    private fun labeled(label: String, view: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@SaleActivity).apply { text = label })
        addView(view)
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun loadCustomers() {
        lifecycleScope.launch {
            PosDatabase.get(this@SaleActivity).customerDao().all().collectLatest { list ->
                customers = list
                val names = listOf("Walk-in") + list.map { it.name }
                customerSpinner.adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, names)
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
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addItem() {
        val n = itemName.text.toString().trim()
        val q = qty.text.toString().toIntOrNull() ?: 0
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
        if (product.stock < q) {
            Toast.makeText(this, "Stock kam hai (available: ${product.stock})", Toast.LENGTH_SHORT).show()
            return
        }

        val amount = q * price
        lines.add(SaleLine(product.barcode, product.name, q, price, product.cost, amount))
        itemsContainer.addView(TextView(this).apply {
            text = "${product.name}  |  Qty: $q  |  Price: $price  |  Amount: %.2f".format(amount)
            setPadding(0, 8, 0, 8)
        })
        itemName.text.clear(); qty.text.clear(); unitPrice.text.clear()
        updateTotals()
    }

    private fun updateTotals() {
        val subtotal = lines.sumOf { it.amount }
        val discount = discountInput.text.toString().toDoubleOrNull() ?: 0.0
        subtotalText.text = "Subtotal: Rs %.2f".format(subtotal)
        totalText.text = "Total: Rs %.2f".format((subtotal - discount).coerceAtLeast(0.0))
    }

    private fun saveSale() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "Kam az kam ek item add karen", Toast.LENGTH_SHORT).show()
            return
        }
        val subtotal = lines.sumOf { it.amount }
        val discount = discountInput.text.toString().toDoubleOrNull() ?: 0.0
        val total = (subtotal - discount).coerceAtLeast(0.0)
        val paid = paidInput.text.toString().toDoubleOrNull() ?: total
        val method = paymentMethodSpinner.selectedItem?.toString() ?: "Cash"
        val custName = customerSpinner.selectedItem?.toString()
        val customer = customers.find { it.name == custName }
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
                    product = it.itemName,
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
}
