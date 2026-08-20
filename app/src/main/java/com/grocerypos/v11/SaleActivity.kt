package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class SaleLine(
    val barcode: String,
    val itemName: String,
    val qty: Double,
    val unit: String,
    val unitPrice: Double,
    val cost: Double,
    val amount: Double,
    val mainUnit: String,
    val secondaryUnit: String,
    val secondaryUnitQty: Double,
    val tertiaryUnit: String = "",
    val tertiaryUnitQty: Double = 0.0
)

// ---- Unit-conversion helpers: a SaleLine may be entered in the secondary or tertiary
// unit (e.g. "grams" while the product's stock is tracked in "pcs"). These convert the
// entered qty/price back to the product's main unit before it touches the DB / stock.
// Chain: 1 main = secondaryUnitQty secondary; 1 secondary = tertiaryUnitQty tertiary. ----
private fun SaleLine.isSecondary(): Boolean =
    secondaryUnit.isNotEmpty() && unit == secondaryUnit && secondaryUnitQty > 0

private fun SaleLine.isTertiary(): Boolean =
    tertiaryUnit.isNotEmpty() && unit == tertiaryUnit && tertiaryUnitQty > 0 && secondaryUnitQty > 0

private fun SaleLine.mainUnitQty(): Double = when {
    isTertiary() -> qty / (secondaryUnitQty * tertiaryUnitQty)
    isSecondary() -> qty / secondaryUnitQty
    else -> qty
}

private fun SaleLine.mainUnitPrice(): Double = when {
    isTertiary() -> unitPrice * secondaryUnitQty * tertiaryUnitQty
    isSecondary() -> unitPrice * secondaryUnitQty
    else -> unitPrice
}

class SaleActivity : AppCompatActivity() {

    companion object {
        // ---- NEW: lets other screens (PartyDashboardActivity, PartyTransactionActivity)
        // open an existing sale for viewing/editing by invoice number. ----
        const val EXTRA_INVOICE = "invoice"
        private const val PREFS_NAME = "sale_draft_prefs"
        private const val KEY_DRAFT = "draft_json"
    }

    // ---- NAVY + TEAL + WHITE PALETTE — same as PurchaseActivity, so Sale and
    // Purchase now share one visual language across the app. ----
    private val bg = "#F4F6F8"
    private val cardBg = "#FFFFFF"
    private val navy = "#0B2545"     // primary brand — header, Save button
    private val teal = "#0F9B8E"     // secondary accent — chips, Add Item, totals, "+" icons
    private val green = "#1FA971"
    private val greenDark = "#158A5A"
    private val red = "#E5484D"
    private val redDark = "#C93A3E"
    private val amber = "#F5A524"
    private val textDark = "#0B2545"
    private val textGray = "#7C8798"
    private val border = "#E3E8EE"

    private lateinit var dateValueText: TextView
    private lateinit var customerName: AutoCompleteTextView
    private lateinit var cashBtn: Button
    private lateinit var creditBtn: Button
    private lateinit var saleTypeSpinner: Spinner
    private lateinit var addItemsTrigger: TextView
    private lateinit var itemEntrySection: LinearLayout
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var unitPrice: EditText
    private lateinit var itemsContainer: LinearLayout
    private lateinit var subtotalText: TextView
    private lateinit var discountInput: EditText
    private lateinit var totalText: TextView
    private lateinit var totalCard: LinearLayout
    private lateinit var paymentSection: LinearLayout
    private lateinit var paidInput: EditText
    private lateinit var paymentMethodSpinner: Spinner
    private lateinit var firmNameText: TextView
    // ---- NEW: made into lateinit vars (were built inline) so Delete can sit beside Save. ----
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button

    private var customers = listOf<Customer>()
    private var products = listOf<Product>()
    private val lines = mutableListOf<SaleLine>()
    private var selectedProduct: Product? = null
    private var isCashSale = true
    private var saleDateMillis = System.currentTimeMillis()

    // ---- NEW: edit-mode state — set when this screen was opened to view/edit an
    // existing sale rather than create a new one. ----
    private var editInvoice: String? = null
    private var originalSale: Sale? = null
    private var originalItems: List<SaleItem> = emptyList()

    // ---- tracks the last-entered unit price converted to a "per MAIN unit" price, so
    // switching the unit spinner (e.g. kg -> bag) auto-converts the price proportionally
    // from whatever the user actually typed, instead of always snapping back to the
    // product's saved sale/wholesale price and discarding a manual override. ----
    private var lastMainPrice: Double = 0.0
    private var suppressPriceWatcher = false

