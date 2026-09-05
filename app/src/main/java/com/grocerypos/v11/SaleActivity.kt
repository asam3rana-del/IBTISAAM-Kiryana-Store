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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.*
import com.grocerypos.v11.domain.SaleLine
import com.grocerypos.v11.pricing.DiscountCalculator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SaleActivity : AppCompatActivity() {

    private val viewModel: SaleViewModel by viewModels { SaleViewModelFactory(applicationContext) }

    companion object {
        const val EXTRA_INVOICE = "invoice"
        private const val PREFS_NAME = "sale_draft_prefs"
        private const val KEY_DRAFT = "draft_json"

        // ---- CHANGED (category-based unit auto-select): category name compared
        // case-insensitively against Product.category for 2-tier products. ----
        private const val BEVERAGE_CATEGORY = "Beverages"
    }

    // ---------- Palette (same colors as before, Purchase-style card vocabulary) ----------
    private val bg = "#F4F6F8"
    private val cardBg = "#FFFFFF"
    private val fieldFill = "#FAFBFD"
    private val navy = "#0B2545"
    private val navyLight = "#123C6B"
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
    private lateinit var firmNameText: TextView
    private lateinit var customerBox: LinearLayout
    private lateinit var customerName: AutoCompleteTextView
    private lateinit var saleTypeSpinner: Spinner
    private lateinit var itemEntrySection: LinearLayout
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var unitToggleRow: LinearLayout
    private lateinit var unitPrice: EditText
    private lateinit var conversionInfo: TextView
    private lateinit var itemLineTotalText: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var billedItemsHeader: LinearLayout
    private lateinit var billedItemsTrigger: TextView
    private lateinit var billedItemsChevron: TextView
    private var billedItemsDialog: AlertDialog? = null
    private lateinit var subtotalText: TextView
    private lateinit var discountInput: EditText
    private lateinit var totalText: TextView
    private lateinit var paymentSection: LinearLayout
    private lateinit var paidInput: EditText
    private lateinit var paidWarningText: TextView
    private lateinit var paymentMethodSpinner: Spinner
    private lateinit var dueAmountText: TextView
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button
    private lateinit var overflowButton: TextView
    private lateinit var scrollView: ScrollView

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
    // FIX (sale-type keyboard-scroll bug): hasFocus() was unreliable right after a
    // touch-driven Spinner selection on some OEM keyboards (e.g. Samsung), so the
    // scroll-to-top / focus-Item-Name step below would silently skip. This flag is
    // set true the instant the spinner is actually touched, so the scroll/focus no
    // longer depends on focus state — it fires on every real user selection, but not
    // on the automatic first-item callback Android fires when the adapter is set.
    private var saleTypeUserInteracted = false
    private var suppressPriceWatcher = false
    private var suppressPaidWatcher = false

    private var suppressDraftSave = false
    private var draftRestored = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        editInvoice = intent.getStringExtra(EXTRA_INVOICE)

        // FIX (#11 — SaleActivity direct edit protection): New Sale stays open to
        // cashier (reasonable — that's their normal job), but Edit/Delete Sale is
        // admin-only per the app's role rules. Without this check, a cashier could
        // reach this same screen with EXTRA_INVOICE set (e.g. via Sale History) and
        // edit/delete an existing bill despite MainActivity only exposing that
        // action to admin — MainActivity hiding a button doesn't stop the Activity
        // itself being opened another way. Same pattern as ProductActivity/
        // PurchaseActivity/UserManagementActivity's role checks.
        if (editInvoice != null) {
            val myRole = getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
            if (myRole != "admin") {
                Toast.makeText(this, "Sirf Admin sale edit/delete kar sakte hain", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 80)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ---------- Header (Purchase-style bleeding gradient header) ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 30, 22, 26)
            background = gradientBg(navy, navyLight, cornerBottom = 26)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(-24, 0, -24, 16) }
            applyElevation(this, 8f)
        }
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = if (editInvoice != null) com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Edit Sale", "سیل میں ترمیم") else com.grocerypos.v11.util.Loc.t(this@SaleActivity, "New Sale", "نئی سیل")
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.01f
        })
        headerCol.addView(TextView(this).apply {
            text = "RETAIL · WHOLESALE BILLING"
            textSize = 10.5f
            setTextColor(Color.parseColor("#9FB4CC"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, 5, 0, 0)
        })
        header.addView(headerCol)
        header.addView(pillChip("Quick Sale", textSizeSp = 13.5f, hPad = 24, vPad = 14) { quickSaleDialog() })
        header.addView(spacer(8).apply { layoutParams = LinearLayout.LayoutParams((6 * resources.displayMetrics.density).toInt(), 1) })
        header.addView(pillChip("History", textSizeSp = 13.5f, hPad = 24, vPad = 14) { startActivity(Intent(this@SaleActivity, SaleHistoryActivity::class.java)) })
        header.addView(spacer(8).apply { layoutParams = LinearLayout.LayoutParams((6 * resources.displayMetrics.density).toInt(), 1) })
        overflowButton = TextView(this).apply {
            text = "\u22EE"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = ovalBg("#22FFFFFF")
            val px = (34 * resources.displayMetrics.density).toInt(); width = px; height = px
            setOnClickListener { showOverflowMenu(it) }
        }
        header.addView(overflowButton)
        root.addView(header)

        // Secondary action row (Hold / Recall) — split full-width, left half Hold, right half Recall
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 14) }
        }
        val holdHalf = TextView(this).apply {
            text = "\u23F8  " + com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Hold", "روکیں")
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(navy))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = strokedBg(border, cardBg, 14)
            setPadding(0, 22, 0, 22)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) }
            applyElevation(this, 1.5f)
            setOnClickListener { holdBill() }
        }
        val recallHalf = TextView(this).apply {
            text = "\u21BA  " + com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Recall", "واپس لائیں")
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(navy))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = strokedBg(border, cardBg, 14)
            setPadding(0, 22, 0, 22)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
            applyElevation(this, 1.5f)
            setOnClickListener { openRecallDialog() }
        }
        actionRow.addView(holdHalf)
        actionRow.addView(recallHalf)
        root.addView(actionRow)

        // ---------- Date chip ----------
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 14) }
        }
        val dateChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 12, 22, 12)
            background = strokedBg(border, cardBg, 30)
            applyElevation(this, 1.5f)
            setOnClickListener { openDatePicker() }
        }
        dateChip.addView(TextView(this).apply { text = "\uD83D\uDCC5  "; textSize = 13f })
        dateValueText = TextView(this).apply {
            text = formatDate(saleDateMillis)
            textSize = 13f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        dateChip.addView(dateValueText)
        dateChip.addView(TextView(this).apply { text = "  \u203A"; textSize = 13f; setTextColor(Color.parseColor(teal)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        topRow.addView(dateChip)
        root.addView(topRow)

        // ---------- Firm card ----------
        val firmBox = premiumCard().apply { setPadding(20, 10, 20, 10) }
        val firmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val firmCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        firmCol.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Firm Name", "فرم کا نام").uppercase()
            textSize = 9.5f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.05f
            gravity = Gravity.CENTER
        })
        firmNameText = TextView(this).apply {
            text = "IBTISAAM Kiryana Store"; textSize = 13.5f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 2, 0, 0)
            gravity = Gravity.CENTER
        }
        firmCol.addView(firmNameText)
        firmRow.addView(firmCol)
        firmBox.addView(firmRow)
        root.addView(firmBox)

        // ---------- Customer card ----------
        customerBox = premiumCard()
        customerBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Customer", "کسٹمر")))
        val custRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        customerName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Customer Name (Walk-in)", "کسٹمر کا نام (واک ان)")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15.5f
            threshold = 1
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        custRow.addView(customerName)
        custRow.addView(circleIcon("+", teal, 32) { promptAddCustomer() })
        customerBox.addView(custRow)
        root.addView(customerBox)
        root.addView(spacer(4))

        // ---------- Sale type ----------
        val saleTypeBox = innerField()
        saleTypeBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Sale Type", "سیل کی قسم")))
        saleTypeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Retail", "Wholesale"))
            isFocusableInTouchMode = true
        }
        saleTypeBox.addView(saleTypeSpinner)
        root.addView(saleTypeBox)
        root.addView(spacer(14))

        // ---------- Item entry card ----------
        itemEntrySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 22, 24, 22)
            background = strokedBg(border, cardBg, 20)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
            applyElevation(this, 3f)
        }
        val addItemHeaderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 16) }
        addItemHeaderRow.addView(TextView(this).apply {
            text = "\u2795  " + com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Add Item", "آئٹم شامل کریں")
            textSize = 15f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        itemEntrySection.addView(addItemHeaderRow)

        val itemBox = innerField()
        itemBox.addView(labelRow("\uD83D\uDCE6 " + com.grocerypos.v11.util.Loc.t(this, "Item Name", "آئٹم کا نام")))
        itemName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Type to search…", "تلاش کے لیے لکھیں…")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15.5f
            threshold = 1
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        itemBox.addView(itemName)
        itemEntrySection.addView(itemBox)

        unitToggleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        itemEntrySection.addView(unitToggleRow)
        itemEntrySection.addView(spacer(10))

        val qtyUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qtyBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } }
        qtyBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Quantity", "مقدار")))
        qty = EditText(this).apply {
            hint = "0"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15.5f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        qtyBox.addView(qty)
        val unitBox = innerField().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) } }
        unitBox.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Unit", "یونٹ")))
        unitSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
        }
        unitBox.addView(unitSpinner)
        qtyUnitRow.addView(qtyBox)
        qtyUnitRow.addView(unitBox)
        itemEntrySection.addView(qtyUnitRow)
        itemEntrySection.addView(spacer(10))

        val rateBox = innerField()
        rateBox.addView(labelRow("\uD83D\uDCB0 " + com.grocerypos.v11.util.Loc.t(this, "Rate", "ریٹ")))
        unitPrice = EditText(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Auto-filled, editable", "خودکار، قابل ترمیم")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15.5f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(windowToken, 0)
                    addItem()
                    true
                } else {
                    false
                }
            }
        }
        rateBox.addView(unitPrice)
        itemEntrySection.addView(rateBox)

        conversionInfo = TextView(this).apply {
            text = ""; textSize = 12f; setTextColor(Color.parseColor(teal)); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(16, 10, 16, 10); visibility = View.GONE
            background = strokedBg("#CDEEEC", "#EFFBFA", 10)
        }
        itemEntrySection.addView(conversionInfo)
        itemEntrySection.addView(spacer(6))

        itemLineTotalText = TextView(this).apply { text = "Total Amount: Rs 0"; textSize = 14.5f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor(navy)); setPadding(6, 8, 0, 10) }
        itemEntrySection.addView(itemLineTotalText)

        itemEntrySection.addView(Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "ADD ITEM", "آئٹم شامل کریں")
            setTextColor(Color.WHITE)
            textSize = 14.5f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = gradientBg(teal, greenDark, cornerBottom = 14, cornerTop = 14)
            setPadding(0, 26, 0, 26)
            applyElevation(this, 3f)
            setOnClickListener { addItem() }
        })
        root.addView(itemEntrySection)

        // ---------- Billed items header ----------
        billedItemsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 16, 22, 16)
            background = gradientBg(navy, navyLight, cornerBottom = 14, cornerTop = 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 4, 0, 0) }
            applyElevation(this, 2f)
            setOnClickListener { openBilledItemsDialog() }
        }
        billedItemsTrigger = TextView(this).apply {
            text = "\uD83E\uDDFE  " + com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Billed Items (0)", "بل کردہ آئٹمز (0)")
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        billedItemsHeader.addView(billedItemsTrigger)
        billedItemsChevron = TextView(this).apply { text = "\u203A"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        billedItemsHeader.addView(billedItemsChevron)
        root.addView(billedItemsHeader)
        root.addView(spacer(14))

        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 0) }

        // ---------- Subtotal + discount card ----------
        val billingCard = premiumCard()
        val subtotalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        subtotalRow.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Subtotal", "سب ٹوٹل").uppercase(); textSize = 11f
            setTextColor(Color.parseColor(textGray)); setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        subtotalText = TextView(this).apply { text = "Rs 0.00"; textSize = 14f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        subtotalRow.addView(subtotalText)
        billingCard.addView(subtotalRow)
        billingCard.addView(spacer(12))
        billingCard.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Discount", "رعایت")))
        discountInput = EditText(this).apply {
            hint = "0.00"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        discountInput.addTextChangedListener(simpleWatcher { updateTotals() })
        billingCard.addView(discountInput)
        root.addView(billingCard)

        // ---------- Grand total card ----------
        val totalCard = premiumCard().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(24, 20, 24, 20); background = strokedBg(border, fieldFill, 18) }
        totalCard.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Total Amount", "کل رقم").uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(textGray))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        totalText = TextView(this).apply { text = "Rs 0.00"; textSize = 21f; setTextColor(Color.parseColor(navy)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        totalCard.addView(totalText)
        root.addView(totalCard)

        // ---------- Payment section ----------
        paymentSection = premiumCard().apply { orientation = LinearLayout.VERTICAL; setPadding(22, 14, 22, 14) }
        val paidRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        paidRow.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Paid Amount", "ادا شدہ رقم").uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(textGray))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        paidRow.addView(TextView(this).apply { text = "Rs "; textSize = 17f; setTextColor(Color.parseColor(green)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        // ---- CHANGED (keyboard-driven save): paidInput now finishes with IME_ACTION_DONE
        // and a listener that hides the keyboard and calls saveSale() directly — so once
        // the user is on Paid Amount, Enter/Done on the keyboard saves the sale without
        // touching the screen. ----
        paidInput = EditText(this).apply {
            hint = "0.00"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(green))
            background = null
            textSize = 20f
            gravity = Gravity.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minWidth = (120 * resources.displayMetrics.density).toInt()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(windowToken, 0)
                    saveSale()
                    true
                } else {
                    false
                }
            }
        }
        paidInput.addTextChangedListener(simpleWatcher {
            if (!suppressPaidWatcher) {
                refreshDue()
                if (editInvoice == null) saveDraft()
            }
        })
        paidRow.addView(paidInput)
        paymentSection.addView(paidRow)
        paymentSection.addView(spacer(6))
        paymentSection.addView(labelRow(com.grocerypos.v11.util.Loc.t(this, "Payment Method", "ادائیگی کا طریقہ")))
        paymentMethodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cash", "Bank"))
        }
        paymentSection.addView(paymentMethodSpinner)
        paidWarningText = TextView(this).apply {
            text = "⚠️ Paid khali hai - Ye Udhaar me jayega"
            textSize = 11f
            setTextColor(Color.parseColor(red))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 0)
            visibility = View.GONE
        }
        paymentSection.addView(paidWarningText)
        root.addView(paymentSection)

        // ---------- Due card ----------
        val dueCard = premiumCard().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(22, 18, 22, 18); background = strokedBg(border, fieldFill, 18) }
        dueCard.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Due Amount", "باقی رقم").uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(textGray))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        dueAmountText = TextView(this).apply { text = "Rs 0.00"; textSize = 18f; setTextColor(Color.parseColor(green)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        dueCard.addView(dueAmountText)
        root.addView(dueCard)
        root.addView(spacer(24))

        // ---------- Save / delete ----------
        saveButton = Button(this).apply {
            text = if (editInvoice != null) com.grocerypos.v11.util.Loc.t(this@SaleActivity, "UPDATE SALE", "سیل اپ ڈیٹ کریں") else com.grocerypos.v11.util.Loc.t(this@SaleActivity, "SAVE SALE", "سیل محفوظ کریں")
            setTextColor(Color.WHITE)
            textSize = 15.5f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = gradientBg(navy, navyLight, cornerBottom = 16, cornerTop = 16)
            setPadding(0, 30, 0, 30)
            applyElevation(this, 5f)
            setOnClickListener { saveSale() }
        }
        deleteButton = Button(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "DELETE", "حذف کریں")
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(red, 16)
            setPadding(0, 30, 0, 30)
            applyElevation(this, 3f)
            visibility = if (editInvoice != null) View.VISIBLE else View.GONE
            setOnClickListener { confirmDeleteSale() }
        }
        val saveDeleteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveDeleteRow.addView(saveButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) })
        saveDeleteRow.addView(deleteButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply { setMargins(8, 0, 0, 0) })
        root.addView(saveDeleteRow)
        root.addView(spacer(30))

        scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scrollView)

        observeViewModel()
        loadFirmName()
        updateTotals()
        editInvoice?.let { loadForEdit(it) }

        customerName.setOnClickListener { if (customers.isNotEmpty()) customerName.showDropDown() }
        customerName.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && customers.isNotEmpty()) customerName.showDropDown() }
        customerName.setOnItemClickListener { _, _, position, _ ->
            val name = customerName.adapter.getItem(position).toString()
            customerName.setText(name)
            customerName.setSelection(customerName.text.length)
            goToSaleTypeFromCustomer()
        }
        customerName.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_NEXT ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                goToSaleTypeFromCustomer()
                true
            } else false
        }

        itemName.setOnItemClickListener { _, _, position, _ ->
            val name = itemName.adapter.getItem(position).toString()
            onItemPicked(name)
        }
        itemName.addTextChangedListener(simpleWatcher {
            val match = products.find { it.name.equals(itemName.text.toString().trim(), ignoreCase = true) }
            if (match == null) {
                selectedProduct = null
                unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
                conversionInfo.visibility = View.GONE
                unitToggleRow.visibility = View.GONE
            }
        })
        itemName.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_NEXT ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val typed = itemName.text.toString().trim()
                val match = products.find { it.name.equals(typed, ignoreCase = true) }
                if (match != null && selectedProduct?.barcode != match.barcode) {
                    onItemPicked(match.name)
                } else {
                    qty.requestFocus()
                    qty.selectAll()
                }
                true
            } else false
        }
        qty.addTextChangedListener(simpleWatcher { updateItemLineTotal() })
        unitPrice.addTextChangedListener(simpleWatcher {
            if (!suppressPriceWatcher) {
                val entered = unitPrice.text.toString().toDoubleOrNull() ?: 0.0
                lastMainPrice = toMainUnitPrice(entered)
            }
            updateItemLineTotal()
        })
        unitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { refillAutoPrice() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        // ---- CHANGED (auto-scroll on Sale Type select): once a sale type is picked, the
        // ScrollView jumps back to the very top (so Item Name is visible right under the
        // header, matching the reference screenshot) before focus moves into Item Name.
        // FIX: previously gated on saleTypeSpinner.hasFocus(), which was unreliable right
        // after a touch-driven selection on some OEM keyboards — now gated on
        // saleTypeUserInteracted instead (set true by the touch listener below), so this
        // reliably fires on every real user selection. ----
        saleTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                lastMainPrice = 0.0
                refillAutoPrice()
                if (saleTypeUserInteracted) {
                    // FIX (position bug): scrolling to absolute 0 landed at the very top
                    // of the screen (Header/Quick Sale/Date/Firm/Customer cards), which
                    // pushed the Add Item section back down under the keyboard. Scrolling
                    // to saleTypeBox's own top position instead matches the reference
                    // screenshot — Sale Type box at the top, Add Item right below it.
                    scrollView.post { scrollView.smoothScrollTo(0, saleTypeBox.top) }
                    itemName.requestFocus()
                    itemName.post {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(itemName, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        saleTypeSpinner.setOnTouchListener { _, _ ->
            saleTypeUserInteracted = true
            false // let the touch continue so the dropdown still opens normally
        }

        if (editInvoice == null) {
            restoreDraftIfAny()
        }
    }

    // ---- Keyboard flow: after customer is picked/confirmed, jump straight into
    // Sale Type so the rest (item -> qty -> rate -> Add Item) can be done without
    // touching the screen. ----
    private fun goToSaleTypeFromCustomer() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(customerName.windowToken, 0)
        saleTypeSpinner.requestFocus()
        saleTypeSpinner.performClick()
    }

    override fun onResume() {
        super.onResume()
        loadFirmName()
    }

    override fun onPause() {
        super.onPause()
        if (editInvoice == null && !suppressDraftSave) {
            saveDraft()
        }
    }

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
            updateItemLineTotal()

            if (lines.isNotEmpty() || pendingItemName.isNotBlank()) {
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
            val savedName = viewModel.firmName()
            firmNameText.text = if (!savedName.isNullOrBlank()) savedName else "IBTISAAM Kiryana Store"
        }
    }

    private fun loadForEdit(invoice: String) {
        lifecycleScope.launch {
            val edit = viewModel.loadForEdit(invoice) ?: return@launch
            originalSale = edit.sale
            originalItems = edit.items

            saleDateMillis = edit.sale.createdAt
            dateValueText.text = formatDate(saleDateMillis)

            customerName.setText(edit.customerName)

            saleTypeSpinner.setSelection(if (edit.sale.saleType == "wholesale") 1 else 0)
            discountInput.setText(if (edit.sale.discount > 0) "%.2f".format(edit.sale.discount) else "")
            suppressPaidWatcher = true
            paidInput.setText(if (edit.sale.paid > 0) "%.2f".format(edit.sale.paid) else "0.00")
            suppressPaidWatcher = false
            val methodIndex = if (edit.sale.paymentMethod.equals("bank", ignoreCase = true)) 1 else 0
            paymentMethodSpinner.setSelection(methodIndex)

            lines.clear()
            lines.addAll(edit.lines)
            renderItemsList()
            recomputeAmounts()
            refreshDue()
            deleteButton.visibility = View.VISIBLE
        }
    }

    // ================= UI helpers (Purchase-style vocabulary) =================
    private fun premiumCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 18, 22, 18)
        background = strokedBg(border, cardBg, 18)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 14) }
        applyElevation(this, 3f)
    }

    private fun innerField() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 13, 18, 13)
        background = strokedBg(border, fieldFill, 14)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
    }

    private fun labelRow(label: String) = TextView(this).apply {
        text = label.uppercase(); textSize = 10.5f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 6); letterSpacing = 0.05f
    }

    private fun pillChip(label: String, textSizeSp: Float = 12.5f, hPad: Int = 22, vPad: Int = 12, onClick: () -> Unit) = TextView(this).apply {
        this.text = label; textSize = textSizeSp; setTextColor(Color.parseColor(navy)); setTypeface(typeface, android.graphics.Typeface.BOLD); background = roundedBg(cardBg, 30); setPadding(hPad, vPad, hPad, vPad); setOnClickListener { onClick() }
    }

    private fun circleIcon(label: String, colorHex: String, sizeDp: Int, onClick: (() -> Unit)? = null) = TextView(this).apply {
        this.text = label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = ovalBg(colorHex); val px = (sizeDp * resources.displayMetrics.density).toInt(); width = px; height = px; if (onClick != null) setOnClickListener { onClick() }
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = radius.toFloat() }
    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(fillHex)); setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex)); cornerRadius = radius.toFloat() }
    private fun ovalBg(colorHex: String) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(colorHex)) }
    private fun gradientBg(startHex: String, endHex: String, cornerTop: Int = 0, cornerBottom: Int = 0) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
    ).apply {
        val density = resources.displayMetrics.density
        cornerRadii = floatArrayOf(
            cornerTop * density, cornerTop * density,
            cornerTop * density, cornerTop * density,
            cornerBottom * density, cornerBottom * density,
            cornerBottom * density, cornerBottom * density
        )
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

    // ---- CHANGED: overflow "Share" now resolves the selected customer (if any) so
    // openBillPreview() can pass a partyId through to BillPreviewActivity's WhatsApp
    // share flow — previously only the typed name was passed. ----
    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(com.grocerypos.v11.util.Loc.t(this, "Print", "پرنٹ"))
        popup.menu.add(com.grocerypos.v11.util.Loc.t(this, "Share", "شیئر کریں"))
        popup.setOnMenuItemClickListener {
            val invoice = editInvoice
            if (invoice == null) {
                Toast.makeText(this, "Save the sale first", Toast.LENGTH_SHORT).show()
            } else {
                val subtotal = lines.sumOf { it.amount }
                val enteredDiscount = discountInput.text.toString().toDoubleOrNull() ?: 0.0
                val enteredPaid = paidInput.text.toString().toDoubleOrNull() ?: 0.0
                val totals = DiscountCalculator.compute(subtotal, enteredDiscount, enteredPaid)
                val method = paymentMethodSpinner.selectedItem?.toString() ?: "Cash"
                val enteredName = customerName.text.toString().trim()
                val matchedCustomer = customers.find { it.name.equals(enteredName, ignoreCase = true) }
                openBillPreview(
                    invoice, forSaving = false,
                    party = enteredName, partyId = matchedCustomer?.id,
                    subtotal = subtotal, discount = totals.discount, total = totals.total,
                    paid = totals.paid, paymentMethod = method
                )
            }
            true
        }
        popup.show()
    }

    /** Continuous customer/product lists (for the two autocomplete fields) come
     * from [SaleViewModel.uiState]; one-shot save/delete/hold outcomes come from
     * [SaleViewModel.events]. The Activity never queries Room directly for
     * either. */
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                customers = state.customers
                customerName.setAdapter(ArrayAdapter(this@SaleActivity, android.R.layout.simple_dropdown_item_1line, state.customers.map { it.name }))
                products = state.products
                itemName.setAdapter(ArrayAdapter(this@SaleActivity, android.R.layout.simple_dropdown_item_1line, state.products.map { it.name }))
            }
        }
        lifecycleScope.launch {
            viewModel.events.collectLatest { event -> handleSaleEvent(event) }
        }
    }

    private fun handleSaleEvent(event: SaleEvent) {
        when (event) {
            is SaleEvent.EmptyItems ->
                Toast.makeText(this, "Kam az kam ek item add karen", Toast.LENGTH_SHORT).show()
            is SaleEvent.CustomerRequiredForDue ->
                Toast.makeText(this, "Due amount ke liye Customer zaroori hai", Toast.LENGTH_SHORT).show()
            is SaleEvent.StockIssue ->
                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
            is SaleEvent.SaveSuccess -> {
                val result = event.result
                result.stockWarnings.forEach { warning ->
                    Toast.makeText(this, warning, Toast.LENGTH_LONG).show()
                }
                suppressDraftSave = true
                clearDraft()
                editInvoice = result.invoice
                Toast.makeText(
                    this,
                    if (result.isUpdate) "Sale updated" else "Sale saved",
                    Toast.LENGTH_SHORT
                ).show()
                openBillPreview(
                    invoice = result.invoice,
                    forSaving = true,
                    party = result.customer?.name ?: result.customerNameEntered,
                    partyId = result.customer?.id,
                    subtotal = result.subtotal,
                    discount = result.discount,
                    total = result.total,
                    paid = result.paid,
                    paymentMethod = result.paymentMethod
                )
            }
            is SaleEvent.QuickSaleSuccess -> {
                vibrateShort()
                Toast.makeText(
                    this,
                    if (event.isCredit) "Credit sale saved: ${event.invoice}" else "Cash sale saved: ${event.invoice}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            is SaleEvent.QuickSaleStockIssue ->
                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
            is SaleEvent.QuickSaleInvalidQty ->
                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
            is SaleEvent.SaleDeleted -> {
                Toast.makeText(this, "Sale deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
            is SaleEvent.BillHeld -> {
                Toast.makeText(this, "Bill hold ho gayi", Toast.LENGTH_SHORT).show()
                clearAll()
            }
            is SaleEvent.CustomerAdded -> {
                Toast.makeText(this, "Customer added", Toast.LENGTH_SHORT).show()
                customerName.setText(event.name)
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
                if (v.isNotEmpty()) viewModel.addCustomer(v)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- FIX (unit auto-selection): single source of truth for which unit tier
    // should be pre-selected when an item is picked, used both here (normal Add
    // Item flow) and in the Quick Sale dialog below — so both stay in sync.
    //
    //   1-tier (no secondary unit at all)         -> index 0 (the only unit)
    //   2-tier (secondary exists, no tertiary):
    //       - Beverages category                  -> index 0 (1st/primary unit)
    //       - every other category                 -> index 1 (2nd/secondary unit)
    //   3-tier (secondary AND tertiary both exist) -> index 1 (secondary/2nd unit)
    //
    // ---- CHANGED (category-based unit auto-select): previously every 2-tier product
    // (no tertiary) defaulted to index 0 regardless of category. Now Beverages keep
    // defaulting to the 1st unit, while every other 2-tier category defaults to the
    // 2nd unit instead — matching how those items are actually sold day to day. ----
    private fun defaultUnitIndexFor(product: Product): Int {
        val hasSecondary = product.secondaryUnit.isNotEmpty()
        val hasTertiary = hasSecondary && product.tertiaryUnit.isNotEmpty() && product.tertiaryUnitQty > 0

        if (!hasSecondary) return 0          // 1-tier: only one unit exists
        if (hasTertiary) return 1            // 3-tier: always default to the 2nd unit

        // 2-tier: Beverages keep the 1st (primary) unit; everything else defaults
        // to the 2nd (secondary) unit.
        val isBeverage = product.category.equals(BEVERAGE_CATEGORY, ignoreCase = true)
        return if (isBeverage) 0 else 1
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
        val defaultIndex = defaultUnitIndexFor(product)
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitChoices)
        unitSpinner.setSelection(defaultIndex)
        buildUnitChips(unitChoices, unitChoices[defaultIndex])
        conversionInfo.text = buildString {
            if (unitChoices.size > 1 && product.secondaryUnitQty > 0) append("1 ${product.unit} = ${product.secondaryUnitQty} ${product.secondaryUnit}")
            if (unitChoices.size > 2 && product.tertiaryUnitQty > 0) { if (isNotEmpty()) append("   •   "); append("1 ${product.secondaryUnit} = ${product.tertiaryUnitQty} ${product.tertiaryUnit}") }
        }
        conversionInfo.visibility = if (conversionInfo.text.isNotEmpty()) View.VISIBLE else View.GONE
        lastMainPrice = 0.0
        refillAutoPrice()
        qty.requestFocus()
        qty.selectAll()
        qty.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(qty, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun buildUnitChips(options: List<String>, selected: String) {
        unitToggleRow.removeAllViews()
        if (options.size < 2) { unitToggleRow.visibility = View.GONE; return }
        unitToggleRow.visibility = View.VISIBLE
        options.forEachIndexed { index, unitLabel ->
            val isSelected = unitLabel == selected
            val chip = TextView(this).apply {
                text = unitLabel; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(28, 13, 28, 13)
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor(teal))
                background = if (isSelected) roundedBg(teal, 30) else strokedBg(teal, cardBg, 30)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(if (index == 0) 0 else 8, 0, 0, 0) }
                setOnClickListener { unitSpinner.setSelection(options.indexOf(unitLabel)); buildUnitChips(options, unitLabel) }
            }
            unitToggleRow.addView(chip)
        }
    }

    // ---- delegated to Database.kt's toPrimaryUnitRate()/fromPrimaryUnitRate() —
    // the same central unitLadder() that backs toSmallestUnits()/fromSmallestUnits() —
    // instead of a duplicated inline formula (was previously copy-pasted separately in
    // PurchaseActivity too — any future fix now only needs to happen in one place). ----
    private fun toMainUnitPrice(entered: Double): Double {
        val product = selectedProduct ?: return entered
        val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
        return product.toPrimaryUnitRate(entered, chosenUnit)
    }

    private fun fromMainUnitPrice(mainPrice: Double, chosenUnit: String): Double {
        val product = selectedProduct ?: return mainPrice
        return product.fromPrimaryUnitRate(mainPrice, chosenUnit)
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
        updateItemLineTotal()
    }

    private fun updateItemLineTotal() {
        val q = qty.text.toString().toDoubleOrNull() ?: 0.0
        val p = unitPrice.text.toString().toDoubleOrNull() ?: 0.0
        itemLineTotalText.text = "Total Amount: Rs %.0f".format(Math.round(q * p).toDouble())
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

        // FIX (fraction control): reject a cart-add whose qty doesn't resolve to a
        // whole smallest-unit for non-fractional items (Piece/Dabbi/Bottle etc.) —
        // catches the bad entry here instead of at save time.
        if (!product.isValidSmallestQty(neededSmallest)) {
            Toast.makeText(this, "Qty ($q $chosenUnit) whole ${product.smallestUnitName()} mein convert nahi hoti", Toast.LENGTH_SHORT).show()
            return
        }

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

        // FIX (UX): after adding an item, jump the ScrollView back to the very top so
        // the rest of the form (Billed Items / Subtotal / Total / Payment / Save) is
        // visible right away instead of staying wherever the keyboard had scrolled to.
        scrollView.post { scrollView.smoothScrollTo(0, 0) }

        itemName.text.clear(); qty.text.clear(); unitPrice.text.clear()
        selectedProduct = null
        lastMainPrice = 0.0
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
        conversionInfo.visibility = View.GONE
        unitToggleRow.visibility = View.GONE
        itemLineTotalText.text = "Total Amount: Rs 0"
        itemName.requestFocus()

        if (editInvoice == null) saveDraft()
        if (lines.isNotEmpty() && paidInput.text.toString().isBlank()) {
            paymentSection.background = strokedBg("#FF9800", "#FFF8E1", 18)
            paymentSection.postDelayed({ paymentSection.background = strokedBg(border, cardBg, 18) }, 2000)
        }
    }

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
                // NEW: split into a muted "Item Subtotal" label + the calculation line below it,
                // matching the reference bill-view layout (label on its own line, qty x rate = amount underneath).
                addView(TextView(this@SaleActivity).apply {
                    text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Item Subtotal", "آئٹم سب ٹوٹل")
                    textSize = 11f
                    setTextColor(Color.parseColor(textGray))
                    setPadding(0, 8, 0, 0)
                })
                addView(TextView(this@SaleActivity).apply {
                    text = "${formatQty(line.qty)} ${line.unit} x ${line.unitPrice} = Rs %.2f".format(line.amount)
                    textSize = 12.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(textDark))
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
        // NEW: footer summary row — Total Qty + Subtotal — shown under the item cards inside
        // the Billed Items dialog, so the totals are visible without closing the dialog.
        if (lines.isNotEmpty()) {
            val totalQty = lines.sumOf { it.qty }
            val subtotal = lines.sumOf { it.amount }
            itemsContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(18, 14, 18, 4)
                addView(TextView(this@SaleActivity).apply {
                    text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Total Qty", "کل مقدار") + ": ${formatQty(totalQty)}"
                    textSize = 12.5f
                    setTextColor(Color.parseColor(textGray))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@SaleActivity).apply {
                    text = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Subtotal", "سب ٹوٹل") + ": Rs %.2f".format(subtotal)
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(teal))
                    gravity = Gravity.END
                })
            })
        }
        updateBilledItemsTrigger()
    }

    private fun updateBilledItemsTrigger() {
        if (!::billedItemsTrigger.isInitialized) return
        val count = lines.size
        val total = lines.sumOf { it.amount }
        billedItemsTrigger.text = "\uD83E\uDDFE  " + com.grocerypos.v11.util.Loc.t(
            this,
            "Billed Items",
            "بل کردہ آئٹمز"
        ) + "  ($count)  ·  Rs %.0f".format(total)
    }

    // ---- Purchase-style billed items dialog: a plain AlertDialog with a Close
    // button, restoring focus to the Paid field on dismiss.
    // ---- CHANGED (billed items -> paid amount flow): the ScrollView now also scrolls
    // down to the Payment section on dismiss (in addition to focusing paidInput), so
    // the field the keyboard is about to fill is actually visible on screen. ----
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
        val wrapper = ScrollView(this).apply {
            setPadding(20, 10, 20, 4)
            addView(itemsContainer)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(com.grocerypos.v11.util.Loc.t(this, "Billed Items", "بل کردہ آئٹمز"))
            .setView(wrapper)
            .setPositiveButton(com.grocerypos.v11.util.Loc.t(this, "Close", "بند کریں"), null)
            .setOnDismissListener {
                (itemsContainer.parent as? ViewGroup)?.removeView(itemsContainer)
                billedItemsDialog = null
                scrollView.post { scrollView.smoothScrollTo(0, paymentSection.top) }
                paidInput.requestFocus()
                paidInput.post {
                    paidInput.selectAll()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(paidInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            .create()
        billedItemsDialog = dialog
        dialog.show()
    }

    // ---- Purchase-style live totals: subtotal/discount/total mirror what the save
    // path will actually persist, computed through the same DiscountCalculator. ----
    private fun recomputeAmounts(): Double {
        val subtotal = lines.sumOf { it.amount }
        val enteredDiscount = discountInput.text.toString().toDoubleOrNull() ?: 0.0
        val totals = DiscountCalculator.compute(subtotal, enteredDiscount, 0.0)
        subtotalText.text = "Rs %.2f".format(subtotal)
        totalText.text = "Rs %.2f".format(totals.total)
        return totals.total
    }

    // FIX (Paid amount bug): this used to auto-fill Paid = Total whenever Paid was
    // blank. That meant Paid silently got stamped with whatever the *first* item's
    // total happened to be (since Paid was blank right after the first add), and then
    // stayed stuck at that stale number as more items were added — while also fighting
    // with the "Paid khali hai - Udhaar jayega" warning below, which assumes Paid
    // starts blank. Paid is now left exactly as the user typed it; nothing auto-fills it.
    private fun updateTotals() {
        recomputeAmounts()
        refreshDue()
        updateBilledItemsTrigger()
    }

    private fun refreshDue() {
        val total = recomputeAmounts()
        val enteredPaid = paidInput.text.toString().toDoubleOrNull() ?: 0.0
        val paidClamped = enteredPaid.coerceIn(0.0, total)
        val due = (total - paidClamped).coerceAtLeast(0.0)
        dueAmountText.text = "Rs %.2f".format(due)
        dueAmountText.setTextColor(Color.parseColor(if (due > 0.009) red else green))
        isCashSale = due <= 0.009
        if (::paidWarningText.isInitialized) {
            if (enteredPaid <= 0.009 && total > 0) {
                paidWarningText.visibility = View.VISIBLE
                paidWarningText.text = "⚠️ Paid khali hai - Rs %.2f Udhaar jayega".format(due)
            } else {
                paidWarningText.visibility = View.GONE
            }
        }
    }

    // ================= QUICK SALE =================
    private fun quickSaleDialog() {
        lifecycleScope.launch {
            showQuickSaleDialog(viewModel.topSellingProducts())
        }
    }

    private fun showQuickSaleDialog(topNames: List<String>) {
        var qsSelectedProduct: Product? = null
        var qsLastMainPrice: Double = 0.0

        // ---- helpers scoped to this dialog ----
        fun qsUnitsFor(p: Product): List<String> {
            val list = mutableListOf(p.unit)
            if (p.secondaryUnit.isNotEmpty()) {
                list.add(p.secondaryUnit)
                if (p.tertiaryUnit.isNotEmpty() && p.tertiaryUnitQty > 0) list.add(p.tertiaryUnit)
            }
            return list
        }
        fun qsToMainPrice(p: Product, entered: Double, unit: String): Double =
            p.toPrimaryUnitRate(entered, unit)
        fun qsFromMainPrice(p: Product, mainPrice: Double, unit: String): Double =
            p.fromPrimaryUnitRate(mainPrice, unit)
        fun qsAvailableInUnit(p: Product, unit: String): Double {
            val perUnitFactor = p.toSmallestUnits(1.0, unit)
            return if (perUnitFactor > 0) p.stock / perUnitFactor else p.stock.toDouble()
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }

        container.addView(TextView(this).apply {
            text = "📦  " + com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Item Name", "آئٹم کا نام")
            textSize = 11f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 0, 0, 6)
        })
        val qsItemName = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Type to search…", "تلاش کے لیے لکھیں…")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = strokedBg(border, cardBg, 12)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            threshold = 0
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        val topAvailable = topNames.filter { name -> products.any { it.name == name } }
        val remaining = products.map { it.name }.filter { it !in topAvailable }
        val orderedNames = (topAvailable + remaining).distinct()
        qsItemName.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, orderedNames))
        container.addView(qsItemName)
        container.addView(spacer(4))

        val qsStockText = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.parseColor(teal))
            setPadding(2, 0, 0, 0)
        }
        container.addView(qsStockText)
        container.addView(spacer(8))

        container.addView(TextView(this).apply {
            text = "📏  " + com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Unit", "یونٹ")
            textSize = 11f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 0, 0, 6)
        })
        val qsUnitSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SaleActivity, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
            background = strokedBg(border, cardBg, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        container.addView(qsUnitSpinner)
        container.addView(spacer(12))

        container.addView(TextView(this).apply {
            text = "🔢  " + com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Quantity", "مقدار")
            textSize = 11f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 0, 0, 6)
        })
        val qsQty = EditText(this).apply {
            hint = "1"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = strokedBg(border, cardBg, 12)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val qsQtyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        qsQtyRow.addView(qsQty)
        qsQtyRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        qsQtyRow.addView(circleIcon("+", teal, 36) {
            val current = qsQty.text.toString().toDoubleOrNull() ?: 0.0
            val next = current + 1
            qsQty.setText(if (next == next.toLong().toDouble()) next.toLong().toString() else next.toString())
            qsQty.setSelection(qsQty.text.length)
        })
        container.addView(qsQtyRow)
        container.addView(spacer(12))

        container.addView(TextView(this).apply {
            text = "💰  " + com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Rate", "ریٹ")
            textSize = 11f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 0, 0, 6)
        })
        val qsPrice = EditText(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Auto-filled, editable", "خودکار، قابل ترمیم")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = strokedBg(border, cardBg, 12)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        container.addView(qsPrice)
        container.addView(spacer(12))

        container.addView(TextView(this).apply {
            text = "👤  " + com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Customer (blank = Cash Sale)", "کسٹمر (خالی = کیش سیل)")
            textSize = 11f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 0, 0, 6)
        })
        val qsCustomer = AutoCompleteTextView(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@SaleActivity, "Blank = Cash, Name = Credit", "خالی = کیش، نام = ادھار")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = strokedBg(border, cardBg, 12)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            threshold = 1
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        qsCustomer.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, customers.map { it.name }))
        container.addView(qsCustomer)
        container.addView(spacer(10))

        val qsTotalText = TextView(this).apply {
            text = "Total: Rs 0.00"
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(2, 0, 0, 4)
        }
        container.addView(qsTotalText)
        container.addView(spacer(4))

        fun qsRefreshTotal() {
            val q = qsQty.text.toString().toDoubleOrNull() ?: 0.0
            val price = qsPrice.text.toString().toDoubleOrNull() ?: 0.0
            qsTotalText.text = "Total: Rs %.2f".format(q * price)
        }
        fun qsRefreshStock() {
            val p = qsSelectedProduct
            if (p == null) { qsStockText.text = ""; return }
            val unit = qsUnitSpinner.selectedItem?.toString() ?: p.unit
            val avail = qsAvailableInUnit(p, unit)
            qsStockText.text = com.grocerypos.v11.util.Loc.t(
                this,
                "Available: %s %s".format(formatQty(avail), unit),
                "دستیاب: %s %s".format(formatQty(avail), unit)
            )
        }

        qsQty.addTextChangedListener(simpleWatcher { qsRefreshTotal() })
        qsPrice.addTextChangedListener(simpleWatcher { qsRefreshTotal() })

        qsItemName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                val typed = qsItemName.text.toString().trim()
                val match = products.find { it.name.equals(typed, ignoreCase = true) }
                if (match != null) {
                    qsSelectedProduct = match
                    qsLastMainPrice = 0.0
                    val qsUnits = qsUnitsFor(match)
                    // FIX (unit auto-selection): same rule as normal Add Item flow —
                    // see defaultUnitIndexFor(). 1-tier defaults to the only unit,
                    // 2-tier defaults by category (Beverages -> 1st, others -> 2nd),
                    // and 3-tier always defaults to the 2nd (secondary) unit.
                    val qsDefaultIndex = defaultUnitIndexFor(match)
                    qsUnitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qsUnits)
                    qsUnitSpinner.setSelection(qsDefaultIndex)
                    if (qsQty.text.toString().isBlank()) qsQty.setText("1")
                    val defaultUnit = qsUnits[qsDefaultIndex]
                    val price = qsFromMainPrice(match, match.salePrice, defaultUnit)
                    qsPrice.setText(if (price > 0) "%.2f".format(price) else "")
                    qsRefreshStock(); qsRefreshTotal()
                }
                qsQty.requestFocus()
                qsQty.setSelection(qsQty.text.length)
                true
            } else false
        }
        qsQty.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { qsPrice.requestFocus(); true } else false
        }
        qsPrice.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { qsCustomer.requestFocus(); true } else false
        }

        qsItemName.setOnItemClickListener { _, _, position, _ ->
            val name = qsItemName.adapter.getItem(position).toString()
            val p = products.find { it.name.equals(name, ignoreCase = true) }
            qsSelectedProduct = p
            qsLastMainPrice = 0.0
            if (p != null) {
                val qsUnits = qsUnitsFor(p)
                // FIX (unit auto-selection): same rule as above — see defaultUnitIndexFor().
                val qsDefaultIndex = defaultUnitIndexFor(p)
                qsUnitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qsUnits)
                qsUnitSpinner.setSelection(qsDefaultIndex)
                qsQty.setText(if (qsQty.text.toString().isBlank()) "1" else qsQty.text.toString())
                val defaultUnit = qsUnits[qsDefaultIndex]
                val price = qsFromMainPrice(p, p.salePrice, defaultUnit)
                qsPrice.setText(if (price > 0) "%.2f".format(price) else "")
                qsQty.requestFocus()
                qsQty.setSelection(qsQty.text.length)
                qsRefreshStock(); qsRefreshTotal()
            }
        }

        qsUnitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val p = qsSelectedProduct ?: return
                val unit = qsUnitSpinner.selectedItem?.toString() ?: p.unit
                val base = if (qsLastMainPrice > 0) qsLastMainPrice else p.salePrice
                val price = qsFromMainPrice(p, base, unit)
                qsPrice.setText(if (price > 0) "%.2f".format(price) else "")
                qsRefreshStock(); qsRefreshTotal()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        qsPrice.addTextChangedListener(simpleWatcher {
            val p = qsSelectedProduct ?: return@simpleWatcher
            val unit = qsUnitSpinner.selectedItem?.toString() ?: p.unit
            val entered = qsPrice.text.toString().toDoubleOrNull() ?: 0.0
            qsLastMainPrice = qsToMainPrice(p, entered, unit)
        })

        val scroll = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(com.grocerypos.v11.util.Loc.t(this, "Quick Sale", "فوری سیل"))
            .setView(scroll)
            .setPositiveButton(com.grocerypos.v11.util.Loc.t(this, "SAVE", "محفوظ کریں"), null)
            .setNegativeButton(com.grocerypos.v11.util.Loc.t(this, "Cancel", "منسوخ"), null)
            .create()

        dialog.setOnShowListener {
            qsItemName.post { qsItemName.showDropDown() }
            val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveBtn.setOnClickListener {
                val typedName = qsItemName.text.toString().trim()
                val product = qsSelectedProduct?.takeIf { it.name.equals(typedName, ignoreCase = true) }
                    ?: products.find { it.name.equals(typedName, ignoreCase = true) }
                if (product == null) {
                    Toast.makeText(this, "Ye item product list mein nahi hai", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val q = qsQty.text.toString().toDoubleOrNull() ?: 0.0
                if (q <= 0) {
                    Toast.makeText(this, "Quantity theek se likhen", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val chosenUnit = qsUnitSpinner.selectedItem?.toString() ?: product.unit
                val price = qsPrice.text.toString().toDoubleOrNull() ?: product.salePrice
                val neededSmallest = product.toSmallestUnits(q, chosenUnit)
                if (product.stock < neededSmallest) {
                    Toast.makeText(
                        this,
                        "Stock kam hai (available: ${formatQty(product.stock.toDouble())} ${product.smallestUnitName()})",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                val custName = qsCustomer.text.toString().trim()
                saveQuickSale(product, q, price, chosenUnit, custName)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveQuickSale(product: Product, q: Double, price: Double, unit: String, custName: String) {
        viewModel.saveQuickSale(product, q, price, unit, custName)
    }

    private fun vibrateShort() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(android.os.VibratorManager::class.java)
                vm?.defaultVibrator?.vibrate(
                    android.os.VibrationEffect.createOneShot(35, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(android.os.VibrationEffect.createOneShot(35, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(35)
                }
            }
        } catch (e: Exception) {
            // no vibrator permission/hardware — ignore silently
        }
    }

    // ================= Save =================
    // ---- discount/total/paid/due come from the same DiscountCalculator used
    // everywhere else in this screen (recomputeAmounts/refreshDue), so the live
    // preview and the saved bill always agree. ----
    private fun saveSale() {
        val enteredCustomer = customerName.text.toString().trim()
        val enteredDiscount = discountInput.text.toString().toDoubleOrNull() ?: 0.0
        val enteredPaid = paidInput.text.toString().toDoubleOrNull() ?: 0.0
        val saleTypeLabel = saleTypeSpinner.selectedItem?.toString() ?: "Retail"
        val paymentMethodLabel = paymentMethodSpinner.selectedItem?.toString() ?: "Cash"

        // FIX (Phase 1 - Data Safety): the whole save (stock reversal on edit, stock
        // check, stock deduction, sale+items insert, customer balance update, cash
        // transaction insert) runs inside a single Room transaction in
        // SaleRepository.saveSale — see that function for details. Validation (empty
        // items, due needs a customer, discount/total/paid clamping) happens first in
        // SaveSaleUseCase; this screen only reacts to the resulting SaleEvent.
        viewModel.saveSale(
            editInvoice = editInvoice,
            enteredCustomerName = enteredCustomer,
            saleTypeLabel = saleTypeLabel,
            lines = lines.toList(),
            discountInput = enteredDiscount,
            paidInput = enteredPaid,
            paymentMethodLabel = paymentMethodLabel,
            saleDateMillis = saleDateMillis,
            original = originalSale,
            originalItems = originalItems
        )
    }

    // ---- Purchase-style single reusable bill-preview launcher, used both for the
    // normal post-save navigation and for the overflow menu's reprint/share flow.
    // CHANGED: now also accepts partyId so BillPreviewActivity can look up (or later
    // save) the customer's phone number for the WhatsApp share button. ----
    private fun openBillPreview(invoice: String, forSaving: Boolean, party: String, partyId: Long?, subtotal: Double, discount: Double, total: Double, paid: Double, paymentMethod: String) {
        val itemsEncoded = lines.joinToString("\u0002") {
            listOf(it.itemName, formatQty(it.qty), it.unit, it.unitPrice, it.amount).joinToString("\u0003")
        }
        val previewIntent = Intent(this, BillPreviewActivity::class.java).apply {
            putExtra(BillPreviewActivity.EXTRA_TYPE, "sale")
            putExtra(BillPreviewActivity.EXTRA_REFERENCE, invoice)
            putExtra(BillPreviewActivity.EXTRA_PARTY_NAME, party)
            putExtra(BillPreviewActivity.EXTRA_PARTY_LABEL, "Customer")
            if (partyId != null) putExtra(BillPreviewActivity.EXTRA_PARTY_ID, partyId)
            putExtra(BillPreviewActivity.EXTRA_DATE_MILLIS, saleDateMillis)
            putExtra(BillPreviewActivity.EXTRA_SUBTOTAL, subtotal)
            putExtra(BillPreviewActivity.EXTRA_DISCOUNT, discount)
            putExtra(BillPreviewActivity.EXTRA_TOTAL, total)
            putExtra(BillPreviewActivity.EXTRA_PAID, paid)
            putExtra(BillPreviewActivity.EXTRA_PAYMENT_METHOD, paymentMethod)
            putExtra(BillPreviewActivity.EXTRA_ITEMS_ENCODED, itemsEncoded)
        }
        startActivity(previewIntent)
        if (forSaving) finish()
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
        // FIX (Phase 1 - Data Safety): stock reversal + balance reversal + row
        // deletes are one atomic transaction — see SaleRepository.deleteSale.
        viewModel.deleteSale(invoice, originalSale, originalItems)
    }

    private fun formatQty(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun holdBill() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "Add items pehle, phir hold karen", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.holdBill(encodeHold())
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
        conversionInfo.visibility = View.GONE
        unitToggleRow.visibility = View.GONE
        itemLineTotalText.text = "Total Amount: Rs 0"
        paidInput.text.clear()
        updateTotals()
        saleDateMillis = System.currentTimeMillis()
        dateValueText.text = formatDate(saleDateMillis)
        clearDraft()
    }

    private fun openRecallDialog() {
        lifecycleScope.launch {
            val held = viewModel.heldBills()

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
                        viewModel.deleteHeldBill(h)
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
                        viewModel.deleteHeldBill(h)
                        Toast.makeText(this@SaleActivity, "Held bill hata di", Toast.LENGTH_SHORT).show()
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
