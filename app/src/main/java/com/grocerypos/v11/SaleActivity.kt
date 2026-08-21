package com.grocerypos.v11.ui

import android.app.DatePickerDialog
import android.content.Context
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
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AlertDialog
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

class SaleActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INVOICE = "invoice"
        private const val PREFS_NAME = "sale_draft_prefs"
        private const val KEY_DRAFT = "draft_json"
    }

    private val bg = "#F4F6F8"
    private val cardBg = "#FFFFFF"
    private val navy = "#0B2545"
    private val teal = "#0F9B8E"
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
    private lateinit var saleTypeSpinner: Spinner
    private lateinit var addItemsTrigger: TextView
    private lateinit var itemEntrySection: LinearLayout
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var unitPrice: EditText
    private lateinit var itemsContainer: LinearLayout
    private lateinit var billedItemsTrigger: TextView
    private var billedItemsDialog: AlertDialog? = null
    private lateinit var subtotalText: TextView
    private lateinit var discountInput: EditText
    private lateinit var totalText: TextView
    private lateinit var paidInput: EditText
    private lateinit var paymentMethodSpinner: Spinner
    private lateinit var dueAmountText: TextView
    private lateinit var firmNameText: TextView
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button

    private var customers = listOf<Customer>()
    private var products = listOf<Product>()
    private val lines = mutableListOf<SaleLine>()
    private var selectedProduct: Product? = null
    private var isCashSale = true
    private var saleDateMillis = System.currentTimeMillis()

    private var editInvoice: String? = null
    private var originalSale: Sale? = null
    private var originalItems: List<SaleItem> = emptyList()

    private var lastMainPrice: Double = 0.0
    private var suppressPriceWatcher = false
    private var suppressPaidWatcher = false

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

        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 22, 22)
            background = premiumGradientBg(navy, "#123C6B", 26)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 18) }
            applyElevation(this, 10f)
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

        val dateBox = outlinedBox()
        dateBox.setOnClickListener { openDatePicker() }
        dateBox.addView(labelRow("📅 " + com.grocerypos.v11.util.Loc.t(this, "Date", "تاریخ")))
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

        val custBox = outlinedBox()
        custBox.addView(labelRow("👤 " + com.grocerypos.v11.util.Loc.t(this, "Customer", "کسٹمر")))
        val custRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        customerName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Customer Name (Walk-in)", "کسٹمر کا نام (واک ان)")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            threshold = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        custRow.addView(customerName)
        custRow.addView(circleIcon("+", teal, 30) { promptAddCustomer() })
        custBox.addView(custRow)
        root.addView(custBox)
        root.addView(spacer(14))

        val saleTypeBox = outlinedBox()
        saleTypeBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Sale Type", "سیل کی قسم")))
        saleTypeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Retail", "Wholesale"))
        }
        saleTypeBox.addView(saleTypeSpinner)
        root.addView(saleTypeBox)
        root.addView(spacer(16))

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

        itemEntrySection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val itemBox = outlinedBox()
        itemBox.addView(labelRow("📦 " + com.grocerypos.v11.util.Loc.t(this, "Item Name", "آئٹم کا نام")))
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
            background = premiumGradientBg(teal, greenDark, 16)
            setPadding(0, 24, 0, 24)
            setOnClickListener { addItem() }
            applyElevation(this, 4f)
        })
        root.addView(itemEntrySection)
        root.addView(spacer(16))

        // ---- Billed items are no longer rendered inline in the main scroll (that used
        // to sit right under a floating summary card and could get hidden behind the
        // keyboard while typing). Instead this is just a trigger row; tapping it opens
        // the items list in its own dialog/window (see openBilledItemsDialog()). ----
        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val billedItemsBox = outlinedBox()
        val billedItemsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        billedItemsRow.addView(TextView(this).apply {
            text = "🧾  "
            textSize = 15f
        })
        billedItemsTrigger = TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Billed Items (0)", "بل کردہ آئٹمز (0)")
            textSize = 14.5f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        billedItemsRow.addView(billedItemsTrigger)
        billedItemsRow.addView(TextView(this).apply {
            text = "\u203A"
            textSize = 18f
            setTextColor(Color.parseColor(teal))
        })
        billedItemsBox.addView(billedItemsRow)
        billedItemsBox.setOnClickListener { openBilledItemsDialog() }
        root.addView(billedItemsBox)
        root.addView(spacer(14))

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
        root.addView(spacer(12))

        // ---- Total / Discount / Paid / Due controls (created standalone, attached
        // into summaryCard() below). No longer a floating overlay — it now sits
        // inline in the normal scroll flow, so it can never cover the keyboard or
        // hide other controls. ----
        totalText = TextView(this).apply {
            text = "Rs 0.00"; textSize = 16.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        discountInput = EditText(this).apply {
            hint = "0.00"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 13.5f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        discountInput.addTextChangedListener(simpleWatcher { updateTotals() })
        paidInput = EditText(this).apply {
            hint = "0.00"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 13.5f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        paidInput.addTextChangedListener(simpleWatcher {
            if (!suppressPaidWatcher) {
                refreshDue()
                if (editInvoice == null) saveDraft()
            }
        })
        paymentMethodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cash", "Bank"))
        }
        dueAmountText = TextView(this).apply {
            text = "Rs 0.00"; textSize = 16.5f
            setTextColor(Color.parseColor(green))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        root.addView(summaryCard())
        root.addView(spacer(16))

        saveButton = Button(this).apply {
            text = if (editInvoice != null) com.grocerypos.v11.util.Loc.t(this@SaleActivity, "UPDATE SALE", "سیل اپ ڈیٹ کریں") else com.grocerypos.v11.util.Loc.t(this@SaleActivity, "SAVE SALE", "سیل محفوظ کریں")
            setTextColor(Color.WHITE)
            textSize = 15.5f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = premiumGradientBg(navy, "#123C6B", 18)
            setPadding(0, 26, 0, 26)
            setOnClickListener { saveSale() }
            applyElevation(this, 8f)
        }
        deleteButton = Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "DELETE", "حذف کریں")
            setTextColor(Color.WHITE)
            textSize = 15.5f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = premiumGradientBg(red, redDark, 18)
            setPadding(0, 26, 0, 26)
            visibility = if (editInvoice != null) View.VISIBLE else View.GONE
            setOnClickListener { confirmDeleteSale() }
            applyElevation(this, 8f)
        }
        val saveDeleteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveDeleteRow.addView(saveButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) })
        saveDeleteRow.addView(deleteButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply { setMargins(8, 0, 0, 0) })
        root.addView(saveDeleteRow)
        root.addView(spacer(24))

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scrollView)

        loadCustomers()
        loadProducts()
        loadFirmName()
        updateTotals()
        editInvoice?.let { loadForEdit(it) }

        customerName.setOnClickListener { if (customers.isNotEmpty()) customerName.showDropDown() }
        customerName.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && customers.isNotEmpty()) customerName.showDropDown() }

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
                lastMainPrice = 0.0
                refillAutoPrice()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        if (editInvoice == null) {
            restoreDraftIfAny()
        }
    }

    override fun onPause() {
        super.onPause()
        if (editInvoice == null && !suppressDraftSave) {
            saveDraft()
        }
    }

    // Extra safety net: back press (button or gesture) can happen before onPause
    // finishes on some devices/launchers, so save explicitly here too.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (editInvoice == null && !suppressDraftSave) {
            saveDraft()
        }
        super.onBackPressed()
    }

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

    // ---- Loads an existing sale into the form. `unit` ab si.unit se aata hai (stored
    // entered unit), current product ke primary unit se force-nahi hota — SaleItem.unit
    // ab Purchase ki tarah persist hota hai. ----
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
            discountInput.setText(if (sale.discount > 0) "%.2f".format(sale.discount) else "")
            suppressPaidWatcher = true
            paidInput.setText(if (sale.paid > 0) "%.2f".format(sale.paid) else "0.00")
            suppressPaidWatcher = false
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
                        unit = si.unit.ifBlank { product?.unit ?: "" },
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
            recomputeAmounts()
            refreshDue()
            deleteButton.visibility = View.VISIBLE
        }
    }

    // ================= UI helpers =================
    private fun outlinedBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 16, 22, 16)
        background = strokedBg(border, cardBg, 18)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) }
        applyElevation(this, 3f)
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

    // ---- "Bill summary" card: Total / Discount / Paid / Due. Now attached inline in
    // the normal scroll flow (not a floating overlay), so it can never sit on top of
    // the keyboard or block a field while typing. Cash-vs-credit is derived from
    // Paid vs Total (Due = Total − Paid; Due > 0 means part/all of the sale is on
    // credit). ----
    private fun summaryCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = premiumCardBg()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(4)) }
            applyElevation(this, 6f)
        }
        card.addView(TextView(this).apply {
            text = "BILL SUMMARY"
            textSize = 10f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(10))
        })
        card.addView(summaryRow("Total", totalText))
        card.addView(spacer(8))
        card.addView(summaryRow("Discount", discountInput.apply {
            layoutParams = LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT)
        }))
        card.addView(spacer(8))
        val paidRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@SaleActivity).apply {
                text = "Paid"; textSize = 12f
                setTextColor(Color.parseColor(textGray))
                layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(paidInput.apply {
                layoutParams = LinearLayout.LayoutParams(dp(68), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(paymentMethodSpinner.apply {
                layoutParams = LinearLayout.LayoutParams(dp(66), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
        card.addView(paidRow)
        card.addView(spacer(8))
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor(border))
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(1)).apply { setMargins(0, dp(2), 0, dp(8)) }
        })
        card.addView(summaryRow("Due", dueAmountText))
        return card
    }

    private fun summaryRow(label: String, valueView: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@SaleActivity).apply {
            text = label; textSize = 12f
            setTextColor(Color.parseColor(textGray))
            layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        addView(valueView)
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

    private fun premiumGradientBg(fromHex: String, toHex: String, radiusDp: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.parseColor(fromHex), Color.parseColor(toHex))
    ).apply { cornerRadius = dp(radiusDp).toFloat() }

    private fun premiumCardBg() = GradientDrawable().apply {
        setColor(Color.parseColor(cardBg))
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), Color.parseColor("#D7ECE8"))
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        // ---- `cost` yahan is LINE ka TOTAL COGS hai (amount jaisa), per-unit rate nahi —
        // Product.toSmallestUnits() (multiply-only) se compute, PurchaseActivity jaisi
        // approach, kabhi divide-then-round nahi. ----
        val smallestQtyForCost = product.toSmallestUnits(q, chosenUnit)
        val factor = product.smallestUnitFactor()
        val costPerSmallest = if (factor > 0) product.cost / factor else product.cost
        val lineCost = smallestQtyForCost * costPerSmallest

        lines.add(
            SaleLine(
                barcode = product.barcode,
                itemName = product.name,
                qty = q,
                unit = chosenUnit,
                unitPrice = price,
                cost = lineCost,
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

    // ---- Renders the current bill lines into itemsContainer. This container is only
    // ever attached to the screen while the "Billed Items" dialog is open (see
    // openBilledItemsDialog()) — it is intentionally kept out of the main scroll so it
    // never sits behind the keyboard while the user is typing in the item-entry form. ----
    private fun renderItemsList() {
        itemsContainer.removeAllViews()
        lines.forEachIndexed { index, line ->
            itemsContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
                background = strokedBg(border, cardBg, 18)
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
                        if (lines.isEmpty()) billedItemsDialog?.dismiss()
                    }
                })
            })
        }
        updateBilledItemsTrigger()
    }

    private fun updateBilledItemsTrigger() {
        if (!::billedItemsTrigger.isInitialized) return
        val count = lines.size
        val total = lines.sumOf { it.amount }
        billedItemsTrigger.text = com.grocerypos.v11.util.Loc.t(
            this,
            "Billed Items (%d) — Rs %.2f".format(count, total),
            "بل کردہ آئٹمز (%d) — Rs %.2f".format(count, total)
        )
    }

    // ---- Opens the billed-items list in its own dialog/window instead of showing it
    // inline in the main scroll. Keeps typing in the item-entry form free of the
    // keyboard ever covering the list or the bill summary. ----
    private fun openBilledItemsDialog() {
        if (lines.isEmpty()) {
            Toast.makeText(
                this,
                com.grocerypos.v11.util.Loc.t(this, "No items added yet", "ابھی تک کوئی آئٹم شامل نہیں"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        (itemsContainer.parent as? ViewGroup)?.removeView(itemsContainer)
        renderItemsList()

        val scroll = ScrollView(this).apply {
            setPadding(dp(16), dp(12), dp(16), dp(4))
            addView(itemsContainer)
        }

        val dialogHeader = LinearLayout(this).apply {
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = roundedBg(navy, 0)
        }
        dialogHeader.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Billed Items", "بل کردہ آئٹمز")
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(cardBg))
            addView(dialogHeader)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(420)))
            addView(Button(this@SaleActivity).apply {
                text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Close", "بند کریں")
                isAllCaps = false
                setTextColor(Color.parseColor(textGray))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { billedItemsDialog?.dismiss() }
            })
        }

        billedItemsDialog = AlertDialog.Builder(this).setView(wrapper).create()
        billedItemsDialog?.setOnDismissListener {
            (itemsContainer.parent as? ViewGroup)?.removeView(itemsContainer)
            billedItemsDialog = null
        }
        billedItemsDialog?.show()
    }

    // Recomputes subtotal/total display only (does NOT touch the paid field).
    // Returns the current total.
    private fun recomputeAmounts(): Double {
        val subtotal = lines.sumOf { it.amount }
        val discount = discountInput.text.toString().toDoubleOrNull() ?: 0.0
        subtotalText.text = "Rs %.2f".format(subtotal)
        val total = (subtotal - discount).coerceAtLeast(0.0)
        totalText.text = "Rs %.2f".format(total)
        return total
    }

    // Full refresh used during interactive editing (adding/removing items,
    // changing discount): also defaults Paid to the new Total if the user
    // hasn't typed a paid amount yet.
    private fun updateTotals() {
        val total = recomputeAmounts()
        if (paidInput.text.toString().isBlank()) {
            suppressPaidWatcher = true
            paidInput.setText(if (total > 0) "%.2f".format(total) else "")
            suppressPaidWatcher = false
        }
        refreshDue()
        updateBilledItemsTrigger()
    }

    // Due = Total − Paid. Due > 0 means part/all credit — this is what now
    // determines cash-vs-credit instead of the old toggle.
    private fun refreshDue() {
        val total = recomputeAmounts()
        val paid = paidInput.text.toString().toDoubleOrNull() ?: 0.0
        val due = (total - paid).coerceAtLeast(0.0)
        dueAmountText.text = "Rs %.2f".format(due)
        dueAmountText.setTextColor(Color.parseColor(if (due > 0.009) red else green))
        isCashSale = due <= 0.009
    }

    // ================= Save =================
    private fun saveSale() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "Kam az kam ek item add karen", Toast.LENGTH_SHORT).show()
            return
        }
        val enteredCustomer = customerName.text.toString().trim()

        val subtotal = lines.sumOf { it.amount }
        val discount = (discountInput.text.toString().toDoubleOrNull() ?: 0.0).coerceIn(0.0, subtotal)
        val total = (subtotal - discount).coerceAtLeast(0.0)
        val paid = (paidInput.text.toString().toDoubleOrNull() ?: 0.0).coerceIn(0.0, total)
        val due = (total - paid).coerceAtLeast(0.0)

        if (due > 0.009 && enteredCustomer.isEmpty()) {
            Toast.makeText(this, "Due amount ke liye Customer zaroori hai", Toast.LENGTH_SHORT).show()
            return
        }

        val method = if (paid <= 0.009) "credit" else (paymentMethodSpinner.selectedItem?.toString() ?: "Cash")
        var customer = customers.find { it.name.equals(enteredCustomer, ignoreCase = true) }
        val saleType = if (saleTypeSpinner.selectedItem?.toString() == "Wholesale") "wholesale" else "retail"
        // ---- Ref number now starts with a 4-digit month+year (MMyy) so bills are easy
        // to trace by when they were made, followed by a unique suffix. e.g. a sale
        // made in Aug 2026 starts with "0826…". ----
        val invoice = editInvoice ?: run {
            val mmYY = SimpleDateFormat("MMyy", Locale.getDefault()).format(Date(saleDateMillis))
            mmYY + System.currentTimeMillis().toString().takeLast(8)
        }

        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)

            // ---- Edit mode: reverse ORIGINAL bill's stock via its own stored unit
            // (si.unit), same toSmallestUnits() multiply-only approach as Purchase. ----
            val original = originalSale
            if (original != null) {
                originalItems.forEach { si ->
                    val p = db.productDao().find(si.barcode)
                    if (p != null) {
                        val smallestQty = p.toSmallestUnits(si.qty.toDouble(), si.unit.ifBlank { p.unit }).roundToInt()
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

            // ---- Store the actual entered qty/unit/rate (matches PurchaseItem pattern) —
            // no divide-into-primary-unit conversion, so a fractional-primary-unit qty can
            // never round down to 0. ----
            val saleItems = lines.map {
                SaleItem(
                    invoice = invoice,
                    barcode = it.barcode,
                    product = it.itemName,
                    qty = it.qty.roundToInt(),
                    unit = it.unit,
                    unitPrice = it.unitPrice,
                    cost = it.cost,
                    amount = it.amount
                )
            }
            db.saleDao().items(saleItems)

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

            suppressDraftSave = true
            clearDraft()
            editInvoice = invoice

            Toast.makeText(
                this@SaleActivity,
                if (original != null) "Sale updated" else "Sale saved",
                Toast.LENGTH_SHORT
            ).show()

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
                    val smallestQty = p.toSmallestUnits(si.qty.toDouble(), si.unit.ifBlank { p.unit }).roundToInt()
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
        paidInput.text.clear()
        updateTotals()
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
