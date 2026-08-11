package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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

    private lateinit var saleDateText: TextView
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

    private val holdPrefsName = "sale_hold_data"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildScreen()
        loadCustomers()
        loadProducts()
        updateTotals()
    }

    // =========================================================
    // MAIN SCREEN
    // =========================================================

    private fun buildScreen() {

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 249, 251))
        }

        // -----------------------------------------------------
        // TOOLBAR
        // -----------------------------------------------------

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.WHITE)
            elevation = dp(3).toFloat()
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 42f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(30, 30, 30))

            setOnClickListener {
                finish()
            }
        }

        toolbar.addView(
            back,
            LinearLayout.LayoutParams(dp(48), dp(56))
        )

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleBox.addView(
            TextView(this).apply {
                text = "Sale"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(25, 25, 25))
            }
        )

        titleBox.addView(
            TextView(this).apply {
                text = "Create new sales invoice"
                textSize = 12f
                setTextColor(Color.GRAY)
            }
        )

        toolbar.addView(
            titleBox,
            LinearLayout.LayoutParams(
                0,
                dp(56),
                1f
            )
        )

        val recallTop = TextView(this).apply {
            text = "↻"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(20, 120, 205))

            setOnClickListener {
                showRecallDialog()
            }
        }

        toolbar.addView(
            recallTop,
            LinearLayout.LayoutParams(dp(52), dp(56))
        )

        main.addView(toolbar)

        // -----------------------------------------------------
        // SCROLL
        // -----------------------------------------------------

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(14),
                dp(12),
                dp(14),
                dp(100)
            )
        }

        // =====================================================
        // CREDIT / CASH
        // =====================================================

        val modeCard = card()

        modeCard.addView(
            sectionTitle("Sale Payment")
        )

        val modeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(
                dp(3),
                dp(3),
                dp(3),
                dp(3)
            )
            background = rounded(
                Color.rgb(235, 238, 242),
                30
            )
        }

        creditButton = modeButton("Credit").apply {
            setOnClickListener {
                setSaleMode("Credit")
            }
        }

        cashButton = modeButton("Cash").apply {
            setOnClickListener {
                setSaleMode("Cash")
            }
        }

        modeLayout.addView(
            creditButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )

        modeLayout.addView(
            cashButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )

        modeCard.addView(modeLayout)

        content.addView(modeCard)

        setSaleMode("Credit")

        // =====================================================
        // SALE INFORMATION
        // =====================================================

        val saleInfoCard = card()

        saleInfoCard.addView(
            sectionTitle("Sale Information")
        )

        saleDateText = TextView(this).apply {
            text = "Date\n${todayDate()}"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(
                dp(14),
                dp(12),
                dp(14),
                dp(12)
            )
            background = fieldBackground()

            setOnClickListener {
                showSaleDatePicker()
            }
        }

        saleInfoCard.addView(saleDateText)

        val firm = TextView(this).apply {
            text = "Firm Name\nIbtesaam Traders"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(
                dp(14),
                dp(12),
                dp(14),
                dp(12)
            )
            background = fieldBackground()
        }

        saleInfoCard.addView(
            firm,
            marginTopParams(10)
        )

        content.addView(saleInfoCard)

        // =====================================================
        // SALE TYPE
        // =====================================================

        val saleTypeCard = card()

        saleTypeCard.addView(
            sectionTitle("Sale Type")
        )

        saleTypeSpinner = Spinner(this).apply {

            adapter = ArrayAdapter(
                this@SaleActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Retail",
                    "Wholesale"
                )
            )

            onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {

                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        if (itemName.text.toString().trim().isNotEmpty()) {
                            fillPriceFromProduct()
                        }
                    }

                    override fun onNothingSelected(
                        parent: AdapterView<*>?
                    ) {
                    }
                }
        }

        saleTypeCard.addView(
            saleTypeSpinner,
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            )
        )

        content.addView(saleTypeCard)

        // =====================================================
        // CUSTOMER
        // =====================================================

        val customerCard = card()

        customerCard.addView(
            sectionTitle("Customer")
        )

        customerInput = AutoCompleteTextView(this).apply {
            hint = "Customer name"
            textSize = 16f
            singleLine = true
            setPadding(
                dp(14),
                0,
                dp(14),
                0
            )
            background = fieldBackground()
        }

        customerCard.addView(
            customerInput,
            LinearLayout.LayoutParams(
                -1,
                dp(56)
            )
        )

        val addCustomer = TextView(this).apply {
            text = "+  Add New Customer"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(
                Color.rgb(20, 120, 205)
            )
            setPadding(
                dp(4),
                dp(14),
                0,
                dp(4)
            )

            setOnClickListener {
                promptAddCustomer()
            }
        }

        customerCard.addView(addCustomer)

        content.addView(customerCard)

        // =====================================================
        // PURCHASE ORDER
        // =====================================================

        val poCard = card()

        poCard.addView(
            sectionTitle("Purchase Order")
        )

        val poRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        poDateInput = TextView(this).apply {
            text = "PO Date"
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(14),
                0,
                dp(10),
                0
            )
            background = fieldBackground()

            setOnClickListener {
                showDatePicker()
            }
        }

        poNumberInput = EditText(this).apply {
            hint = "PO Number"
            textSize = 15f
            singleLine = true
            setPadding(
                dp(14),
                0,
                dp(14),
                0
            )
            background = fieldBackground()
        }

        poRow.addView(
            poDateInput,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {
                marginEnd = dp(5)
            }
        )

        poRow.addView(
            poNumberInput,
            LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {
                marginStart = dp(5)
            }
        )

        poCard.addView(poRow)

        content.addView(poCard)

        // =====================================================
        // ADD ITEMS
        // =====================================================

        val addItemCard = card()

        addItemCard.addView(
            sectionTitle("Add Items")
        )

        itemName = AutoCompleteTextView(this).apply {
            hint = "Item Name"
            textSize = 16f
            singleLine = true
            setPadding(
                dp(14),
                0,
                dp(14),
                0
            )
            background = fieldBackground()
        }

        addItemCard.addView(
            itemName,
            LinearLayout.LayoutParams(
                -1,
                dp(56)
            )
        )

        val qtyUnitRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        qty = EditText(this).apply {
            hint = "Quantity"
            textSize = 16f
            singleLine = true

            inputType =
                InputType.TYPE_CLASS_NUMBER

            setPadding(
                dp(14),
                0,
                dp(14),
                0
            )

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
            LinearLayout.LayoutParams(
                0,
                dp(56),
                1f
            ).apply {
                marginEnd = dp(5)
            }
        )

        qtyUnitRow.addView(
            unitSpinner,
            LinearLayout.LayoutParams(
                0,
                dp(56),
                1f
            ).apply {
                marginStart = dp(5)
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

            setPadding(
                dp(14),
                0,
                dp(14),
                0
            )

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
            background =
                rounded(
                    Color.rgb(25, 130, 210),
                    12
                )

            setPadding(
                0,
                dp(14),
                0,
                dp(14)
            )

            setOnClickListener {
                addItem()
            }
        }

        addItemCard.addView(
            addButton,
            marginTopParams(12)
        )

        content.addView(addItemCard)

        // =====================================================
        // SALE ITEMS
        // =====================================================

        val itemsCard = card()

        itemsCard.addView(
            sectionTitle("Sale Items")
        )

        itemsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        itemsCard.addView(itemsContainer)

        content.addView(itemsCard)

        // =====================================================
        // TOTAL
        // =====================================================

        val totalCard = card()

        subtotalText = TextView(this).apply {
            text = "Subtotal                 Rs 0.00"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
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

            setPadding(
                dp(14),
                0,
                dp(14),
                0
            )

            background = fieldBackground()
        }

        totalCard.addView(
            discountInput,
            marginTopParams(10)
        )

        totalText = TextView(this).apply {
            text = "TOTAL                   Rs 0.00"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(20, 20, 20))

            setPadding(
                0,
                dp(18),
                0,
                dp(5)
            )
        }

        totalCard.addView(totalText)

        content.addView(totalCard)

        discountInput.addTextChangedListener(
            simpleWatcher {
                updateTotals()
            }
        )

        // =====================================================
        // PAYMENT
        // =====================================================

        val paymentCard = card()

        paymentCard.addView(
            sectionTitle("Payment")
        )

        paymentMethodSpinner = Spinner(this).apply {

            adapter = ArrayAdapter(
                this@SaleActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Cash",
                    "Bank"
                )
            )
        }

        paymentCard.addView(
            labeled(
                "Payment Method",
                paymentMethodSpinner
            )
        )

        paidInput = EditText(this).apply {
            hint = "Amount Paid"
            textSize = 16f
            singleLine = true

            inputType =
                InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL

            setPadding(
                dp(14),
                0,
                dp(14),
                0
            )

            background = fieldBackground()
        }

        paymentCard.addView(
            paidInput,
            marginTopParams(10)
        )

        content.addView(paymentCard)

        // =====================================================
        // COMPLETE SCROLL
        // =====================================================

        scroll.addView(content)

        main.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        // =====================================================
        // BOTTOM BAR
        // =====================================================

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                dp(7),
                dp(7),
                dp(7),
                dp(7)
            )
            setBackgroundColor(Color.WHITE)
            elevation = dp(10).toFloat()
        }

        val holdButton = bottomButton(
            "⏸ Hold",
            Color.rgb(100, 105, 115)
        ) {
            holdSale()
        }

   
