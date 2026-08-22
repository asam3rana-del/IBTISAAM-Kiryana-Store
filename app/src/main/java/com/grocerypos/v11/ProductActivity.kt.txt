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
import com.grocerypos.v11.UnitType
import com.grocerypos.v11.formatStockBreakdown
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

    // ---- Premium palette — kept compatible with Purchase/Sale. These are now `var`s instead
    // of `val`s because their values depend on the current theme (light/dark) and get assigned
    // once in onCreate(), before any UI is built, by copying the values out of ThemeManager's
    // shared AppPalette (see loadThemePrefs()). Keeping them as local vars here means the rest
    // of this file (every "Color.parseColor(bg)" etc.) didn't need to change at all. ----
    private var bg = "#F4F6F8"
    private var cardWhite = "#FFFFFF"
    private var navy = "#0B2545"
    private var teal = "#0F9B8E"
    private var red = "#E5484D"
    private var textDark = "#0B2545"
    private var textMuted = "#7C8798"
    private var border = "#E3E8EE"
    private var amber = "#F5A524"

    // ---- Extra theme-aware roles for literals that used to be hardcoded hex strings scattered
    // through the layout code (field backgrounds, header subtitle, header badge overlay, and
    // the light-teal "just saved" card highlight). ----
    private var fieldFill = "#FAFBFC"
    private var headerSubtitleColor = "#9FB4CC"
    private var headerBadgeOverlay = "#33FFFFFF"
    private var savedHighlightBg = "#E9FBF9"

    // ---- Pulls this screen's colors from the app-wide ThemeManager (shared across every
    // activity — see ThemeManager.kt) instead of reading SharedPreferences directly. This is
    // what keeps ProductActivity in sync with the theme choice made on any other screen. ----
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

    // ---- Flips the app-wide theme preference (via ThemeManager) and recreates this activity so
    // every color-dependent view gets rebuilt from scratch with the new palette. Any other open
    // activity that extends ThemedActivity will pick up the change automatically the next time
    // the user resumes it — see ThemedActivity.onResume(). ----
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
    private lateinit var stock: EditText
    private lateinit var stockUnitSpinner: Spinner
    private lateinit var stockPreview: TextView
    private lateinit var stockNote: TextView
    private lateinit var saveButton: Button
    private lateinit var cancelEditChip: TextView
    private lateinit var searchField: EditText
    private lateinit var listContainer: LinearLayout
    private lateinit var noResultsCard: LinearLayout
    private lateinit var productsSectionAnchor: View

    // ---- Wraps the search box + product list + "no results" card. Kept GONE by default so
    // the saved-products list doesn't sit permanently under the form — it only becomes visible
    // when the user taps "View List" in the header (see toggleProductsList()). ----
    private lateinit var productsSectionContainer: LinearLayout

    // ---- Tracks whichever EditText currently has focus so the ScrollView's global layout
    // listener (registered once, below) can keep re-applying the "stay above keyboard" scroll
    // on every layout pass while the keyboard is animating open/closed — not just once on the
    // initial focus event. This is what stops fields like Retail Sale Rate from getting hidden
    // behind the keyboard. ----
    private var focusedFieldForScroll: View? = null

    // ---- No hardcoded English defaults (pcs, ctn, box, gram, ml, ...). Only units the user
    // has actually added (via "New Unit" in the unit dialog, or by typing a fresh unit name,
    // see ensureUnitSaved) are loaded from the DB and offered as suggestions. ----
    private var units: List<String> = emptyList()

    private var categoryNames: List<String> = listOf("General")

    // Unit chain:
    // Primary -> Secondary -> Tertiary
    // Example: 1 carton = 50 outer, 1 outer = 10 dabbi.
    private var selectedPrimaryUnit = "pcs"
    private var selectedSecondaryUnit = "None"
    private var selectedSecondaryQty = 0.0
    private var selectedTertiaryUnit = "None"
    private var selectedTertiaryQty = 0.0

    private var selectedOpeningStockUnit = "pcs"
    private var editingProduct: Product? = null
    private var allProducts: List<Product> = emptyList()

    // Barcode of the product that was most recently saved — used to visually highlight it in
    // the list below so the user doesn't have to hunt for what they just added/edited.
    private var justSavedBarcode: String? = null

    // Set true right after a save; consumed by renderProducts() to scroll precisely to the
    // just-saved card once it exists in the freshly rendered list, instead of only jumping to
    // the top of the Products section (which could still leave the saved item scrolled out of
    // view further down the list). Only actually scrolls if the products section is currently
    // visible — it does NOT force the (now hidden-by-default) list open on its own.
    private var pendingScrollToSaved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---- Lets the OS resize/pan the layout as the keyboard opens, which is what makes
        // the manual "stay above keyboard" scrolling below actually work reliably. ----
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // ---- Must run before any of the build*() calls below, since they all read the color
        // vars (bg, cardWhite, textDark, etc.) while constructing views. ----
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

        // ---- Generic "keep the focused field visible above the keyboard" tracking — re-applies
        // the scroll on every layout pass while the keyboard animates, so it self-corrects
        // instead of relying on a single guessed scroll amount from the focus event alone. ----
        scrollView.viewTreeObserver.addOnGlobalLayoutListener {
            focusedFieldForScroll?.let { scrollFieldIntoView(it) }
        }

        saveButton = Button(this).apply {
            text = "💾  " + Loc.t(this@ProductActivity, "SAVE PRODUCT", "پروڈکٹ محفوظ کریں")
            setTextColor(Color.WHITE)
            textSize = 15.5f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(navy, 16)
            setPadding(0, 26, 0, 26)
            setOnClickListener { saveProduct() }
            applyElevation(this, 4f)
        }

        val saveBar = LinearLayout(this).apply {
            setPadding(24, 14, 24, 18)
            setBackgroundColor(Color.parseColor(cardWhite))
            applyElevation(this, 8f)
            addView(
                saveButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
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
            background = roundedBg(navy, 20)
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

        // ---- Light/dark toggle. Shows the icon for the mode you'd switch TO, matching the
        // usual convention (sun while in dark mode, moon while in light mode). ----
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

        // ---- Reveals the (hidden-by-default) products list below and scrolls straight to it.
        // This is now the ONLY way the list becomes visible — it no longer shows permanently
        // under the form. ----
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

        cancelEditChip = TextView(this).apply {
            text = "✕  " + Loc.t(this@ProductActivity, "Cancel Edit", "ترمیم منسوخ کریں")
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(red, 30)
            setPadding(24, 12, 24, 12)
            visibility = View.GONE
            setOnClickListener { clearForm() }
        }

        titleRow.addView(formCardTitle)
        titleRow.addView(cancelEditChip)
        root.addView(titleRow)
        root.addView(spacer(10))

        val nameCard = premiumCard()
        nameCard.addView(sectionLabel("🏷️", Loc.t(this, "Product Name", "پروڈکٹ کا نام")))

        val nameBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 6, 6, 6)
            background = strokedBg(border, fieldFill, 12)
        }

        name = EditText(this).apply {
            hint = Loc.t(this@ProductActivity, "Product Name", "پروڈکٹ کا نام")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        selectUnitBtn = TextView(this).apply {
            text = "📏 " + Loc.t(this@ProductActivity, "Select Unit", "یونٹ منتخب کریں")
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(teal, 30)
            setPadding(26, 15, 26, 15)
            setOnClickListener { openUnitDialog() }
        }

        nameBox.addView(name)
        nameBox.addView(selectUnitBtn)
        nameCard.addView(nameBox)
        root.addView(nameCard)

        val categoryCard = premiumCard()
        categoryCard.addView(sectionLabel("🗂️", Loc.t(this, "Category", "کیٹیگری")))

        val categoryBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, fieldFill, 12)
            setPadding(18, 4, 18, 4)
        }

        // ---- Editable + auto-suggest instead of a fixed dropdown-only Spinner: existing
        // categories show as suggestions while typing, but a brand-new category can just be
        // typed directly — no separate "+" dialog needed to keep moving. ----
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

        val ratesCard = premiumCard()
        ratesCard.addView(sectionLabel("💰", Loc.t(this, "Pricing", "قیمتیں")))

        cost = rateField(Loc.t(this, "Purchase Rate", "خریداری کی قیمت"))
        ratesCard.addView(fieldBox(cost, "🛒"))
        ratesCard.addView(spacer(12))

        wholesalePrice = rateField(Loc.t(this, "Wholesale Sale Rate", "تھوک فروخت کی قیمت"))
        ratesCard.addView(fieldBox(wholesalePrice, "📦"))
        ratesCard.addView(spacer(12))

        salePrice = rateField(Loc.t(this, "Retail Sale Rate", "پرچون فروخت کی قیمت"))
        ratesCard.addView(fieldBox(salePrice, "🏪"))
        ratesCard.addView(spacer(12))

        // Opening stock is entered together with its unit.
        stock = EditText(this).apply {
            hint = Loc.t(this@ProductActivity, "Opening Stock", "ابتدائی اسٹاک")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addTextChangedListener(simpleWatcher { updateOpeningStockPreview() })
        }

        val stockBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, fieldFill, 12)
            setPadding(18, 4, 8, 4)
        }

        stockBox.addView(TextView(this).apply {
            text = "🔢  "
            textSize = 14f
        })
        stockBox.addView(stock)

        stockUnitSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                110.dp(), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        stockBox.addView(stockUnitSpinner)
        ratesCard.addView(stockBox)

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
        root.addView(ratesCard)

        // ---- Keyboard-first flow: pressing Next/Enter on the keyboard jumps straight to the
        // next field, all the way through to Save — no need to tap into each box by hand. ----
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

        // ---- Keeps each of these fields clear of the keyboard while typing — this is what
        // fixes Retail Sale Rate (and the others) getting hidden behind the keyboard. Every
        // focus event updates focusedFieldForScroll, and the global layout listener registered
        // in onCreate() keeps re-applying the scroll as the keyboard animates open. ----
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
        productsSectionAnchor = sectionLabel("🗃️", Loc.t(this, "Products", "پروڈکٹس"))
        root.addView(productsSectionAnchor)
        root.addView(spacer(10))

        // ---- Everything below (search box, the list itself, "no results") lives inside this
        // container so it can be hidden as one unit. GONE by default — only becomes visible via
        // toggleProductsList(), triggered by the header's "View List" button. ----
        productsSectionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 4, 18, 4)
            background = strokedBg(border, cardWhite, 14)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, 12)
            }
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

    private fun premiumCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 18, 22, 18)
        background = strokedBg(border, cardWhite, 16)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, 14)
        }
        applyElevation(this, 2f)
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

    private fun sectionLabel(icon: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 12)
        addView(TextView(this@ProductActivity).apply {
            text = "$icon  "
            textSize = 14f
        })
        addView(TextView(this@ProductActivity).apply {
            text = label.uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.02f
        })
    }

    private fun pillLink(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12.5f
        setTextColor(Color.parseColor(teal))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(4, 4, 4, 4)
        setOnClickListener { onClick() }
    }

    private fun rateField(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.parseColor(textMuted))
        setTextColor(Color.parseColor(textDark))
        background = null
        textSize = 15f
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

    // ---- Same crash guard used in PurchaseActivity: AutoCompleteTextView's dropdown is a
    // PopupWindow, and calling showDropDown() while the view isn't attached (or during a fast
    // focus/detach race) can crash the whole app with "PopupWindow not attached to window
    // manager". isAttachedToWindow + try/catch make that impossible here. ----
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

    // ---- Called by the header's "View List" button. Makes the (otherwise hidden) products
    // section visible and scrolls straight to it. ----
    private fun toggleProductsList() {
        if (!::productsSectionContainer.isInitialized) return
        productsSectionContainer.visibility = View.VISIBLE
        scrollToProductsList()
    }

    // ---- Scrolls scrollView just enough so `target` stays clear of the on-screen keyboard.
    // Only nudges the minimum needed (keeps target's bottom clear if the keyboard covers it,
    // or brings it back down if it's scrolled above the visible area) — never over-scrolls. ----
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

    // ---- Only loads units the user has actually saved (via the unit dialog's "New Unit" flow,
    // or by typing a fresh name — see ensureUnitSaved). No hardcoded English defaults are mixed
    // in anymore, so the suggestion list only ever shows what this shop actually uses. ----
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
        // Opening stock may only be entered in units that belong to this
        // product's configured conversion chain. We must not invent a
        // conversion for an unrelated unit such as kg or box.
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

    /** Saves a newly-typed unit name to the DB (so it becomes a suggestion everywhere) if it
     * isn't already known. Safe to call with "None"/blank — those are ignored. */
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
    //
    // NOTE: All smallest-unit conversion/formatting now goes through the shared
    // Product extension functions in Database.kt (toSmallestUnits, smallestUnitFactor,
    // smallestUnitName, formatStockBreakdown) instead of re-implementing the same math
    // locally. This keeps ProductActivity, PurchaseActivity and SaleActivity guaranteed
    // to agree on how stock is stored/displayed — if the conversion logic ever changes,
    // it only needs to change in one place.

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

    /**
     * A throwaway [Product] representing the unit chain currently selected in the form
     * (primary/secondary/tertiary + quantities), so we can reuse the exact same
     * Database.kt conversion/formatting helpers a saved [Product] would use — without
     * duplicating that math here. [stock] is filled in by callers that need it.
     */
    private fun draftProduct(stockValue: Int = 0) = Product(
        barcode = "",
        name = "",
        stock = stockValue,
        unit = selectedPrimaryUnit,
        secondaryUnit = if (selectedSecondaryUnit == "None") "" else selectedSecondaryUnit,
        secondaryUnitQty = selectedSecondaryQty,
        tertiaryUnit = if (selectedTertiaryUnit == "None") "" else selectedTertiaryUnit,
        tertiaryUnitQty = selectedTertiaryQty
    )

    private fun openingStockToSmallest(quantity: Double, unit: String): Int {
        if (quantity <= 0) return 0
        return draftProduct().toSmallestUnits(quantity, unit).roundToInt()
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
            "Stored stock: $smallest ${draft.smallestUnitName()}  •  Display: ${draft.formatStockBreakdown()}",
            "محفوظ اسٹاک: $smallest ${draft.smallestUnitName()}  •  ڈسپلے: ${draft.formatStockBreakdown()}"
        )
    }

    // ---------------- Unit dialog ----------------

    private fun openUnitDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(cardWhite))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 26, 28, 26)
            background = roundedBg(navy, 0)
        }

        header.addView(TextView(this).apply {
            text = "📏  " + Loc.t(this@ProductActivity, "Add Item Unit", "آئٹم یونٹ شامل کریں")
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })

        header.addView(TextView(this).apply {
            text = Loc.t(
                this@ProductActivity,
                "Set how this product's units convert into each other",
                "یہ پروڈکٹ کے یونٹس ایک دوسرے میں کیسے تبدیل ہوں گے، ترتیب دیں"
            )
            textSize = 11.5f
            setTextColor(Color.parseColor(headerSubtitleColor))
            setPadding(0, 4, 0, 0)
        })
        content.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 22, 20, 6)
            setBackgroundColor(Color.parseColor(bg))
        }

        val scroll = ScrollView(this)
        scroll.addView(body)

        // Primary — editable + auto-suggest (type a known unit or a brand-new one directly).
        val primaryCard = premiumCard()
        primaryCard.addView(sectionLabel("📏", Loc.t(this, "Primary Unit", "بنیادی یونٹ")))
        val primaryField = unitAutoCompleteField(
            Loc.t(this, "Type or pick unit, e.g. pcs, kg, box", "یونٹ لکھیں یا منتخب کریں، مثلاً pcs, kg, box")
        )
        primaryCard.addView(fieldBox(primaryField, "🔤"))
        body.addView(primaryCard)
        body.addView(spacer(14))

        // Secondary
        val secondaryCard = premiumCard()
        secondaryCard.addView(
            sectionLabel(
                "🔹",
                Loc.t(this, "Secondary Unit (smaller quantity, optional)", "ثانوی یونٹ (چھوٹی مقدار، اختیاری)")
            )
        )
        val secondaryField = unitAutoCompleteField(
            Loc.t(this, "Leave blank if not needed", "اگر ضرورت نہیں تو خالی چھوڑ دیں")
        )
        secondaryCard.addView(fieldBox(secondaryField, "🔤"))
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
        secondaryCard.addView(fieldBox(secondaryQtyField, "🔁"))
        body.addView(secondaryCard)
        body.addView(spacer(14))

        // Tertiary
        val tertiaryCard = premiumCard()
        tertiaryCard.addView(
            sectionLabel(
                "🔸",
                Loc.t(this, "Tertiary Unit (smallest quantity, optional)", "تیسرا یونٹ (سب سے چھوٹی مقدار، اختیاری)")
            )
        )
        val tertiaryField = unitAutoCompleteField(
            Loc.t(this, "Leave blank if not needed", "اگر ضرورت نہیں تو خالی چھوڑ دیں")
        )
        tertiaryCard.addView(fieldBox(tertiaryField, "🔤"))
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
        tertiaryCard.addView(fieldBox(tertiaryQtyField, "🔁"))
        body.addView(tertiaryCard)

        content.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(28, 18, 28, 26)
        }
        content.addView(footer)

        val dialog = AlertDialog.Builder(this).setView(content).create()

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

        // Validates the three fields, commits them to selectedPrimaryUnit/etc., saves any
        // brand-new unit names to the DB, and closes the dialog. Shared by both the on-screen
        // "Save" tap and pressing the keyboard's Done button on the last field.
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

        // ---- Keyboard-first flow inside the dialog too: Next chains straight through every
        // field, and Done on the very last field saves the whole unit selection. ----
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
            setPadding(0, 22, 0, 22)
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
            background = roundedBg(teal, 14)
            setPadding(0, 22, 0, 22)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener { trySaveUnitSelection() }
        })

        dialog.show()
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

        // Database stock is the smallest-unit count after the architecture change.
        stock.setText(product.stock.toString())
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

        /*
         * IMPORTANT:
         * Product.stock is treated as the smallest-unit count.
         * New product opening stock is converted from the selected entry unit.
         * Existing product stock is never overwritten here.
         */
        val resolvedStock: Int
        val resolvedOpeningStock: Int

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
                if (selectedTertiaryUnit == "None") 0.0 else selectedTertiaryQty
        )

        lifecycleScope.launch {
            try {
                val db = PosDatabase.get(this@ProductActivity)

                // A category typed fresh (not picked from suggestions) gets saved so it shows
                // up as a suggestion from now on — same idea as units.
                if (categoryValue != "General" && categoryNames.none { it.equals(categoryValue, ignoreCase = true) }) {
                    db.categoryDao().insert(Category(categoryValue))
                }

                db.productDao().upsert(product)

                Toast.makeText(
                    this@ProductActivity,
                    if (existing != null) {
                        Loc.t(this@ProductActivity, "Product updated", "پروڈکٹ اپ ڈیٹ ہو گئی")
                    } else {
                        Loc.t(this@ProductActivity, "Product saved", "پروڈکٹ محفوظ ہو گئی")
                    },
                    Toast.LENGTH_SHORT
                ).show()

                // Marks which card to highlight if/when the user opens the list — does NOT
                // force the (hidden-by-default) products section open on its own; see the
                // visibility check inside renderProducts().
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
                        PosDatabase.get(this@ProductActivity)
                            .productDao()
                            .delete(product)

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
                setPadding(20, 16, 20, 16)
                background = if (isJustSaved) {
                    strokedBg(teal, savedHighlightBg, 14)
                } else {
                    strokedBg(border, cardWhite, 14)
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, 0, 0, 10)
                }
                applyElevation(this, 2f)
            }

            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            top.addView(TextView(this).apply {
                text = if (isJustSaved) "✓ ${product.name}" else product.name
                textSize = 14.5f
                setTextColor(Color.parseColor(if (isJustSaved) teal else textDark))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })

            top.addView(TextView(this).apply {
                text = product.category
                setTextColor(Color.WHITE)
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                background = roundedBg(teal, 16)
                setPadding(16, 5, 16, 5)
            })

            card.addView(top)

            card.addView(TextView(this).apply {
                val breakdown = product.formatStockBreakdown()
                text = "📊  ${Loc.t(this@ProductActivity, "Stock", "اسٹاک")}: $breakdown"
                textSize = 12f
                setTextColor(Color.parseColor(textMuted))
                setPadding(0, 8, 0, 4)
            })

            card.addView(TextView(this).apply {
                text = "🛒 %s: %.2f   •   📦 %s: %.2f   •   🏪 %s: %.2f".format(
                    Loc.t(this@ProductActivity, "Purchase", "خریداری"),
                    product.cost,
                    Loc.t(this@ProductActivity, "Wholesale", "تھوک"),
                    product.wholesalePrice,
                    Loc.t(this@ProductActivity, "Retail", "پرچون"),
                    product.salePrice
                )
                textSize = 12f
                setTextColor(Color.parseColor(teal))
                setTypeface(typeface, Typeface.BOLD)
            })

            if (product.secondaryUnit.isNotBlank()) {
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
                    setPadding(0, 4, 0, 0)
                })
            }

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 0)
            }

            actions.addView(TextView(this).apply {
                text = "✏️  " + Loc.t(this@ProductActivity, "Edit", "ترمیم کریں")
                textSize = 12f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                background = roundedBg(navy, 30)
                setPadding(24, 10, 24, 10)
                setOnClickListener { loadProductForEdit(product) }
            })

            actions.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(10, 1)
            })

            actions.addView(TextView(this).apply {
                text = "🗑️  " + Loc.t(this@ProductActivity, "Delete", "حذف کریں")
                textSize = 12f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                background = roundedBg(red, 30)
                setPadding(24, 10, 24, 10)
                setOnClickListener { confirmDeleteProduct(product) }
            })

            card.addView(actions)
            listContainer.addView(card)

            if (isJustSaved) savedCardView = card
        }

        // The card views were just added, so layout hasn't happened yet on this pass — wait
        // two frames (one for listContainer's children, one for the ScrollView content) before
        // reading .top, otherwise we'd scroll to a stale position of 0. Only actually scrolls
        // if the products section is currently visible — it does NOT auto-open the (now
        // hidden-by-default) list on its own; the user must tap "View List" for that.
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
