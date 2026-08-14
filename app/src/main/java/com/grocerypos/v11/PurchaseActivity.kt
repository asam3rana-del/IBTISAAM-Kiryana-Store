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

    // ---- Palette matched to the Dashboard ----
    private val bg = "#F4F3FB"
    private val gradientStart = "#3949AB"
    private val gradientEnd = "#5C6BC0"
    private val orange = "#EF6C00"
    private val blue = "#1565C0"
    private val green = "#2E7D32"
    private val cardWhite = "#FFFFFF"
    private val labelGray = "#9E9E9E"

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

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= GRADIENT HEADER =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 40, 24, 32)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(gradientStart), Color.parseColor(gradientEnd))
            )
        }
        header.addView(TextView(this).apply {
            text = "\uD83E\uDDFE"
            textSize = 22f
            gravity = Gravity.CENTER
            background = ovalBg(cardWhite)
            width = (48 * resources.displayMetrics.density).toInt()
            height = (48 * resources.displayMetrics.density).toInt()
        })
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerText.addView(TextView(this).apply {
            text = if (editBillNo != null) "Edit Purchase" else "New Purchase"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headerText.addView(TextView(this).apply {
            text = editBillNo ?: "Fill in the details below"
            textSize = 12f
            setTextColor(Color.parseColor("#D5D8F5"))
        })
        header.addView(headerText)
        header.addView(TextView(this).apply {
            text = "History"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(28, 14, 28, 14)
            background = roundedBg("#5058B5", 30)
            setOnClickListener {
                startActivity(Intent(this@PurchaseActivity, PurchaseHistoryActivity::class.java))
            }
        })
        outer.addView(header)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 28)
        }

        // ================= DATE (elevated card, left aligned) =================
        val dateBox = premiumCard()
        dateBox.setOnClickListener { openDatePicker() }
        dateBox.addView(cardLabel("Date", blue, "\uD83D\uDCC5"))
        val dateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dateValueText = TextView(this).apply {
            text = formatDate(purchaseDateMillis)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(4, 6, 0, 0)
        }
        dateRow.addView(dateValueText)
        dateRow.addView(TextView(this).apply { text = "\u25BE"; textSize = 15f; setTextColor(Color.parseColor(blue)) })
        dateBox.addView(dateRow)
        root.addView(dateBox)
        root.addView(spacer(0))

        // ================= FIRM NAME (static display row) =================
        val firmBox = premiumCard().apply { setPadding(22, 14, 22, 14) }
        val firmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        firmRow.addView(TextView(this).apply {
            text = "\uD83C\uDFEC  "
            textSize = 16f
        })
        firmRow.addView(TextView(this).apply {
            // TODO: replace with your actual store/firm name, or bind to a firms table if you support more than one
            text = "IBTISAAM Kiryana Store"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        firmRow.addView(TextView(this).apply { text = "\u25BE"; textSize = 15f; setTextColor(Color.parseColor(labelGray)) })
        firmBox.addView(firmRow)
        root.addView(firmBox)
        root.addView(spacer(16))

        // ================= PARTY NAME (elevated card, + inline) =================
        val partyBox = premiumCard()
        partyBox.addView(cardLabel("Party (Supplier)", green, "\uD83D\uDC64"))
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
            background = ovalBg(blue)
            width = (30 * resources.displayMetrics.density).toInt()
            height = (30 * resources.displayMetrics.density).toInt()
            setOnClickListener { promptAddSupplier() }
        })
        partyBox.addView(partyRow)
        root.addView(partyBox)
        root.addView(spacer(12))

        // ---- Cash / Credit ----
        val cashCreditToggle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cashBtn = Button(this).apply {
            text = "CASH"; textSize = 11f; setTextColor(Color.WHITE)
            background = roundedBg(orange, 20)
            setPadding(20, 6, 20, 6); minWidth = 0; minHeight = 0
            setOnClickListener { setPurchaseMode(true) }
        }
        creditBtn = Button(this).apply {
            text = "CREDIT"; textSize = 11f; setTextColor(Color.parseColor(labelGray))
            background = roundedBg("#EEEEEE", 20)
            setPadding(20, 6, 20, 6); minWidth = 0; minHeight = 0
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
            background = ovalBg(orange)
            width = (30 * resources.displayMetrics.density).toInt()
            height = (30 * resources.displayMetrics.density).toInt()
        })
        addItemsTrigger = TextView(this).apply {
            text = "  Add Items (Optional)"
            textSize = 14f
            setTextColor(Color.parseColor(orange))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addItemsRow.addView(addItemsTrigger)
        addItemsBox.addView(addItemsRow)
        addItemsBox.setOnClickListener { toggleItemEntry() }
        root.addView(addItemsBox)
        root.addView(spacer(14))

        // ================= ITEM ENTRY (collapsible, its own card) =================
        itemEntrySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(20, 20, 20, 20)
            background = elevatedCardBg()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
        }

        val itemBox = innerField()
        itemName = AutoCompleteTextView(this).apply { hint = "Item Name"; background = null; textSize = 15f }
        itemBox.addView(itemName)
        itemEntrySection.addView(itemBox)

        // Unit / Secondary Unit toggle — shows which unit this line is being
        // purchased in as soon as a known product is picked.
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
            setOnClickListener { addItem() }
        })
        root.addView(itemEntrySection)
        root.addView(spacer(14))

        // ================= ITEMS LIST =================
        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        // ================= GRAND TOTAL (its own accent card) =================
        val totalCard = premiumCard().apply {
            setPadding(24, 18, 24, 18)
            background = strokedBg(green, cardWhite, 12)
        }
        val totalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        totalRow.addView(TextView(this).apply {
            text = "Total Amount"
            textSize = 15f
            setTextColor(Color.parseColor("#555555"))
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
        root.addView(spacer(14))

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
        root.addView(spacer(10))

        // ================= SAVE (fixed bottom bar) =================
        saveButton = Button(this).apply {
            text = if (editBillNo != null) "UPDATE PURCHASE" else "SAVE PURCHASE"
            setTextColor(Color.WHITE)
            textSize = 15f
            background = roundedBg(green, 14)
            setPadding(0, 22, 0, 22)
            setOnClickListener { savePurchase() }
        }

        val scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(root)
        }

        val saveBar = LinearLayout(this).apply {
            setPadding(24, 10, 24, 18)
            setBackgroundColor(Color.parseColor(cardWhite))
            elevation = 12f
            addView(saveButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(saveBar) { view, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 18 + sysBars.bottom)
            insets
        }

        outer.addView(scrollArea)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(outer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
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

    /** Elevated white card — replaces the old flat outlined box everywhere. */
    private fun premiumCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 16, 22, 16)
        background = elevatedCardBg()
        elevation = 4f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) }
    }

    /** Kept for backward compatibility with any code still calling the old name. */
    private fun outlinedBox() = premiumCard()

    /** A lighter-weight field wrapper used inside the item-entry card. */
    private fun innerField() = LinearLayout(this).apply {
        orientation = Linear
