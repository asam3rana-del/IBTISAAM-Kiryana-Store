package com.grocerypos.v11.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
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
    val secondaryUnitQty: Double,
    val tertiaryUnit: String,
    val tertiaryUnitQty: Double
)

private fun genBillNo(): String = "PUR" + System.currentTimeMillis()

class PurchaseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BILL_NO = "billNo"
    }

    // ================= PREMIUM COLOR PALETTE (matches Sale / Product) =================
    private val bg = "#F3F2FA"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#1A1A2E"
    private val textMuted = "#8A8FA3"
    private val orange = "#F5730B"
    private val orangeDark = "#D4590A"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val blue = "#2F6FED"
    private val green = "#1FA971"
    private val greenDark = "#158A5A"
    private val red = "#E5484D"
    private val border = "#E7E5F3"

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
            setPadding(24, 44, 24, 28)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 22, 22)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(orange), Color.parseColor(orangeDark))
            ).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 18) }
            applyElevation(this, 10f)
        }
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = if (editBillNo != null) "✏️  Edit Purchase" else "🧾  Purchase"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = "Stock In / Supplier Billing"
            textSize = 11.5f
            setTextColor(Color.parseColor("#FFE3CC"))
            setPadding(0, 4, 0, 0)
        })
        header.addView(headerCol)
        header.addView(pillChip("🕘 History", "#FF8A3D") {
            startActivity(Intent(this@PurchaseActivity, PurchaseHistoryActivity::class.java))
        })
        root.addView(header)

        // ================= DATE =================
        val dateBox = premiumCard()
        dateBox.setOnClickListener { openDatePicker() }
        dateBox.addView(labelRow("📅", "Date"))
        val dateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dateValueText = TextView(this).apply {
            text = formatDate(purchaseDateMillis)
            textSize = 15.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        dateRow.addView(dateValueText)
        dateRow.addView(circleIcon("▾", orange, 26))
        dateBox.addView(dateRow)
        root.addView(dateBox)

        // ================= FIRM NAME =================
        val firmBox = premiumCard().apply {
            setPadding(20, 14, 20, 14)
            background = strokedBg(border, "#FFF6EF", 14)
        }
        val firmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        firmRow.addView(circleIcon("🏪", orange, 32))
        firmRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(14, 1) })
        val firmCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        firmCol.addView(TextView(this).apply { text = "Firm Name"; textSize = 11.5f; setTextColor(Color.parseColor(textMuted)) })
        firmCol.addView(TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 14.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        firmRow.addView(firmCol)
        firmBox.addView(firmRow)
        root.addView(firmBox)

        // ================= PARTY NAME =================
        val partyBox = premiumCard()
        partyBox.addView(labelRow("🤝", "Party / Supplier"))
        val partyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        partyName = AutoCompleteTextView(this).apply {
            hint = "Party Name (Supplier) *"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        partyRow.addView(partyName)
        partyRow.addView(circleIcon("+", orange, 32) { promptAddSupplier() })
        partyBox.addView(partyRow)
        root.addView(partyBox)
        root.addView(spacer(4))

        // ---- Cash / Credit ----
        val cashCreditToggle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cashBtn = Button(this).apply {
            text = "💵 CASH"; textSize = 11f; setTextColor(Color.WHITE)
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(orange, 20)
            setPadding(24, 14, 24, 14); minWidth = 0; minHeight = 0
            setOnClickListener { setPurchaseMode(true) }
            applyElevation(this, 4f)
        }
        creditBtn = Button(this).apply {
            text = "🧾 CREDIT"; textSize = 11f; setTextColor(Color.parseColor(textMuted))
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg("#EDEBFA", 20)
            setPadding(24, 14, 24, 14); minWidth = 0; minHeight = 0
            setOnClickListener { setPurchaseMode(false) }
        }
        cashCreditToggle.addView(cashBtn)
        cashCreditToggle.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(10, 1) })
        cashCreditToggle.addView(creditBtn)
        root.addView(cashCreditToggle)
        root.addView(spacer(18))

        // ================= "Add Items (Optional)" trigger =================
        val addItemsBox = premiumCard().apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF1E5"))
                setStroke((1.4 * resources.displayMetrics.density).toInt(), Color.parseColor(orange))
                cornerRadius = 14f
            }
        }
        val addItemsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addItemsRow.addView(circleIcon("+", orange, 32))
        addItemsTrigger = TextView(this).apply {
            text = "  📦 Add Items (Optional)"
            textSize = 14.5f
            setTextColor(Color.parseColor(orangeDark))
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
            background = strokedBg(border, cardWhite, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 12) }
            applyElevation(this, 4f)
        }

        val itemBox = innerField()
        itemBox.addView(labelRow("📦", "Item Name"))
        itemName = AutoCompleteTextView(this).apply {
            hint = "Type to search…"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
        }
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
        qtyBox.addView(labelRow("🔢", "Quantity"))
        qty = EditText(this).apply {
            hint = "0"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        qtyBox.addView(qty)
        val unitBox = innerField().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
        }
        unitBox.addView(labelRow("📏", "Unit"))
        unitSpinner = Spinner(this)
        unitBox.addView(unitSpinner)
        qtyUnitRow.addView(qtyBox)
        qtyUnitRow.addView(unitBox)
        itemEntrySection.addView(qtyUnitRow)
        itemEntrySection.addView(spacer(10))

        val rateBox = innerField()
        rateBox.addView(labelRow("💰", "Rate"))
        rate = EditText(this).apply {
            hint = "Price / Unit"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        rateBox.addView(rate)
        itemEntrySection.addView(rateBox)

        conversionInfo = TextView(this).apply {
            text = ""
            textSize = 12.5f
            setTextColor(Color.parseColor(blue))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(6, 10, 0, 0)
            visibility = View.GONE
        }
        itemEntrySection.addView(conversionInfo)

        totalAmountText = TextView(this).apply {
            text = "🧮  Total Amount: Rs 0.00"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(greenDark))
            setPadding(6, 10, 0, 10)
        }
        itemEntrySection.addView(totalAmountText)

        val watcher = simpleWatcher { updateLineTotal() }
        qty.addTextChangedListener(watcher)
        rate.addTextChangedListener(watcher)

        itemEntrySection.addView(Button(this).apply {
            text = "✚  ADD ITEM"
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(blue, 16)
            setPadding(0, 24, 0, 24)
            setOnClickListener { addItem() }
            applyElevation(this, 3f)
        })
        root.addView(itemEntrySection)

        // ================= ITEMS LIST =================
        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        // ================= GRAND TOTAL =================
        val totalCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 20, 22, 20)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(greenDark), Color.parseColor(green))
            ).apply { cornerRadius = 16f }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 14) }
            applyElevation(this, 6f)
        }
        totalCard.addView(TextView(this).apply {
            text = "💎  Total Amount"
            textSize = 15.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        grandTotalText = TextView(this).apply {
            text = "Rs 0.00"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        totalCard.addView(grandTotalText)
        root.addView(totalCard)

        // ================= PAYMENT =================
        paymentSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val payRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val methodBox = premiumCard().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) }
            setPadding(18, 6, 18, 6)
        }
        methodBox.addView(labelRow("💳", "Method"))
        paymentMethodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cash", "Bank"))
        }
        methodBox.addView(paymentMethodSpinner)
        val paidBox = premiumCard().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
            setPadding(18, 6, 18, 6)
        }
        paidBox.addView(labelRow("💵", "Amount Paid"))
        paidInput = EditText(this).apply {
            hint = "0.00"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
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
            text = if (editBillNo != null) "💾  UPDATE PURCHASE" else "💾  SAVE PURCHASE"
            setTextColor(Color.WHITE)
            textSize = 15.5f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor(orange), Color.parseColor(orangeDark))
            ).apply { cornerRadius = 18f }
            setPadding(0, 26, 0, 26)
            setOnClickListener { savePurchase() }
            applyElevation(this, 6f)
        }

        val scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(root)
        }

        val saveBar = LinearLayout(this).apply {
            setPadding(24, 14, 24, 18)
            setBackgroundColor(Color.parseColor(cardWhite))
            applyElevation(this, 10f)
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
            val pickedName = itemName.adapter.getItem(position).toString()
            onItemPicked(pickedName)
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
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 14) }
        applyElevation(this, 3f)
    }

    private fun outlinedBox() = premiumCard()

    private fun innerField() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 12, 18, 12)
        background = strokedBg(border, "#FAFAFF", 14)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) }
    }

    private fun labelRow(icon: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 4)
        addView(TextView(this@PurchaseActivity).apply { this.text = "$icon  "; textSize = 12f })
        addView(TextView(this@PurchaseActivity).apply {
            this.text = label; textSize = 11.5f
            setTextColor(Color.parseColor(textMuted))
        })
    }

    private fun pillChip(label: String, colorHex: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = label
        textSize = 12.5f
        setTextColor(Color.WHITE)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = roundedBg(colorHex, 30)
        setPadding(24, 12, 24, 12)
        setOnClickListener { onClick() }
    }

    private fun circleIcon(label: String, colorHex: String, sizeDp: Int, onClick: (() -> Unit)? = null) = TextView(this).apply {
        this.text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        width = px; height = px
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun toggleItemEntry() {
        itemEntrySection.visibility = if (itemEntrySection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        addItemsTrigger.text = if (itemEntrySection.visibility == View.VISIBLE) "  ✕ Hide Item Entry" else "  📦 Add Items (Optional)"
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
    }

    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.4 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }

    private fun ovalBg(colorHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
    }

    private fun applyElevation(view: View, dp: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.elevation = dp * resources.displayMetrics.density
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }

    private fun setPurchaseMode(cash: Boolean) {
        isCashPurchase = cash
        if (cash) {
            cashBtn.background = roundedBg(orange, 20); cashBtn.setTextColor(Color.WHITE)
            applyElevation(cashBtn, 4f)
            creditBtn.background = roundedBg("#EDEBFA", 20); creditBtn.setTextColor(Color.parseColor(textMuted))
            creditBtn.elevation = 0f
            paymentSection.visibility = View.VISIBLE
        } else {
            creditBtn.background = roundedBg(red, 20); creditBtn.setTextColor(Color.WHITE)
            applyElevation(creditBtn, 4f)
            cashBtn.background = roundedBg("#EDEBFA", 20); cashBtn.setTextColor(Color.parseColor(textMuted))
            cashBtn.elevation = 0f
            paymentSection.visibility = View.GONE
        }
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

    private fun formatDate(millis: Long) = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(millis))

    private fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = purchaseDateMillis }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            purchaseDateMillis = cal.timeInMillis
            dateValueText.text = formatDate(purchaseDateMillis)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ---- Load an existing bill into the form for editing ----
    private fun loadForEdit(bill: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)
            val purchase = db.purchaseDao().findPurchase(bill) ?: return@launch
            val items = db.purchaseDao().itemsForBill(bill)
            originalPurchase = purchase
            originalItems = items

            purchaseDateMillis = purchase.createdAt
            dateValueText.text = formatDate(purchaseDateMillis)

            val supplierName = purchase.supplierId?.let { id ->
                db.supplierDao().all().first().find { it.id == id }?.name
            } ?: ""
            partyName.setText(supplierName)

            setPurchaseMode(purchase.paid > 0)
            paidInput.setText(purchase.paid.toString())

            lines.clear()
            items.forEach { pi ->
                val product = db.productDao().find(pi.barcode)
                lines.add(
                    PurchaseLine(
                        itemName = product?.name ?: pi.barcode,
                        barcode = pi.barcode,
                        qty = pi.qty.toDouble(),
                        unit = pi.unit.ifBlank { product?.unit ?: "" },
                        rate = pi.unitCost,
                        amount = pi.amount,
                        mainUnit = product?.unit ?: "",
                        secondaryUnit = product?.secondaryUnit ?: "",
                        secondaryUnitQty = product?.secondaryUnitQty ?: 0.0,
                        tertiaryUnit = product?.tertiaryUnit ?: "",
                        tertiaryUnitQty = product?.tertiaryUnitQty ?: 0.0
                    )
                )
            }
            renderItemsList()
            updateGrandTotal()
            if (lines.isNotEmpty()) {
                itemEntrySection.visibility = View.VISIBLE
                addItemsTrigger.text = "  ✕ Hide Item Entry"
            }
        }
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

    // ---- Item entry logic (supports 3 unit tiers) ----
    private fun onItemPicked(pickedName: String) {
        val product = products.find { it.name.equals(pickedName, ignoreCase = true) } ?: return
        selectedProduct = product
        rate.setText(product.cost.toString())

        val unitOptions = mutableListOf(product.unit)
        if (product.secondaryUnit.isNotBlank() && product.secondaryUnit != product.unit) {
            unitOptions.add(product.secondaryUnit)
        }
        if (product.tertiaryUnit.isNotBlank() && !unitOptions.contains(product.tertiaryUnit)) {
            unitOptions.add(product.tertiaryUnit)
        }
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitOptions)
        buildUnitChips(unitOptions, product.unit)

        val conversionParts = mutableListOf<String>()
        if (product.secondaryUnit.isNotBlank() && product.secondaryUnitQty > 0) {
            conversionParts.add("1 ${product.unit} = ${product.secondaryUnitQty} ${product.secondaryUnit}")
        }
        if (product.tertiaryUnit.isNotBlank() && product.tertiaryUnitQty > 0) {
            conversionParts.add("1 ${product.secondaryUnit} = ${product.tertiaryUnitQty} ${product.tertiaryUnit}")
        }
        if (conversionParts.isNotEmpty()) {
            conversionInfo.text = "🔁  " + conversionParts.joinToString("   •   ")
            conversionInfo.visibility = View.VISIBLE
        } else {
            conversionInfo.visibility = View.GONE
        }
        updateLineTotal()
    }

    private fun buildUnitChips(options: List<String>, selected: String) {
        unitToggleRow.removeAllViews()
        if (options.size < 2) {
            unitToggleRow.visibility = View.GONE
            return
        }
        unitToggleRow.visibility = View.VISIBLE
        options.forEachIndexed { index, unitLabel ->
            val isSelected = unitLabel == selected
            val chip = TextView(this).apply {
                text = unitLabel
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(30, 14, 30, 14)
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor(orange))
                background = if (isSelected)
                    GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(Color.parseColor(orange), Color.parseColor(orangeDark))
                    ).apply { cornerRadius = 30f }
                else strokedBg(orange, "#FFF6EF", 30)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(if (index == 0) 0 else 8, 0, 0, 0) }
                if (isSelected) applyElevation(this, 2f)
                setOnClickListener {
                    unitSpinner.setSelection(options.indexOf(unitLabel))
                    buildUnitChips(options, unitLabel)
                    updateLineTotal()
                }
            }
            unitToggleRow.addView(chip)
        }
    }

    private fun updateLineTotal() {
        val q = qty.text.toString().toDoubleOrNull() ?: 0.0
        val r = rate.text.toString().toDoubleOrNull() ?: 0.0
        val amount = q * r
        totalAmountText.text = "🧮  Total Amount: Rs %.2f".format(amount)
    }

    private fun addItem() {
        val enteredName = itemName.text.toString().trim()
        val q = qty.text.toString().toDoubleOrNull()
        val r = rate.text.toString().toDoubleOrNull()
        val unit = (unitSpinner.selectedItem as? String) ?: "pcs"

        if (enteredName.isEmpty()) { itemName.error = "Required"; return }
        if (q == null || q <= 0) { qty.error = "Enter quantity"; return }
        if (r == null || r < 0) { rate.error = "Enter rate"; return }

        val product = selectedProduct ?: products.find { it.name.equals(enteredName, ignoreCase = true) }
        if (product == null) {
            itemName.error = "Select an existing product, or add it in Products first"
            return
        }

        val line = PurchaseLine(
            itemName = product.name,
            barcode = product.barcode,
            qty = q,
            unit = unit,
            rate = r,
            amount = q * r,
            mainUnit = product.unit,
            secondaryUnit = product.secondaryUnit,
            secondaryUnitQty = product.secondaryUnitQty,
            tertiaryUnit = product.tertiaryUnit,
            tertiaryUnitQty = product.tertiaryUnitQty
        )
        lines.add(line)
        renderItemsList()
        updateGrandTotal()

        itemName.setText("")
        qty.setText("")
        rate.setText("")
        selectedProduct = null
        conversionInfo.visibility = View.GONE
        unitToggleRow.visibility = View.GONE
        totalAmountText.text = "🧮  Total Amount: Rs 0.00"
        itemName.requestFocus()
    }

    private fun renderItemsList() {
        itemsContainer.removeAllViews()
        lines.forEachIndexed { index, line ->
            val row = premiumCard().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(circleIcon("📥", orange, 30))
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(12, 1) })
            row.addView(TextView(this).apply {
                text = "${line.itemName}\n${line.qty} ${line.unit} x Rs ${line.rate}"
                textSize = 13f
                setTextColor(Color.parseColor(textDark))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "Rs %.2f".format(line.amount)
                textSize = 13.5f
                setTextColor(Color.parseColor(greenDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(12, 0, 12, 0)
            })
            row.addView(TextView(this).apply {
                text = "✕"
                textSize = 14f
                setTextColor(Color.WHITE)
                background = ovalBg(red)
                gravity = Gravity.CENTER
                val px = (26 * resources.displayMetrics.density).toInt()
                width = px; height = px
                setOnClickListener {
                    lines.removeAt(index)
                    renderItemsList()
                    updateGrandTotal()
                }
            })
            itemsContainer.addView(row)
        }
    }

    private fun updateGrandTotal() {
        val total = lines.sumOf { it.amount }
        grandTotalText.text = "Rs %.2f".format(total)
    }

    // ---- Supplier quick-add ----
    private fun promptAddSupplier() {
        val input = EditText(this).apply { hint = "Supplier name"; setPadding(32, 24, 32, 24) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Add Supplier")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val supplierNameEntered = input.text.toString().trim()
                if (supplierNameEntered.isNotBlank()) {
                    lifecycleScope.launch {
                        val supplier = Supplier(name = supplierNameEntered)
                        PosDatabase.get(this@PurchaseActivity).supplierDao().insert(supplier)
                        partyName.setText(supplierNameEntered)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Save (with 3-tier unit -> main-unit conversion for stock & unitCost) ----
    private fun savePurchase() {
        val party = partyName.text.toString().trim()
        if (party.isEmpty()) { partyName.error = "Required"; return }
        if (lines.isEmpty()) {
            Toast.makeText(this, "Add at least one item, or continue without items", Toast.LENGTH_SHORT).show()
        }

        val grandTotal = lines.sumOf { it.amount }
        val amountPaid = if (isCashPurchase) (paidInput.text.toString().toDoubleOrNull() ?: grandTotal) else 0.0
        val paymentMethod = (paymentMethodSpinner.selectedItem as? String) ?: "Cash"

        val matchedSupplier = suppliers.find { it.name.equals(party, ignoreCase = true) }
        val supplierId = matchedSupplier?.id

        val billNo = editBillNo ?: genBillNo()

        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)

            val original = originalPurchase
            if (original != null) {
                // originalItems.qty is already stored in main-unit terms, so this reversal is safe as-is.
                originalItems.forEach { db.productDao().decrease(it.barcode, it.qty) }
                val originalOutstanding = original.total - original.paid
                if (original.supplierId != null && originalOutstanding > 0) {
                    db.supplierDao().addBalance(original.supplierId, -originalOutstanding)
                }
                db.purchaseDao().deleteItems(billNo)
                db.purchaseDao().deletePurchase(billNo)
                db.paymentDao().deleteByReference(billNo)
            }

            db.purchaseDao().purchase(
                Purchase(
                    billNo = billNo,
                    supplierId = supplierId,
                    total = grandTotal,
                    paid = amountPaid,
                    createdAt = purchaseDateMillis,
                    subtotal = grandTotal,
                    discount = 0.0
                )
            )

            // ---- Convert each line's qty/rate into main-unit terms before storing/adding stock ----
            fun conversionFactor(line: PurchaseLine): Double = when {
                line.unit == line.secondaryUnit && line.secondaryUnitQty > 0 -> line.secondaryUnitQty
                line.unit == line.tertiaryUnit && line.tertiaryUnitQty > 0 ->
                    if (line.secondaryUnitQty > 0) line.secondaryUnitQty * line.tertiaryUnitQty else line.tertiaryUnitQty
                else -> 1.0
            }

            db.purchaseDao().items(
                lines.map { line ->
                    val factor = conversionFactor(line)
                    val mainUnitQty = line.qty / factor
                    val unitCostPerMainUnit = line.rate * factor
                    PurchaseItem(
                        billNo = billNo,
                        barcode = line.barcode ?: "",
                        qty = mainUnitQty.roundToInt(),
                        unitCost = unitCostPerMainUnit,
                        amount = line.amount,
                        unit = line.unit
                    )
                }
            )

            lines.forEach { line ->
                val factor = conversionFactor(line)
                val mainUnitQty = line.qty / factor
                line.barcode?.let { db.productDao().increase(it, mainUnitQty.roundToInt()) }
            }

            val outstanding = grandTotal - amountPaid
            if (supplierId != null && outstanding > 0) {
                db.supplierDao().addBalance(supplierId, outstanding)
            }
            if (supplierId != null && amountPaid > 0) {
                db.paymentDao().insert(
                    Payment(
                        reference = billNo,
                        partyType = "supplier",
                        partyId = supplierId,
                        amount = amountPaid,
                        method = paymentMethod,
                        note = if (original != null) "Purchase payment (edited)" else "Purchase payment"
                    )
                )
            }

            Toast.makeText(
                this@PurchaseActivity,
                if (original != null) "Purchase updated" else "Purchase saved",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
}
