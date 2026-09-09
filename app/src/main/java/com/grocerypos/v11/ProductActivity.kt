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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Category
import com.grocerypos.v11.R
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
import com.grocerypos.v11.ui.components.*

class ProductActivity : ThemedActivity() {

    companion object {
        const val EXTRA_EDIT_BARCODE = "editBarcode"
        private const val TAG = "ProductActivity"
    }

    // ---- Premium palette — kept compatible with Purchase/Sale. ----
    internal var bg = "#F4F6F8"
    internal var cardWhite = "#FFFFFF"
    internal var navy = "#0B2545"
    internal var navyLight = "#173863"
    internal var teal = "#0F9B8E"
    internal var tealDark = "#0C8F8A"     // gradient partner for `teal`; kept in step per theme
    private var red = "#E5484D"
    internal var blue = "#3B82F6"
    internal var orange = "#F5A524"
    private var purple = "#8B5CF6"
    internal var textDark = "#0B2545"
    internal var textMuted = "#7C8798"
    internal var border = "#E3E8EE"
    private var amber = "#F5A524"

    internal var fieldFill = "#FAFBFC"
    internal var headerSubtitleColor = "#9FB4CC"
    internal var headerBadgeOverlay = "#33FFFFFF"
    private var savedHighlightBg = "#E9FBF9"