    // ---- draft persistence: guards against process death (e.g. fingerprint prompt,
    // switching apps, or the OS killing the app in the background) wiping out an
    // in-progress sale that hasn't been saved/held yet. Separate from the explicit
    // "Hold" feature, which is a manual, user-initiated save. Only relevant for a
    // brand-new sale — edit mode loads its own data via loadForEdit(). ----
    private var suppressDraftSave = false
    private var draftRestored = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        editInvoice = intent.getStringExtra(EXTRA_INVOICE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 56, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER (flat navy, teal chips) =================
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 22, 22)
            background = roundedBg(navy, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 18) }
            applyElevation(this, 6f)
        }
        val headerTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerTextCol.addView(TextView(this).apply {
            text = if (editInvoice != null) com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Edit Sale", "سیل میں ترمیم") else com.grocerypos.v11.util.Loc.t(this@SaleActivity, "New Sale", "نئی سیل")
            textSize = 18.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headerTextCol.addView(TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 11f
            setTextColor(Color.parseColor("#9FB4CC"))
            setPadding(0, 3, 0, 0)
        })
        headerCard.addView(headerTextCol)

        val headerActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        headerActions.addView(pillChip("History") {
            startActivity(Intent(this@SaleActivity, SaleHistoryActivity::class.java))
        })
        headerActions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(8, 1) })
        headerActions.addView(pillChip("Hold") { holdBill() })
        headerActions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(8, 1) })
        headerActions.addView(pillChip("Recall") { openRecallDialog() })
        headerCard.addView(headerActions)
        root.addView(headerCard)

        // ================= DATE =================
        val dateBox = outlinedBox()
        dateBox.setOnClickListener { openDatePicker() }
        dateBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Date", "تاریخ")))
        val dateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dateValueText = TextView(this).apply {
            text = formatDate(saleDateMillis)
            textSize = 15.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        dateRow.addView(dateValueText)
        dateRow.addView(TextView(this).apply {
            text = "\u203A"
            textSize = 18f
            setTextColor(Color.parseColor(teal))
        })
        dateBox.addView(dateRow)
        root.addView(dateBox)

        // ================= FIRM NAME (loaded from saved Shop Info) =================
        val firmBox = outlinedBox().apply { setPadding(20, 14, 20, 14) }
        val firmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val firmCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        firmCol.addView(TextView(this).apply { text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Firm Name", "فرم کا نام"); textSize = 11f; setTextColor(Color.parseColor(textGray)) })
        firmNameText = TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 14.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        firmCol.addView(firmNameText)
        firmRow.addView(firmCol)
        firmBox.addView(firmRow)
        root.addView(firmBox)

        // ================= CUSTOMER NAME =================
        val custBox = outlinedBox()
        custBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Customer", "کسٹمر")))
        val custRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        customerName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Customer Name (Walk-in)", "کسٹمر کا نام (واک ان)")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        custRow.addView(customerName)
        custRow.addView(circleIcon("+", teal, 30) { promptAddCustomer() })
        custBox.addView(custRow)
        root.addView(custBox)
        root.addView(spacer(14))

        // ---- Cash / Credit ----
        val cashCreditToggle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cashBtn = Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "CASH", "نقد"); textSize = 12f; setTextColor(Color.WHITE)
            background = roundedBg(teal, 20)
            setPadding(24, 14, 24, 14); minWidth = 0; minHeight = 0
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { setSaleMode(true) }
            applyElevation(this, 4f)
        }
        creditBtn = Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "CREDIT", "ادھار"); textSize = 12f; setTextColor(Color.parseColor(textGray))
            background = roundedBg("#EEF0F7", 20)
            setPadding(24, 14, 24, 14); minWidth = 0; minHeight = 0
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { setSaleMode(false) }
        }
        cashCreditToggle.addView(cashBtn)
        cashCreditToggle.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(10, 1) })
        cashCreditToggle.addView(creditBtn)
        root.addView(cashCreditToggle)
        root.addView(spacer(18))

        // ================= SALE TYPE =================
        val saleTypeBox = outlinedBox()
        saleTypeBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Sale Type", "سیل کی قسم")))
        saleTypeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Retail", "Wholesale"))
        }
        saleTypeBox.addView(saleTypeSpinner)
        root.addView(saleTypeBox)
        root.addView(spacer(16))

        // ================= "Add Items" trigger =================
        val addItemsBox = outlinedBox()
        val addItemsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addItemsRow.addView(circleIcon("+", teal, 30))
        addItemsTrigger = TextView(this).apply {
            text = "  " + com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Add Items", "آئٹمز شامل کریں")
            textSize = 14.5f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addItemsRow.addView(addItemsTrigger)
        addItemsBox.addView(addItemsRow)
        addItemsBox.setOnClickListener { toggleItemEntry() }
        root.addView(addItemsBox)
        root.addView(spacer(14))

        // ================= ITEM ENTRY (collapsible) =================
        itemEntrySection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val itemBox = outlinedBox()
        itemBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Item Name", "آئٹم کا نام")))
        itemName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Type to search…", "تلاش کے لیے لکھیں…")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
        }
        itemBox.addView(itemName)
        itemEntrySection.addView(itemBox)
        itemEntrySection.addView(spacer(10))

        val qtyUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qtyBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,6,0) }
        }
        qtyBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Quantity", "مقدار")))
        qty = EditText(this).apply {
            hint = "0"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        qtyBox.addView(qty)
        val unitBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6,0,0,0) }
        }
        unitBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Unit", "یونٹ")))
        unitSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
        }
        unitBox.addView(unitSpinner)
        qtyUnitRow.addView(qtyBox)
        qtyUnitRow.addView(unitBox)
        itemEntrySection.addView(qtyUnitRow)
        itemEntrySection.addView(spacer(10))

        val rateBox = outlinedBox()
        rateBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Rate", "ریٹ")))
        unitPrice = EditText(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Auto-filled, editable", "خودکار، قابل ترمیم")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        rateBox.addView(unitPrice)
        itemEntrySection.addView(rateBox)
        itemEntrySection.addView(spacer(14))

        itemEntrySection.addView(Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "ADD ITEM", "آئٹم شامل کریں")
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(teal, 14)
            setPadding(0, 24, 0, 24)
            setOnClickListener { addItem() }
            applyElevation(this, 3f)
        })
        root.addView(itemEntrySection)
        root.addView(spacer(16))

        // ================= BILL ITEMS =================
        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        // ================= SUBTOTAL / DISCOUNT / TOTAL =================
        val subtotalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(6, 8, 6, 8) }
        subtotalRow.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Subtotal", "سب ٹوٹل"); textSize = 14f
            setTextColor(Color.parseColor(textGray))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        subtotalText = TextView(this).apply {
            text = "Rs 0.00"; textSize = 14f
            setTextColor(Color.parseColor(textDark))
        }
        subtotalRow.addView(subtotalText)
        root.addView(subtotalRow)

        val discountBox = outlinedBox().apply { setPadding(18, 4, 18, 4) }
        val discRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        discountInput = EditText(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Discount (Rs)", "رعایت (روپے)")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        discRow.addView(discountInput)
        discountBox.addView(discRow)
        root.addView(discountBox)
        discountInput.addTextChangedListener(simpleWatcher { updateTotals() })
        root.addView(spacer(12))

        totalCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 20, 22, 20)
            background = roundedBg(teal, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            applyElevation(this, 6f)
        }
        totalCard.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Total Amount", "کل رقم"); textSize = 15.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        totalText = TextView(this).apply {
            text = "Rs 0.00"; textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        totalCard.addView(totalText)
        root.addView(totalCard)
        root.addView(spacer(14))

        // ================= PAYMENT (compact) =================
        paymentSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val payRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val methodBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,6,0) }
            setPadding(18, 6, 18, 6)
        }
        methodBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Method", "طریقہ")))
        paymentMethodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cash", "Bank"))
        }
        methodBox.addView(paymentMethodSpinner)
        val paidBox = outlinedBox().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6,0,0,0) }
            setPadding(18, 6, 18, 6)
        }
        paidBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Amount Paid", "ادا شدہ رقم")))
        paidInput = EditText(this).apply {
            hint = "0.00"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        paidBox.addView(paidInput)
        payRow.addView(methodBox)
        payRow.addView(paidBox)
        paymentSection.addView(payRow)
        root.addView(paymentSection)
        root.addView(spacer(18))

        // ================= SAVE + DELETE =================
        saveButton = Button(this).apply {
            text = if (editInvoice != null) com.grocerypos.v11.util.Loc.t(this@SaleActivity, "UPDATE SALE", "سیل اپ ڈیٹ کریں") else com.grocerypos.v11.util.Loc.t(this@SaleActivity, "SAVE SALE", "سیل محفوظ کریں")
            setTextColor(Color.WHITE)
            textSize = 15.5f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(navy, 16)
            setPadding(0, 26, 0, 26)
            setOnClickListener { saveSale() }
            applyElevation(this, 8f)
        }
        // ---- NEW: Delete — only shown in edit mode. Reverses stock + customer balance
        // + the cash-in record before removing the bill, mirroring PurchaseActivity. ----
        deleteButton = Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "DELETE", "حذف کریں")
            setTextColor(Color.WHITE)
            textSize = 15.5f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(red, 16)
            setPadding(0, 26, 0, 26)
            visibility = if (editInvoice != null) View.VISIBLE else View.GONE
            setOnClickListener { confirmDeleteSale() }
            applyElevation(this, 8f)
        }
        val saveDeleteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveDeleteRow.addView(saveButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) })
        saveDeleteRow.addView(deleteButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply { setMargins(8, 0, 0, 0) })
        root.addView(saveDeleteRow)
        root.addView(spacer(40))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadCustomers()
        loadProducts()
        loadFirmName()
        setSaleMode(true)
        // ---- NEW: if opened for an existing invoice, load it into the form. ----
        editInvoice?.let { loadForEdit(it) }

        itemName.setOnItemClickListener { _, _, position, _ ->
            val name = itemName.adapter.getItem(position).toString()
            onItemPicked(name)
        }
        itemName.addTextChangedListener(simpleWatcher {
            val match = products.find { it.name.equals(itemName.text.toString().trim(), ignoreCase = true) }
            if (match == null) {
                selectedProduct = null
                unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
            }
        })
        // ---- Records the user-entered price (converted to "per MAIN unit" terms) on
        // every edit, so a manual override survives a later unit switch instead of being
        // discarded in favor of the product's saved sale/wholesale price. ----
        unitPrice.addTextChangedListener(simpleWatcher {
            if (!suppressPriceWatcher) {
                val entered = unitPrice.text.toString().toDoubleOrNull() ?: 0.0
                lastMainPrice = toMainUnitPrice(entered)
            }
        })
        unitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { refillAutoPrice() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        saleTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                // Sale Type (Retail/Wholesale) changes the BASE price, so any manual
                // override should reset here — otherwise switching type would keep
                // showing a price computed for the old type.
                lastMainPrice = 0.0
                refillAutoPrice()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // ---- Restore an unsaved draft. Recovers in-progress entries lost when the OS
        // killed the app in the background (fingerprint unlock, app switch, low memory)
        // before the user could tap Save or Hold. Skipped in edit mode — loadForEdit()
        // above already populated the form from the DB. ----
        if (editInvoice == null) {
            restoreDraftIfAny()
        }
    }

    override fun onPause() {
        super.onPause()
        // Snapshot whatever is currently on screen so a process death while backgrounded
        // doesn't wipe out an in-progress sale. Cleared once the sale is actually saved
        // (see saveSale()) or explicitly held (see holdBill()/clearAll()). Skipped in
        // edit mode — edits reload from the DB via loadForEdit(), not the draft.
        if (editInvoice == null && !suppressDraftSave) {
            saveDraft()
        }
    }

    // ---- Draft persistence (SharedPreferences, JSON-encoded) ----

    private fun draftPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveDraft() {
        val hasContent = lines.isNotEmpty() ||
            customerName.text.toString().isNotBlank() ||
            itemName.text.toString().isNotBlank() ||
            qty.text.toString().isNotBlank() ||
            unitPrice.text.toString().isNotBlank()
        if (!hasContent) {
            clearDraft()
            return
        }

        val linesArray = JSONArray()
        lines.forEach { line ->
            linesArray.put(JSONObject().apply {
                put("barcode", line.barcode)
                put("itemName", line.itemName)
                put("qty", line.qty)
                put("unit", line.unit)
                put("unitPrice", line.unitPrice)
                put("cost", line.cost)
                put("amount", line.amount)
                put("mainUnit", line.mainUnit)
                put("secondaryUnit", line.secondaryUnit)
                put("secondaryUnitQty", line.secondaryUnitQty)
                put("tertiaryUnit", line.tertiaryUnit)
                put("tertiaryUnitQty", line.tertiaryUnitQty)
            })
        }

        val draft = JSONObject().apply {
            put("customer", customerName.text.toString())
            put("saleType", saleTypeSpinner.selectedItem?.toString() ?: "Retail")
            put("isCashSale", isCashSale)
            put("discount", discountInput.text.toString())
            put("paid", paidInput.text.toString())
            put("dateMillis", saleDateMillis)
            put("pendingItemName", itemName.text.toString())
            put("pendingQty", qty.text.toString())
            put("pendingPrice", unitPrice.text.toString())
            put("lines", linesArray)
        }

        draftPrefs().edit().putString(KEY_DRAFT, draft.toString()).apply()
    }

    private fun clearDraft() {
        draftPrefs().edit().remove(KEY_DRAFT).apply()
    }

    private fun restoreDraftIfAny() {
        val raw = draftPrefs().getString(KEY_DRAFT, null) ?: return
        val draft = try { JSONObject(raw) } catch (e: Exception) { null } ?: return
        if (draftRestored) return
        draftRestored = true
        suppressDraftSave = true

        try {
            val customer = draft.optString("customer", "")
            if (customer.isNotBlank()) customerName.setText(customer)

            val saleType = draft.optString("saleType", "Retail")
            saleTypeSpinner.setSelection(if (saleType == "Wholesale") 1 else 0)

            setSaleMode(draft.optBoolean("isCashSale", true))

            val discount = draft.optString("discount", "")
            if (discount.isNotBlank()) discountInput.setText(discount)
            val paid = draft.optString("paid", "")
            if (paid.isNotBlank()) paidInput.setText(paid)

            val savedDate = draft.optLong("dateMillis", 0L)
            if (savedDate > 0L) {
                saleDateMillis = savedDate
                dateValueText.text = formatDate(saleDateMillis)
            }

            val linesArray = draft.optJSONArray("lines")
            if (linesArray != null) {
                for (i in 0 until linesArray.length()) {
                    val o = linesArray.getJSONObject(i)
                    lines.add(
                        SaleLine(
                            barcode = o.optString("barcode"),
                            itemName = o.optString("itemName"),
                            qty = o.optDouble("qty", 0.0),
                            unit = o.optString("unit"),
                            unitPrice = o.optDouble("unitPrice", 0.0),
                            cost = o.optDouble("cost", 0.0),
                            amount = o.optDouble("amount", 0.0),
                            mainUnit = o.optString("mainUnit"),
                            secondaryUnit = o.optString("secondaryUnit"),
                            secondaryUnitQty = o.optDouble("secondaryUnitQty", 0.0),
                            tertiaryUnit = o.optString("tertiaryUnit"),
                            tertiaryUnitQty = o.optDouble("tertiaryUnitQty", 0.0)
                        )
                    )
                }
                renderItemsList()
                updateTotals()
            }

            // Restore whatever was mid-entry in the item-entry row (not yet added as a line).
            val pendingItemName = draft.optString("pendingItemName", "")
            if (pendingItemName.isNotBlank()) {
                itemName.setText(pendingItemName)
                val match = products.find { it.name.equals(pendingItemName, ignoreCase = true) }
                if (match != null) onItemPicked(match.name)
            }
            val pendingQty = draft.optString("pendingQty", "")
            if (pendingQty.isNotBlank()) qty.setText(pendingQty)
            val pendingPrice = draft.optString("pendingPrice", "")
            if (pendingPrice.isNotBlank()) unitPrice.setText(pendingPrice)

            if (lines.isNotEmpty() || pendingItemName.isNotBlank()) {
                itemEntrySection.visibility = View.VISIBLE
                addItemsTrigger.text = "  Hide Item Entry"
                Toast.makeText(
                    this,
                    com.grocerypos.v11.util.Loc.t(this, "Restored your unsaved sale draft", "آپ کا غیر محفوظ شدہ سیل ڈرافٹ بحال کر دیا گیا"),
                    Toast.LENGTH_LONG
                ).show()
            }
        } finally {
            suppressDraftSave = false
        }
    }

    private fun loadFirmName() {
        lifecycleScope.launch {
            val savedName = PosDatabase.get(this@SaleActivity).appSettingDao().get("shop_name")?.value
            if (!savedName.isNullOrBlank()) firmNameText.text = savedName
        }
    }

    // ---- NEW: load an existing sale into the form for viewing/editing. Fetches
    // customers/products directly (instead of relying on the `customers`/`products`
    // class vars) so it doesn't race with loadCustomers()/loadProducts(), which are
    // async Flow collectors started just before this is called. ----
    private fun loadForEdit(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            originalSale = sale
            originalItems = items

            saleDateMillis = sale.createdAt
            dateValueText.text = formatDate(saleDateMillis)

            val custList = db.customerDao().all().first()
            val custName = sale.customerId?.let { id -> custList.find { it.id == id }?.name } ?: ""
            customerName.setText(custName)

            saleTypeSpinner.setSelection(if (sale.saleType == "wholesale") 1 else 0)
            setSaleMode(sale.paymentMethod != "credit")
            discountInput.setText(if (sale.discount > 0) "%.2f".format(sale.discount) else "")
            paidInput.setText(if (sale.paid > 0) "%.2f".format(sale.paid) else "")
            val methodIndex = if (sale.paymentMethod.equals("bank", ignoreCase = true)) 1 else 0
            paymentMethodSpinner.setSelection(methodIndex)

            val prodList = db.productDao().all().first()
            lines.clear()
            items.forEach { si ->
                val product = prodList.find { it.barcode == si.barcode }
                lines.add(
                    SaleLine(
                        barcode = si.barcode,
                        itemName = si.product,
                        qty = si.qty.toDouble(),
                        unit = product?.unit ?: "",
                        unitPrice = si.unitPrice,
                        cost = si.cost,
                        amount = si.amount,
                        mainUnit = product?.unit ?: "",
                        secondaryUnit = product?.secondaryUnit ?: "",
                        secondaryUnitQty = product?.secondaryUnitQty ?: 0.0,
                        tertiaryUnit = product?.tertiaryUnit ?: "",
                        tertiaryUnitQty = product?.tertiaryUnitQty ?: 0.0
                    )
                )
            }
            renderItemsList()
            updateTotals()
            deleteButton.visibility = View.VISIBLE
        }
    }

    // ================= UI helpers =================
    private fun outlinedBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 16, 22, 16)
        background = strokedBg(border, cardBg, 16)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) }
        applyElevation(this, 2f)
    }

    private fun labelRow(label: String) = TextView(this).apply {
        text = label.uppercase()
        textSize = 10.5f
        setTextColor(Color.parseColor(textGray))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, 6)
        letterSpacing = 0.03f
    }

    private fun pillChip(label: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = label
        textSize = 12.5f
        setTextColor(Color.parseColor(navy))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = roundedBg(cardBg, 30)
        setPadding(24, 12, 24, 12)
        setOnClickListener { onClick() }
    }

    private fun circleIcon(text: String, colorHex: String, sizeDp: Int, onClick: (() -> Unit)? = null) = TextView(this).apply {
        this.text = text
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
        addItemsTrigger.text = if (itemEntrySection.visibility == View.VISIBLE) "  Hide Item Entry" else "  Add Items"
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
        val cal = Calendar.getInstance().apply { timeInMillis = saleDateMillis }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            saleDateMillis = cal.timeInMillis
            dateValueText.text = formatDate(saleDateMillis)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun setSaleMode(cash: Boolean) {
        isCashSale = cash
        if (cash) {
            cashBtn.background = roundedBg(teal, 20); cashBtn.setTextColor(Color.WHITE)
            applyElevation(cashBtn, 4f)
            creditBtn.background = roundedBg("#EEF0F7", 20); creditBtn.setTextColor(Color.parseColor(textGray))
            creditBtn.elevation = 0f
            paymentSection.visibility = View.VISIBLE
        } else {
            creditBtn.background = roundedBg(red, 20); creditBtn.setTextColor(Color.WHITE)
            applyElevation(creditBtn, 4f)
            cashBtn.background = roundedBg("#EEF0F7", 20); cashBtn.setTextColor(Color.parseColor(textGray))
            cashBtn.elevation = 0f
            paymentSection.visibility = View.GONE
        }
    }

    private fun loadCustomers() {
        lifecycleScope.launch {
            PosDatabase.get(this@SaleActivity).customerDao().all().collectLatest { list ->
                customers = list
                customerName.setAdapter(ArrayAdapter(this@SaleActivity, android.R.layout.simple_dropdown_item_1line, list.map { it.name }))
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
        val input = EditText(this).apply { setPadding(32, 24, 32, 24) }
        AlertDialog.Builder(this)
            .setTitle("New Customer")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) lifecycleScope.launch {
                    PosDatabase.get(this@SaleActivity).customerDao().insert(Customer(name = v))
                    Toast.makeText(this@SaleActivity, "Customer added", Toast.LENGTH_SHORT).show()
                    customerName.setText(v)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Item selection: unit choices now include the tertiary tier too, provided a
    // secondary unit exists (tertiary is defined relative to secondary, not primary). ----
    private fun onItemPicked(name: String) {
        val product = products.find { it.name.equals(name, ignoreCase = true) } ?: return
        selectedProduct = product
        val unitChoices = mutableListOf(product.unit)
        if (product.secondaryUnit.isNotEmpty()) {
            unitChoices.add(product.secondaryUnit)
            if (product.tertiaryUnit.isNotEmpty() && product.tertiaryUnitQty > 0) {
                unitChoices.add(product.tertiaryUnit)
            }
        }
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitChoices)
        lastMainPrice = 0.0
        refillAutoPrice()
    }

    // ---- Converts a "price per currently-selected unit" to a "price per MAIN unit",
    // and back. Mirrors the qty conversion but inverted (price scales the opposite way
    // qty does: smaller unit -> smaller price). ----
    private fun toMainUnitPrice(entered: Double): Double {
        val product = selectedProduct ?: return entered
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        return when {
            chosenUnit == product.tertiaryUnit && product.tertiaryUnit.isNotEmpty() &&
                product.tertiaryUnitQty > 0 && product.secondaryUnitQty > 0 ->
                entered * product.secondaryUnitQty * product.tertiaryUnitQty
            chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0 ->
                entered * product.secondaryUnitQty
            else -> entered
        }
    }

    private fun fromMainUnitPrice(mainPrice: Double, chosenUnit: String): Double {
        val product = selectedProduct ?: return mainPrice
        return when {
            chosenUnit == product.tertiaryUnit && product.tertiaryUnit.isNotEmpty() &&
                product.tertiaryUnitQty > 0 && product.secondaryUnitQty > 0 ->
                mainPrice / (product.secondaryUnitQty * product.tertiaryUnitQty)
            chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0 ->
                mainPrice / product.secondaryUnitQty
            else -> mainPrice
        }
    }

    // ---- Picking the product suggests the sale/wholesale price as a starting point;
    // once the user types their OWN price, lastMainPrice takes over so switching units
    // converts proportionally from what was actually entered instead of snapping back
    // to the product's saved price. Example: 1 bag = 50 kg, sale price per bag is 5000,
    // user overrides to 5500 on "bag" -> switching to "kg" auto-fills 110 (5500 / 50),
    // and switching back to "bag" restores 5500. ----
    private fun refillAutoPrice() {
        val product = selectedProduct ?: return
        val isWholesale = saleTypeSpinner.selectedItem?.toString() == "Wholesale"
        val basePrice = if (isWholesale) product.wholesalePrice else product.salePrice
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        val base = if (lastMainPrice > 0) lastMainPrice else basePrice
        val price = fromMainUnitPrice(base, chosenUnit)
        suppressPriceWatcher = true
        unitPrice.setText(if (price > 0) "%.2f".format(price) else "")
        suppressPriceWatcher = false
    }

    // ================= Add item to bill =================
    private fun addItem() {
        val n = itemName.text.toString().trim()
        val q = qty.text.toString().toDoubleOrNull() ?: 0.0
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

        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit

        // ---- FIX: Product.stock is now counted in the product's SMALLEST configured
        // unit (see Database.kt), not the primary unit. Validate against that directly
        // via Product.toSmallestUnits() (multiplication only) instead of converting the
        // entered qty DOWN into primary-unit terms and comparing against a stock number
        // that no longer means "primary units" — that mismatch would make the check
        // either wrongly block valid sales or wrongly allow oversold ones. ----
        val alreadyInCartSmallest = lines.filter { it.barcode == product.barcode }
            .sumOf { product.toSmallestUnits(it.qty, it.unit) }
        val neededSmallest = product.toSmallestUnits(q, chosenUnit)
        val availableForThisAdd = product.stock - alreadyInCartSmallest

        if (availableForThisAdd < neededSmallest) {
            Toast.makeText(
                this,
                "Stock kam hai (available: ${formatQty(availableForThisAdd.coerceAtLeast(0.0))} ${product.smallestUnitName()})",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val amount = q * price
        lines.add(
            SaleLine(
                barcode = product.barcode,
                itemName = product.name,
                qty = q,
                unit = chosenUnit,
                unitPrice = price,
                cost = product.cost,
                amount = amount,
                mainUnit = product.unit,
                secondaryUnit = product.secondaryUnit,
                secondaryUnitQty = product.secondaryUnitQty,
                tertiaryUnit = product.tertiaryUnit,
                tertiaryUnitQty = product.tertiaryUnitQty
            )
        )
        renderItemsList()
        updateTotals()

        itemName.text.clear(); qty.text.clear(); unitPrice.text.clear()
        selectedProduct = null
        lastMainPrice = 0.0
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
        itemName.requestFocus()

        if (editInvoice == null) saveDraft()
    }

    private fun renderItemsList() {
        itemsContainer.removeAllViews()
        lines.forEachIndexed { index, line ->
            itemsContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
                background = strokedBg(border, cardBg, 14)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 10) }
                applyElevation(this, 2f)

                val top = LinearLayout(this@SaleActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(TextView(this@SaleActivity).apply {
                    text = line.itemName; textSize = 15f
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                top.addView(TextView(this@SaleActivity).apply {
                    text = "Rs %.2f".format(line.amount)
                    setTextColor(Color.parseColor(teal))
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(top)
                addView(TextView(this@SaleActivity).apply {
                    text = "Qty: ${line.qty} ${line.unit}   •   Rate: ${line.unitPrice}"
                    textSize = 12.5f
                    setTextColor(Color.parseColor(textGray))
                    setPadding(0, 6, 0, 0)
                })
                addView(TextView(this@SaleActivity).apply {
                    text = "\u2715 " + com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Remove", "ہٹائیں")
                    textSize = 12f
                    setTextColor(Color.parseColor(red))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 10, 0, 0)
                    setOnClickListener {
                        lines.removeAt(index)
                        renderItemsList()
                        updateTotals()
                        if (editInvoice == null) saveDraft()
                    }
                })
            })
        }
    }

    private fun updateTotals() {
        val subtotal = lines.sumOf { it.amount }
        val discount = discountInput.text.toString().toDoubleOrNull() ?: 0.0
        subtotalText.text = "Rs %.2f".format(subtotal)
        val total = (subtotal - discount).coerceAtLeast(0.0)
        totalText.text = "Rs %.2f".format(total)
        if (isCashSale) paidInput.setText("%.2f".format(total))
    }

    // ================= Save =================
    private fun saveSale() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "Kam az kam ek item add karen", Toast.LENGTH_SHORT).show()
            return
        }
        val enteredCustomer = customerName.text.toString().trim()
        if (!isCashSale && enteredCustomer.isEmpty()) {
            Toast.makeText(this, "Credit sale ke liye Customer zaroori hai", Toast.LENGTH_SHORT).show()
            return
        }

        val subtotal = lines.sumOf { it.amount }
        val discount = (discountInput.text.toString().toDoubleOrNull() ?: 0.0).coerceIn(0.0, subtotal)
        val total = (subtotal - discount).coerceAtLeast(0.0)
        val paid = if (isCashSale) (paidInput.text.toString().toDoubleOrNull() ?: total) else 0.0
        val method = if (isCashSale) (paymentMethodSpinner.selectedItem?.toString() ?: "Cash") else "credit"
        var customer = customers.find { it.name.equals(enteredCustomer, ignoreCase = true) }
        val saleType = if (saleTypeSpinner.selectedItem?.toString() == "Wholesale") "wholesale" else "retail"
        // ---- CHANGE: reuse the invoice being edited, if any, instead of always
        // generating a fresh one. ----
        val invoice = editInvoice ?: ("INV" + System.currentTimeMillis().toString())

        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)

            // ---- NEW: if editing, reverse the ORIGINAL bill's stock + customer-balance
            // effect first (before re-validating stock against the new lines), then
            // delete its old rows — mirrors PurchaseActivity's edit/update flow.
            // ---- FIX: reversal must add back SMALLEST units, matching how the original
            // deduction was made (see decrease() call below). sale_items only stores the
            // primary-unit-equivalent qty (si.qty), so it's converted up via the current
            // product's own conversion chain — same approach PurchaseActivity uses in
            // reverseStockForItems(). ----
            val original = originalSale
            if (original != null) {
                originalItems.forEach { si ->
                    val p = db.productDao().find(si.barcode)
                    if (p != null) {
                        val smallestQty = p.toSmallestUnits(si.qty.toDouble(), p.unit).roundToInt()
                        db.productDao().increase(si.barcode, smallestQty)
                    }
                }
                val originalOutstanding = original.total - original.paid
                if (original.customerId != null && originalOutstanding > 0) {
                    db.customerDao().addBalance(original.customerId, -originalOutstanding)
                }
                db.saleDao().deleteItems(invoice)
                db.saleDao().deleteSale(invoice)
                db.cashTransactionDao().deleteByReference(invoice)
            }

            // ---- FIX: re-validate stock right before committing, against the LATEST
            // DB values (not the possibly-stale `products` list snapshot from whenever
            // the screen loaded), and now in the product's SMALLEST-unit terms — matching
            // what Product.stock actually represents. Aggregates multiple lines of the
            // same product first, converting each via toSmallestUnits() (multiplication,
            // never a divide that can round a fractional amount down to 0). If ANY item
            // comes up short, the whole sale is rejected — nothing is saved, so there's
            // no risk of a half-recorded bill with wrong stock. ----
            val productsByBarcode = mutableMapOf<String, Product>()
            for ((barcode, group) in lines.groupBy { it.barcode }) {
                val current = db.productDao().find(barcode)
                if (current == null) {
                    Toast.makeText(this@SaleActivity, "Stock badal gaya hai — item nahi mila. Bill dobara check karen.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val neededSmallest = group.sumOf { current.toSmallestUnits(it.qty, it.unit) }
                if (current.stock < neededSmallest) {
                    Toast.makeText(
                        this@SaleActivity,
                        "Stock badal gaya hai — \"${current.name}\" mein sirf ${formatQty(current.stock.toDouble())} ${current.smallestUnitName()} available hai. Bill dobara check karen.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                productsByBarcode[barcode] = current
            }

            if (customer == null && enteredCustomer.isNotEmpty()) {
                val newId = db.customerDao().insert(Customer(name = enteredCustomer))
                customer = Customer(id = newId, name = enteredCustomer)
            }

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
                    saleType = saleType,
                    createdAt = saleDateMillis
                )
            )

            val saleItems = lines.map {
                SaleItem(
                    invoice = invoice,
                    barcode = it.barcode,
                    product = it.itemName,
                    qty = it.mainUnitQty().roundToInt(),
                    unitPrice = it.mainUnitPrice(),
                    cost = it.cost,
                    amount = it.amount
                )
            }
            db.saleDao().items(saleItems)

            // ---- FIX: deduct in SMALLEST units via toSmallestUnits() (multiplication),
            // not it.mainUnitQty().roundToInt() (division into primary units, which is
            // exactly the "10 Outer / 50 = 0.2 -> rounds to 0, stock never moves" bug).
            // decrease() is guarded (won't go below zero) — already re-validated above,
            // so this should always succeed, but if it somehow doesn't (another sale
            // slipped in between the check and here), we still don't silently pretend
            // the stock moved. ----
            for (line in lines) {
                val product = productsByBarcode[line.barcode] ?: db.productDao().find(line.barcode)
                if (product == null) continue
                val smallestQty = product.toSmallestUnits(line.qty, line.unit).roundToInt()
                val rowsAffected = db.productDao().decrease(line.barcode, smallestQty)
                if (rowsAffected == 0) {
                    Toast.makeText(
                        this@SaleActivity,
                        "Warning: \"${line.itemName}\" ka stock update nahi ho saka — check karen.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            if (customer != null && paid < total) {
                db.customerDao().addBalance(customer!!.id, total - paid)
            }

            if (paid > 0) {
                db.cashTransactionDao().insert(
                    CashTransaction(
                        type = "IN",
                        method = method.lowercase(),
                        amount = paid,
                        reason = "Sale",
                        reference = invoice
                    )
                )
            }

            // Sale is safely persisted now — clear the recovery draft so a future launch
            // doesn't try to restore an already-saved sale.
            suppressDraftSave = true
            clearDraft()
            editInvoice = invoice

            Toast.makeText(
                this@SaleActivity,
                if (original != null) "Sale updated" else "Sale saved",
                Toast.LENGTH_SHORT
            ).show()

            // ---- Open the receipt-style Bill Preview instead of just finishing ----
            val itemsEncoded = lines.joinToString("\u0002") {
                listOf(it.itemName, formatQty(it.qty), it.unit, it.unitPrice, it.amount).joinToString("\u0003")
            }
            val previewIntent = Intent(this@SaleActivity, BillPreviewActivity::class.java).apply {
                putExtra(BillPreviewActivity.EXTRA_TYPE, "sale")
                putExtra(BillPreviewActivity.EXTRA_REFERENCE, invoice)
                putExtra(BillPreviewActivity.EXTRA_PARTY_NAME, customer?.name ?: enteredCustomer)
                putExtra(BillPreviewActivity.EXTRA_PARTY_LABEL, "Customer")
                putExtra(BillPreviewActivity.EXTRA_DATE_MILLIS, saleDateMillis)
                putExtra(BillPreviewActivity.EXTRA_SUBTOTAL, subtotal)
                putExtra(BillPreviewActivity.EXTRA_DISCOUNT, discount)
                putExtra(BillPreviewActivity.EXTRA_TOTAL, total)
                putExtra(BillPreviewActivity.EXTRA_PAID, paid)
                putExtra(BillPreviewActivity.EXTRA_PAYMENT_METHOD, method)
                putExtra(BillPreviewActivity.EXTRA_ITEMS_ENCODED, itemsEncoded)
            }
            startActivity(previewIntent)
            finish()
        }
    }

    // ---- NEW: Delete (edit mode only) — reverses stock + customer balance + the cash-in
    // record, then removes the bill. Mirrors PurchaseActivity.confirmDeletePurchase().
    // ---- FIX: same SMALLEST-unit reversal as the edit path in saveSale() above. ----
    private fun confirmDeleteSale() {
        val invoice = editInvoice ?: return
        AlertDialog.Builder(this)
            .setTitle(com.grocerypos.v11.util.Loc.t(this, "Delete Sale", "سیل حذف کریں"))
            .setMessage(com.grocerypos.v11.util.Loc.t(this, "This will remove the bill and reverse its stock and customer balance effect. Continue?", "یہ بل حذف کر دے گا اور اس کا اسٹاک اور کسٹمر بیلنس پر اثر واپس کر دے گا۔ جاری رکھیں؟"))
            .setPositiveButton(com.grocerypos.v11.util.Loc.t(this, "Delete", "حذف کریں")) { _, _ -> deleteSale(invoice) }
            .setNegativeButton(com.grocerypos.v11.util.Loc.t(this, "Cancel", "منسوخ"), null)
            .show()
    }

    private fun deleteSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)
            val sale = originalSale ?: db.saleDao().findSale(invoice) ?: return@launch
            val items = originalItems.ifEmpty { db.saleDao().itemsForInvoice(invoice) }

            items.forEach { si ->
                val p = db.productDao().find(si.barcode)
                if (p != null) {
                    val smallestQty = p.toSmallestUnits(si.qty.toDouble(), p.unit).roundToInt()
                    db.productDao().increase(si.barcode, smallestQty)
                }
            }
            val outstanding = sale.total - sale.paid
            if (sale.customerId != null && outstanding > 0) {
                db.customerDao().addBalance(sale.customerId, -outstanding)
            }
            db.saleDao().deleteItems(invoice)
            db.saleDao().deleteSale(invoice)
            db.cashTransactionDao().deleteByReference(invoice)

            Toast.makeText(this@SaleActivity, "Sale deleted", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun formatQty(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun holdBill() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "Add items pehle, phir hold karen", Toast.LENGTH_SHORT).show()
            return
        }
        val holdId = "HOLD" + System.currentTimeMillis().toString()
        val payload = encodeHold()

        lifecycleScope.launch {
            PosDatabase.get(this@SaleActivity).heldDao().hold(HeldBill(holdId = holdId, payload = payload))
            Toast.makeText(this@SaleActivity, "Bill hold ho gayi", Toast.LENGTH_SHORT).show()
            clearAll()
        }
    }

    // ---- Encoding includes the tertiary tier now (fields 10 & 11). Old held bills
    // saved before this change only have 10 fields per line — decodeHold() below
    // defaults tertiary to "" / 0.0 for those via getOrNull, so they still recall fine. ----
    private fun encodeHold(): String {
        val header = listOf(
            customerName.text.toString(),
            saleTypeSpinner.selectedItem?.toString() ?: "Retail",
            if (isCashSale) "CASH" else "CREDIT",
            discountInput.text.toString()
        ).joinToString("\u0001")

        val itemsPart = lines.joinToString("\u0002") {
            listOf(
                it.barcode, it.itemName, it.qty, it.unit, it.unitPrice, it.cost, it.amount,
                it.mainUnit, it.secondaryUnit, it.secondaryUnitQty, it.tertiaryUnit, it.tertiaryUnitQty
            ).joinToString("\u0003")
        }
        return header + "\u0004" + itemsPart
    }

    private fun decodeHold(payload: String) {
        val parts = payload.split("\u0004")
        if (parts.isEmpty()) return
        val header = parts[0].split("\u0001")
        if (header.size >= 4) {
            customerName.setText(header[0])
            val saleTypeIndex = if (header[1] == "Wholesale") 1 else 0
            saleTypeSpinner.setSelection(saleTypeIndex)
            setSaleMode(header[2] != "CREDIT")
            discountInput.setText(header[3])
        }

        lines.clear()
        if (parts.size > 1 && parts[1].isNotEmpty()) {
            parts[1].split("\u0002").forEach { row ->
                val f = row.split("\u0003")
                if (f.size >= 10) {
                    lines.add(
                        SaleLine(
                            barcode = f[0],
                            itemName = f[1],
                            qty = f[2].toDoubleOrNull() ?: 0.0,
                            unit = f[3],
                            unitPrice = f[4].toDoubleOrNull() ?: 0.0,
                            cost = f[5].toDoubleOrNull() ?: 0.0,
                            amount = f[6].toDoubleOrNull() ?: 0.0,
                            mainUnit = f[7],
                            secondaryUnit = f[8],
                            secondaryUnitQty = f[9].toDoubleOrNull() ?: 0.0,
                            tertiaryUnit = f.getOrNull(10) ?: "",
                            tertiaryUnitQty = f.getOrNull(11)?.toDoubleOrNull() ?: 0.0
                        )
                    )
                }
            }
        }
        renderItemsList()
        updateTotals()
        if (editInvoice == null) saveDraft()
    }

    private fun clearAll() {
        lines.clear()
        renderItemsList()
        customerName.text.clear()
        discountInput.text.clear()
        itemName.text.clear(); qty.text.clear(); unitPrice.text.clear()
        selectedProduct = null
        lastMainPrice = 0.0
        setSaleMode(true)
        subtotalText.text = "Rs 0.00"
        totalText.text = "Rs 0.00"
        paidInput.text.clear()
        saleDateMillis = System.currentTimeMillis()
        dateValueText.text = formatDate(saleDateMillis)
        clearDraft()
    }

    private fun openRecallDialog() {
        lifecycleScope.launch {
            val held = PosDatabase.get(this@SaleActivity).heldDao().all().first()

            val content = LinearLayout(this@SaleActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor(cardBg))
            }
            val dialogHeader = LinearLayout(this@SaleActivity).apply {
                setPadding(28, 26, 28, 26)
                background = roundedBg(navy, 0)
            }
            dialogHeader.addView(TextView(this@SaleActivity).apply {
                text = "Held Bills"
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            content.addView(dialogHeader)

            val list = LinearLayout(this@SaleActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 18, 24, 16)
            }

            if (held.isEmpty()) {
                list.addView(TextView(this@SaleActivity).apply {
                    text = "Koi held bill nahi hai"
                    setTextColor(Color.parseColor(textGray))
                    setPadding(8, 20, 8, 20)
                })
            }

            val dialog = AlertDialog.Builder(this@SaleActivity).setView(content).create()
            val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

            for (h in held) {
                val itemCount = h.payload.split("\u0004").getOrNull(1)?.split("\u0002")?.filter { it.isNotEmpty() }?.size ?: 0
                val row = LinearLayout(this@SaleActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(18, 16, 18, 16)
                    background = strokedBg(border, "#F7F8FC", 14)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 10) }
                }
                row.addView(circleIcon("\u23F8", amber, 30))
                row.addView(View(this@SaleActivity).apply { layoutParams = LinearLayout.LayoutParams(12, 1) })
                val info = LinearLayout(this@SaleActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                info.addView(TextView(this@SaleActivity).apply {
                    text = "$itemCount items"; textSize = 15f
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                info.addView(TextView(this@SaleActivity).apply {
                    text = fmt.format(Date(h.createdAt))
                    textSize = 12f
                    setTextColor(Color.parseColor(textGray))
                })
                row.addView(info)
                row.addView(TextView(this@SaleActivity).apply {
                    text = "RECALL"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = roundedBg(teal, 20)
                    setPadding(22, 10, 22, 10)
                    setOnClickListener {
                        decodeHold(h.payload)
                        lifecycleScope.launch {
                            PosDatabase.get(this@SaleActivity).heldDao().delete(h)
                        }
                        dialog.dismiss()
                    }
                })
                row.addView(View(this@SaleActivity).apply { layoutParams = LinearLayout.LayoutParams(10, 1) })
                row.addView(TextView(this@SaleActivity).apply {
                    text = "\u2715"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    background = ovalBg(red)
                    gravity = Gravity.CENTER
                    val px = (26 * resources.displayMetrics.density).toInt()
                    width = px; height = px
                    setOnClickListener {
                        lifecycleScope.launch {
                            PosDatabase.get(this@SaleActivity).heldDao().delete(h)
                            Toast.makeText(this@SaleActivity, "Held bill hata di", Toast.LENGTH_SHORT).show()
                        }
                        dialog.dismiss()
                    }
                })
                list.addView(row)
            }
            content.addView(list)

            content.addView(Button(this@SaleActivity).apply {
                text = "Close"
                isAllCaps = false
                setTextColor(Color.parseColor(textGray))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { dialog.dismiss() }
            })

            dialog.show()
        }
    }
}
