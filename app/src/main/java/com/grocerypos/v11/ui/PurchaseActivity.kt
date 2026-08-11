package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class PurchaseLine(
    val itemName: String,
    val barcode: String?,          // null agar ye bilkul naya item hai (product list mein nahi)
    val qty: Double,
    val unit: String,               // jis unit mein kharida
    val rate: Double,                // rate usi unit ke hisaab se
    val amount: Double,
    val mainUnit: String,            // product ka asal (bada) unit
    val secondaryUnit: String,       // product ka chhota unit ("" agar set nahi)
    val secondaryUnitQty: Double     // 1 mainUnit = kitne secondaryUnit
)

class PurchaseActivity : AppCompatActivity() {

    private lateinit var dateButton: Button
    private lateinit var partySpinner: Spinner
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var rate: EditText
    private lateinit var conversionInfo: TextView
    private lateinit var totalAmountText: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var grandTotalText: TextView

    private var suppliers = listOf<Supplier>()
    private var products = listOf<Product>()
    private var allUnits = listOf("pcs", "kg", "box", "dozen")
    private val lines = mutableListOf<PurchaseLine>()

    private var purchaseDateMillis = System.currentTimeMillis()

    // currently matched product for the item being entered (null = naya/unknown item)
    private var selectedProduct: Product? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
        }

        root.addView(TextView(this).apply { text = "New Purchase"; textSize = 22f; setPadding(0,0,0,16) })

        // ---- 1) Date (calendar) ----
        root.addView(TextView(this).apply { text = "Purchase Date" })
        dateButton = Button(this).apply {
            text = formatDate(purchaseDateMillis)
            setOnClickListener { openDatePicker() }
        }
        root.addView(dateButton)

        // ---- 2) Party Name with side "+" ----
        partySpinner = Spinner(this)
        root.addView(labeledWithAdd("Party Name (Supplier)", partySpinner) { promptAddSupplier() })

        root.addView(divider())
        root.addView(TextView(this).apply { text = "Add Item"; textSize = 18f; setPadding(0,8,0,8) })

        // ---- 3 & 4) Item Name with side "+" ----
        itemName = AutoCompleteTextView(this).apply { hint = "Item Name" }
        root.addView(labeledWithAdd("Item Name", itemName) { promptAddItem() })

        // ---- 5) Quantity, then Unit (dropdown) ----
        qty = EditText(this).apply {
            hint = "Quantity"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(qty)

        unitSpinner = Spinner(this)
        root.addView(labeled("Unit", unitSpinner))

        // ---- 6) Rate per Unit ----
        rate = EditText(this).apply {
            hint = "Rate per Unit"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(rate)

        conversionInfo = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, 8, 0, 0)
            visibility = View.GONE
        }
        root.addView(conversionInfo)

        totalAmountText = TextView(this).apply { text = "Total Amount: Rs 0.00"; textSize = 16f; setPadding(0,8,0,8) }
        root.addView(totalAmountText)

        val watcher = simpleWatcher { updateLineTotal() }
        qty.addTextChangedListener(watcher)
        rate.addTextChangedListener(watcher)

        root.addView(Button(this).apply {
            text = "ADD ITEM"
            setOnClickListener { addItem() }
        })

        root.addView(TextView(this).apply { text = "\nItems in this Purchase"; textSize = 18f })
        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        grandTotalText = TextView(this).apply { text = "Grand Total: Rs 0.00"; textSize = 18f; setPadding(0,12,0,12) }
        root.addView(grandTotalText)

        root.addView(Button(this).apply {
            text = "SAVE PURCHASE"
            setOnClickListener { savePurchase() }
        })

        setContentView(ScrollView(this).apply { addView(root) })

        loadSuppliers()
        loadUnits()
        loadProducts()

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
            }
        })

        unitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { updateLineTotal() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ---- UI helpers ----
    private fun labeled(label: String, view: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@PurchaseActivity).apply { text = label })
        addView(view)
    }

    // Dropdown/field on the left, chhota "+" button side pe
    private fun labeledWithAdd(label: String, view: View, onAdd: () -> Unit): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this@PurchaseActivity).apply { text = label })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(view)
        row.addView(Button(this).apply {
            text = "+"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(12, 0, 0, 0) }
            setOnClickListener { onAdd() }
        })
        col.addView(row)
        return col
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0xFFDDDDDD.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { setMargins(0,16,0,16) }
    }

    private fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange()
    }

    private fun formatDate(millis: Long) = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))

    private fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = purchaseDateMillis }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            purchaseDateMillis = cal.timeInMillis
            dateButton.text = formatDate(purchaseDateMillis)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ---- Data loading ----
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

    // ---- Item selected from dropdown: set up unit choices (main + secondary) and prefill rate ----
    private fun onItemPicked(name: String) {
        val product = products.find { it.name.equals(name, ignoreCase = true) } ?: return
        selectedProduct = product

        val unitChoices = if (product.secondaryUnit.isNotEmpty())
            listOf(product.unit, product.secondaryUnit) else listOf(product.unit)
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitChoices)

        rate.setText(if (product.cost > 0) product.cost.toString() else "")
        updateLineTotal()
    }

    // ---- Live total + secondary unit conversion info ----
    private fun updateLineTotal() {
        val q = qty.text.toString().toDoubleOrNull() ?: 0.0
        val r = rate.text.toString().toDoubleOrNull() ?: 0.0
        totalAmountText.text = "Total Amount: Rs %.2f".format(q * r)

        val product = selectedProduct
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: ""
        if (product != null && product.secondaryUnit.isNotEmpty() && chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0) {
            val mainUnitCost = r * product.secondaryUnitQty
            conversionInfo.text = "1 ${product.unit} = ${product.secondaryUnitQty} ${product.secondaryUnit}  →  ${product.unit} cost: Rs %.2f".format(mainUnitCost)
            conversionInfo.visibility = View.VISIBLE
        } else {
            conversionInfo.visibility = View.GONE
        }
    }

    // ---- Add new supplier / new item on the fly ----
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

    private fun promptAddItem() {
        val input = EditText(this).apply { hint = "Item Name" }
        AlertDialog.Builder(this)
            .setTitle("New Item")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) lifecycleScope.launch {
                    val code = "P" + System.currentTimeMillis().toString()
                    PosDatabase.get(this@PurchaseActivity).productDao().upsert(
                        Product(barcode = code, name = v, unit = "pcs")
                    )
                    Toast.makeText(this@PurchaseActivity, "Item added", Toast.LENGTH_SHORT).show()
                    itemName.setText(v)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Add item line to the purchase ----
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
        val product = selectedProduct

        lines.add(
            PurchaseLine(
                itemName = n,
                barcode = product?.barcode,
                qty = q,
                unit = u,
                rate = r,
                amount = amount,
                mainUnit = product?.unit ?: u,
                secondaryUnit = product?.secondaryUnit ?: "",
                secondaryUnitQty = product?.secondaryUnitQty ?: 0.0
            )
        )

        itemsContainer.addView(TextView(this).apply {
            text = "$n  |  Qty: $q $u  |  Rate: $r  |  Amount: %.2f".format(amount)
            setPadding(0, 8, 0, 8)
        })
        grandTotalText.text = "Grand Total: Rs %.2f".format(lines.sumOf { it.amount })

        itemName.text.clear(); qty.text.clear(); rate.text.clear()
        totalAmountText.text = "Total Amount: Rs 0.00"
        conversionInfo.visibility = View.GONE
        selectedProduct = null
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, allUnits)
    }

    // ---- Save the whole purchase ----
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
                    subtotal = grandTotal,
                    createdAt = purchaseDateMillis
                )
            )

            val purchaseItems = mutableListOf<PurchaseItem>()
            for (line in lines) {

                // ---- Secondary unit -> main unit conversion ----
                val isSecondary = line.secondaryUnit.isNotEmpty() && line.unit == line.secondaryUnit && line.secondaryUnitQty > 0
                val mainUnitQty = if (isSecondary) line.qty / line.secondaryUnitQty else line.qty
                val costPerMainUnit = if (isSecondary) line.rate * line.secondaryUnitQty else line.rate

                var product = if (line.barcode != null) products.find { it.barcode == line.barcode }
                    else products.find { it.name.equals(line.itemName, ignoreCase = true) }

                if (product == null) {
                    val newBarcode = "P" + System.currentTimeMillis().toString() + line.itemName.hashCode()
                    product = Product(
                        barcode = newBarcode,
                        name = line.itemName,
                        cost = costPerMainUnit,
                        salePrice = costPerMainUnit,
                        stock = 0,
                        unit = line.mainUnit
                    )
                    db.productDao().upsert(product)
                } else {
                    db.productDao().upsert(product.copy(cost = costPerMainUnit))
                }

                db.productDao().increase(product.barcode, mainUnitQty.roundToInt())
                purchaseItems.add(
                    PurchaseItem(
                        billNo = billNo,
                        barcode = product.barcode,
                        qty = mainUnitQty.roundToInt(),
                        unitCost = costPerMainUnit,
                        amount = line.amount
                    )
                )
            }
            db.purchaseDao().items(purchaseItems)

            Toast.makeText(this@PurchaseActivity, "Purchase saved: $billNo", Toast.LENGTH_LONG).show()
            lines.clear()
            itemsContainer.removeAllViews()
            grandTotalText.text = "Grand Total: Rs 0.00"
            purchaseDateMillis = System.currentTimeMillis()
            dateButton.text = formatDate(purchaseDateMillis)
        }
    }
}