    private fun loadThemePrefs() {
        val p = ThemeManager.palette(this)
        bg = p.bg
        cardWhite = p.cardWhite
        navy = p.navy
        teal = p.teal
        tealDark = if (ThemeManager.isDarkMode(this)) "#0D9E96" else "#0C8F8A"
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
    internal lateinit var selectUnitBtn: TextView
    private lateinit var categoryField: AutoCompleteTextView
    private lateinit var cost: EditText
    private lateinit var wholesalePrice: EditText
    private lateinit var salePrice: EditText
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
    internal var units: List<String> = emptyList()
    private var categoryNames: List<String> = listOf("General")

    internal var selectedPrimaryUnit = "pcs"
    internal var selectedSecondaryUnit = "None"
    internal var selectedSecondaryQty = 0.0
    internal var selectedTertiaryUnit = "None"
    internal var selectedTertiaryQty = 0.0

    internal var selectedOpeningStockUnit = "pcs"
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
            text = Loc.t(this@ProductActivity, "SAVE PRODUCT", "پروڈکٹ محفوظ کریں")
            setTextColor(Color.WHITE)
            textSize = 15.5f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = gradientBg(navy, navyLight, cornerTop = 16, cornerBottom = 16)
            setPadding(0, 26, 0, 26)
            setLeadingIcon(R.drawable.ic_save, "#FFFFFF", 18, 8)
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
        // FLAT REDESIGN: was a custom navy→navyLight banner with a translucent-white icon
        // badge. Replaced with the shared premiumHeader() (back chevron + light badge); the
        // dark/light toggle and "View List" pill are appended the same way ItemsActivity
        // appends its "Translate" pill — solid pastel-tinted backgrounds instead of the old
        // translucent-white-on-dark treatment (which would be invisible on a flat header).
        val header = premiumHeader(
            R.drawable.ic_box,
            Loc.t(this@ProductActivity, "Add / Edit Product", "پروڈکٹ شامل / تبدیل کریں"),
            Loc.t(this@ProductActivity, "Inventory Management", "انوینٹری مینجمنٹ"),
            navy, navy
        ) { finish() }

        header.addView(ImageView(this).apply {
            setImageDrawable(
                tintedDrawable(if (isDarkMode) R.drawable.ic_sun else R.drawable.ic_moon, navy, 18)
            )
            setPadding(14, 12, 14, 12)
            background = ovalBg("#EEEDFE")
            applyElevation(this, 2f)
            setOnClickListener { toggleTheme() }
        })

        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(10.dp(), 1)
        })

        header.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "View List", "فہرست دیکھیں")
            textSize = 11.5f
            setTextColor(Color.parseColor(navy))
            setTypeface(typeface, Typeface.BOLD)
            background = strokedBg(border, "#EEEDFE", 30)
            setPadding(20, 12, 20, 12)
            applyElevation(this, 2f)
            setLeadingIcon(R.drawable.ic_list, navy, 14, 6)
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
            text = Loc.t(this@ProductActivity, "New Product", "نئی پروڈکٹ")
            textSize = 12.5f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.01f
            background = roundedBg(fadeHex(teal), 30)
            setPadding(20, 10, 20, 10)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setLeadingIcon(R.drawable.ic_add, teal, 14, 6)
        }

        deleteFormButton = TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Delete", "حذف کریں")
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = gradientBg(red, "#C93B40", cornerTop = 30, cornerBottom = 30)
            setPadding(22, 12, 22, 12)
            visibility = View.GONE
            applyElevation(this, 2f)
            setLeadingIcon(R.drawable.ic_delete, "#FFFFFF", 14, 6)
            setOnClickListener { editingProduct?.let { confirmDeleteProduct(it) } }
        }

        cancelEditChip = TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Cancel Edit", "ترمیم منسوخ کریں")
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(textMuted, 30)
            setPadding(24, 12, 24, 12)
            visibility = View.GONE
            setLeadingIcon(R.drawable.ic_close, "#FFFFFF", 14, 6)
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
        nameCard.addView(sectionLabel(R.drawable.ic_tag, Loc.t(this, "Product Name", "پروڈکٹ کا نام"), teal))

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
            text = Loc.t(this@ProductActivity, "Select Unit", "یونٹ منتخب کریں")
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = gradientBg(teal, "#0C8F8A", cornerTop = 30, cornerBottom = 30)
            setPadding(26, 15, 26, 15)
            applyElevation(this, 3f)
            setLeadingIcon(R.drawable.ic_ruler, "#FFFFFF", 14, 6)
            setOnClickListener { openUnitDialog() }
        }

        nameBox.addView(name)
        nameBox.addView(selectUnitBtn)
        nameCard.addView(nameBox)
        root.addView(nameCard)

        // ================= CATEGORY CARD =================
        val categoryCard = premiumCard(accentTopHex = purple)
        categoryCard.addView(sectionLabel(R.drawable.ic_category, Loc.t(this, "Category", "کیٹیگری"), purple))

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
            pillLink(Loc.t(this, "Add New Category", "نئی کیٹیگری شامل کریں"), R.drawable.ic_add) {
                promptAddCategory()
            }
        )
        root.addView(categoryCard)

        // ================= PRICING CARD (premium: persistent labels + colored badges) =================
        val ratesCard = premiumCard(accentTopHex = amber)
        ratesCard.addView(sectionLabel(R.drawable.ic_wallet, Loc.t(this, "Pricing", "قیمتیں"), amber))

        cost = rateField()
        ratesCard.addView(
            premiumLabeledField(
                cost, R.drawable.ic_cart,
                Loc.t(this, "Purchase Rate", "خریداری کی قیمت"),
                amber
            )
        )
        ratesCard.addView(spacer(12))

        wholesalePrice = rateField()
        ratesCard.addView(
            premiumLabeledField(
                wholesalePrice, R.drawable.ic_box,
                Loc.t(this, "Wholesale Sale Rate", "تھوک فروخت کی قیمت"),
                blue
            )
        )
        ratesCard.addView(spacer(12))

        salePrice = rateField()
        ratesCard.addView(
            premiumLabeledField(
                salePrice, R.drawable.ic_store,
                Loc.t(this, "Retail Sale Rate", "پرچون فروخت کی قیمت"),
                teal
            )
        )
        ratesCard.addView(spacer(12))

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
        stockRow.addView(badgeIcon(R.drawable.ic_number, navy))
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
                reorderLevel, R.drawable.ic_warning,
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
        productsSectionAnchor = sectionLabel(R.drawable.ic_archive, Loc.t(this, "Products", "پروڈکٹس"), navy)
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

        searchBox.addView(ImageView(this).apply {
            setImageDrawable(tintedDrawable(R.drawable.ic_search, textMuted, 17))
            setPadding(0, 0, 10, 0)
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

        val clearSearchBtn = ImageView(this).apply {
            setImageDrawable(tintedDrawable(R.drawable.ic_close, textMuted, 15))
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

            addView(ImageView(this@ProductActivity).apply {
                setImageDrawable(tintedDrawable(R.drawable.ic_search, textMuted, 30))
                scaleType = ImageView.ScaleType.CENTER
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
                    // FLAT REDESIGN: solid accentTopHex now (was accentTopHex->fadeHex blend).
                    intArrayOf(Color.parseColor(accentTopHex), Color.parseColor(accentTopHex))
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

    // ---- Vector-icon helpers (replace emoji throughout this screen with tinted
    // drawables from res/drawable, per the item-6 UI improvement pass). tintedDrawable()
    // loads+tints+sizes a vector; setLeadingIcon() puts one as a TextView/Button's compound
    // drawable so button/label text keeps working exactly as before, just without an emoji
    // prefix in the string itself. ----
    internal fun tintedDrawable(iconRes: Int, tintHex: String, sizeDp: Int = 16) =
        ContextCompat.getDrawable(this, iconRes)?.mutate()?.apply {
            setTint(Color.parseColor(tintHex))
            val px = sizeDp.dp()
            setBounds(0, 0, px, px)
        }

    internal fun TextView.setLeadingIcon(iconRes: Int, tintHex: String, sizeDp: Int = 16, paddingDp: Int = 8) {
        setCompoundDrawablesRelative(tintedDrawable(iconRes, tintHex, sizeDp), null, null, null)
        compoundDrawablePadding = paddingDp.dp()
    }

    // ---- Small round colored icon badge, reused by sectionLabel() and premiumLabeledField()
    // so every icon across the form reads as a consistent "premium" chip instead of a plain
    // emoji floating in text. Now carries its own soft elevation so badges lift off the card. ----
    private fun badgeIcon(iconRes: Int, accentHex: String, sizeDp: Int = 30) = ImageView(this).apply {
        setImageDrawable(tintedDrawable(iconRes, "#FFFFFF", (sizeDp * 0.55).toInt()))
        scaleType = ImageView.ScaleType.CENTER
        background = gradientBg(accentHex, fadeHexDark(accentHex), cornerTop = sizeDp, cornerBottom = sizeDp)
        val px = sizeDp.dp()
        layoutParams = android.view.ViewGroup.LayoutParams(px, px)
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
    private fun sectionLabel(iconRes: Int, label: String, accentHex: String = teal) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 14)
        addView(badgeIcon(iconRes, accentHex))
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
    private fun premiumLabeledField(field: EditText, iconRes: Int, label: String, accentHex: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, fieldFill, 16)
            setPadding(14, 10, 18, 10)

            addView(badgeIcon(iconRes, accentHex, 38))
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
    // ---- Small capsule showing one price figure with its own icon + label, used three times
    // per product card (Purchase/Wholesale/Retail) instead of one plain bullet-separated line. ----
    private fun priceChip(iconRes: Int, label: String, value: Double, accentHex: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = strokedBg(border, fieldFill, 12)
            setPadding(12, 10, 12, 10)

            addView(TextView(this@ProductActivity).apply {
                text = label
                textSize = 9.5f
                setTextColor(Color.parseColor(textMuted))
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.01f
                setLeadingIcon(iconRes, textMuted, 11, 5)
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
    private fun actionButton(iconRes: Int, label: String, startHex: String, endHex: String, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = gradientBg(startHex, endHex, cornerTop = 30, cornerBottom = 30)
            setPadding(0, 12, 0, 12)
            applyElevation(this, 2f)

            addView(ImageView(this@ProductActivity).apply {
                setImageDrawable(tintedDrawable(iconRes, "#FFFFFF", 15))
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

    private fun pillLink(label: String, iconRes: Int? = null, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12.5f
        setTextColor(Color.parseColor(teal))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(4, 4, 4, 4)
        if (iconRes != null) setLeadingIcon(iconRes, teal, 14, 6)
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

    // ---- Diagonal gradient background, matching PurchaseActivity's premium header/button
    // treatment (navy header, teal buttons, etc.) so this screen and the dialog feel consistent
    // with the rest of the app instead of using flat single-color fills everywhere. ----
    internal fun Int.dp(): Int =
        (this * resources.displayMetrics.density).roundToInt()

    internal fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange()
    }

    internal fun hideKeyboard() {
        currentFocus?.let { focused ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
    }

    internal fun safeShowDropDown(view: AutoCompleteTextView) {
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

    internal fun refreshStockUnitAdapter() {
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

    internal fun ensureUnitSaved(value: String) {
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

    internal fun standardUnitQty(fromUnit: String, toUnit: String): Double? {
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

    internal fun trimNum(value: Double): String =
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

    internal fun updateOpeningStockPreview() {
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
            append(selectedPrimaryUnit)
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
            Loc.t(this, "Editing", "ترمیم ہو رہی ہے") + ": ${product.name}"
        formCardTitle.setLeadingIcon(R.drawable.ic_edit, teal, 14, 6)

        deleteFormButton.visibility = View.VISIBLE
        cancelEditChip.visibility = View.VISIBLE
        saveButton.text =
            Loc.t(this, "UPDATE PRODUCT", "پروڈکٹ اپ ڈیٹ کریں")
        saveButton.setLeadingIcon(R.drawable.ic_save, "#FFFFFF", 18, 8)

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

    private fun clearForm() {
        name.text.clear()
        cost.text.clear()
        wholesalePrice.text.clear()
        salePrice.text.clear()
        stock.text.clear()
        reorderLevel.text.clear()

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
            Loc.t(this, "Select Unit", "یونٹ منتخب کریں")

        refreshStockUnitAdapter()

        editingProduct = null
        formCardTitle.text =
            Loc.t(this, "New Product", "نئی پروڈکٹ")
        formCardTitle.setLeadingIcon(R.drawable.ic_add, teal, 14, 6)
        deleteFormButton.visibility = View.GONE
        cancelEditChip.visibility = View.GONE
        saveButton.text =
            Loc.t(this, "SAVE PRODUCT", "پروڈکٹ محفوظ کریں")
        saveButton.setLeadingIcon(R.drawable.ic_save, "#FFFFFF", 18, 8)

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
                layoutParams = android.view.ViewGroup.LayoutParams(px, px)
            })

            top.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(14.dp(), 1)
            })

            val nameCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            nameCol.addView(TextView(this).apply {
                text = product.name
                textSize = 15f
                setTextColor(Color.parseColor(if (isJustSaved) teal else textDark))
                setTypeface(typeface, Typeface.BOLD)
                if (isJustSaved) setLeadingIcon(R.drawable.ic_check, teal, 15, 5)
            })
            nameCol.addView(TextView(this).apply {
                text = product.category
                textSize = 11f
                setTextColor(Color.parseColor(textMuted))
                setPadding(0, 2, 0, 0)
            })
            top.addView(nameCol)

            top.addView(TextView(this).apply {
                text = product.formatStockBreakdown()
                setTextColor(Color.WHITE)
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                background = gradientBg(teal, "#0C8F8A", cornerTop = 30, cornerBottom = 30)
                setPadding(18, 8, 18, 8)
                setLeadingIcon(R.drawable.ic_chart, "#FFFFFF", 12, 5)
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
                    R.drawable.ic_cart,
                    Loc.t(this@ProductActivity, "Purchase", "خریداری"),
                    product.cost,
                    textMuted
                ),
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 6, 0) }
            )
            priceRow.addView(
                priceChip(
                    R.drawable.ic_box,
                    Loc.t(this@ProductActivity, "Wholesale", "تھوک"),
                    product.wholesalePrice,
                    blue
                ),
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 0, 6, 0) }
            )
            priceRow.addView(
                priceChip(
                    R.drawable.ic_store,
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
                            "1 ${product.unit} = " +
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
                    setLeadingIcon(R.drawable.ic_ruler, textMuted, 13, 6)
                })
            }

            card.addView(spacer(14))

            // ---- Gradient action buttons with round icon badges, matching the premium
            // Save/Cancel treatment used in the Add Item Unit dialog. ----
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            actions.addView(
                actionButton(R.drawable.ic_edit, Loc.t(this@ProductActivity, "Edit", "ترمیم کریں"), navy, navyLight) {
                    loadProductForEdit(product)
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 6, 0) }
            )

            actions.addView(
                actionButton(R.drawable.ic_delete, Loc.t(this@ProductActivity, "Delete", "حذف کریں"), red, "#C93B40") {
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
