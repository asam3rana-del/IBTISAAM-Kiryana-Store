package com.grocerypos.v11.ui

import android.app.AlertDialog
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Category
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.UnitType
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ProductActivity : AppCompatActivity() {

    companion object {
        /** Pass a product's barcode via this extra to open the form pre-loaded in edit mode
         *  (used by CategoriesUnitsActivity's per-product Edit button). */
        const val EXTRA_EDIT_BARCODE = "editBarcode"
    }

    // ================= NAVY + TEAL + WHITE PALETTE (matches Purchase / Sale) =================
    private val bg = "#F4F6F8"
    private val cardWhite = "#FFFFFF"
    private val navy = "#0B2545"     // primary brand — header, Save button
    private val teal = "#0F9B8E"     // secondary accent — headings, chips, "+" icons, totals
    private val red = "#E5484D"      // functional only — delete, cancel-edit
    private val redDark = "#C93A3E"
    private val textDark = "#0B2545" // headings/values reuse navy
    private val textMuted = "#7C8798"
    private val border = "#E3E8EE"

    private lateinit var scrollView: ScrollView
    private lateinit var formCardTitle: TextView
    private lateinit var name: EditText
    private lateinit var selectUnitBtn: TextView
    private lateinit var categorySpinner: Spinner
    private lateinit var cost: EditText
    private lateinit var wholesalePrice: EditText
    private lateinit var salePrice: EditText
    private lateinit var stock: EditText
    private lateinit var stockNote: TextView
    private lateinit var saveButton: Button
    private lateinit var cancelEditChip: TextView
    private lateinit var searchField: EditText
    private lateinit var listContainer: LinearLayout
    private lateinit var noResultsCard: LinearLayout

    private var units = listOf("pcs", "kg", "box", "dozen")

    // ---- currently chosen unit + secondary/tertiary unit (set via the "Select Unit" dialog) ----
    // Chain: 1 primary = secondaryUnitQty secondary; 1 secondary = tertiaryUnitQty tertiary.
    private var selectedPrimaryUnit = "pcs"
    private var selectedSecondaryUnit = "None"
    private var selectedSecondaryQty = 0.0
    private var selectedTertiaryUnit = "None"
    private var selectedTertiaryQty = 0.0

    // ---- when non-null, the form is editing this existing product instead of creating a new one ----
    private var editingProduct: Product? = null

    // ---- full unfiltered product list, kept so search can filter locally without re-querying ----
    private var allProducts: List<Product> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 44, 24, 28)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER (flat navy, teal subtitle — matches Purchase) =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 22, 22)
            background = roundedBg(navy, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 18) }
            applyElevation(this, 6f)
        }
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Add / Edit Product", "پروڈکٹ شامل / تبدیل کریں")
            textSize = 18.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Inventory Management", "انوینٹری مینجمنٹ")
            textSize = 11f
            setTextColor(Color.parseColor("#9FB4CC"))
            setPadding(0, 3, 0, 0)
        })
        header.addView(headerCol)
        root.addView(header)

        // ================= FORM CARD TITLE (title + cancel-edit chip) =================
        val formHeaderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        formCardTitle = TextView(this).apply {
            text = "✚  " + Loc.t(this@ProductActivity, "New Product", "نئی پروڈکٹ")
            textSize = 14.5f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        cancelEditChip = TextView(this).apply {
            text = "✕  " + Loc.t(this@ProductActivity, "Cancel Edit", "ترمیم منسوخ کریں")
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(red, 30)
            setPadding(24, 12, 24, 12)
            visibility = View.GONE
            setOnClickListener { clearForm() }
        }
        formHeaderRow.addView(formCardTitle)
        formHeaderRow.addView(cancelEditChip)
        root.addView(formHeaderRow)
        root.addView(spacer(10))

        // ================= NAME + embedded "Select Unit" pill =================
        val nameCard = premiumCard()
        nameCard.addView(sectionLabel("🏷️", Loc.t(this, "Product Name", "پروڈکٹ کا نام")))

        val nameBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 6, 6, 6)
            background = strokedBg(border, "#FAFBFC", 12)
        }
        name = EditText(this).apply {
            hint = Loc.t(this@ProductActivity, "Product Name", "پروڈکٹ کا نام")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        selectUnitBtn = TextView(this).apply {
            text = "📏 " + Loc.t(this@ProductActivity, "Select Unit", "یونٹ منتخب کریں")
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(teal, 30)
            setPadding(26, 15, 26, 15)
            setOnClickListener { openUnitDialog() }
        }
        nameBox.addView(name)
        nameBox.addView(selectUnitBtn)
        nameCard.addView(nameBox)
        root.addView(nameCard)

        // ================= CATEGORY =================
        val categoryCard = premiumCard()
        categoryCard.addView(sectionLabel("🗂️", Loc.t(this, "Category", "کیٹیگری")))
        val spinnerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = strokedBg(border, "#FAFBFC", 12)
            setPadding(16, 2, 16, 2)
        }
        categorySpinner = Spinner(this)
        spinnerBox.addView(categorySpinner)
        categoryCard.addView(spinnerBox)
        categoryCard.addView(spacer(10))
        categoryCard.addView(pillLink("✚  " + Loc.t(this, "Add New Category", "نئی کیٹیگری شامل کریں")) { promptAddCategory() })
        root.addView(categoryCard)

        // ================= RATES =================
        val ratesCard = premiumCard()
        ratesCard.addView(sectionLabel("💰", Loc.t(this, "Pricing", "قیمتیں")))

        cost = rateField("🛒", Loc.t(this, "Purchase Rate", "خریداری کی قیمت"))
        ratesCard.addView(fieldBox(cost))
        ratesCard.addView(spacer(12))

        wholesalePrice = rateField("📦", Loc.t(this, "Wholesale Sale Rate", "تھوک فروخت کی قیمت"))
        ratesCard.addView(fieldBox(wholesalePrice))
        ratesCard.addView(spacer(12))

        salePrice = rateField("🏪", Loc.t(this, "Retail Sale Rate", "پرچون فروخت کی قیمت"))
        ratesCard.addView(fieldBox(salePrice))
        ratesCard.addView(spacer(12))

        stock = EditText(this).apply {
            hint = Loc.t(this@ProductActivity, "Opening Stock (optional)", "ابتدائی اسٹاک (اختیاری)")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        ratesCard.addView(fieldBox(stock, "🔢"))
        stockNote = TextView(this).apply {
            text = Loc.t(
                this@ProductActivity,
                "Stock is locked while editing — change it via Purchase/Sale instead",
                "ترمیم کے دوران اسٹاک لاک ہے — اسے تبدیل کرنے کے لیے خریداری/سیل استعمال کریں"
            )
            textSize = 11f
            setTextColor(Color.parseColor("#F5A524"))
            setPadding(6, 8, 0, 0)
            visibility = View.GONE
        }
        ratesCard.addView(stockNote)
        root.addView(ratesCard)

        val scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // ---- everything below the pricing card (products list) also scrolls, kept inline ----
        val listHeaderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listHeaderRow.addView(sectionLabel("🗃️", Loc.t(this, "Products", "پروڈکٹس")))
        root.addView(spacer(4))
        root.addView(listHeaderRow)
        root.addView(spacer(10))

        // ================= SEARCH BAR =================
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 4, 18, 4)
            background = strokedBg(border, cardWhite, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
        }
        searchBox.addView(TextView(this).apply { text = "🔍  "; textSize = 15f })
        searchField = EditText(this).apply {
            hint = Loc.t(this@ProductActivity, "Search products by name or category…", "نام یا کیٹیگری سے پروڈکٹ تلاش کریں…")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 14.5f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchBox.addView(searchField)
        val clearSearchBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(14, 10, 6, 10)
            visibility = View.GONE
            setOnClickListener { searchField.text.clear() }
        }
        searchBox.addView(clearSearchBtn)
        root.addView(searchBox)

        searchField.addTextChangedListener(simpleWatcher {
            val q = searchField.text.toString()
            clearSearchBtn.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
            renderProducts(filterProducts(q))
        })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

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
                text = Loc.t(this@ProductActivity, "No matching products", "کوئی مماثل پروڈکٹ نہیں ملی")
                textSize = 13f
                setTextColor(Color.parseColor(textMuted))
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 0)
            })
        }
        root.addView(noResultsCard)
        root.addView(spacer(24))

        scrollArea.addView(root)

        // ================= SAVE (fixed bottom bar, flat navy — matches Purchase/Sale) =================
        saveButton = Button(this).apply {
            text = "💾  " + Loc.t(this@ProductActivity, "SAVE PRODUCT", "پروڈکٹ محفوظ کریں")
            setTextColor(Color.WHITE)
            textSize = 15.5f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(navy, 16)
            setPadding(0, 26, 0, 26)
            setOnClickListener { saveProduct() }
            applyElevation(this, 4f)
        }

        val saveBar = LinearLayout(this).apply {
            setPadding(24, 14, 24, 18)
            setBackgroundColor(Color.parseColor(cardWhite))
            applyElevation(this, 8f)
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

        scrollView = scrollArea

        loadCategories()
        loadUnits()
        loadProducts()

        // ---- Deep-link from CategoriesUnitsActivity's Edit button: open pre-loaded in edit mode ----
        intent.getStringExtra(EXTRA_EDIT_BARCODE)?.let { barcode ->
            lifecycleScope.launch {
                PosDatabase.get(this@ProductActivity).productDao().find(barcode)?.let { p ->
                    loadProductForEdit(p)
                }
            }
        }
    }

    // ---- UI helpers (premium card look — matches Purchase/Sale) ----
    private fun premiumCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 18, 22, 18)
        background = strokedBg(border, cardWhite, 16)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 14) }
        applyElevation(this, 2f)
    }

    private fun fieldBox(field: EditText, icon: String = "") = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = strokedBg(border, "#FAFBFC", 12)
        setPadding(18, 4, 18, 4)
        if (icon.isNotEmpty()) {
            addView(TextView(this@ProductActivity).apply { text = "$icon  "; textSize = 14f })
        }
        (field.parent as? ViewGroup)?.removeView(field)
        field.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(field)
    }

    /** Section heading — now teal, matching the accent color used across Purchase/Sale. */
    private fun sectionLabel(icon: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 12)
        addView(TextView(this@ProductActivity).apply { text = "$icon  "; textSize = 14f })
        addView(TextView(this@ProductActivity).apply {
            text = label.uppercase()
            textSize = 12f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.02f
        })
    }

    private fun pillLink(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12.5f
        setTextColor(Color.parseColor(teal))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(4, 4, 4, 4)
        setOnClickListener { onClick() }
    }

    private fun circleIcon(text: String, colorHex: String, sizeDp: Int) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        width = px; height = px
    }

    private fun rateField(icon: String, hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Color.parseColor(textMuted))
        setTextColor(Color.parseColor(textDark))
        background = null
        textSize = 15f
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
    }

    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }

    private fun ovalBg(colorHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
    }

    /** Adds a soft elevation/shadow to a view that has a rounded background (API 21+). */
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

    // ---- Data loading ----
    private fun loadCategories() {
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).categoryDao().all().collectLatest { list ->
                val names = (listOf("General") + list.map { it.name }).distinct()
                categorySpinner.adapter = ArrayAdapter(this@ProductActivity, android.R.layout.simple_spinner_dropdown_item, names)
                // If a product is mid-edit, re-apply its category selection after the adapter refresh.
                editingProduct?.let { p ->
                    val idx = names.indexOf(p.category)
                    if (idx >= 0) categorySpinner.setSelection(idx)
                }
            }
        }
    }

    private fun loadUnits() {
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).unitDao().all().collectLatest { list ->
                units = (listOf("pcs", "kg", "box", "dozen") + list.map { it.name }).distinct()
            }
        }
    }

    // ================= Universal unit-conversion lookup =================
    // Known standard conversions between common unit names (case/spacing-insensitive),
    // regardless of whether the pair sits in the primary→secondary or secondary→tertiary slot.
    // Returns null for any pair not in this table — those still need manual entry.
    private fun normalizeUnitName(u: String) = u.trim().lowercase()

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

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun promptAddCategory() {
        val input = EditText(this).apply { setPadding(32, 24, 32, 24) }
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "New Category", "نئی کیٹیگری"))
            .setView(input)
            .setPositiveButton(Loc.t(this, "Add", "شامل کریں")) { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) lifecycleScope.launch {
                    PosDatabase.get(this@ProductActivity).categoryDao().insert(Category(v))
                    Toast.makeText(this@ProductActivity, Loc.t(this@ProductActivity, "Category added", "کیٹیگری شامل ہو گئی"), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    // ================= "Add Item Unit" dialog: Primary / Secondary / Tertiary Unit =================
    // Chain: 1 Primary = secondaryQty Secondary; 1 Secondary = tertiaryQty Tertiary.
    // Tertiary only makes sense once a Secondary unit is chosen, since it's defined
    // relative to the Secondary unit, not the Primary one directly.
    private fun openUnitDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(cardWhite))
        }

        val dialogHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 26, 28, 26)
            background = roundedBg(navy, 0)
        }
        dialogHeader.addView(TextView(this).apply {
            text = "📏  " + Loc.t(this@ProductActivity, "Add Item Unit", "آئٹم یونٹ شامل کریں")
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(dialogHeader)

        val scrollableBody = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 26, 28, 8)
        }
        scrollableBody.addView(body)

        // ---- Primary Unit ----
        body.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "PRIMARY UNIT", "بنیادی یونٹ"); textSize = 11.5f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })
        val primaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val primarySpinnerBox = LinearLayout(this).apply {
            background = strokedBg(border, "#FAFBFC", 12)
            setPadding(14, 2, 14, 2)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val primarySpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        primarySpinnerBox.addView(primarySpinner)
        primaryRow.addView(primarySpinnerBox)
        primaryRow.addView(smallAddButton {
            promptAddUnitInline { newUnit ->
                units = (units + newUnit).distinct()
                primarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
                primarySpinner.setSelection(units.indexOf(newUnit))
            }
        })
        body.addView(primaryRow)
        body.addView(spacer(20))

        // ---- Secondary Unit ----
        body.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "SECONDARY UNIT (smaller quantity, optional)", "ثانوی یونٹ (چھوٹی مقدار، اختیاری)"); textSize = 11.5f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })
        val secondaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val secondarySpinnerBox = LinearLayout(this).apply {
            background = strokedBg(border, "#FAFBFC", 12)
            setPadding(14, 2, 14, 2)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val secondarySpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        secondarySpinnerBox.addView(secondarySpinner)
        secondaryRow.addView(secondarySpinnerBox)
        secondaryRow.addView(smallAddButton {
            promptAddUnitInline { newUnit ->
                units = (units + newUnit).distinct()
                val opts = listOf("None") + units
                secondarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opts)
                secondarySpinner.setSelection(opts.indexOf(newUnit))
            }
        })
        body.addView(secondaryRow)
        body.addView(spacer(16))

        val qtyBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, "#FAFBFC", 12)
            setPadding(16, 4, 16, 4)
        }
        qtyBox.addView(TextView(this).apply { text = "🔁  "; textSize = 14f })
        val qtyField = EditText(this).apply {
            hint = Loc.t(this@ProductActivity, "1 Unit = how many Secondary Units? (e.g. 1 box = 12 pcs)", "1 یونٹ = کتنے ثانوی یونٹس؟ (مثلاً 1 باکس = 12 پیس)")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (selectedSecondaryQty > 0) setText(selectedSecondaryQty.toString())
        }
        qtyBox.addView(qtyField)
        body.addView(qtyBox)
        body.addView(spacer(20))

        // ---- Tertiary Unit (smallest tier, defined relative to the Secondary unit) ----
        body.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "TERTIARY UNIT (smallest quantity, optional)", "تیسرا یونٹ (سب سے چھوٹی مقدار، اختیاری)"); textSize = 11.5f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })
        val tertiaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tertiarySpinnerBox = LinearLayout(this).apply {
            background = strokedBg(border, "#FAFBFC", 12)
            setPadding(14, 2, 14, 2)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tertiarySpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        tertiarySpinnerBox.addView(tertiarySpinner)
        tertiaryRow.addView(tertiarySpinnerBox)
        tertiaryRow.addView(smallAddButton {
            promptAddUnitInline { newUnit ->
                units = (units + newUnit).distinct()
                val opts = listOf("None") + units
                tertiarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opts)
                tertiarySpinner.setSelection(opts.indexOf(newUnit))
            }
        })
        body.addView(tertiaryRow)
        body.addView(spacer(16))

        val tertiaryQtyBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, "#FAFBFC", 12)
            setPadding(16, 4, 16, 4)
        }
        tertiaryQtyBox.addView(TextView(this).apply { text = "🔁  "; textSize = 14f })
        val tertiaryQtyField = EditText(this).apply {
            hint = Loc.t(this@ProductActivity, "1 Secondary Unit = how many Tertiary Units? (e.g. 1 pcs = 10 grams)", "1 ثانوی یونٹ = کتنے تیسرے یونٹس؟ (مثلاً 1 پیس = 10 گرام)")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (selectedTertiaryQty > 0) setText(selectedTertiaryQty.toString())
        }
        tertiaryQtyBox.addView(tertiaryQtyField)
        body.addView(tertiaryQtyBox)

        content.addView(scrollableBody, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- initial adapters + preselect current values ----
        primarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
        primarySpinner.setSelection(units.indexOf(selectedPrimaryUnit).coerceAtLeast(0))
        val secondaryOptions = listOf("None") + units
        secondarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, secondaryOptions)
        secondarySpinner.setSelection(secondaryOptions.indexOf(selectedSecondaryUnit).coerceAtLeast(0))
        val tertiaryOptions = listOf("None") + units
        tertiarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tertiaryOptions)
        tertiarySpinner.setSelection(tertiaryOptions.indexOf(selectedTertiaryUnit).coerceAtLeast(0))

        // ---- Universal conversion auto-fill: when a known unit pair is picked (dozen→pcs=12,
        // kg→gram=1000, etc.), suggest the standard quantity automatically. Only fills an EMPTY
        // field, so it never overwrites a value the user already typed. ----
        fun autoFillSecondaryQty() {
            val p = primarySpinner.selectedItem?.toString() ?: return
            val s = secondarySpinner.selectedItem?.toString() ?: return
            if (s == "None") return
            val std = standardUnitQty(p, s) ?: return
            if (qtyField.text.toString().isBlank()) qtyField.setText(trimNum(std))
        }
        fun autoFillTertiaryQty() {
            val s = secondarySpinner.selectedItem?.toString() ?: return
            val t = tertiarySpinner.selectedItem?.toString() ?: return
            if (s == "None" || t == "None") return
            val std = standardUnitQty(s, t) ?: return
            if (tertiaryQtyField.text.toString().isBlank()) tertiaryQtyField.setText(trimNum(std))
        }
        primarySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { autoFillSecondaryQty() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        secondarySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                autoFillSecondaryQty()
                autoFillTertiaryQty()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        tertiarySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { autoFillTertiaryQty() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        // Run once immediately for whatever is already preselected (e.g. when editing an existing product).
        autoFillSecondaryQty()
        autoFillTertiaryQty()

        // ---- Cancel / Save footer ----
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(28, 18, 28, 26)
        }
        content.addView(footer)

        val dialog = AlertDialog.Builder(this).setView(content).create()

        footer.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Cancel", "منسوخ کریں")
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = strokedBg(border, "#FAFBFC", 14)
            setPadding(0, 22, 0, 22)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
            setOnClickListener { dialog.dismiss() }
        })
        footer.addView(TextView(this).apply {
            text = "✓  " + Loc.t(this@ProductActivity, "Save", "محفوظ کریں")
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(teal, 14)
            setPadding(0, 22, 0, 22)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 0) }
            setOnClickListener {
                selectedPrimaryUnit = primarySpinner.selectedItem?.toString() ?: "pcs"
                selectedSecondaryUnit = secondarySpinner.selectedItem?.toString() ?: "None"
                selectedSecondaryQty = qtyField.text.toString().toDoubleOrNull() ?: 0.0
                selectedTertiaryUnit = tertiarySpinner.selectedItem?.toString() ?: "None"
                selectedTertiaryQty = tertiaryQtyField.text.toString().toDoubleOrNull() ?: 0.0

                // Tertiary is meaningless without a Secondary chain — don't silently keep it.
                if (selectedSecondaryUnit == "None") {
                    selectedTertiaryUnit = "None"
                    selectedTertiaryQty = 0.0
                }

                selectUnitBtn.text = buildString {
                    append("📏 $selectedPrimaryUnit")
                    if (selectedSecondaryUnit != "None") append(" / $selectedSecondaryUnit")
                    if (selectedTertiaryUnit != "None") append(" / $selectedTertiaryUnit")
                }

                updateUnitConversionToast()
                dialog.dismiss()
            }
        })

        dialog.show()
    }

    private fun smallAddButton(onClick: () -> Unit) = TextView(this).apply {
        text = "+"
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = ovalBg(teal)
        val px = (36 * resources.displayMetrics.density).toInt()
        width = px; height = px
        layoutParams = LinearLayout.LayoutParams(px, px).apply { setMargins(10, 0, 0, 0) }
        setOnClickListener { onClick() }
    }

    private fun promptAddUnitInline(onAdded: (String) -> Unit) {
        val input = EditText(this).apply { setPadding(32, 24, 32, 24) }
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "New Unit", "نیا یونٹ"))
            .setView(input)
            .setPositiveButton(Loc.t(this, "Add", "شامل کریں")) { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) lifecycleScope.launch {
                    PosDatabase.get(this@ProductActivity).unitDao().insert(UnitType(v))
                    Toast.makeText(this@ProductActivity, Loc.t(this@ProductActivity, "Unit added", "یونٹ شامل ہو گیا"), Toast.LENGTH_SHORT).show()
                    onAdded(v)
                }
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    // ---- Conversion confirmation: shown briefly (Toast) when the unit dialog is saved, not as a permanent box ----
    private fun updateUnitConversionToast() {
        val parts = mutableListOf<String>()
        if (selectedSecondaryUnit != "None" && selectedSecondaryQty > 0) {
            parts.add("1 $selectedPrimaryUnit = $selectedSecondaryQty $selectedSecondaryUnit")
        }
        if (selectedTertiaryUnit != "None" && selectedTertiaryQty > 0) {
            parts.add("1 $selectedSecondaryUnit = $selectedTertiaryQty $selectedTertiaryUnit")
        }
        if (parts.isEmpty()) return
        Toast.makeText(this, parts.joinToString("   •   ") + "  noted", Toast.LENGTH_SHORT).show()
    }

    // ================= Load an existing product into the form for editing =================
    private fun loadProductForEdit(p: Product) {
        editingProduct = p

        name.setText(p.name)

        selectedPrimaryUnit = p.unit
        selectedSecondaryUnit = if (p.secondaryUnit.isBlank()) "None" else p.secondaryUnit
        selectedSecondaryQty = p.secondaryUnitQty
        selectedTertiaryUnit = if (p.tertiaryUnit.isBlank()) "None" else p.tertiaryUnit
        selectedTertiaryQty = p.tertiaryUnitQty

        selectUnitBtn.text = buildString {
            append("📏 $selectedPrimaryUnit")
            if (selectedSecondaryUnit != "None") append(" / $selectedSecondaryUnit")
            if (selectedTertiaryUnit != "None") append(" / $selectedTertiaryUnit")
        }

        val adapter = categorySpinner.adapter
        if (adapter != null) {
            for (i in 0 until adapter.count) {
                if (adapter.getItem(i).toString() == p.category) {
                    categorySpinner.setSelection(i)
                    break
                }
            }
        }

        cost.setText(if (p.cost > 0) p.cost.toString() else "")
        wholesalePrice.setText(if (p.wholesalePrice > 0) p.wholesalePrice.toString() else "")
        salePrice.setText(if (p.salePrice > 0) p.salePrice.toString() else "")

        // Stock is shown but locked — it should only move via Purchase/Sale, not a direct overwrite here.
        stock.setText(p.stock.toString())
        stock.isEnabled = false
        stockNote.visibility = View.VISIBLE

        formCardTitle.text = "✏️  " + Loc.t(this, "Editing", "ترمیم ہو رہی ہے") + ": ${p.name}"
        cancelEditChip.visibility = View.VISIBLE
        saveButton.text = "💾  " + Loc.t(this@ProductActivity, "UPDATE PRODUCT", "پروڈکٹ اپ ڈیٹ کریں")

        scrollView.post { scrollView.smoothScrollTo(0, 0) }
    }

    // ================= Delete a product (with confirmation) =================
    private fun confirmDeleteProduct(p: Product) {
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Delete Product", "پروڈکٹ حذف کریں"))
            .setMessage(
                Loc.t(
                    this,
                    "Delete \"${p.name}\"? This cannot be undone.",
                    "\"${p.name}\" کو حذف کریں؟ یہ واپس نہیں ہو سکتا۔"
                )
            )
            .setPositiveButton(Loc.t(this, "Delete", "حذف کریں")) { _, _ ->
                lifecycleScope.launch {
                    PosDatabase.get(this@ProductActivity).productDao().delete(p)
                    // If the deleted product was mid-edit in the form, reset the form too.
                    if (editingProduct?.barcode == p.barcode) clearForm()
                    Toast.makeText(this@ProductActivity, Loc.t(this@ProductActivity, "Product deleted", "پروڈکٹ حذف ہو گئی"), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    private fun saveProduct() {
        val pname = name.text.toString().trim()
        if (pname.isEmpty()) {
            Toast.makeText(this, Loc.t(this, "Product Name is required", "پروڈکٹ کا نام ضروری ہے"), Toast.LENGTH_SHORT).show()
            return
        }

        val existing = editingProduct
        val code = existing?.barcode ?: ("P" + System.currentTimeMillis().toString())
        // Preserve current stock when editing — only new products take stock from the form.
        val resolvedStock = existing?.stock ?: (stock.text.toString().toIntOrNull() ?: 0)
        val resolvedOpeningStock = existing?.openingStock ?: resolvedStock

        val product = Product(
            barcode = code,
            name = pname,
            category = categorySpinner.selectedItem?.toString() ?: "General",
            cost = cost.text.toString().toDoubleOrNull() ?: 0.0,
            salePrice = salePrice.text.toString().toDoubleOrNull() ?: 0.0,
            wholesalePrice = wholesalePrice.text.toString().toDoubleOrNull() ?: 0.0,
            stock = resolvedStock,
            openingStock = resolvedOpeningStock,
            unit = selectedPrimaryUnit,
            secondaryUnit = if (selectedSecondaryUnit == "None") "" else selectedSecondaryUnit,
            secondaryUnitQty = selectedSecondaryQty,
            tertiaryUnit = if (selectedTertiaryUnit == "None") "" else selectedTertiaryUnit,
            tertiaryUnitQty = selectedTertiaryQty
        )
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).productDao().upsert(product)
            Toast.makeText(
                this@ProductActivity,
                if (existing != null) Loc.t(this@ProductActivity, "Product updated", "پروڈکٹ اپ ڈیٹ ہو گئی")
                else Loc.t(this@ProductActivity, "Product saved", "پروڈکٹ محفوظ ہو گئی"),
                Toast.LENGTH_SHORT
            ).show()
            clearForm()
        }
    }

    // ---- Reset the whole form so it's ready for the next product (also exits edit mode) ----
    private fun clearForm() {
        name.text.clear()
        cost.text.clear()
        wholesalePrice.text.clear()
        salePrice.text.clear()
        stock.text.clear()
        stock.isEnabled = true
        stockNote.visibility = View.GONE

        selectedPrimaryUnit = "pcs"
        selectedSecondaryUnit = "None"
        selectedSecondaryQty = 0.0
        selectedTertiaryUnit = "None"
        selectedTertiaryQty = 0.0
        selectUnitBtn.text = "📏 " + Loc.t(this, "Select Unit", "یونٹ منتخب کریں")

        editingProduct = null
        formCardTitle.text = "✚  " + Loc.t(this, "New Product", "نئی پروڈکٹ")
        cancelEditChip.visibility = View.GONE
        saveButton.text = "💾  " + Loc.t(this@ProductActivity, "SAVE PRODUCT", "پروڈکٹ محفوظ کریں")

        if (categorySpinner.adapter != null && categorySpinner.adapter.count > 0) {
            categorySpinner.setSelection(0)
        }
    }

    // ================= Search filtering =================
    /** Filters the cached [allProducts] list by name or category (case-insensitive, partial match). */
    private fun filterProducts(query: String): List<Product> {
        val q = query.trim()
        if (q.isEmpty()) return allProducts
        return allProducts.filter {
            it.name.contains(q, ignoreCase = true) || it.category.contains(q, ignoreCase = true)
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).productDao().all().collectLatest { list ->
                allProducts = list
                renderProducts(filterProducts(searchField.text.toString()))
            }
        }
    }

    /** Renders the given product list into [listContainer]; shows [noResultsCard] when a search yields nothing. */
    private fun renderProducts(list: List<Product>) {
        listContainer.removeAllViews()

        if (list.isEmpty()) {
            noResultsCard.visibility = View.VISIBLE
            return
        }
        noResultsCard.visibility = View.GONE

        for (p in list) {
            listContainer.addView(LinearLayout(this@ProductActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
                background = strokedBg(border, cardWhite, 14)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 10) }
                applyElevation(this, 2f)

                val top = LinearLayout(this@ProductActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(TextView(this@ProductActivity).apply {
                    text = p.name; textSize = 14.5f
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                top.addView(TextView(this@ProductActivity).apply {
                    text = p.category
                    setTextColor(Color.WHITE)
                    textSize = 10.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = roundedBg(teal, 16)
                    setPadding(16, 5, 16, 5)
                })
                addView(top)
                addView(TextView(this@ProductActivity).apply {
                    text = "📊 ${Loc.t(this@ProductActivity, "Stock", "اسٹاک")}: ${p.stock} ${p.unit}"
                    textSize = 12f
                    setTextColor(Color.parseColor(textMuted))
                    setPadding(0, 8, 0, 4)
                })
                addView(TextView(this@ProductActivity).apply {
                    text = "🛒 %s: %.2f   •   📦 %s: %.2f   •   🏪 %s: %.2f".format(
                        Loc.t(this@ProductActivity, "Purchase", "خریداری"), p.cost,
                        Loc.t(this@ProductActivity, "Wholesale", "تھوک"), p.wholesalePrice,
                        Loc.t(this@ProductActivity, "Retail", "پرچون"), p.salePrice
                    )
                    textSize = 12f
                    setTextColor(Color.parseColor(teal))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                if (p.secondaryUnit.isNotEmpty()) {
                    addView(TextView(this@ProductActivity).apply {
                        text = buildString {
                            append("📏 1 ${p.unit} = ${p.secondaryUnitQty} ${p.secondaryUnit}")
                            if (p.tertiaryUnit.isNotEmpty()) append("   •   1 ${p.secondaryUnit} = ${p.tertiaryUnitQty} ${p.tertiaryUnit}")
                        }
                        textSize = 11.5f
                        setTextColor(Color.parseColor(textMuted))
                        setPadding(0, 4, 0, 0)
                    })
                }

                // ---- Edit / Delete actions ----
                val actionsRow = LinearLayout(this@ProductActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 10, 0, 0)
                }
                actionsRow.addView(TextView(this@ProductActivity).apply {
                    text = "✏️  " + Loc.t(this@ProductActivity, "Edit", "ترمیم کریں")
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = roundedBg(navy, 30)
                    setPadding(24, 10, 24, 10)
                    setOnClickListener { loadProductForEdit(p) }
                })
                actionsRow.addView(View(this@ProductActivity).apply { layoutParams = LinearLayout.LayoutParams(10, 1) })
                actionsRow.addView(TextView(this@ProductActivity).apply {
                    text = "🗑️  " + Loc.t(this@ProductActivity, "Delete", "حذف کریں")
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = roundedBg(red, 30)
                    setPadding(24, 10, 24, 10)
                    setOnClickListener { confirmDeleteProduct(p) }
                })
                addView(actionsRow)
            })
        }
    }
}
