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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    private val bg = "#F3F4F9"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#1A1D2E"
    private val textMuted = "#8A8FA3"
    private val orange = "#EF6C00"
    private val blue = "#1565C0"
    private val green = "#2E7D32"
    private val border = "#E6E8F0"

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
            setPadding(4, 0, 4, 20)
            addView(TextView(this@PurchaseActivity).apply {
                text = if (editBillNo != null) "Edit Purchase" else "Purchase"
                textSize = 20f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@PurchaseActivity).apply {
                text = "History"
                textSize = 12.5f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(24, 12, 24, 12)
                background = roundedBg(blue, 30)
                setOnClickListener {
                    startActivity(Intent(this@PurchaseActivity, PurchaseHistoryActivity::class.java))
                }
            })
        })

        // ================= DATE =================
        val dateBox = premiumCard()
        dateBox.setOnClickListener { openDatePicker() }
        dateBox.addView(TextView(this).apply { text = "Date"; textSize = 12f; setTextColor(Color.parseColor(textMuted)) })
        val dateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dateValueText = TextView(this).apply {
            text = formatDate(purchaseDateMillis)
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 4, 0, 0)
        }
        dateRow.addView(dateValueText)
        dateRow.addView(TextView(this).apply { text = "▾"; textSize = 15f; setTextColor(Color.parseColor(blue)) })
        dateBox.addView(dateRow)
        root.addView(dateBox)

        // ================= FIRM NAME =================
        val firmBox = premiumCard().apply { setPadding(22, 14, 22, 14) }
        val firmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        firmRow.addView(TextView(this).apply { text = "Firm Name:  "; textSize = 13f; setTextColor(Color.parseColor(textMuted)) })
        firmRow.addView(TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 14f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        firmRow.addView(TextView(this).apply { text = "▾"; textSize = 15f; setTextColor(Color.parseColor(textMuted)) })
        firmBox.addView(firmRow)
        root.addView(firmBox)

        // ================= PARTY NAME =================
        val partyBox = premiumCard()
        val partyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        partyName = AutoCompleteTextView(this).apply {
            hint = "Party Name (Supplier) *"
            background = null
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        partyRow.addView(partyName)
        partyRow.addView(TextView(this).apply {
            text = "+"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            elevation = 4f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#42A5F5"), Color.parseColor(blue))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
            width = (32 * resources.displayMetrics.density).toInt()
            height = (32 * resources.displayMetrics.density).toInt()
            setOnClickListener { promptAddSupplier() }
        })
        partyBox.addView(partyRow)
        root.addView(partyBox)
        root.addView(spacer(4))

        // ---- Cash / Credit ----
        val cashCreditToggle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cashBtn = Button(this).apply {
            text = "CASH"; textSize = 11f; setTextColor(Color.WHITE)
            background = roundedBg(orange, 20)
            setPadding(24, 10, 24, 10); minWidth = 0; minHeight = 0
            setOnClickListener { setPurchaseMode(true) }
        }
        creditBtn = Button(this).apply {
            text = "CREDIT"; textSize = 11f; setTextColor(Color.parseColor(textMuted))
            background = roundedBg("#EEEEEE", 20)
            setPadding(24, 10, 24, 10); minWidth = 0; minHeight = 0
            setOnClickListener { setPurchaseMode(false) }
        }
        cashCreditToggle.addView(cashBtn)
        cashCreditToggle.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(8, 1) })
        cashCreditToggle.addView(creditBtn)
        root.addView(cashCreditToggle)
        root.addView(spacer(16))

        // ================= "Add Items (Optional)" trigger =================
        val addItemsBox = premiumCard()
        val addItemsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addItemsRow.addView(TextView(this).apply {
            text = "+"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            elevation = 4f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#42A5F5"), Color.parseColor(blue))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
            width = (30 * resources.displayMetrics.density).toInt()
            height = (30 * resources.displayMetrics.density).toInt()
        })
        addItemsTrigger = TextView(this).apply {
            text = "  Add Items (Optional)"
            textSize = 14f
            setTextColor(Color.parseColor(blue))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addItemsRow.addView(addItemsTrigger)
        addItemsBox.addView(addItemsRow)
        addItemsBox.setOnClickListener { toggleItemEntry() }
        root.addView(addItemsBox)

        // ================= ITEM ENTRY (collapsible) =================
        itemEntrySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(22, 20, 22, 20)
            background = roundedBg(cardWhite, 22)
            elevation = 4f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 12) }
        }

        val itemBox = innerField()
        itemName = AutoCompleteTextView(this).apply { hint = "Item Name"; background = null; textSize = 15f }
        itemBox.addView(itemName)
        itemEntrySection.addView(itemBox)

        unitToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        itemEntrySection.addView(unitToggleRow)
        itemEntrySection.addView(spacer(10))

        val qtyUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qtyBox = innerField().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) }
        }
        qty = EditText(this).apply {
            hint = "Quantity"
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        qtyBox.addView(qty)
        val unitBox = innerField().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
        }
        unitSpinner = Spinner(this)
        unitBox.addView(unitSpinner)
        qtyUnitRow.addView(qtyBox)
        qtyUnitRow.addView(unitBox)
        itemEntrySection.addView(qtyUnitRow)
        itemEntrySection.addView(spacer(10))

        val rateBox = innerField()
        rate = EditText(this).apply {
            hint = "Rate (Price/Unit)"
            background = null
            textSize = 15f
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
            setTextColor(Color.parseColor(green))
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
            setPadding(0, 20, 0, 20)
            setOnClickListener { addItem() }
        })
        root.addView(itemEntrySection)

        // ================= ITEMS LIST =================
        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        // ================= GRAND TOTAL =================
        val totalCard = premiumCard().apply {
            setPadding(24, 18, 24, 18)
            background = strokedBg(green, cardWhite, 18)
        }
        val totalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        totalRow.addView(TextView(this).apply {
            text = "Total Amount"
            textSize = 15f
            setTextColor(Color.parseColor(textMuted))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        grandTotalText = TextView(this).apply {
            text = "Rs 0.00"
            textSize = 18f
            setTextColor(Color.parseColor(green))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        totalRow.addView(grandTotalText)
        totalCard.addView(totalRow)
        root.addView(totalCard)

        // ================= PAYMENT =================
        paymentSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val payRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val methodBox = premiumCard().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) }
            setPadding(16, 4, 16, 4)
        }
        paymentMethodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cash", "Bank"))
        }
        methodBox.addView(paymentMethodSpinner)
        val paidBox = premiumCard().apply {
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

        // ================= SAVE (fixed bottom bar) =================
        saveButton = Button(this).apply {
            text = if (editBillNo != null) "UPDATE PURCHASE" else "SAVE PURCHASE"
            setTextColor(Color.WHITE)
            textSize = 15f
            background = roundedBg(green, 16)
            setPadding(0, 24, 0, 24)
            setOnClickListener { savePurchase() }
        }

        val scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(root)
        }

        val saveBar = LinearLayout(this).apply {
            setPadding(24, 14, 24, 18)
            setBackgroundColor(Color.parseColor(cardWhite))
            elevation = 10f
            addView(saveButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(saveBar) { view, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 18 + sysBars.bottom)
            insets
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
    private fun premiumCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 16, 22, 16)
        background = strokedBg(border, cardWhite, 18)
        elevation = 3f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 14) }
    }

    private fun outlinedBox() = premiumCard()

    private fun innerField() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 12, 18, 12)
        background = strokedBg(border, cardWhite, 14)
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
    
