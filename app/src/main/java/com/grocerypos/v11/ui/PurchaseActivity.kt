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

class PurchaseActivity : AppCompatActivity() {

    private val bg = "#F4F3FB"
    private val orange = "#EF6C00"
    private val blue = "#1565C0"
    private val green = "#2E7D32"
    private val purple = "#6A1B9A"

    private lateinit var dateButton: Button
    private lateinit var partyName: AutoCompleteTextView
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var rate: EditText
    private lateinit var conversionInfo: TextView
    private lateinit var totalAmountText: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var grandTotalText: TextView
    private lateinit var paymentMethodSpinner: Spinner
    private lateinit var paidInput: EditText

    private var suppliers = listOf<Supplier>()
    private var products = listOf<Product>()
    private var allUnits = listOf("pcs", "kg", "box", "dozen")
    private val lines = mutableListOf<PurchaseLine>()
    private var purchaseDateMillis = System.currentTimeMillis()
    private var selectedProduct: Product? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 32)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER: title + date in top-right corner =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 20, 20, 20)
            background = roundedBg(orange, 20)
            elevation = 8f
        }
        header.addView(TextView(this).apply {
            text = "New Purchase"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        dateButton = Button(this).apply {
            text = formatDate(purchaseDateMillis)
            textSize = 12f
            setTextColor(Color.parseColor(orange))
            background = roundedBg("#FFFFFF", 30)
            setPadding(24, 8, 24, 8)
            minWidth = 0; minHeight = 0
            setOnClickListener { openDatePicker() }
        }
        header.addView(dateButton)
        root.addView(header)
        root.addView(spacer(20))

        // ================= PARTY CARD (type freely OR pick from "+" list) =================
        val partyCard = cardContainer()
        partyCard.addView(sectionLabel("Party Name (Supplier)"))
        partyName = AutoCompleteTextView(this).apply { hint = "Type or pick supplier name" }
        val partyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        partyName.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        partyRow.addView(partyName)
        partyRow.addView(Button(this).apply {
            text = "+"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(12, 0, 0, 0) }
            setOnClickListener { promptAddSupplier() }
        })
        partyCard.addView(partyRow)
        root.addView(partyCard)
        root.addView(spacer(20))

        // ================= ADD ITEM CARD =================
        val itemCard = cardContainer()
        itemCard.addView(sectionLabel("Add Item"))

        // centered "+" to quick-add a brand new item
        val plusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 16)
        }
        plusRow.addView(TextView(this).apply {
            text = "+"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            width = (44 * resources.displayMetrics.density).toInt()
            height = (44 * resources.displayMetrics.density).toInt()
            background = ovalBg(purple)
            setOnClickListener { promptAddItem() }
        })
        itemCard.addView(plusRow)

        itemName = AutoCompleteTextView(this).apply { hint = "Item Name" }
        itemCard.addView(itemName)
        itemCard.addView(spacer(14))

        // Quantity + Unit side by side
        val qtyUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        qty = EditText(this).apply {
            hint = "Quantity"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,8,0) }
        }
        unitSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8,0,0,0) }
        }
        qtyUnitRow.addView(qty)
        qtyUnitRow.addView(unitSpinner)
        itemCard.addView(qtyUnitRow)
        itemCard.addView(spacer(14))

        rate = EditText(this).apply {
            hint = "Rate per Unit"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        itemCard.addView(rate)

        conversionInfo = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor(blue))
            setPadding(0, 8, 0, 0)
            visibility = View.GONE
        }
        itemCard.addView(conversionInfo)

        totalAmountText = TextView(this).apply {
            text = "Total Amount: Rs 0.00"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 12, 0, 12)
        }
        itemCard.addView(totalAmountText)

        val watcher = simpleWatcher { updateLineTotal() }
        qty.addTextChangedListener(watcher)
        rate.addTextChangedListener(watcher)

        itemCard.addView(Button(this).apply {
            text = "ADD ITEM"
            setTextColor(Color.WHITE)
            background = roundedBg(blue, 14)
            setOnClickListener { addItem() }
        })
        root.addView(itemCard)
        root.addView(spacer(20))

        // ================= ITEMS LIST =================
        root.addView(sectionLabel("Items in this Purchase"))
        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)
        root.addView(spacer(12))

        // ================= PAYMENT (drives Cash OUT) =================
        val paymentCard = cardContainer()
        paymentCard.addView(sectionLabel("Payment"))
        paymentMethodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cash", "Bank"))
        }
        paymentCard.addView(paymentMethodSpinner)
        paymentCard.addView(spacer(12))
        paidInput = EditText(this).apply {
            hint = "Paid Amount (khaali chhoden agar poora udhaar hai)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        paymentCard.addView(paidInput)
        root.addView(paymentCard)
        root.addView(spacer(20))

        // ================= TOTAL + SAVE =================
        val totalCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 18, 22, 18)
            background = roundedBg(green, 18)
            elevation = 6f
        }
        grandTotalText = TextView(this).apply {
            text = "Grand Total: Rs 0.00"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        totalCard.addView(grandTotalText)
        root.addView(totalCard)
        root.addView(spacer(16))

        root.addView(Button(this).apply {
            text = "SAVE PURCHASE"
            setTextColor(Color.WHITE)
            textSize = 16f
            background = roundedBg(green, 16)
            setPadding(0, 28, 0, 28)
            setOnClickListener { savePurchase() }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

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
    private fun cardContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 22, 24, 22)
        background = roundedBg("#FFFFFF", 20)
        elevation = 4f
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.parseColor("#424242"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, 10)
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
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

    private fun onItemPicked(name: String) {
        val product = products.find { it.name.equals(name, ignoreCase = true) } ?: return
        selectedProduct = product
        val unitChoices = if (product.secondaryUnit.isNotEmpty())
            listOf(product.unit, product.secondaryUnit) else listOf(product.unit)
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitChoices)
        rate.setText(if (product.cost > 0) product.cost.toString() else "")
        updateLineTotal()
    }

    private fun updateLineTotal() {
        val q = qty.text.toString().toDoubleOrNull() ?: 0.0
        val r = rate.text.toString().toDoubleOrNull() ?: 0.0
        totalAmountText.text = "Total Amount: Rs %.2f".format(q * r)

        val product = selectedProduct
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: ""
        if (product != null && product.secondaryUnit.isNotEmpty() && chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0) {
            val mainUnitCost = r * product.secondaryUnitQty
            conversionInfo.text = "1 ${product.unit} = ${product.secondaryUnitQty} ${product.secondaryUnit}  ->  ${product.unit} cost: Rs %.2f".format(mainUnitCost)
            conversionInfo.visibility = View.VISIBLE
        } else {
            conversionInfo.visibility = View.GONE
        }
    }

    private fun promptAddSupplier() {
        val input = EditText(this).apply { hint = "Supplier Name" }
        AlertDialog.Builder(this)
            .setTitle("New Supplier")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) lifecycleScope.launch {
                    PosDatabase.get(this@PurchaseActivity).supplierDao().insert(Supplier(name = v))
                    Toast.makeText(this@PurchaseActivity, "Supplier added", Toast.LENGTH_SHORT).show()
                    partyName.setText(v)
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

        itemsContainer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            background = roundedBg("#FFFFFF", 14)
            elevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }

            val top = LinearLayout(this@PurchaseActivity).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(TextView(this@PurchaseActivity).apply {
                text = n; textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this@PurchaseActivity).apply {
                text = "Rs %.2f".format(amount)
                setTextColor(Color.parseColor(orange))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(top)
            addView(TextView(this@PurchaseActivity).apply {
                text = "Qty: $q $u  -  Rate: $r"
                textSize = 13f
                setTextColor(Color.GRAY)
            })
        })

        grandTotalText.text = "Grand Total: Rs %.2f".format(lines.sumOf { it.amount })

        itemName.text.clear(); qty.text.clear(); rate.text.clear()
        totalAmountText.text = "Total Amount: Rs 0.00"
        conversionInfo.visibility = View.GONE
        selectedProduct = null
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, allUnits)
    }

    private fun savePurchase() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "Kam az kam ek item add karen", Toast.LENGTH_SHORT).show()
            return
        }
        val enteredParty = partyName.text.toString().trim()
        var supplier = suppliers.find { it.name.equals(enteredParty, ignoreCase = true) }
        val billNo = "PUR" + System.currentTimeMillis().toString()
        val grandTotal = lines.sumOf { it.amount }
        val paidAmount = (paidInput.text.toString().toDoubleOrNull() ?: 0.0).coerceIn(0.0, grandTotal)
        val method = paymentMethodSpinner.selectedItem?.toString() ?: "Cash"

        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)

            // Naya supplier type kiya ho (list se select nahi kiya) to usay khud add kar dein
            if (supplier == null && enteredParty.isNotEmpty()) {
                val newId = db.supplierDao().insert(Supplier(name = enteredParty))
                supplier = Supplier(id = newId, name = enteredParty)
            }

            db.purchaseDao().purchase(
                Purchase(
                    billNo = billNo,
                    supplierId = supplier?.id,
                    total = grandTotal,
                    paid = paidAmount,
                    subtotal = grandTotal,
                    createdAt = purchaseDateMillis
                )
            )

            val purchaseItems = mutableListOf<PurchaseItem>()
            for (line in lines) {
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

            if (paidAmount > 0) {
                db.cashTransactionDao().insert(
                    CashTransaction(
                        type = "OUT",
                        method = method.lowercase(),
                        amount = paidAmount,
                        reason = "Purchase",
                        reference = billNo
                    )
                )
            }

            Toast.makeText(this@PurchaseActivity, "Purchase saved: $billNo", Toast.LENGTH_LONG).show()
            lines.clear()
            itemsContainer.removeAllViews()
            grandTotalText.text = "Grand Total: Rs 0.00"
            purchaseDateMillis = System.currentTimeMillis()
            dateButton.text = formatDate(purchaseDateMillis)
            partyName.text.clear()
            paidInput.text.clear()
        }
    }
}
