package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    val unitPrice: Double,
    val cost: Double,
    val amount: Double
)

class SaleActivity : AppCompatActivity() {

    private lateinit var customerInput: AutoCompleteTextView
    private lateinit var saleTypeSpinner: Spinner

    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var unitPrice: EditText

    private lateinit var itemsContainer: LinearLayout
    private lateinit var subtotalText: TextView
    private lateinit var discountInput: EditText
    private lateinit var totalText: TextView
    private lateinit var paidInput: EditText
    private lateinit var paymentMethodSpinner: Spinner

    private lateinit var creditButton: TextView
    private lateinit var cashButton: TextView

    private lateinit var poDateInput: TextView
    private lateinit var poNumberInput: EditText

    private var customers = listOf<Customer>()
    private var products = listOf<Product>()
    private val lines = mutableListOf<SaleLine>()

    private var saleMode = "Credit"

    private val units = listOf(
        "Piece",
        "Box",
        "Pack",
        "Kg",
        "Gram",
        "Liter",
        "Dozen",
        "Bag"
    )

    private val dateFormat =
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildScreen()

        loadCustomers()
        loadProducts()

        updateTotals()
    }

    // ---------------------------------------------------------
    // MAIN SCREEN
    // ---------------------------------------------------------

    private fun buildScreen() {

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 249, 251))
        }

        // ---------------- TOP BAR ----------------

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.WHITE)
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 42f
            setTextColor(Color.rgb(30, 30, 30))
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }

        toolbar.addView(
            back,
            LinearLayout.LayoutParams(dp(48), dp(56))
        )

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleBox.addView(TextView(this).apply {
            text = "Sale"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(25, 25, 25))
        })

        titleBox.addView(TextView(this).apply {
            text = "Create new sales invoice"
            textSize = 12f
            setTextColor(Color.GRAY)
        })

        toolbar.addView(
            titleBox,
            LinearLayout.LayoutParams(0, dp(56), 1f)
        )

        val recallTop = TextView(this).apply {
            text = "↻"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(30, 120, 200))
            setOnClickListener { showRecallDialog() }
        }

        toolbar.addView(
            recallTop,
            LinearLayout.LayoutParams(dp(48), dp(56))
        )

        main.addView(toolbar)

        // ---------------- SCROLL CONTENT ----------------

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(100))
        }

        // ---------------- CREDIT / CASH ----------------

        val modeCard = card()

        val modeTitle = TextView(this).apply {
            text = "Sale Payment"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.DKGRAY)
        }

        modeCard.addView(
            modeTitle,
            LinearLayout.LayoutParams(
                -1,
                dp(30)
            )
        )

        val modeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = rounded(
                Color.rgb(235, 238, 242),
                30
            )
        }

        creditButton = modeButton("Credit")
        cashButton = modeButton("Cash")

        modeLayout.addView(
            creditButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        modeLayout.addView(
            cashButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        modeCard.addView(modeLayout)

        content.addView(modeCard)

        setSaleMode("Credit")

        // ---------------- DATE ----------------

        val dateCard = card()

        dateCard.addView(sectionTitle("Sale Information"))

        val dateText = TextView(this).apply {
            text = "Date\n${dateFormat.format(Calendar.getInstance().time)}"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = fieldBackground()
        }

        dateCard.addView(dateText)

        // ---------------- FIRM NAME ----------------

        val firm = TextView(this).apply {
            text = "Firm Name\nIbtesaam Traders"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = fieldBackground()
        }

        dateCard.addView(
            firm,
            marginTopParams(10)
        )

        content.addView(dateCard)

        // ---------------- CUSTOMER ----------------

        val customerCard = card()

        customerCard.addView(sectionTitle("Customer"))

        customerInput = AutoCompleteTextView(this).apply {
            hint = "Customer name"
            textSize = 16f
            singleLine = true
            setPadding(dp(14), 0, dp(14), 0)
            background = fieldBackground()
        }

        customerCard.addView(customerInput)

        val addCustomer = TextView(this).apply {
            text = "+  Add New Customer"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(20, 120, 205))
            setPadding(dp(4), dp(14), 0, dp(4))
            setOnClickListener {
                promptAddCustomer()
            }
        }

        customerCard.addView(addCustomer)

        content.addView(customerCard)

        // ---------------- PO INFORMATION ----------------

        val poCard = card()

        poCard.addView(sectionTitle("Purchase Order"))

        val poRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        poDateInput = TextView(this).apply {
            text = "PO Date"
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
            background = fieldBackground()
            setOnClickListener {
                showDatePicker()
            }
        }

        poNumberInput = EditText(this).apply {
            hint = "PO Number"
            textSize = 15f
            singleLine = true
            setPadding(dp(14), 0, dp(14), 0)
            background = fieldBackground()
        }

        poRow.addView(
            poDateInput,
            LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                marginEnd = dp(6)
            }
        )

        poRow.addView(
            poNumberInput,
            LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                marginStart = dp(6)
            }
        )

        poCard.addView(poRow)

        content.addView(poCard)

        // ---------------- ADD ITEM CARD ----------------

        val addItemCard = card()

        addItemCard.addView(sectionTitle("Add Items"))

        itemName = AutoCompleteTextView(this).apply {
            hint = "Item Name"
            textSize = 16f
            singleLine = true
            setPadding(dp(14), 0, dp(14), 0)
            background = fieldBackground()
        }

        addItemCard.addView(itemName)

        val qtyUnitRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        qty = EditText(this).apply {
            hint = "Quantity"
            textSize = 16f
            singleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(dp(14), 0, dp(14), 0)
            background = fieldBackground()
        }

        unitSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SaleActivity,
                android.R.layout.simple_spinner_dropdown_item,
                units
            )
        }

        qtyUnitRow.addView(
            qty,
            LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(6)
            }
        )

        qtyUnitRow.addView(
            unitSpinner,
            LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginStart = dp(6)
            }
        )

        addItemCard.addView(
            qtyUnitRow,
            marginTopParams(10)
        )

        unitPrice = EditText(this).apply {
            hint = "Rate / Price per Unit"
            textSize = 16f
            singleLine = true
            inputType =
                InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(14), 0, dp(14), 0)
            background = fieldBackground()
        }

        addItemCard.addView(
            unitPrice,
            marginTopParams(10)
        )

        itemName.setOnItemClickListener { _, _, _, _ ->
            fillPriceFromProduct()
        }

        val addButton = TextView(this).apply {
            text = "＋  ADD ITEM"
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(25, 130, 210), 12)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                addItem()
            }
        }

        addItemCard.addView(
            addButton,
            marginTopParams(12)
        )

        content.addView(addItemCard)

        // ---------------- ITEMS ----------------

        val itemsCard = card()

        itemsCard.addView(sectionTitle("Sale Items"))

        itemsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        itemsCard.addView(itemsContainer)

        content.addView(itemsCard)

        // ---------------- TOTAL ----------------

        val totalCard = card()

        subtotalText = TextView(this).apply {
            text = "Subtotal                         Rs 0.00"
            textSize = 16f
            setTextColor(Color.DKGRAY)
        }

        totalCard.addView(subtotalText)

        discountInput = EditText(this).apply {
            hint = "Discount (Rs)"
            textSize = 15f
            singleLine = true
            inputType =
                InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(14), 0, dp(14), 0)
            background = fieldBackground()
        }

        totalCard.addView(
            discountInput,
            marginTopParams(10)
        )

        totalText = TextView(this).apply {
            text = "TOTAL                         Rs 0.00"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(20, 20, 20))
            setPadding(0, dp(18), 0, dp(5))
        }

        totalCard.addView(totalText)

        content.addView(totalCard)

        discountInput.addTextChangedListener(
            simpleWatcher {
                updateTotals()
            }
        )

        // ---------------- PAYMENT ----------------

        val paymentCard = card()

        paymentCard.addView(sectionTitle("Payment"))

        paymentMethodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SaleActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Cash", "Bank")
            )
        }

        paymentCard.addView(
            labeled("Payment Method", paymentMethodSpinner)
        )

        paidInput = EditText(this).apply {
            hint = "Amount Paid"
            textSize = 16f
            singleLine = true
            inputType =
                InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(14), 0, dp(14), 0)
            background = fieldBackground()
        }

        paymentCard.addView(
            paidInput,
            marginTopParams(10)
        )

        content.addView(paymentCard)

        scroll.addView(content)
        main.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        // ---------------- BOTTOM BAR ----------------

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
            elevation = dp(10).toFloat()
        }

        val holdButton = bottomButton(
            "⏸ Hold",
            Color.rgb(100, 105, 115)
        ) {
            holdSale()
        }

        val saveNewButton = bottomButton(
            "Save & New",
            Color.rgb(90, 95, 105)
        ) {
            saveSale(true)
        }

        val saveButton = bottomButton(
            "SAVE",
            Color.rgb(20, 125, 220)
        ) {
            saveSale(false)
        }

        bottom.addView(
            holdButton,
            LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(4)
            }
        )

        bottom.addView(
            saveNewButton,
            LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        )

        bottom.addView(
            saveButton,
            LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginStart = dp(4)
            }
        )

        main.addView(bottom)

        setContentView(main)
    }

    // ---------------------------------------------------------
    // SALE MODE
    // ---------------------------------------------------------

    private fun modeButton(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(10))
        }
    }

    private fun setSaleMode(mode: String) {

        saleMode = mode

        if (mode == "Credit") {

            creditButton.setTextColor(Color.WHITE)
            creditButton.background =
                rounded(Color.rgb(20, 175, 115), 30)

            cashButton.setTextColor(Color.DKGRAY)
            cashButton.background =
                rounded(Color.TRANSPARENT, 30)

        } else {

            cashButton.setTextColor(Color.WHITE)
            cashButton.background =
                rounded(Color.rgb(20, 150, 215), 30)

            creditButton.setTextColor(Color.DKGRAY)
            creditButton.background =
                rounded(Color.TRANSPARENT, 30)
        }
    }

    // ---------------------------------------------------------
    // LOAD CUSTOMERS
    // ---------------------------------------------------------

    private fun loadCustomers() {

        lifecycleScope.launch {

            PosDatabase.get(this@SaleActivity)
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

                    if (customerInput.text.isEmpty()) {
                        customerInput.setText(
                            "Walk-in",
                            false
                        )
                    }
                }
        }
    }

    // ---------------------------------------------------------
    // LOAD PRODUCTS
    // ---------------------------------------------------------

    private fun loadProducts() {

        lifecycleScope.launch {

            PosDatabase.get(this@SaleActivity)
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

    // ---------------------------------------------------------
    // AUTO PRICE
    // ---------------------------------------------------------

    private fun fillPriceFromProduct() {

        val name = itemName.text.toString().trim()

        val product = products.find {
            it.name.equals(name, ignoreCase = true)
        }

        if (product != null) {

            val price =
                if (
                    saleTypeSpinner.selectedItem
                        ?.toString() == "Wholesale"
                ) {
                    product.wholesalePrice
                } else {
                    product.salePrice
                }

            unitPrice.setText(
                String.format(Locale.getDefault(), "%.2f", price)
            )
        }
    }

    // ---------------------------------------------------------
    // ADD ITEM
    // ---------------------------------------------------------

    private fun addItem() {

        val name = itemName.text.toString().trim()

        val quantity =
            qty.text.toString().toIntOrNull() ?: 0

        val price =
            unitPrice.text.toString().toDoubleOrNull() ?: 0.0

        val product = products.find {
            it.name.equals(name, ignoreCase = true)
        }

        if (product == null) {
            toast("Ye item product list mein nahi hai")
            return
        }

        if (quantity <= 0) {
            toast("Quantity theek se likhe
