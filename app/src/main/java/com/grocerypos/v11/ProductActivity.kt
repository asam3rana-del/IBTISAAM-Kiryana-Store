package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Category
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.UnitType
import com.grocerypos.v11.formatStockBreakdown
import com.grocerypos.v11.isValidSmallestQty
import com.grocerypos.v11.smallestUnitName
import com.grocerypos.v11.toSmallestUnits
import com.grocerypos.v11.util.Loc
import com.grocerypos.v11.util.ThemeManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class ProductActivity : ThemedActivity() {

    companion object {
        const val EXTRA_EDIT_BARCODE = "editBarcode"
        private const val TAG = "ProductActivity"
    }

    // ---- Premium palette — kept compatible with Purchase/Sale. ----
    private var bg = "#F4F6F8"
    private var cardWhite = "#FFFFFF"
    private var navy = "#0B2545"
    private var navyLight = "#173863"
    private var teal = "#0F9B8E"
    private var red = "#E5484D"
    private var blue = "#3B82F6"
    private var orange = "#F5A524"
    private var purple = "#8B5CF6"
    private var textDark = "#0B2545"
    private var textMuted = "#7C8798"
    private var border = "#E3E8EE"
    private var amber = "#F5A524"

    private var fieldFill = "#FAFBFC"
    private var headerSubtitleColor = "#9FB4CC"
    private var headerBadgeOverlay = "#33FFFFFF"
    private var savedHighlightBg = "#E9FBF9"

    private fun loadThemePrefs() {
        val p = ThemeManager.palette(this)
        bg = p.bg
        cardWhite = p.cardWhite
        navy = p.navy
        teal = p.teal
        red = p.red
        textDark = p.textDark
        textMuted = p.textMuted
        border = p.border
        amber = p.amber
        fieldFill = p.fieldFill
        headerSubtitleColor = p.headerSubtitleColor
        headerBadgeOverlay = p.headerBadgeOverlay
        savedHighlightBg = p.savedHighlightBg
    }

    private fun toggleTheme() {
        ThemeManager.toggleDarkMode(this)
        recreate()
    }

    private lateinit var scrollView: ScrollView
    private lateinit var formCardTitle: TextView
    private lateinit var name: EditText
    private lateinit var selectUnitBtn: TextView
    private lateinit var categoryField: AutoCompleteTextView
    private lateinit var cost: EditText
    private lateinit var wholesalePrice: EditText
    private lateinit var salePrice: EditText
    // ---- NEW: tracks whether the shopkeeper has personally typed into Retail/
    // Wholesale (or the item already had a saved rate) for this open form. See
    // autoFillRatesFromCost() — a field stays untouched by auto-markup once
    // either is true, so an item's price can be pinned regardless of Purchase
    // Rate changes just by not clearing that field. ----
    private var retailManuallyEdited = false
    private var wholesaleManuallyEdited = false
    private var isAutoFillingRates = false
    private lateinit var stock: EditText
    private lateinit var stockUnitSpinner: Spinner
    private lateinit var stockPreview: TextView
    private lateinit var stockNote: TextView
    private lateinit var reorderLevel: EditText
    private lateinit var saveButton: Button
    private lateinit var deleteFormButton: TextView
    private lateinit var cancelEditChip: TextView
    private lateinit var searchField: EditText
    private lateinit var listContainer: LinearLayout
    private lateinit var noResultsCard: LinearLayout
    private lateinit var productsSectionAnchor: View
    private lateinit var productsSectionContainer: LinearLayout

    private var focusedFieldForScroll: View? = null
    private var units: List<String> = emptyList()
    private var categoryNames: List<String> = listOf("General")

    private var selectedPrimaryUnit = "pcs"
    private var selectedSecondaryUnit = "None"
    private var selectedSecondaryQty = 0.0
    private var selectedTertiaryUnit = "None"
    private var selectedTertiaryQty = 0.0

    private var selectedOpeningStockUnit = "pcs"
    private var editingProduct: Product? = null
    private var allProducts: List<Product> = emptyList()

    private var justSavedBarcode: String? = null
    private var pendingScrollToSaved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FIX (Phase 4 - Security): role check enforced here too, not just by hiding the
        // "Products" tile for non-admins in MainActivity — that only stopped normal
        // navigation, not a cashier reaching this Activity another way (recents, restored
        // task, deep link).
        val myRole = getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        if (myRole != "admin") {
            Toast.makeText(this, "Sirf Admin is screen ko access kar sakta hai", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        loadThemePrefs()

        val contentRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 44, 24, 28)
        }

        buildHeader(scrollContent)
        buildProductForm(scrollContent)
        buildProductsSection(scrollContent)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(scrollContent)
            isFillViewport = true
        }

        scrollView.viewTreeObserver.addOnGlobalLayoutListener {
            focusedFieldForScroll?.let { scrollFieldIntoView(it) }
        }

        saveButton = Button(this).apply {
            text = "💾  " + Loc.t(this@ProductActivity, "SAVE PRODUCT", "پروڈکٹ محفوظ کریں")
            setTextColor(Color.WHITE)
            textSize = 15.5f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = gradientBg(navy, navyLight, cornerTop = 16, cornerBottom = 16)
            setPadding(0, 26, 0, 26)
            setOnClickListener { saveProduct() }
            applyElevation(this, 5f)
        }

        val saveBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 14, 24, 18)
            setBackgroundColor(Color.parseColor(cardWhite))
            applyElevation(this, 8f)
            addView(
                saveButton,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(saveBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 18 + bars.bottom)
            insets
        }

        contentRoot.addView(scrollView)
        contentRoot.addView(saveBar)
        setContentView(contentRoot)

        loadCategories()
        loadUnits()
        loadProducts()

        intent.getStringExtra(EXTRA_EDIT_BARCODE)?.let { barcode ->
            lifecycleScope.launch {
                PosDatabase.get(this@ProductActivity).productDao().find(barcode)?.let {
                    loadProductForEdit(it)
                }
            }
        }
    }

    private fun buildHeader(root: LinearLayout) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 22, 22)
            background = gradientBg(navy, navyLight, cornerTop = 20, cornerBottom = 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 18) }
            applyElevation(this, 6f)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        col.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Add / Edit Product", "پروڈکٹ شامل / تبدیل کریں")
            textSize = 18.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })

        col.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Inventory Management", "انوینٹری مینجمنٹ")
            textSize = 11f
            setTextColor(Color.parseColor(headerSubtitleColor))
            setPadding(0, 3, 0, 0)
        })

        header.addView(col)

        header.addView(TextView(this).apply {
            text = if (isDarkMode) "☀️" else "🌙"
            textSize = 15f
            setPadding(14, 12, 14, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(headerBadgeOverlay))
                cornerRadius = 30f
            }
            setOnClickListener { toggleTheme() }
        })

        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(10.dp(), 1)
        })

        header.addView(TextView(this).apply {
            text = "📋 " + Loc.t(this@ProductActivity, "View List", "فہرست دیکھیں")
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(headerBadgeOverlay))
                cornerRadius = 30f
            }
            setPadding(20, 12, 20, 12)
            setOnClickListener { toggleProductsList() }
        })

        root.addView(header)
    }

    private fun buildProductForm(root: LinearLayout) {
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        formCardTitle = TextView(this).apply {
            text = "✚  " + Loc.t(this@ProductActivity, "New Product", "نئی پروڈکٹ")
            textSize = 14.5f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        deleteFormButton = TextView(this).apply {
            text = "🗑️  " + Loc.t(this@ProductActivity, "Delete", "حذف کریں")
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = gradientBg(red, "#C93B40", cornerTop = 30, cornerBottom = 30)
            setPadding(22, 12, 22, 12)
            visibility = View.GONE
            applyElevation(this, 2f)
            setOnClickListener { editingProduct?.let { confirmDeleteProduct(it) } }
        }

        cancelEditChip = TextView(this).apply {
            text = "✕  " + Loc.t(this@ProductActivity, "Cancel Edit", "ترمیم منسوخ کریں")
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(textMuted, 30)
            setPadding(24, 12, 24, 12)
            visibility = View.GONE
            setOnClickListener { clearForm() }
        }

        titleRow.addView(formCardTitle)
        titleRow.addView(deleteFormButton)
        titleRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(8.dp(), 1)
        })
        titleRow.addView(cancelEditChip)
        root.addView(titleRow)
        root.addView(spacer(10))

        // ================= PRODUCT NAME CARD =================
        val nameCard = premiumCard(accentTopHex = teal)
        nameCard.addView(sectionLabel("🏷️", Loc.t(this, "Product Name", "پروڈکٹ کا نام"), teal))

        val nameBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 8, 8, 8)
            background = strokedBg(border, fieldFill, 16)
        }

        name = EditText(this).apply {
            hint = Loc.t(this@ProductActivity, "Product Name", "پروڈکٹ کا نام")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        selectUnitBtn = TextView(this).apply {
            text = "📏 " + Loc.t(this@ProductActivity, "Select Unit", "یونٹ منتخب کریں")
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = gradientBg(teal, "#0C8F8A", cornerTop = 30, cornerBottom = 30)
            setPadding(26, 15, 26, 15)
            applyElevation(this, 3f)
            setOnClickListener { openUnitDialog() }
        }

        nameBox.addView(name)
        nameBox.addView(selectUnitBtn)
        nameCard.addView(nameBox)
        root.addView(nameCard)

        // ================= CATEGORY CARD =================
        val categoryCard = premiumCard(accentTopHex = purple)
        categoryCard.addView(sectionLabel("🗂️", Loc.t(this, "Category", "کیٹیگری"), purple))

        val categoryBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, fieldFill, 16)
            setPadding(18, 6, 18, 6)
        }

        categoryField = AutoCompleteTextView(this).apply {
            hint = Loc.t(this@ProductActivity, "Type or pick a category", "کیٹیگری لکھیں یا منتخب کریں")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            threshold = 1
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        categoryBox.addView(categoryField)
        categoryCard.addView(categoryBox)
        categoryCard.addView(spacer(10))
        categoryCard.addView(
            pillLink("✚  " + Loc.t(this, "Add New Category", "نئی کیٹیگری شامل کریں")) {
                promptAddCategory()
            }
        )
        root.addView(categoryCard)

        // ================= PRICING CARD (premium: persistent labels + colored badges) =================
        val ratesCard = premiumCard(accentTopHex = amber)
        ratesCard.addView(sectionLabel("💰", Loc.t(this, "Pricing", "قیمتیں"), amber))

        cost = rateField()
        ratesCard.addView(
            premiumLabeledField(
                cost, "🛒",
                Loc.t(this, "Purchase Rate", "خریداری کی قیمت"),
                amber
            )
        )
        ratesCard.addView(spacer(12))

        wholesalePrice = rateField()
        ratesCard.addView(
            premiumLabeledField(
                wholesalePrice, "📦",
                Loc.t(this, "Wholesale Sale Rate", "تھوک فروخت کی قیمت"),
                blue
            )
        )
        ratesCard.addView(spacer(12))

        salePrice = rateField()
        ratesCard.addView(
            premiumLabeledField(
                salePrice, "🏪",
                Loc.t(this, "Retail Sale Rate", "پرچون فروخت کی قیمت"),
                teal
            )
        )
        ratesCard.addView(spacer(12))

        // ---- NEW: auto-fill Wholesale/Retail from Purchase Rate using the
        // shop's saved Markup % (see RateMarkupSettings, set from the Dashboard's
        // Items tab). Only ever fills a field the shopkeeper hasn't personally
        // typed into and that doesn't already have a saved rate — see
        // autoFillRatesFromCost(). ----
        salePrice.addTextChangedListener(simpleWatcher { if (!isAutoFillingRates) retailManuallyEdited = true })
        wholesalePrice.addTextChangedListener(simpleWatcher { if (!isAutoFillingRates) wholesaleManuallyEdited = true })
        cost.addTextChangedListener(simpleWatcher {
            autoFillRatesFromCost(cost.text.toString().trim().toDoubleOrNull() ?: 0.0)
        })

        // ---- Opening Stock: same premium labeled-badge treatment as the price fields above,
        // with the unit Spinner sharing the same row so quantity + unit read as one control. ----
        stock = EditText(this).apply {
            hint = Loc.t(this@ProductActivity, "0", "0")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addTextChangedListener(simpleWatcher { updateOpeningStockPreview() })
        }

        val stockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, fieldFill, 16)
            setPadding(14, 8, 8, 8)
        }
        stockRow.addView(badgeIcon("🔢", navy))
        stockRow.addView(spacer(14).apply {
            layoutParams = LinearLayout.LayoutParams(14.dp(), 1)
        })
        val stockCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        stockCol.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Opening Stock", "ابتدائی اسٹاک").uppercase()
            textSize = 9.5f
            setTextColor(Color.parseColor(navy))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.02f
        })
        stockCol.addView(stock)
        stockRow.addView(stockCol)

        stockUnitSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                110.dp(), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        stockRow.addView(stockUnitSpinner)
        ratesCard.addView(stockRow)

        stockPreview = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(6, 8, 0, 0)
        }
        ratesCard.addView(stockPreview)

        stockNote = TextView(this).apply {
            text = Loc.t(
                this@ProductActivity,
                "Stock is locked while editing — change it via Purchase/Sale instead.",
                "ترمیم کے دوران اسٹاک لاک ہے — اسٹاک تبدیل کرنے کے لیے Purchase/Sale استعمال کریں۔"
            )
            textSize = 11f
            setTextColor(Color.parseColor(amber))
            setPadding(6, 8, 0, 0)
            visibility = View.GONE
        }
        ratesCard.addView(stockNote)

        // ---- FIX: reorderLevel had no UI field anywhere, so it always stayed the DB default
        // (0), which meant "Low Stock" alerts (StockReportActivity / ProductDao.lowStock())
        // never fired until stock hit exactly zero. Entered here in the product's SMALLEST
        // unit (same unit stock is stored/compared in), so it lines up with `stock<=reorderLevel`. ----
        reorderLevel = EditText(this).apply {
            hint = Loc.t(this@ProductActivity, "0", "0")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        ratesCard.addView(spacer(12))
        ratesCard.addView(
            premiumLabeledField(
                reorderLevel, "⚠️",
                Loc.t(this, "Reorder Level (smallest unit)", "ری آرڈر لیول (سب سے چھوٹی یونٹ)"),
                red
            )
        )
        ratesCard.addView(TextView(this).apply {
            text = Loc.t(
                this@ProductActivity,
                "Alert when stock falls to/below this many smallest units (e.g. pcs). Leave 0 for no alert.",
                "جب اسٹاک اس تعداد (سب سے چھوٹی یونٹ) تک یا کم ہو جائے تو الرٹ کریں۔ الرٹ نہ چاہیے تو 0 رہنے دیں۔"
            )
            textSize = 11f
            setTextColor(Color.parseColor(textMuted))
            setPadding(6, 6, 0, 0)
        })

        root.addView(ratesCard)

        name.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { categoryField.requestFocus(); true } else false
        }
        categoryField.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                safeShowDropDown(categoryField)
                focusedFieldForScroll = categoryField
                scrollView.post { scrollFieldIntoView(categoryField) }
            }
        }
        categoryField.addTextChangedListener(simpleWatcher {
            if (categoryField.hasFocus() && categoryField.text.length >= 1) safeShowDropDown(categoryField)
        })
        categoryField.setOnItemClickListener { _, _, _, _ -> cost.requestFocus() }
        categoryField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { cost.requestFocus(); true } else false
        }
        cost.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { wholesalePrice.requestFocus(); true } else false
        }
        wholesalePrice.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { salePrice.requestFocus(); true } else false
        }
        salePrice.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { stock.requestFocus(); true } else false
        }
        stock.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { hideKeyboard(); saveProduct(); true } else false
        }

        name.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                focusedFieldForScroll = name
                scrollView.post { scrollFieldIntoView(name) }
            }
        }
        cost.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                focusedFieldForScroll = cost
                scrollView.post { scrollFieldIntoView(cost) }
            }
        }
        wholesalePrice.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                focusedFieldForScroll = wholesalePrice
                scrollView.post { scrollFieldIntoView(wholesalePrice) }
            }
        }
        salePrice.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                focusedFieldForScroll = salePrice
                scrollView.post { scrollFieldIntoView(salePrice) }
            }
        }
        stock.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                focusedFieldForScroll = stock
                scrollView.post { scrollFieldIntoView(stock) }
            }
        }
    }

    private fun buildProductsSection(root: LinearLayout) {
        root.addView(spacer(4))
        productsSectionAnchor = sectionLabel("🗃️", Loc.t(this, "Products", "پروڈکٹس"), navy)
        root.addView(productsSectionAnchor)
        root.addView(spacer(10))

        productsSectionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 6, 20, 6)
            background = strokedBg(border, cardWhite, 30)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, 14)
            }
            applyElevation(this, 1.5f)
        }

        searchBox.addView(TextView(this).apply {
            text = "🔍  "
            textSize = 15f
        })

        searchField = EditText(this).apply {
            hint = Loc.t(
                this@ProductActivity,
                "Search products by name or category…",
                "نام یا کیٹیگری سے پروڈکٹ تلاش کریں…"
            )
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 14.5f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        searchBox.addView(searchField)

        val clearSearchBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(14, 10, 6, 10)
            visibility = View.GONE
            setOnClickListener { searchField.text.clear() }
        }
        searchBox.addView(clearSearchBtn)
        productsSectionContainer.addView(searchBox)

        searchField.addTextChangedListener(simpleWatcher {
            val q = searchField.text.toString()
            clearSearchBtn.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
            renderProducts(filterProducts(q))
        })
        searchField.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                focusedFieldForScroll = searchField
                scrollView.post { scrollFieldIntoView(searchField) }
            }
        }

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        productsSectionContainer.addView(listContainer)

        noResultsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 30, 20, 30)
            background = strokedBg(border, cardWhite, 14)
            visibility = View.GONE

            addView(TextView(this@ProductActivity).apply {
                text = "🔍"
                textSize = 26f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@ProductActivity).apply {
                text = Loc.t(
                    this@ProductActivity,
                    "No matching products",
                    "کوئی مماثل پروڈکٹ نہیں ملی"
                )
                textSize = 13f
                setTextColor(Color.parseColor(textMuted))
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 0)
            })
        }
        productsSectionContainer.addView(noResultsCard)

        root.addView(productsSectionContainer)
        root.addView(spacer(24))
    }

    // ---------------- UI helpers ----------------

    // ---- ULTRA PREMIUM: cards now get a thin colored top accent strip (matching the card's
    // section color) sitting above a softer, larger-radius white body, plus a touch more
    // elevation than before so each card reads as a distinct "floating" surface rather than a
    // flat bordered box. Pass null for a neutral card (no accent strip). ----
    private fun premiumCard(accentTopHex: String? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 20, 22, 20)
        background = strokedBg(border, cardWhite, 22)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, 16)
        }
        applyElevation(this, 4f)

        // ---- Thin two-tone accent strip along the top edge, matching the card's section
        // color, so each card reads as visually distinct at a glance. Negative margins pull
        // it out past the card's own padding so it bleeds fully edge-to-edge under the
        // rounded top corners, rather than floating inset inside the card like a plain bar. ----
        if (accentTopHex != null) {
            addView(View(this@ProductActivity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor(accentTopHex), Color.parseColor(fadeHex(accentTopHex)))
                ).apply {
                    val d = resources.displayMetrics.density
                    cornerRadii = floatArrayOf(
                        22 * d, 22 * d, 22 * d, 22 * d, 0f, 0f, 0f, 0f
                    )
                }
                layoutParams = LinearLayout.LayoutParams(-1, 5.dp()).apply {
                    setMargins((-22).dp(), (-20).dp(), (-22).dp(), 14.dp())
                }
            }, 0)
        }
    }

    // ---- Lightens a hex color toward white for a subtle two-tone accent strip. ----
    private fun fadeHex(hex: String): String {
        return try {
            val c = Color.parseColor(hex)
            val r = (Color.red(c) + 255) / 2
            val g = (Color.green(c) + 255) / 2
            val b = (Color.blue(c) + 255) / 2
            String.format("#%02X%02X%02X", r, g, b)
        } catch (e: Exception) {
            hex
        }
    }

    // ---- Small round colored icon badge, reused by sectionLabel() and premiumLabeledField()
    // so every icon across the form reads as a consistent "premium" chip instead of a plain
    // emoji floating in text. Now carries its own soft elevation so badges lift off the card. ----
    private fun badgeIcon(icon: String, accentHex: String, sizeDp: Int = 30) = TextView(this).apply {
        text = icon
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = gradientBg(accentHex, fadeHexDark(accentHex), cornerTop = sizeDp, cornerBottom = sizeDp)
        val px = sizeDp.dp()
        width = px
        height = px
        applyElevation(this, 2f)
    }

    // ---- Darkens a hex color slightly, used as the second stop of badge/button gradients so
    // every colored chip in the app reads as a subtle gradient rather than a flat fill. ----
    private fun fadeHexDark(hex: String): String {
        return try {
            val c = Color.parseColor(hex)
            val r = (Color.red(c) * 0.82).roundToInt().coerceIn(0, 255)
            val g = (Color.green(c) * 0.82).roundToInt().coerceIn(0, 255)
            val b = (Color.blue(c) * 0.82).roundToInt().coerceIn(0, 255)
            String.format("#%02X%02X%02X", r, g, b)
        } catch (e: Exception) {
            hex
        }
    }

    // ---- Section label upgraded to use a colored circular icon badge (matching the style
    // already used inside the Add Item Unit dialog) instead of a plain emoji, and now takes
    // an accent color so each card (Name=teal, Category=purple, Pricing=amber, Products=navy)
    // reads as visually distinct at a glance. ----
    private fun sectionLabel(icon: String, label: String, accentHex: String = teal) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 14)
        addView(badgeIcon(icon, accentHex))
        addView(View(this@ProductActivity).apply {
            layoutParams = LinearLayout.LayoutParams(10.dp(), 1)
        })
        addView(TextView(this@ProductActivity).apply {
            text = label.uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.02f
        })
    }

    // ---- PREMIUM labeled field: colored round icon badge + a small persistent label ABOVE
    // the input, so the field's meaning stays visible even once it's filled with a number —
    // fixes the old fieldBox() where the hint (and therefore the field's identity) disappeared
    // the moment a value was typed in, which is what made the Pricing card confusing. ----
    private fun premiumLabeledField(field: EditText, icon: String, label: String, accentHex: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, fieldFill, 16)
            setPadding(14, 10, 18, 10)

            addView(badgeIcon(icon, accentHex, 38))
            addView(View(this@ProductActivity).apply {
                layoutParams = LinearLayout.LayoutParams(14.dp(), 1)
            })

            val col = LinearLayout(this@ProductActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            col.addView(TextView(this@ProductActivity).apply {
                text = label.uppercase()
                textSize = 9.5f
                setTextColor(Color.parseColor(accentHex))
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.02f
            })
            (field.parent as? ViewGroup)?.removeView(field)
            field.setPadding(0, 4, 0, 0)
            field.layoutParams = LinearLayout.LayoutParams(-1, -2)
            col.addView(field)
            addView(col)
        }

    private fun fieldBox(field: EditText, icon: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = strokedBg(border, fieldFill, 12)
        setPadding(18, 4, 18, 4)
        addView(TextView(this@ProductActivity).apply {
            text = "$icon  "
            textSize = 14f
        })
        (field.parent as? ViewGroup)?.removeView(field)
        field.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        addView(field)
    }

    // ---- Premium section label with a colored round icon badge instead of a plain emoji —
    // used inside the Add Item Unit dialog to visually separate Primary/Secondary/Tertiary. ----
    private fun badgedSectionLabel(icon: String, label: String, accentHex: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 14)
        addView(TextView(this@ProductActivity).apply {
            text = icon
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(accentHex))
            }
            val px = 30.dp()
            width = px
            height = px
        })
        addView(View(this@ProductActivity).apply {
            layoutParams = LinearLayout.LayoutParams(10.dp(), 1)
        })
        addView(TextView(this@ProductActivity).apply {
            text = label.uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(navy))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.02f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
    }

    // ---- Small capsule showing one price figure with its own icon + label, used three times
    // per product card (Purchase/Wholesale/Retail) instead of one plain bullet-separated line. ----
    private fun priceChip(icon: String, label: String, value: Double, accentHex: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = strokedBg(border, fieldFill, 12)
            setPadding(12, 10, 12, 10)

            addView(TextView(this@ProductActivity).apply {
                text = "$icon $label"
                textSize = 9.5f
                setTextColor(Color.parseColor(textMuted))
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.01f
            })
            addView(TextView(this@ProductActivity).apply {
                text = "%.2f".format(value)
                textSize = 13.5f
                setTextColor(Color.parseColor(accentHex))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 3, 0, 0)
            })
        }

    // ---- Gradient pill button with a round icon badge, used for Edit/Delete on each product
    // card so they match the premium Save/Cancel button treatment used elsewhere. ----
    private fun actionButton(icon: String, label: String, startHex: String, endHex: String, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = gradientBg(startHex, endHex, cornerTop = 30, cornerBottom = 30)
            setPadding(0, 12, 0, 12)
            applyElevation(this, 2f)

            addView(TextView(this@ProductActivity).apply {
                text = icon
                textSize = 13f
            })
            addView(View(this@ProductActivity).apply {
                layoutParams = LinearLayout.LayoutParams(6.dp(), 1)
            })
            addView(TextView(this@ProductActivity).apply {
                text = label
                textSize = 12.5f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            })

            setOnClickListener { onClick() }
        }

    private fun pillLink(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12.5f
        setTextColor(Color.parseColor(teal))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(4, 4, 4, 4)
        setOnClickListener { onClick() }
    }

    // ---- Simplified: no longer takes a hint, since premiumLabeledField() now supplies the
    // persistent label externally. Font bumped to bold + larger size so the entered amount
    // reads clearly as the "value" half of a labeled field. ----
    private fun rateField() = EditText(this).apply {
        hint = "0.00"
        setHintTextColor(Color.parseColor(textMuted))
        setTextColor(Color.parseColor(textDark))
        background = null
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        imeOptions = EditorInfo.IME_ACTION_NEXT
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
    }

    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) =
        GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            setStroke(
                (1.2 * resources.displayMetrics.density).toInt(),
                Color.parseColor(strokeHex)
            )
            cornerRadius = radius.toFloat()
        }

    // ---- Diagonal gradient background, matching PurchaseActivity's premium header/button
    // treatment (navy header, teal buttons, etc.) so this screen and the dialog feel consistent
    // with the rest of the app instead of using flat single-color fills everywhere. ----
    private fun gradientBg(startHex: String, endHex: String, cornerTop: Int = 0, cornerBottom: Int = 0) =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
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
        layoutParams = LinearLayout.LayoutParams(-1, heightDp.dp())
    }

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).roundToInt()

    private fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange()
    }

    private fun hideKeyboard() {
        currentFocus?.let { focused ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
    }

    private fun safeShowDropDown(view: AutoCompleteTextView) {
        if (!view.isAttachedToWindow) return
        try {
            view.showDropDown()
        } catch (e: Exception) {
            Log.e(TAG, "safeShowDropDown failed for ${view.hint}", e)
        }
    }

    private fun scrollToProductsList() {
        if (!::productsSectionAnchor.isInitialized || !::scrollView.isInitialized) return
        scrollView.post { scrollView.smoothScrollTo(0, productsSectionAnchor.top) }
    }

    private fun toggleProductsList() {
        if (!::productsSectionContainer.isInitialized) return
        productsSectionContainer.visibility = View.VISIBLE
        scrollToProductsList()
    }

    private fun scrollFieldIntoView(target: View) {
        if (!::scrollView.isInitialized || !target.isAttachedToWindow) return
        val visibleFrame = Rect()
        scrollView.getWindowVisibleDisplayFrame(visibleFrame)
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val top = location[1]
        val bottom = top + target.height
        val extraPadding = (24 * resources.displayMetrics.density).toInt()
        when {
            bottom > visibleFrame.bottom -> scrollView.scrollBy(0, (bottom - visibleFrame.bottom) + extraPadding)
            top < visibleFrame.top -> scrollView.scrollBy(0, top - visibleFrame.top - extraPadding)
        }
    }

    // ---------------- Categories / units ----------------

    private fun loadCategories() {
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).categoryDao().all().collectLatest { list ->
                categoryNames = (listOf("General") + list.map { it.name }).distinct()
                categoryField.setAdapter(
                    ArrayAdapter(
                        this@ProductActivity,
                        android.R.layout.simple_dropdown_item_1line,
                        categoryNames
                    )
                )
            }
        }
    }

    private fun loadUnits() {
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).unitDao().all().collectLatest { list ->
                units = list.map { it.name }.filter { it.isNotBlank() }.distinct()

                if (stockUnitSpinner.adapter == null) {
                    setStockUnitAdapter()
                }
            }
        }
    }

    private fun setStockUnitAdapter() {
        val options = currentUnitOptions()
        stockUnitSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options
        )

        val index = options.indexOf(selectedOpeningStockUnit)
        stockUnitSpinner.setSelection(if (index >= 0) index else 0)

        stockUnitSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectedOpeningStockUnit =
                        parent?.getItemAtPosition(position)?.toString() ?: selectedPrimaryUnit
                    updateOpeningStockPreview()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun currentUnitOptions(): List<String> {
        val result = mutableListOf<String>()
        result.add(selectedPrimaryUnit)

        if (
            selectedSecondaryUnit != "None" &&
            selectedSecondaryUnit.isNotBlank() &&
            !result.contains(selectedSecondaryUnit)
        ) {
            result.add(selectedSecondaryUnit)
        }

        if (
            selectedTertiaryUnit != "None" &&
            selectedTertiaryUnit.isNotBlank() &&
            !result.contains(selectedTertiaryUnit)
        ) {
            result.add(selectedTertiaryUnit)
        }

        return result
    }

    private fun refreshStockUnitAdapter() {
        if (!::stockUnitSpinner.isInitialized) return

        val current = selectedOpeningStockUnit
        val options = currentUnitOptions()

        stockUnitSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options
        )

        val index = options.indexOf(current)
        stockUnitSpinner.setSelection(if (index >= 0) index else 0)
    }

    private fun promptAddCategory() {
        val input = EditText(this).apply {
            setPadding(32, 24, 32, 24)
        }

        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "New Category", "نئی کیٹیگری"))
            .setView(input)
            .setPositiveButton(Loc.t(this, "Add", "شامل کریں")) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    lifecycleScope.launch {
                        PosDatabase.get(this@ProductActivity)
                            .categoryDao()
                            .insert(Category(value))

                        Toast.makeText(
                            this@ProductActivity,
                            Loc.t(this@ProductActivity, "Category added", "کیٹیگری شامل ہو گئی"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    private fun promptAddUnitInline(onAdded: (String) -> Unit) {
        val input = EditText(this).apply {
            setPadding(32, 24, 32, 24)
        }

        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "New Unit", "نیا یونٹ"))
            .setView(input)
            .setPositiveButton(Loc.t(this, "Add", "شامل کریں")) { _, _ ->
                val value = input.text.toString().trim()

                if (value.isNotEmpty()) {
                    lifecycleScope.launch {
                        PosDatabase.get(this@ProductActivity)
                            .unitDao()
                            .insert(UnitType(value))

                        units = (units + value).distinct()
                        refreshStockUnitAdapter()

                        Toast.makeText(
                            this@ProductActivity,
                            Loc.t(this@ProductActivity, "Unit added", "یونٹ شامل ہو گیا"),
                            Toast.LENGTH_SHORT
                        ).show()

                        onAdded(value)
                    }
                }
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    private fun ensureUnitSaved(value: String) {
        if (value.isBlank() || value.equals("None", ignoreCase = true)) return
        if (units.any { it.equals(value, ignoreCase = true) }) return
        units = (units + value).distinct()
        lifecycleScope.launch {
            try {
                PosDatabase.get(this@ProductActivity).unitDao().insert(UnitType(value))
            } catch (e: Exception) {
                Log.e(TAG, "ensureUnitSaved failed for '$value'", e)
            }
        }
    }

    // ---------------- Unit conversion ----------------

    private fun normalizeUnitName(value: String): String =
        value.trim().lowercase()

    private fun standardUnitQty(fromUnit: String, toUnit: String): Double? {
        val f = normalizeUnitName(fromUnit)
        val t = normalizeUnitName(toUnit)

        val gramNames = setOf("gram", "grams", "g", "gm")
        val pieceNames = setOf("pcs", "pc", "piece", "pieces")
        val mlNames = setOf("ml", "milliliter", "millilitre")
        val kgNames = setOf("kg", "kgs", "kilogram", "kilograms")
        val litreNames = setOf("litre", "liter", "l", "ltr")

        return when {
            f == "dozen" && t in pieceNames -> 12.0
            f == "gross" && t == "dozen" -> 12.0
            f == "gross" && t in pieceNames -> 144.0
            f in kgNames && t in gramNames -> 1000.0
            f in litreNames && t in mlNames -> 1000.0
            f == "quintal" && t in kgNames -> 100.0
            f == "ton" && t in kgNames -> 1000.0
            f == "pao" && t in gramNames -> 250.0
            f in kgNames && t == "pao" -> 4.0
            else -> null
        }
    }

    private fun trimNum(value: Double): String =
        if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    private fun draftProduct(stockValue: Double = 0.0) = Product(
        barcode = "",
        name = "",
        stock = stockValue,
        unit = selectedPrimaryUnit,
        secondaryUnit = if (selectedSecondaryUnit == "None") "" else selectedSecondaryUnit,
        secondaryUnitQty = selectedSecondaryQty,
        tertiaryUnit = if (selectedTertiaryUnit == "None") "" else selectedTertiaryUnit,
        tertiaryUnitQty = selectedTertiaryQty
    )

    private fun openingStockToSmallest(quantity: Double, unit: String): Double {
        if (quantity <= 0) return 0.0
        return draftProduct().toSmallestUnits(quantity, unit)
    }

    private fun updateOpeningStockPreview() {
        if (!::stockPreview.isInitialized) return

        val q = stock.text.toString().toDoubleOrNull() ?: 0.0
        if (q <= 0) {
            stockPreview.text = ""
            return
        }

        val smallest = openingStockToSmallest(q, selectedOpeningStockUnit)
        val draft = draftProduct(smallest)

        stockPreview.text = Loc.t(
            this,
            "Stored stock: ${trimNum(smallest)} ${draft.smallestUnitName()}  •  Display: ${draft.formatStockBreakdown()}",
            "محفوظ اسٹاک: ${trimNum(smallest)} ${draft.smallestUnitName()}  •  ڈسپلے: ${draft.formatStockBreakdown()}"
        )
    }

    // ---------------- Unit dialog (ultra premium style) ----------------

    private fun openUnitDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = strokedBg(cardWhite, cardWhite, 24)
            clipToOutline = true
        }

        // ---- Gradient header with rounded top corners + soft ruler icon badge, matching the
        // premium navy header treatment used across Purchase/Sale/Product. ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 26)
            background = gradientBg(navy, navyLight, cornerTop = 24, cornerBottom = 0)
        }

        val headerTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        headerTop.addView(TextView(this).apply {
            text = "📏"
            textSize = 20f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(headerBadgeOverlay))
            }
            val px = 46.dp()
            width = px
            height = px
        })
        headerTop.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(14.dp(), 1)
        })

        val headerTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        headerTextCol.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Add Item Unit", "آئٹم یونٹ شامل کریں")
            textSize = 18.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        headerTextCol.addView(TextView(this).apply {
            text = Loc.t(
                this@ProductActivity,
                "Set how this product's units convert into each other",
                "یہ پروڈکٹ کے یونٹس ایک دوسرے میں کیسے تبدیل ہوں گے، ترتیب دیں"
            )
            textSize = 11.5f
            setTextColor(Color.parseColor(headerSubtitleColor))
            setPadding(0, 5, 0, 0)
        })
        headerTop.addView(headerTextCol)
        header.addView(headerTop)
        content.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 22, 20, 6)
            setBackgroundColor(Color.parseColor(bg))
        }

        val scroll = ScrollView(this)
        scroll.addView(body)

        // Primary — teal accent badge
        val primaryCard = premiumUnitCard()
        primaryCard.addView(badgedSectionLabel("📏", Loc.t(this, "Primary Unit", "بنیادی یونٹ"), teal))
        val primaryField = unitAutoCompleteField(
            Loc.t(this, "Type or pick unit, e.g. pcs, kg, box", "یونٹ لکھیں یا منتخب کریں، مثلاً pcs, kg, box")
        )
        primaryCard.addView(premiumFieldBox(primaryField, "🔤", teal))
        body.addView(primaryCard)
        body.addView(spacer(16))

        // Secondary — blue accent badge
        val secondaryCard = premiumUnitCard()
        secondaryCard.addView(
            badgedSectionLabel(
                "🔹",
                Loc.t(this, "Secondary Unit (smaller quantity, optional)", "ثانوی یونٹ (چھوٹی مقدار، اختیاری)"),
                blue
            )
        )
        val secondaryField = unitAutoCompleteField(
            Loc.t(this, "Leave blank if not needed", "اگر ضرورت نہیں تو خالی چھوڑ دیں")
        )
        secondaryCard.addView(premiumFieldBox(secondaryField, "🔤", blue))
        secondaryCard.addView(spacer(12))

        val secondaryQtyField = numberDialogField(
            Loc.t(
                this,
                "1 Primary = how many Secondary? e.g. 1 box = 12 pcs",
                "1 بنیادی یونٹ = کتنے ثانوی؟ مثلاً 1 box = 12 pcs"
            ),
            selectedSecondaryQty,
            EditorInfo.IME_ACTION_NEXT
        )
        secondaryCard.addView(premiumFieldBox(secondaryQtyField, "🔁", blue))
        body.addView(secondaryCard)
        body.addView(spacer(16))

        // Tertiary — orange accent badge
        val tertiaryCard = premiumUnitCard()
        tertiaryCard.addView(
            badgedSectionLabel(
                "🔸",
                Loc.t(this, "Tertiary Unit (smallest quantity, optional)", "تیسرا یونٹ (سب سے چھوٹی مقدار، اختیاری)"),
                orange
            )
        )
        val tertiaryField = unitAutoCompleteField(
            Loc.t(this, "Leave blank if not needed", "اگر ضرورت نہیں تو خالی چھوڑ دیں")
        )
        tertiaryCard.addView(premiumFieldBox(tertiaryField, "🔤", orange))
        tertiaryCard.addView(spacer(12))

        val tertiaryQtyField = numberDialogField(
            Loc.t(
                this,
                "1 Secondary = how many Tertiary?",
                "1 ثانوی یونٹ = کتنے تیسرے یونٹس؟"
            ),
            selectedTertiaryQty,
            EditorInfo.IME_ACTION_DONE
        )
        tertiaryCard.addView(premiumFieldBox(tertiaryQtyField, "🔁", orange))
        body.addView(tertiaryCard)
        body.addView(spacer(6))

        content.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        // ---- Footer sits on its own elevated white strip with a soft top divider, gradient
        // Save button, and a bit more breathing room than the old flat footer. ----
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 18, 24, 24)
            setBackgroundColor(Color.parseColor(cardWhite))
            applyElevation(this, 6f)
        }
        content.addView(footer)

        val dialog = AlertDialog.Builder(this).setView(content).create()
        dialog.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 24 * resources.displayMetrics.density
            }
        )

        val unitSuggestions = units.distinct()
        primaryField.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, unitSuggestions))
        secondaryField.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, unitSuggestions))
        tertiaryField.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, unitSuggestions))

        primaryField.setText(selectedPrimaryUnit)
        secondaryField.setText(if (selectedSecondaryUnit == "None") "" else selectedSecondaryUnit)
        tertiaryField.setText(if (selectedTertiaryUnit == "None") "" else selectedTertiaryUnit)

        fun autoSecondary() {
            val p = primaryField.text.toString().trim()
            val s = secondaryField.text.toString().trim()
            if (p.isBlank() || s.isBlank()) return
            val standard = standardUnitQty(p, s) ?: return
            if (secondaryQtyField.text.toString().isBlank()) {
                secondaryQtyField.setText(trimNum(standard))
            }
        }

        fun autoTertiary() {
            val s = secondaryField.text.toString().trim()
            val t = tertiaryField.text.toString().trim()
            if (s.isBlank() || t.isBlank()) return
            val standard = standardUnitQty(s, t) ?: return
            if (tertiaryQtyField.text.toString().isBlank()) {
                tertiaryQtyField.setText(trimNum(standard))
            }
        }

        fun trySaveUnitSelection() {
            val p = primaryField.text.toString().trim()
            var s = secondaryField.text.toString().trim().ifBlank { "None" }
            var t = tertiaryField.text.toString().trim().ifBlank { "None" }
            val sq = secondaryQtyField.text.toString().toDoubleOrNull() ?: 0.0
            val tq = tertiaryQtyField.text.toString().toDoubleOrNull() ?: 0.0

            if (p.isBlank()) {
                Toast.makeText(
                    this@ProductActivity,
                    Loc.t(this@ProductActivity, "Select Primary Unit", "بنیادی یونٹ منتخب کریں"),
                    Toast.LENGTH_SHORT
                ).show()
                primaryField.requestFocus()
                return
            }

            if (s != "None" && s.equals(p, ignoreCase = true)) {
                Toast.makeText(
                    this@ProductActivity,
                    Loc.t(this@ProductActivity, "Secondary must be different", "ثانوی یونٹ مختلف ہونا چاہیے"),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            if (s != "None" && sq <= 0) {
                secondaryQtyField.error =
                    Loc.t(this@ProductActivity, "Enter quantity", "مقدار درج کریں")
                secondaryQtyField.requestFocus()
                return
            }

            if (t != "None" && s == "None") {
                Toast.makeText(
                    this@ProductActivity,
                    Loc.t(this@ProductActivity, "Select Secondary first", "پہلے ثانوی یونٹ منتخب کریں"),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            if (t != "None" && t.equals(s, ignoreCase = true)) {
                Toast.makeText(
                    this@ProductActivity,
                    Loc.t(this@ProductActivity, "Tertiary must be different", "تیسرا یونٹ مختلف ہونا چاہیے"),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            if (t != "None" && tq <= 0) {
                tertiaryQtyField.error =
                    Loc.t(this@ProductActivity, "Enter quantity", "مقدار درج کریں")
                tertiaryQtyField.requestFocus()
                return
            }

            if (s == "None") {
                t = "None"
            }

            selectedPrimaryUnit = p
            selectedSecondaryUnit = s
            selectedSecondaryQty = if (s == "None") 0.0 else sq
            selectedTertiaryUnit = t
            selectedTertiaryQty = if (t == "None") 0.0 else tq

            ensureUnitSaved(p)
            if (s != "None") ensureUnitSaved(s)
            if (t != "None") ensureUnitSaved(t)

            if (
                selectedOpeningStockUnit.isBlank() ||
                selectedOpeningStockUnit == selectedPrimaryUnit
            ) {
                selectedOpeningStockUnit = selectedPrimaryUnit
            }

            selectUnitBtn.text = buildString {
                append("📏 $selectedPrimaryUnit")
                if (selectedSecondaryUnit != "None") {
                    append(" / $selectedSecondaryUnit")
                }
                if (selectedTertiaryUnit != "None") {
                    append(" / $selectedTertiaryUnit")
                }
            }

            refreshStockUnitAdapter()
            updateOpeningStockPreview()

            val conversion = buildString {
                if (selectedSecondaryUnit != "None") {
                    append("1 $selectedPrimaryUnit = ${trimNum(selectedSecondaryQty)} $selectedSecondaryUnit")
                }
                if (selectedTertiaryUnit != "None") {
                    if (isNotEmpty()) append("   •   ")
                    append("1 $selectedSecondaryUnit = ${trimNum(selectedTertiaryQty)} $selectedTertiaryUnit")
                }
            }

            if (conversion.isNotEmpty()) {
                Toast.makeText(
                    this@ProductActivity,
                    conversion,
                    Toast.LENGTH_SHORT
                ).show()
            }

            hideKeyboard()
            dialog.dismiss()
        }

        primaryField.addTextChangedListener(simpleWatcher { autoSecondary() })
        secondaryField.addTextChangedListener(simpleWatcher { autoSecondary(); autoTertiary() })
        tertiaryField.addTextChangedListener(simpleWatcher { autoTertiary() })

        primaryField.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) safeShowDropDown(primaryField) }
        secondaryField.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) safeShowDropDown(secondaryField) }
        tertiaryField.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) safeShowDropDown(tertiaryField) }

        primaryField.setOnItemClickListener { _, _, _, _ -> secondaryField.requestFocus() }
        primaryField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { secondaryField.requestFocus(); true } else false
        }
        secondaryField.setOnItemClickListener { _, _, _, _ -> secondaryQtyField.requestFocus() }
        secondaryField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { secondaryQtyField.requestFocus(); true } else false
        }
        secondaryQtyField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { tertiaryField.requestFocus(); true } else false
        }
        tertiaryField.setOnItemClickListener { _, _, _, _ -> tertiaryQtyField.requestFocus() }
        tertiaryField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { tertiaryQtyField.requestFocus(); true } else false
        }
        tertiaryQtyField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { trySaveUnitSelection(); true } else false
        }

        autoSecondary()
        autoTertiary()

        footer.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Cancel", "منسوخ کریں")
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, Typeface.BOLD)
            background = strokedBg(border, fieldFill, 14)
            setPadding(0, 24, 0, 24)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener { dialog.dismiss() }
        })

        footer.addView(TextView(this).apply {
            text = "✓  " + Loc.t(this@ProductActivity, "Save", "محفوظ کریں")
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = gradientBg(teal, "#0C8F8A", cornerTop = 14, cornerBottom = 14)
            setPadding(0, 24, 0, 24)
            applyElevation(this, 3f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener { trySaveUnitSelection() }
        })

        dialog.show()
    }

    // ---- Card variant used only inside the Add Item Unit dialog — a touch more rounded and
    // slightly lighter elevation than the main-screen premiumCard(), so the stacked
    // Primary/Secondary/Tertiary cards feel like a light, airy list rather than heavy boxes. ----
    private fun premiumUnitCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 18, 20, 18)
        background = strokedBg(border, cardWhite, 18)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, 0)
        }
        applyElevation(this, 1.5f)
    }

    // ---- Field box variant with a colored icon chip (instead of a plain emoji) matching the
    // accent color of its parent card (teal/blue/orange) for Primary/Secondary/Tertiary. ----
    private fun premiumFieldBox(field: EditText, icon: String, accentHex: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = strokedBg(border, fieldFill, 12)
        setPadding(10, 8, 16, 8)
        addView(TextView(this@ProductActivity).apply {
            text = icon
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(accentHex))
                alpha = 210
            }
            val px = 30.dp()
            width = px
            height = px
        })
        addView(View(this@ProductActivity).apply {
            layoutParams = LinearLayout.LayoutParams(10.dp(), 1)
        })
        (field.parent as? ViewGroup)?.removeView(field)
        field.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        addView(field)
    }

    private fun unitAutoCompleteField(hintText: String) = AutoCompleteTextView(this).apply {
        hint = hintText
        setHintTextColor(Color.parseColor(textMuted))
        setTextColor(Color.parseColor(textDark))
        setTypeface(typeface, Typeface.BOLD)
        background = null
        textSize = 15f
        threshold = 1
        imeOptions = EditorInfo.IME_ACTION_NEXT
    }

    private fun numberDialogField(hintText: String, oldValue: Double, imeAction: Int = EditorInfo.IME_ACTION_NEXT) =
        EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = imeAction
            if (oldValue > 0) setText(trimNum(oldValue))
        }

    // ---------------- Editing ----------------

    private fun loadProductForEdit(product: Product) {
        editingProduct = product

        name.setText(product.name)

        selectedPrimaryUnit = product.unit.ifBlank { "pcs" }
        selectedSecondaryUnit =
            if (product.secondaryUnit.isBlank()) "None" else product.secondaryUnit
        selectedSecondaryQty = product.secondaryUnitQty
        selectedTertiaryUnit =
            if (product.tertiaryUnit.isBlank()) "None" else product.tertiaryUnit
        selectedTertiaryQty = product.tertiaryUnitQty

        selectedOpeningStockUnit = selectedPrimaryUnit

        selectUnitBtn.text = buildString {
            append("📏 $selectedPrimaryUnit")
            if (selectedSecondaryUnit != "None") append(" / $selectedSecondaryUnit")
            if (selectedTertiaryUnit != "None") append(" / $selectedTertiaryUnit")
        }

        refreshStockUnitAdapter()

        categoryField.setText(product.category)

        cost.setText(if (product.cost > 0) product.cost.toString() else "")
        wholesalePrice.setText(
            if (product.wholesalePrice > 0) product.wholesalePrice.toString() else ""
        )
        salePrice.setText(if (product.salePrice > 0) product.salePrice.toString() else "")
        // ---- NEW: an item that already has a saved Retail/Wholesale rate is
        // treated as manually set from here on — opening this item again and
        // changing its Purchase Rate will NOT recompute a rate the shopkeeper
        // already fixed. A field that's still blank (0) gets one auto-fill offer
        // right away if a Markup % is configured, instead of waiting for the
        // Purchase Rate to be retyped. ----
        retailManuallyEdited = product.salePrice > 0
        wholesaleManuallyEdited = product.wholesalePrice > 0
        if (product.cost > 0) autoFillRatesFromCost(product.cost)
        reorderLevel.setText(if (product.reorderLevel > 0) trimNum(product.reorderLevel) else "")

        stock.setText(trimNum(product.stock))
        stock.isEnabled = false
        stockUnitSpinner.isEnabled = false
        stockNote.visibility = View.VISIBLE

        stockPreview.text = Loc.t(
            this,
            "Current stock: ${product.formatStockBreakdown()}",
            "موجودہ اسٹاک: ${product.formatStockBreakdown()}"
        )

        formCardTitle.text =
            "✏️  " + Loc.t(this, "Editing", "ترمیم ہو رہی ہے") + ": ${product.name}"

        deleteFormButton.visibility = View.VISIBLE
        cancelEditChip.visibility = View.VISIBLE
        saveButton.text =
            "💾  " + Loc.t(this, "UPDATE PRODUCT", "پروڈکٹ اپ ڈیٹ کریں")

        scrollView.post { scrollView.smoothScrollTo(0, 0) }
    }

    // ---------------- Save ----------------

    private fun saveProduct() {
        val productName = name.text.toString().trim()

        if (productName.isEmpty()) {
            Toast.makeText(
                this,
                Loc.t(this, "Product Name is required", "پروڈکٹ کا نام ضروری ہے"),
                Toast.LENGTH_SHORT
            ).show()
            name.requestFocus()
            return
        }

        if (selectedPrimaryUnit.isBlank()) {
            Toast.makeText(
                this,
                Loc.t(this, "Select a unit", "یونٹ منتخب کریں"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (
            selectedSecondaryUnit != "None" &&
            (
                selectedSecondaryUnit == selectedPrimaryUnit ||
                selectedSecondaryQty <= 0
            )
        ) {
            Toast.makeText(
                this,
                Loc.t(
                    this,
                    "Secondary unit/conversion is invalid",
                    "ثانوی یونٹ یا conversion غلط ہے"
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (
            selectedTertiaryUnit != "None" &&
            (
                selectedSecondaryUnit == "None" ||
                selectedTertiaryUnit == selectedSecondaryUnit ||
                selectedTertiaryQty <= 0
            )
        ) {
            Toast.makeText(
                this,
                Loc.t(
                    this,
                    "Tertiary unit/conversion is invalid",
                    "تیسرے یونٹ یا conversion غلط ہے"
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val existing = editingProduct
        val barcode = existing?.barcode ?: "P" + System.currentTimeMillis()
        val categoryValue = categoryField.text.toString().trim().ifBlank { "General" }

        val resolvedStock: Double
        val resolvedOpeningStock: Double

        if (existing != null) {
            resolvedStock = existing.stock
            resolvedOpeningStock = existing.openingStock
        } else {
            val openingQty = stock.text.toString().toDoubleOrNull() ?: 0.0
            resolvedStock = openingStockToSmallest(
                openingQty,
                selectedOpeningStockUnit
            )
            resolvedOpeningStock = resolvedStock
        }

        // FIX (fraction control): new product's opening stock must resolve to a whole
        // smallest-unit qty unless the smallest unit is fractional (Gram/ml).
        if (existing == null) {
            val probe = draftProduct(resolvedStock)
            if (!probe.isValidSmallestQty(resolvedStock)) {
                Toast.makeText(
                    this,
                    Loc.t(this, "Opening stock whole ${probe.smallestUnitName()} mein convert nahi hoti", "ابتدائی اسٹاک ${probe.smallestUnitName()} کی مکمل تعداد میں تبدیل نہیں ہوتا"),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        val product = Product(
            barcode = barcode,
            name = productName,
            category = categoryValue,
            cost = cost.text.toString().toDoubleOrNull() ?: 0.0,
            salePrice = salePrice.text.toString().toDoubleOrNull() ?: 0.0,
            wholesalePrice = wholesalePrice.text.toString().toDoubleOrNull() ?: 0.0,
            stock = resolvedStock,
            openingStock = resolvedOpeningStock,
            unit = selectedPrimaryUnit,
            secondaryUnit =
                if (selectedSecondaryUnit == "None") "" else selectedSecondaryUnit,
            secondaryUnitQty =
                if (selectedSecondaryUnit == "None") 0.0 else selectedSecondaryQty,
            tertiaryUnit =
                if (selectedTertiaryUnit == "None") "" else selectedTertiaryUnit,
            tertiaryUnitQty =
                if (selectedTertiaryUnit == "None") 0.0 else selectedTertiaryQty,
            reorderLevel = reorderLevel.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        )

        lifecycleScope.launch {
            try {
                val db = PosDatabase.get(this@ProductActivity)

                if (categoryValue != "General" && categoryNames.none { it.equals(categoryValue, ignoreCase = true) }) {
                    db.categoryDao().insert(Category(categoryValue))
                }

                db.productDao().upsert(product)

                // FIX (sync): product create/update was never enqueued into sync_queue,
                // so no product ever reached Firestore even with the worker running.
                // Mirrors the same enqueue+trigger pattern already used for
                // Customer/Supplier saves in PartyActivity.
                SyncQueueHelper.enqueue(
                    db,
                    "product",
                    SyncQueueHelper.productEntityId(product),
                    if (existing != null) "update" else "create",
                    SyncQueueHelper.productJson(product)
                )
                // FIX (conflict-safe sync): productJson() no longer carries "stock" at
                // all (see SyncQueueHelper.kt) — a brand-new product's opening stock is
                // sent separately as an increment instead, same mechanism as every other
                // stock change, so it merges correctly even if another device is also
                // mid-sync.
                if (existing == null) {
                    SyncQueueHelper.enqueueProductOpeningStock(db, product.barcode, product.stock, product.cost)
                }
                SyncQueueHelper.trigger(this@ProductActivity)

                Toast.makeText(
                    this@ProductActivity,
                    if (existing != null) {
                        Loc.t(this@ProductActivity, "Product updated", "پروڈکٹ اپ ڈیٹ ہو گئی")
                    } else {
                        Loc.t(this@ProductActivity, "Product saved", "پروڈکٹ محفوظ ہو گئی")
                    },
                    Toast.LENGTH_SHORT
                ).show()

                justSavedBarcode = barcode
                pendingScrollToSaved = true
                clearForm()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ProductActivity,
                    "Could not save product: ${e.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------------- Delete / reset ----------------

    private fun confirmDeleteProduct(product: Product) {
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Delete Product", "پروڈکٹ حذف کریں"))
            .setMessage(
                Loc.t(
                    this,
                    "Delete \"${product.name}\"? This cannot be undone.",
                    "\"${product.name}\" کو حذف کریں؟ یہ واپس نہیں ہو سکتا۔"
                )
            )
            .setPositiveButton(Loc.t(this, "Delete", "حذف کریں")) { _, _ ->
                lifecycleScope.launch {
                    try {
                        val db = PosDatabase.get(this@ProductActivity)
                        db.productDao().delete(product)

                        // FIX (sync): SyncApi.push() now maps the "product" entity type
                        // to the "products" Firestore collection for the "delete"
                        // operation (see SyncApi.kt), so this is safe to enqueue —
                        // previously a deleted product stayed in Firestore forever since
                        // nothing told the server to remove it.
                        SyncQueueHelper.enqueue(
                            db,
                            "product",
                            SyncQueueHelper.productEntityId(product),
                            "delete",
                            "{}"
                        )
                        SyncQueueHelper.trigger(this@ProductActivity)

                        if (editingProduct?.barcode == product.barcode) {
                            clearForm()
                        }

                        Toast.makeText(
                            this@ProductActivity,
                            Loc.t(
                                this@ProductActivity,
                                "Product deleted",
                                "پروڈکٹ حذف ہو گئی"
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@ProductActivity,
                            "Could not delete product: ${e.message ?: "unknown error"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    /**
     * NEW: same auto-markup behaviour as PartyDashboardActivity's quick "Edit
     * Rates" dialog — see RateMarkupSettings. Fills Wholesale/Retail Sale Rate
     * from Purchase Rate using the shop's saved markup %, but ONLY for a field
     * that hasn't been manually typed into during this form AND doesn't already
     * carry a saved rate (see loadProductForEdit()/clearForm() for how the two
     * flags get set). That's what lets a shopkeeper pin one item's price so it
     * stays put even when its Purchase Rate goes up later — they just leave the
     * field as-is, nothing here will touch it.
     */
    private fun autoFillRatesFromCost(costValue: Double) {
        if (costValue <= 0.0) return
        val retailPct = RateMarkupSettings.getRetailMarkupPercent(this)
        val wholesalePct = RateMarkupSettings.getWholesaleMarkupPercent(this)
        isAutoFillingRates = true
        if (!retailManuallyEdited && retailPct > 0.0) {
            val computed = RateMarkupSettings.computeFromCost(costValue, retailPct)
            if (computed > 0.0) salePrice.setText("%.2f".format(computed))
        }
        if (!wholesaleManuallyEdited && wholesalePct > 0.0) {
            val computed = RateMarkupSettings.computeFromCost(costValue, wholesalePct)
            if (computed > 0.0) wholesalePrice.setText("%.2f".format(computed))
        }
        isAutoFillingRates = false
    }

    private fun clearForm() {
        name.text.clear()
        cost.text.clear()
        wholesalePrice.text.clear()
        salePrice.text.clear()
        stock.text.clear()
        reorderLevel.text.clear()
        // ---- NEW: a fresh "New Product" form should offer auto-fill again ----
        retailManuallyEdited = false
        wholesaleManuallyEdited = false

        stock.isEnabled = true
        stockUnitSpinner.isEnabled = true
        stockNote.visibility = View.GONE
        stockPreview.text = ""

        selectedPrimaryUnit = "pcs"
        selectedSecondaryUnit = "None"
        selectedSecondaryQty = 0.0
        selectedTertiaryUnit = "None"
        selectedTertiaryQty = 0.0
        selectedOpeningStockUnit = "pcs"

        selectUnitBtn.text =
            "📏 " + Loc.t(this, "Select Unit", "یونٹ منتخب کریں")

        refreshStockUnitAdapter()

        editingProduct = null
        formCardTitle.text =
            "✚  " + Loc.t(this, "New Product", "نئی پروڈکٹ")
        deleteFormButton.visibility = View.GONE
        cancelEditChip.visibility = View.GONE
        saveButton.text =
            "💾  " + Loc.t(this, "SAVE PRODUCT", "پروڈکٹ محفوظ کریں")

        categoryField.setText("")

        scrollView.post { scrollView.smoothScrollTo(0, 0) }
    }

    // ---------------- Product list ----------------

    private fun filterProducts(query: String): List<Product> {
        val q = query.trim()
        if (q.isEmpty()) return allProducts

        return allProducts.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.category.contains(q, ignoreCase = true)
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity)
                .productDao()
                .all()
                .collectLatest { list ->
                    allProducts = list
                    renderProducts(filterProducts(searchField.text.toString()))
                }
        }
    }

    private fun renderProducts(products: List<Product>) {
        listContainer.removeAllViews()

        if (products.isEmpty()) {
            noResultsCard.visibility = View.VISIBLE
            return
        }

        noResultsCard.visibility = View.GONE

        var savedCardView: View? = null

        products.forEach { product ->
            val isJustSaved = product.barcode == justSavedBarcode
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(22, 20, 22, 20)
                background = if (isJustSaved) {
                    strokedBg(teal, savedHighlightBg, 18)
                } else {
                    strokedBg(border, cardWhite, 18)
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, 0, 0, 12)
                }
                applyElevation(this, if (isJustSaved) 4f else 2.5f)
            }

            // ---- Avatar-style icon badge (first letter of product name) + name + category
            // pill, replacing the old flat text-only header row. ----
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            top.addView(TextView(this).apply {
                text = product.name.trim().firstOrNull()?.uppercase() ?: "?"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                background = if (isJustSaved) {
                    gradientBg(teal, "#0C8F8A", cornerTop = 30, cornerBottom = 30)
                } else {
                    gradientBg(navy, navyLight, cornerTop = 30, cornerBottom = 30)
                }
                val px = 40.dp()
                width = px
                height = px
            })

            top.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(14.dp(), 1)
            })

            val nameCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            nameCol.addView(TextView(this).apply {
                text = if (isJustSaved) "✓ ${product.name}" else product.name
                textSize = 15f
                setTextColor(Color.parseColor(if (isJustSaved) teal else textDark))
                setTypeface(typeface, Typeface.BOLD)
            })
            nameCol.addView(TextView(this).apply {
                text = product.category
                textSize = 11f
                setTextColor(Color.parseColor(textMuted))
                setPadding(0, 2, 0, 0)
            })
            top.addView(nameCol)

            top.addView(TextView(this).apply {
                text = "📊 ${product.formatStockBreakdown()}"
                setTextColor(Color.WHITE)
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                background = gradientBg(teal, "#0C8F8A", cornerTop = 30, cornerBottom = 30)
                setPadding(18, 8, 18, 8)
            })

            card.addView(top)
            card.addView(spacer(14))

            // ---- Divider ----
            card.addView(View(this).apply {
                setBackgroundColor(Color.parseColor(border))
                layoutParams = LinearLayout.LayoutParams(-1, 1.dp().coerceAtLeast(1))
            })
            card.addView(spacer(14))

            // ---- Pricing row as three individual capsule chips instead of one plain line of
            // text separated by bullet dots. ----
            val priceRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            priceRow.addView(
                priceChip(
                    "🛒",
                    Loc.t(this@ProductActivity, "Purchase", "خریداری"),
                    product.cost,
                    textMuted
                ),
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 6, 0) }
            )
            priceRow.addView(
                priceChip(
                    "📦",
                    Loc.t(this@ProductActivity, "Wholesale", "تھوک"),
                    product.wholesalePrice,
                    blue
                ),
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 0, 6, 0) }
            )
            priceRow.addView(
                priceChip(
                    "🏪",
                    Loc.t(this@ProductActivity, "Retail", "پرچون"),
                    product.salePrice,
                    teal
                ),
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 0, 0, 0) }
            )
            card.addView(priceRow)

            if (product.secondaryUnit.isNotBlank()) {
                card.addView(spacer(12))
                card.addView(TextView(this).apply {
                    text = buildString {
                        append(
                            "📏 1 ${product.unit} = " +
                                "${trimNum(product.secondaryUnitQty)} ${product.secondaryUnit}"
                        )

                        if (
                            product.tertiaryUnit.isNotBlank() &&
                            product.tertiaryUnitQty > 0
                        ) {
                            append(
                                "   •   1 ${product.secondaryUnit} = " +
                                    "${trimNum(product.tertiaryUnitQty)} ${product.tertiaryUnit}"
                            )
                        }
                    }
                    textSize = 11.5f
                    setTextColor(Color.parseColor(textMuted))
                    background = strokedBg(border, fieldFill, 10)
                    setPadding(14, 10, 14, 10)
                })
            }

            card.addView(spacer(14))

            // ---- Gradient action buttons with round icon badges, matching the premium
            // Save/Cancel treatment used in the Add Item Unit dialog. ----
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            actions.addView(
                actionButton("✏️", Loc.t(this@ProductActivity, "Edit", "ترمیم کریں"), navy, navyLight) {
                    loadProductForEdit(product)
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 6, 0) }
            )

            actions.addView(
                actionButton("🗑️", Loc.t(this@ProductActivity, "Delete", "حذف کریں"), red, "#C93B40") {
                    confirmDeleteProduct(product)
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 0, 0, 0) }
            )

            card.addView(actions)
            listContainer.addView(card)

            if (isJustSaved) savedCardView = card
        }

        val cardToReveal = savedCardView
        if (pendingScrollToSaved) {
            pendingScrollToSaved = false
            if (
                cardToReveal != null &&
                ::productsSectionContainer.isInitialized &&
                productsSectionContainer.visibility == View.VISIBLE
            ) {
                scrollView.post {
                    scrollView.post {
                        val targetY = (cardToReveal.top - 24.dp()).coerceAtLeast(0)
                        scrollView.smoothScrollTo(0, targetY)
                    }
                }
            }
        }
    }
}
