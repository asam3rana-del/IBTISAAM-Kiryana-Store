package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class PurchaseLine(
    val itemName: String,
    val qty: Double,
    val unit: String,
    val rate: Double,
    val amount: Double
)

class PurchaseActivity : AppCompatActivity() {

    private lateinit var partySpinner: Spinner
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var rate: EditText
    private lateinit var totalAmountText: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var grandTotalText: TextView

    private var suppliers = listOf<Supplier>()
    private var products = listOf<Product>()
    private var units = listOf("pcs", "kg", "box", "dozen")
    private val lines = mutableListOf<PurchaseLine>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
        }

        root.addView(TextView(this).apply { text = "New Purchase"; textSize = 22f })

        partySpinner = Spinner(this)
        root.addView(labeled("Party Name (Supplier)", partySpinner))
        root.addView(smallButton("+ Add New Supplier") { promptAddSupplier() })

        root.addView(TextView(this).apply { text = "\nAdd Item"; textSize = 18f })

        itemName = AutoCompleteTextView(this).apply { hint = "Item Name" }
        root.addView(itemName)

        unitSpinner = Spinner(this)
        root.addView(labeled("Unit", unitSpinner))

        qty = EditText(this).apply { hint = "Quantity" }
        rate = EditText(this).apply { hint = "Rate per Unit" }
        root.addView(qty); root.addView(rate)

        totalAmountText = TextView(this).apply { text = "Total Amount: Rs 0.00"; textSize = 16f }
        root.addView(totalAmountText)

        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { updateLineTotal() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, e: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        qty.addTextChangedListener(watcher)
        rate.addTextChangedListener(watcher)

        root.addView(Button(this).apply {
            text = "ADD ITEM"
            setOnClickListener { addItem() }
        })

        root.addView(TextView(this).apply { text = "\nItems in this Purchase"; textSize = 18f })
        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        grandTotalText = TextView(this).apply { text = "Grand Total: Rs 0.00"; textSize = 18f }
        root.addView(grandTotalText)

        root.addView(Button(this).apply {
            text = "SAVE PURCHASE"
            setOnClickListener { savePurchase() }
        })

        setContentView(ScrollView(this).apply { addView(root) })

        loadSuppliers()
        loadUnits()
        loadProducts()
    }

    private fun labeled(label: String, view: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@PurchaseActivity).apply { text = label })
        addView(view)
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun loadSuppliers() {
        lifecycleScope.launch {
            PosDatabase.get(this@PurchaseActivity).supplierDao().all().collectLatest { list ->
                suppliers = list
                partySpinner.adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, list.map { it.name })
            }
        }
    }

    private fun loadUnits() {
        lifecycleScope.launch {
            PosDatabase.get(this@PurchaseActivity).unitDao().all().collectLatest { list ->
                units = (listOf("pcs", "kg", "box", "dozen") + list.map { it.name }).distinct()
                unitSpinner.adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, units)
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

    private fun promptAddSupplier() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("New Supplier")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) lifecycleScope.launch {
                    PosDatabase.get(this@PurchaseActivity).supplierDao().insert(Supplier(name = v))
                    Toast.makeText(this@PurchaseActivity, "Supplier added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateLineTotal() {
        val q = qty.text.toString().toDoubleOrNull() ?: 0.0
        val r = rate.text.toString().toDoubleOrNull() ?: 0.0
        totalAmountText.text = "Total Amount: Rs %.2f".format(q * r)
    }

    private fun addItem() {
        val n = itemName.text.toString().trim()
        val q = qty.text.toString().toDoubleOrNull() ?: 0.0
        val r = rate.text.toString().toDoubleOrNull() ?: 0.0
        val u = unitSpinner.selectedItem?.toString() ?: "pcs"
        if (n.isEmpty() || q <= 0.0) {
            Toast.makeText(this, "Item Name aur Quantity zaroori hai", Toast.LENGTH_SHORT).show()
            return
        }
        val amount = q * r
        lines.add(PurchaseLine(n, q, u, r, amount))
        itemsContainer.addView(TextView(this).apply {
            text = "$n  |  Qty: $q $u  |  Rate: $r  |  Amount: %.2f".format(amount)
            setPadding(0, 8, 0, 8)
        })
        grandTotalText.text = "Grand Total: Rs %.2f".format(lines.sumOf { it.amount })

        itemName.text.clear(); qty.text.clear(); rate.text.clear()
        totalAmountText.text = "Total Amount: Rs 0.00"
    }

    private fun savePurchase() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "Kam az kam ek item add karen", Toast.LENGTH_SHORT).show()
            return
        }
        val partyName = partySpinner.selectedItem?.toString()
        val supplier = suppliers.find { it.name == partyName }
        val billNo = "PUR" + System.currentTimeMillis().toString()
        val grandTotal = lines.sumOf { it.amount }

        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)

            db.purchaseDao().purchase(
                Purchase(
                    billNo = billNo,
                    supplierId = supplier?.id,
                    total = grandTotal,
                    paid = 0.0,
                    subtotal = grandTotal
                )
            )

            val purchaseItems = mutableListOf<PurchaseItem>()
            for (line in lines) {
                var product = products.find { it.name.equals(line.itemName, ignoreCase = true) }
                if (product == null) {
                    // new item not in product list yet - create it
                    val newBarcode = "P" + System.currentTimeMillis().toString() + line.itemName.hashCode()
                    product = Product(
                        barcode = newBarcode,
                        name = line.itemName,
                        cost = line.rate,
                        salePrice = line.rate,
                        stock = 0,
                        unit = line.unit
                    )
                    db.productDao().upsert(product)
                } else {
                    db.productDao().upsert(product.copy(cost = line.rate))
                }
                db.productDao().increase(product.barcode, line.qty.toInt())
                purchaseItems.add(
                    PurchaseItem(
                        billNo = billNo,
                        barcode = product.barcode,
                        qty = line.qty.toInt(),
                        unitCost = line.rate,
                        amount = line.amount
                    )
                )
            }
            db.purchaseDao().items(purchaseItems)

            Toast.makeText(this@PurchaseActivity, "Purchase saved: $billNo", Toast.LENGTH_LONG).show()
            lines.clear()
            itemsContainer.removeAllViews()
            grandTotalText.text = "Grand Total: Rs 0.00"
        }
    }
}

