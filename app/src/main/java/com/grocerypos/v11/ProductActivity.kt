package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
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
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

class ProductActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EDIT_BARCODE = "editBarcode"
    }

    // Premium palette — kept compatible with Purchase/Sale.
    private val bg = "#F4F6F8"
    private val cardWhite = "#FFFFFF"
    private val navy = "#0B2545"
    private val teal = "#0F9B8E"
    private val red = "#E5484D"
    private val textDark = "#0B2545"
    private val textMuted = "#7C8798"
    private val border = "#E3E8EE"
    private val amber = "#F5A524"

    private lateinit var scrollView: ScrollView
    private lateinit var formCardTitle: TextView
    private lateinit var name: EditText
    private lateinit var selectUnitBtn: TextView
    private lateinit var categorySpinner: Spinner
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

    private var units = listOf(
        "pcs", "kg", "box", "dozen", "carton", "ctn", "outer", "dabbi",
        "gram", "g", "ml", "litre", "liter", "pao", "quintal", "ton", "gross"
    )

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            setTextColor(Color.parseColor("#9FB4CC"))
            setPadding(0, 3, 0, 0)
        })

        header.addView(col)
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
            background = strokedBg(border, "#FAFBFC", 12)
        }

        name = EditText(this).apply {
            hint = Loc.t(this@ProductActivity, "Product Name", "پروڈکٹ کا نام")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            maxLines = 1
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

        val spinnerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = strokedBg(border, "#FAFBFC", 12)
            setPadding(16, 2, 16, 2)
        }

        categorySpinner = Spinner(this)
        spinnerBox.addView(categorySpinner)
        categoryCard.addView(spinnerBox)
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
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addTextChangedListener(simpleWatcher { updateOpeningStockPreview() })
        }

        val stockBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, "#FAFBFC", 12)
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
    }

    private fun buildProductsSection(root: LinearLayout) {
        root.addView(spacer(4))
        root.addView(sectionLabel("🗃️", Loc.t(this, "Products", "پروڈکٹس")))
        root.addView(spacer(10))

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
        root.addView(searchBox)

        searchField.addTextChangedListener(simpleWatcher {
            val q = searchField.text.toString()
            clearSearchBtn.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
            renderProducts(filterProducts(q))
        })

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
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
        root.addView(noResultsCard)
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
        background = strokedBg(border, "#FAFBFC", 12)
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

    // ---------------- Categories / units ----------------

    private fun loadCategories() {
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).categoryDao().all().collectLatest { list ->
                val names = (listOf("General") + list.map { it.name }).distinct()
                categorySpinner.adapter = ArrayAdapter(
                    this@ProductActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    names
                )

                editingProduct?.let { p ->
                    val index = names.indexOf(p.category)
                    if (index >= 0) categorySpinner.setSelection(index)
                }
            }
        }
    }

    private fun loadUnits() {
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).unitDao().all().collectLatest { list ->
                units = (
                    listOf(
                        "pcs", "kg", "box", "dozen", "carton", "ctn",
                        "outer", "dabbi", "gram", "g", "ml",
                        "litre", "liter", "pao", "quintal", "ton", "gross"
                    ) + list.map { it.name }
                ).filter { it.isNotBlank() }.distinct()

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

    /**
     * Number of smallest units represented by ONE primary unit.
     *
     * Example:
     * primary=carton, secondary=outer, secondaryQty=50,
     * tertiary=dabbi, tertiaryQty=10
     * => 1 carton = 500 dabbi.
     */
    private fun smallestFactor(): Double {
        val secondaryFactor =
            if (
                selectedSecondaryUnit != "None" &&
                selectedSecondaryUnit.isNotBlank() &&
                selectedSecondaryQty > 0
            ) selectedSecondaryQty else 1.0

        val tertiaryFactor =
            if (
                selectedTertiaryUnit != "None" &&
                selectedTertiaryUnit.isNotBlank() &&
                selectedTertiaryQty > 0
            ) selectedTertiaryQty else 1.0

        return secondaryFactor * tertiaryFactor
    }

    private fun unitFactorToSmallest(unit: String): Double {
        if (unit == selectedPrimaryUnit) return smallestFactor()

        if (
            selectedSecondaryUnit != "None" &&
            unit == selectedSecondaryUnit &&
            selectedSecondaryQty > 0
        ) {
            return if (
                selectedTertiaryUnit != "None" &&
                selectedTertiaryUnit.isNotBlank() &&
                selectedTertiaryQty > 0
            ) selectedTertiaryQty else 1.0
        }

        if (
            selectedTertiaryUnit != "None" &&
            unit == selectedTertiaryUnit
        ) {
            return 1.0
        }

        // For a custom/unknown opening-stock unit, do not invent a conversion.
        return 1.0
    }

    private fun openingStockToSmallest(quantity: Double, unit: String): Int {
        if (quantity <= 0) return 0
        return (quantity * unitFactorToSmallest(unit)).roundToInt()
    }

    private fun smallestToBreakdown(totalSmallest: Int): String {
        if (totalSmallest <= 0) {
            return "0 $selectedTertiaryUnit".takeIf {
                selectedTertiaryUnit != "None"
            } ?: "0 $selectedPrimaryUnit"
        }

        val tertiaryConfigured =
            selectedTertiaryUnit != "None" && selectedTertiaryQty > 0

        val secondaryConfigured =
            selectedSecondaryUnit != "None" && selectedSecondaryQty > 0

        if (!secondaryConfigured) {
            return "$totalSmallest $selectedPrimaryUnit"
        }

        val secondaryFactor =
            if (tertiaryConfigured) selectedTertiaryQty else 1.0

        val primaryFactor = selectedSecondaryQty * secondaryFactor

        val primaryQty = floor(totalSmallest / primaryFactor)
        var remainder = totalSmallest - primaryQty * primaryFactor

        val secondaryQty =
            if (tertiaryConfigured) floor(remainder / secondaryFactor)
            else remainder

        if (tertiaryConfigured) {
            remainder -= secondaryQty * secondaryFactor
        } else {
            remainder = 0.0
        }

        val parts = mutableListOf<String>()

        if (primaryQty > 0) {
            parts.add("${trimNum(primaryQty)} $selectedPrimaryUnit")
        }

        if (secondaryQty > 0) {
            parts.add("${trimNum(secondaryQty)} $selectedSecondaryUnit")
        }

        if (tertiaryConfigured && remainder > 0) {
            parts.add("${trimNum(remainder)} $selectedTertiaryUnit")
        }

        return if (parts.isEmpty()) {
            "$totalSmallest $selectedTertiaryUnit"
        } else {
            parts.joinToString(" + ")
        }
    }

    private fun updateOpeningStockPreview() {
        if (!::stockPreview.isInitialized) return

        val q = stock.text.toString().toDoubleOrNull() ?: 0.0
        if (q <= 0) {
            stockPreview.text = ""
            return
        }

        val smallest = openingStockToSmallest(q, selectedOpeningStockUnit)
        val smallestName =
            if (selectedTertiaryUnit != "None" && selectedTertiaryUnit.isNotBlank()) {
                selectedTertiaryUnit
            } else if (selectedSecondaryUnit != "None" && selectedSecondaryUnit.isNotBlank()) {
                selectedSecondaryUnit
            } else {
                selectedPrimaryUnit
            }

        stockPreview.text = Loc.t(
            this,
            "Stored stock: $smallest $smallestName  •  Display: ${smallestToBreakdown(smallest)}",
            "محفوظ اسٹاک: $smallest $smallestName  •  ڈسپلے: ${smallestToBreakdown(smallest)}"
        )
    }

    // ---------------- Unit dialog ----------------

    private fun openUnitDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(cardWhite))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 26, 28, 26)
            background = roundedBg(navy, 0)
        }

        header.addView(TextView(this).apply {
            text = "📏  " + Loc.t(this@ProductActivity, "Add Item Unit", "آئٹم یونٹ شامل کریں")
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 26, 28, 8)
        }

        val scroll = ScrollView(this)
        scroll.addView(body)

        // Primary
        body.addView(dialogLabel("PRIMARY UNIT", "بنیادی یونٹ"))

        val primarySpinner = Spinner(this)
        body.addView(spinnerContainer(primarySpinner))
        body.addView(spacer(20))

        // Secondary
        body.addView(dialogLabel("SECONDARY UNIT (smaller quantity, optional)", "ثانوی یونٹ (چھوٹی مقدار، اختیاری)"))

        val secondarySpinner = Spinner(this)
        body.addView(spinnerContainer(secondarySpinner))
        body.addView(spacer(12))

        val secondaryQtyField = numberDialogField(
            Loc.t(
                this,
                "1 Primary = how many Secondary? e.g. 1 box = 12 pcs",
                "1 بنیادی یونٹ = کتنے ثانوی؟ مثلاً 1 box = 12 pcs"
            ),
            selectedSecondaryQty
        )
        body.addView(secondaryQtyField)
        body.addView(spacer(20))

        // Tertiary
        body.addView(dialogLabel("TERTIARY UNIT (smallest quantity, optional)", "تیسرا یونٹ (سب سے چھوٹی مقدار، اختیاری)"))

        val tertiarySpinner = Spinner(this)
        body.addView(spinnerContainer(tertiarySpinner))
        body.addView(spacer(12))

        val tertiaryQtyField = numberDialogField(
            Loc.t(
                this,
                "1 Secondary = how many Tertiary?",
                "1 ثانوی یونٹ = کتنے تیسرے یونٹس؟"
            ),
            selectedTertiaryQty
        )
        body.addView(tertiaryQtyField)

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

        val primaryOptions = units.distinct()
        primarySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            primaryOptions
        )

        val secondaryOptions = listOf("None") + units.distinct()
        secondarySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            secondaryOptions
        )

        val tertiaryOptions = listOf("None") + units.distinct()
        tertiarySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            tertiaryOptions
        )

        primarySpinner.setSelection(
            primaryOptions.indexOf(selectedPrimaryUnit).coerceAtLeast(0)
        )
        secondarySpinner.setSelection(
            secondaryOptions.indexOf(selectedSecondaryUnit).coerceAtLeast(0)
        )
        tertiarySpinner.setSelection(
            tertiaryOptions.indexOf(selectedTertiaryUnit).coerceAtLeast(0)
        )

        fun autoSecondary() {
            val p = primarySpinner.selectedItem?.toString() ?: return
            val s = secondarySpinner.selectedItem?.toString() ?: return
            if (s == "None") return
            val standard = standardUnitQty(p, s) ?: return
            if (secondaryQtyField.text.toString().isBlank()) {
                secondaryQtyField.setText(trimNum(standard))
            }
        }

        fun autoTertiary() {
            val s = secondarySpinner.selectedItem?.toString() ?: return
            val t = tertiarySpinner.selectedItem?.toString() ?: return
            if (s == "None" || t == "None") return
            val standard = standardUnitQty(s, t) ?: return
            if (tertiaryQtyField.text.toString().isBlank()) {
                tertiaryQtyField.setText(trimNum(standard))
            }
        }

        primarySpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    autoSecondary()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        secondarySpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    autoSecondary()
                    autoTertiary()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        tertiarySpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    autoTertiary()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        autoSecondary()
        autoTertiary()

        footer.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Cancel", "منسوخ کریں")
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, Typeface.BOLD)
            background = strokedBg(border, "#FAFBFC", 14)
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

            setOnClickListener {
                val p = primarySpinner.selectedItem?.toString()?.trim().orEmpty()
                var s = secondarySpinner.selectedItem?.toString()?.trim() ?: "None"
                var t = tertiarySpinner.selectedItem?.toString()?.trim() ?: "None"

                val sq = secondaryQtyField.text.toString().toDoubleOrNull() ?: 0.0
                val tq = tertiaryQtyField.text.toString().toDoubleOrNull() ?: 0.0

                if (p.isBlank()) {
                    Toast.makeText(
                        this@ProductActivity,
                        Loc.t(this@ProductActivity, "Select Primary Unit", "بنیادی یونٹ منتخب کریں"),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                if (s != "None" && s == p) {
                    Toast.makeText(
                        this@ProductActivity,
                        Loc.t(this@ProductActivity, "Secondary must be different", "ثانوی یونٹ مختلف ہونا چاہیے"),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                if (s != "None" && sq <= 0) {
                    secondaryQtyField.error =
                        Loc.t(this@ProductActivity, "Enter quantity", "مقدار درج کریں")
                    return@setOnClickListener
                }

                if (t != "None" && s == "None") {
                    Toast.makeText(
                        this@ProductActivity,
                        Loc.t(this@ProductActivity, "Select Secondary first", "پہلے ثانوی یونٹ منتخب کریں"),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                if (t != "None" && t == s) {
                    Toast.makeText(
                        this@ProductActivity,
                        Loc.t(this@ProductActivity, "Tertiary must be different", "تیسرا یونٹ مختلف ہونا چاہیے"),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                if (t != "None" && tq <= 0) {
                    tertiaryQtyField.error =
                        Loc.t(this@ProductActivity, "Enter quantity", "مقدار درج کریں")
                    return@setOnClickListener
                }

                if (s == "None") {
                    t = "None"
                }

                selectedPrimaryUnit = p
                selectedSecondaryUnit = s
                selectedSecondaryQty = if (s == "None") 0.0 else sq
                selectedTertiaryUnit = t
                selectedTertiaryQty = if (t == "None") 0.0 else tq

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

                dialog.dismiss()
            }
        })

        dialog.show()
    }

    private fun dialogLabel(en: String, ur: String) = TextView(this).apply {
        text = Loc.t(this@ProductActivity, en, ur)
        textSize = 11.5f
        setTextColor(Color.parseColor(textMuted))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, 8)
    }

    private fun spinnerContainer(spinner: Spinner) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = strokedBg(border, "#FAFBFC", 12)
        setPadding(14, 2, 14, 2)
        addView(spinner)
    }

    private fun numberDialogField(hintText: String, oldValue: Double) =
        EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = strokedBg(border, "#FAFBFC", 12)
            setPadding(16, 14, 16, 14)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
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

        categorySpinner.adapter?.let { adapter ->
            for (i in 0 until adapter.count) {
                if (adapter.getItem(i).toString() == product.category) {
                    categorySpinner.setSelection(i)
                    break
                }
            }
        }

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
            "Current stock: ${productStockBreakdown(product)}",
            "موجودہ اسٹاک: ${productStockBreakdown(product)}"
        )

        formCardTitle.text =
            "✏️  " + Loc.t(this, "Editing", "ترمیم ہو رہی ہے") + ": ${product.name}"

        cancelEditChip.visibility = View.VISIBLE
        saveButton.text =
            "💾  " + Loc.t(this, "UPDATE PRODUCT", "پروڈکٹ اپ ڈیٹ کریں")

        scrollView.post { scrollView.smoothScrollTo(0, 0) }
    }

    private fun productStockBreakdown(product: Product): String {
        val total = product.stock

        val primary = product.unit.ifBlank { "pcs" }
        val secondary = product.secondaryUnit
        val tertiary = product.tertiaryUnit

        val secondaryQty =
            if (secondary.isNotBlank() && product.secondaryUnitQty > 0) {
                product.secondaryUnitQty
            } else 0.0

        val tertiaryQty =
            if (
                tertiary.isNotBlank() &&
                product.tertiaryUnitQty > 0 &&
                secondaryQty > 0
            ) product.tertiaryUnitQty else 0.0

        if (secondaryQty <= 0) return "$total $primary"

        val secondaryFactor = if (tertiaryQty > 0) tertiaryQty else 1.0
        val primaryFactor = secondaryQty * secondaryFactor

        val primaryCount = floor(total / primaryFactor)
        var remainder = total - primaryCount * primaryFactor

        val secondaryCount = floor(remainder / secondaryFactor)
        if (tertiaryQty > 0) {
            remainder -= secondaryCount * secondaryFactor
        } else {
            remainder = 0.0
        }

        val parts = mutableListOf<String>()

        if (primaryCount > 0) {
            parts.add("${trimNum(primaryCount)} $primary")
        }

        if (secondaryCount > 0) {
            parts.add("${trimNum(secondaryCount)} $secondary")
        }

        if (tertiaryQty > 0 && remainder > 0) {
            parts.add("${trimNum(remainder)} $tertiary")
        }

        return if (parts.isEmpty()) "$total $tertiary"
        else parts.joinToString(" + ")
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
            category = categorySpinner.selectedItem?.toString() ?: "General",
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
                PosDatabase.get(this@ProductActivity)
                    .productDao()
                    .upsert(product)

                Toast.makeText(
                    this@ProductActivity,
                    if (existing != null) {
                        Loc.t(this@ProductActivity, "Product updated", "پروڈکٹ اپ ڈیٹ ہو گئی")
                    } else {
                        Loc.t(this@ProductActivity, "Product saved", "پروڈکٹ محفوظ ہو گئی")
                    },
                    Toast.LENGTH_SHORT
                ).show()

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

        categorySpinner.adapter?.let {
            if (it.count > 0) categorySpinner.setSelection(0)
        }

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

        products.forEach { product ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
                background = strokedBg(border, cardWhite, 14)
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
                text = product.name
                textSize = 14.5f
                setTextColor(Color.parseColor(textDark))
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
                val breakdown = productStockBreakdown(product)
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
        }
    }
}
