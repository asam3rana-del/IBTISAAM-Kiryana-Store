package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
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

    private lateinit var customerInput: AutoCompleteTextView
    private lateinit var saleTypeSpinner: Spinner
    private lateinit var paymentTypeSpinner: Spinner

    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var qtyUnitSpinner: Spinner
    private lateinit var unitPrice: EditText

    private lateinit var itemsContainer: LinearLayout
    private lateinit var subtotalText: TextView
    private lateinit var discountInput: EditText
    private lateinit var totalText: TextView
    private lateinit var paidInput: EditText

    private var customers = listOf<Customer>()
    private var products = listOf<Product>()
    private val lines = mutableListOf<SaleLine>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val scroll = ScrollView(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 30)
        }

        // =========================
        // HEADER
        // =========================

        root.addView(TextView(this).apply {
            text = "NEW SALE"
            textSize = 25f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 18)
        })

        // =========================
        // CUSTOMER CARD
        // =========================

        val customerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 15, 18, 15)
        }

        customerBox.addView(TextView(this).apply {
            text = "CUSTOMER"
            textSize = 14f
        })

        val customerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        customerInput = AutoCompleteTextView(this).apply {
            hint = "Customer name / Walk-in"
            textSize = 16f
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }

        customerRow.addView(
            customerInput,
            LinearLayout.LayoutParams(0, 60, 1f)
        )

        val addCustomerButton = Button(this).apply {
            text = "+"
            textSize = 20f
            setOnClickListener {
                promptAddCustomer()
            }
        }

        customerRow.addView(
            addCustomerButton,
            LinearLayout.LayoutParams(65, 60)
        )

        customerBox.addView(customerRow)
        root.addView(customerBox)

        // =========================
        // CASH / CREDIT
        // =========================

        root.addView(TextView(this).apply {
            text = "Payment"
            textSize = 15f
            setPadding(0, 12, 0, 4)
        })

        paymentTypeSpinner = Spinner(this)

        paymentTypeSpinner.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Cash Sale", "Credit Sale")
            )

        root.addView(paymentTypeSpinner)

        // =========================
        // SALE TYPE
        // =========================

        root.addView(TextView(this).apply {
            text = "Sale Type"
            textSize = 15f
            setPadding(0, 12, 0, 4)
        })

        saleTypeSpinner = Spinner(this)

        saleTypeSpinner.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Retail", "Wholesale")
            )

        root.addView(saleTypeSpinner)

        // =========================
        // ADD ITEM TITLE
        // =========================

        root.addView(TextView(this).apply {
            text = "ADD ITEM"
            textSize = 20f
            setPadding(0, 25, 0, 10)
        })

        // =========================
        // ITEM NAME
        // =========================

        itemName = AutoCompleteTextView(this).apply {
            hint = "Search item name"
            textSize = 17f
            singleLine = true
        }

        root.addView(itemName)

        // =========================
        // QUANTITY ROW
        // =========================

        val quantityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        qty = EditText(this).apply {
            hint = "Quantity"
            inputType =
                InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
            singleLine = true
        }

        quantityRow.addView(
            qty,
            LinearLayout.LayoutParams(0, 60, 1f)
        )

        qtyUnitSpinner = Spinner(this)

        qtyUnitSpinner.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Unit", "Box")
            )

        quantityRow.addView(
            qtyUnitSpinner,
            LinearLayout.LayoutParams(150, 60)
        )

        root.addView(quantityRow)

        // =========================
        // RATE
        // =========================

        unitPrice = EditText(this).apply {
            hint = "Rate"
            inputType =
                InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
            singleLine = true
        }

        root.addView(unitPrice)

        // =========================
        // ADD ITEM BUTTON
        // =========================

        root.addView(Button(this).apply {
            text = "＋  ADD ITEM"
            textSize = 17f

            setOnClickListener {
                addItem()
            }
        })

        // =========================
        // BILL ITEMS
        // =========================

        root.addView(TextView(this).apply {
            text = "BILL ITEMS"
            textSize = 20f
            setPadding(0, 25, 0, 10)
        })

        itemsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(itemsContainer)

        // =========================
        // TOTALS
        // =========================

        subtotalText = TextView(this).apply {
            text = "Subtotal: Rs 0.00"
            textSize = 17f
            setPadding(0, 18, 0, 5)
        }

        root.addView(subtotalText)

        discountInput = EditText(this).apply {
            hint = "Discount (Rs)"
            inputType =
                InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
            singleLine = true
        }

        root.addView(discountInput)

        totalText = TextView(this).apply {
            text = "TOTAL: Rs 0.00"
            textSize = 22f
            setPadding(0, 10, 0, 15)
        }

        root.addView(totalText)

        discountInput.addTextChangedListener(
            simpleWatcher {
                updateTotals()
            }
        )

        // =========================
        // AMOUNT PAID
        // =========================

        paidInput = EditText(this).apply {
            hint = "Amount Paid"
            inputType =
                InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
            singleLine = true
        }

        root.addView(paidInput)

        // =========================
        // SAVE SALE
        // =========================

        root.addView(Button(this).apply {
            text = "SAVE SALE"
            textSize = 19f

            setOnClickListener {
                saveSale()
            }
        })

        scroll.addView(root)
        setContentView(scroll)

        loadCustomers()
        loadProducts()

        setupItemSearch()
    }

    // =====================================================
    // ITEM SEARCH
    // =====================================================

    private fun setupItemSearch() {

        itemName.setOnItemClickListener { _, _, position, _ ->

            val name = itemName.text.toString()

            val matches = products.filter {
                it.name.contains(name, ignoreCase = true)
            }

            if (position < matches.size) {

                val product = matches[position]

                val wholesale =
                    saleTypeSpinner.selectedItem?.toString() == "Wholesale"

                val price =
                    if (wholesale)
                        product.wholesalePrice
                    else
                        product.salePrice

                unitPrice.setText(price.toString())

                qty.requestFocus()
            }
        }
    }

    // =====================================================
    // LOAD CUSTOMERS
    // =====================================================

    private fun loadCustomers() {

        lifecycleScope.launch {

            PosDatabase
                .get(this@SaleActivity)
                .customerDao()
                .all()
                .collectLatest { list ->

                    customers = list

                    val names =
                        listOf("Walk-in") + list.map { it.name }

                    customerInput.setAdapter(
                        ArrayAdapter(
                            this@SaleActivity,
                            android.R.layout.simple_dropdown_item_1line,
                            names
                        )
                    )

                    customerInput.setText("Walk-in", false)
                }
        }
    }

    // =====================================================
    // LOAD PRODUCTS
    // =====================================================

    private fun loadProducts() {

        lifecycleScope.launch {

            PosDatabase
                .get(this@SaleActivity)
                .productDao()
                .all()
                .collectLatest { list ->

                    products = list

                    itemName.setAdapter(
                        ArrayAdapter(
                            this@SaleActivity,
                            android.R.layout.simple_dropdown_item_1line,
                            list.map { it.name }
                        )
                    )
                }
        }
    }

    // =====================================================
    // ADD CUSTOMER
    // =====================================================

    private fun promptAddCustomer() {

        val input = EditText(this)

        input.hint = "Customer name"

        AlertDialog.Builder(this)
            .setTitle("New Customer")
            .setView(input)
            .setPositiveButton("ADD") { _, _ ->

                val name =
                    input.text.toString().trim()

                if (name.isNotEmpty()) {

                    lifecycleScope.launch {

                        PosDatabase
                            .get(this@SaleActivity)
                            .customerDao()
                            .insert(
                                Customer(name = name)
                            )

                        customerInput.setText(name, false)

                        Toast.makeText(
                            this@SaleActivity,
                            "Customer added",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    // =====================================================
    // ADD ITEM
    // =====================================================

    private fun addItem() {

        val name =
            itemName.text.toString().trim()

        val q =
            qty.text.toString().toDoubleOrNull()

        val price =
            unitPrice.text.toString().toDoubleOrNull()

        val product =
            products.find {
                it.name.equals(name, ignoreCase = true)
            }

        if (product == null) {

            Toast.makeText(
                this,
                "Item product list mein nahi hai",
                Toast.LENGTH_SHORT
            ).show()

            itemName.requestFocus()
            return
        }

        if (q == null || q <= 0) {

            Toast.makeText(
                this,
                "Quantity likhen",
                Toast.LENGTH_SHORT
            ).show()

            qty.requestFocus()
            return
        }

        if (price == null || price < 0) {

            Toast.makeText(
                this,
                "Rate theek se likhen",
                Toast.LENGTH_SHORT
            ).show()

            unitPrice.requestFocus()
            return
        }

        // Current database qty is Int,
        // therefore quantity is converted to Int here.
        val finalQty = q.toInt()

        if (finalQty <= 0) {

            Toast.makeText(
                this,
                "Quantity kam az kam 1 honi chahiye",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (product.stock < finalQty) {

            Toast.makeText(
                this,
                "Stock kam hai (available: ${product.stock})",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val amount =
            finalQty * price

        lines.add(
            SaleLine(
                barcode = product.barcode,
                itemName = product.name,
                qty = finalQty,
                unitPrice = price,
                cost = product.cost,
                amount = amount
            )
        )

        addBillLine(
            product.name,
            finalQty,
            price,
            amount
        )

        // IMPORTANT:
        // Customer, Sale Type aur Payment reset nahi honge.
        // Sirf next item ke fields clear honge.

        itemName.text.clear()
        qty.text.clear()
        unitPrice.text.clear()

        itemName.requestFocus()

        updateTotals()
    }

    // =====================================================
    // ADD BILL ROW
    // =====================================================

    private fun addBillLine(
        name: String,
        quantity: Int,
        price: Double,
        amount: Double
    ) {

        val row = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            setPadding(8, 12, 8, 12)
        }

        val text = TextView(this).apply {

            text =
                "$name\nQty: $quantity   Rate: Rs %.2f\nAmount: Rs %.2f"
                    .format(price, amount)

            textSize = 15f

            layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
        }

        val delete = Button(this).apply {

            text = "X"

            setOnClickListener {

                val index =
                    itemsContainer.indexOfChild(row)

                if (index >= 0 && index < lines.size) {

                    lines.removeAt(index)
                    itemsContainer.removeView(row)

                    updateTotals()
                }
            }
        }

        row.addView(text)
        row.addView(delete)

        itemsContainer.addView(row)
    }

    // =====================================================
    // TOTALS
    // =====================================================

    private fun updateTotals() {

        val subtotal =
            lines.sumOf { it.amount }

        val discount =
            discountInput.text
                .toString()
                .toDoubleOrNull()
                ?: 0.0

        val total =
            (subtotal - discount)
                .coerceAtLeast(0.0)

        subtotalText.text =
            "Subtotal: Rs %.2f".format(subtotal)

        totalText.text =
            "TOTAL: Rs %.2f".format(total)
    }

    // =====================================================
    // SAVE SALE
    // =====================================================

    private fun saveSale() {

        if (lines.isEmpty()) {

            Toast.makeText(
                this,
                "Kam az kam ek item add karen",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val subtotal =
            lines.sumOf { it.amount }

        val discount =
            discountInput.text
                .toString()
                .toDoubleOrNull()
                ?: 0.0

        val total =
            (subtotal - discount)
                .coerceAtLeast(0.0)

        val paymentType =
            paymentTypeSpinner
                .selectedItem
                ?.toString()
                ?: "Cash Sale"

        val isCredit =
            paymentType == "Credit Sale"

        val paid =
            if (isCredit) {
                paidInput.text
                    .toString()
                    .toDoubleOrNull()
                    ?: 0.0
            } else {
                paidInput.text
                    .toString()
                    .toDoubleOrNull()
                    ?: total
            }

        val method =
            "cash"

        val customerName =
            customerInput.text
                .toString()
                .trim()

        val customer =
            customers.find {
                it.name.equals(
                    customerName,
                    ignoreCase = true
                )
            }

        val saleType =
            if (
                saleTypeSpinner.selectedItem
                    ?.toString() == "Wholesale"
            )
                "wholesale"
            else
                "retail"

        val invoice =
            "INV" + System.currentTimeMillis()

        lifecycleScope.launch {

            val db =
                PosDatabase.get(this@SaleActivity)

            // -----------------------------
            // SAVE SALE
            // -----------------------------

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
                    saleType = saleType
                )
            )

            // -----------------------------
            // SALE ITEMS
            // -----------------------------

            val saleItems =
                lines.map {

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

            // -----------------------------
            //
