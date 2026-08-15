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

    // ================= PREMIUM COLOR PALETTE (matches SaleActivity) =================
    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val green = "#1FA971"
    private val greenDark = "#158A5A"
    private val red = "#E5484D"
    private val blue = "#2F6FED"
    private val amber = "#F5A524"
    private val purple = "#8B5CF6"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private lateinit var name: EditText
    private lateinit var selectUnitBtn: TextView
    private lateinit var categorySpinner: Spinner
    private lateinit var cost: EditText
    private lateinit var wholesalePrice: EditText
    private lateinit var salePrice: EditText
    private lateinit var stock: EditText
    private lateinit var listContainer: LinearLayout

    private var units = listOf("pcs", "kg", "box", "dozen")

    // ---- currently chosen unit + secondary unit (set via the "Select Unit" dialog) ----
    private var selectedPrimaryUnit = "pcs"
    private var selectedSecondaryUnit = "None"
    private var selectedSecondaryQty = 0.0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 26, 22)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            ).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            applyElevation(this, 10f)
        }
        header.addView(circleIcon("📦", "#5C4DFF", 40))
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Add / Edit Product", "پروڈکٹ شامل / تبدیل کریں")
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Inventory Management", "انوینٹری مینجمنٹ")
            textSize = 11.5f
            setTextColor(Color.parseColor("#D8D3FF"))
            setPadding(0, 4, 0, 0)
        })
        header.addView(headerCol)
        root.addView(header)
        root.addView(spacer(20))

        // ================= NAME + embedded "Select Unit" pill =================
        val nameCard = cardContainer()
        nameCard.addView(sectionLabel("🏷️", Loc.t(this, "Product Name", "پروڈکٹ کا نام")))

        // Outer bordered box holding BOTH the name field and the unit pill (single "field" look)
        val nameBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 8, 8, 8)
            background = strokedBg(border, "#FAFAFF", 14)
        }
        name = EditText(this).apply {
            hint = Loc.t(this@ProductActivity, "Product Name", "پروڈکٹ کا نام")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        selectUnitBtn = TextView(this).apply {
            text = "📏 " + Loc.t(this@ProductActivity, "Select Unit", "یونٹ منتخب کریں")
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(primary, 30)
            setPadding(28, 16, 28, 16)
            setOnClickListener { openUnitDialog() }
        }
        nameBox.addView(name)
        nameBox.addView(selectUnitBtn)
        nameCard.addView(nameBox)

        root.addView(nameCard)
        root.addView(spacer(18))

        // ================= CATEGORY =================
        val categoryCard = cardContainer()
        categoryCard.addView(sectionLabel("🗂️", Loc.t(this, "Category", "کیٹیگری")))
        val spinnerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = strokedBg(border, "#FAFAFF", 12)
            setPadding(16, 2, 16, 2)
        }
        categorySpinner = Spinner(this)
        spinnerBox.addView(categorySpinner)
        categoryCard.addView(spinnerBox)
        categoryCard.addView(spacer(10))
        categoryCard.addView(pillLink("✚  " + Loc.t(this, "Add New Category", "نئی کیٹیگری شامل کریں"), primary) { promptAddCategory() })
        root.addView(categoryCard)
        root.addView(spacer(18))

        // ================= RATES =================
        val ratesCard = cardContainer()
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
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        ratesCard.addView(fieldBox(stock, "🔢"))

        root.addView(ratesCard)
        root.addView(spacer(22))

        // ================= SAVE =================
        root.addView(Button(this).apply {
            text = "💾  " + Loc.t(this@ProductActivity, "SAVE PRODUCT", "پروڈکٹ محفوظ کریں")
            setTextColor(Color.WHITE)
            textSize = 16f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor(greenDark), Color.parseColor(green))
            ).apply { cornerRadius = 18f }
            setPadding(0, 28, 0, 28)
            setOnClickListener { saveProduct() }
            applyElevation(this, 8f)
        })
        root.addView(spacer(28))

        val listHeaderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listHeaderRow.addView(sectionLabel("🗃️", Loc.t(this, "Products", "پروڈکٹس")))
        root.addView(listHeaderRow)
        root.addView(spacer(6))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        root.addView(spacer(30))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadCategories()
        loadUnits()
        loadProducts()
    }

    // ---- UI helpers ----
    private fun cardContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 22, 24, 22)
        background = strokedBg(border, cardBg, 18)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        applyElevation(this, 3f)
    }

    private fun fieldBox(field: EditText, icon: String = "") = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = strokedBg(border, "#FAFAFF", 12)
        setPadding(18, 4, 18, 4)
        if (icon.isNotEmpty()) {
            addView(TextView(this@ProductActivity).apply { text = "$icon  "; textSize = 14f })
        }
        (field.parent as? ViewGroup)?.removeView(field)
        field.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(field)
    }

    private fun sectionLabel(icon: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 12)
        addView(TextView(this@ProductActivity).apply { text = "$icon  "; textSize = 15f })
        addView(TextView(this@ProductActivity).apply {
            text = label
            textSize = 14.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun pillLink(label: String, colorHex: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12.5f
        setTextColor(Color.parseColor(colorHex))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(4, 4, 4, 4)
        setOnClickListener { onClick() }
    }

    private fun circleIcon(text: String, colorHex: String, sizeDp: Int) = TextView(this).apply {
        this.text = text
        textSize = 17f
        gravity = Gravity.CENTER
        background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        width = px; height = px
    }

    private fun rateField(icon: String, hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Color.parseColor(textGray))
        setTextColor(Color.parseColor(textDark))
        background = null
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
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

    // ================= "Add Item Unit" dialog: Primary Unit + Secondary Unit =================
    private fun openUnitDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(cardBg))
        }

        val dialogHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 26, 28, 26)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            )
        }
        dialogHeader.addView(TextView(this).apply {
            text = "📏  " + Loc.t(this@ProductActivity, "Add Item Unit", "آئٹم یونٹ شامل کریں")
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(dialogHeader)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 26, 28, 8)
        }

        // ---- Primary Unit ----
        body.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "PRIMARY UNIT", "بنیادی یونٹ"); textSize = 11.5f
            setTextColor(Color.parseColor(textGray))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })
        val primaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val primarySpinnerBox = LinearLayout(this).apply {
            background = strokedBg(border, "#FAFAFF", 12)
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
            text = Loc.t(this@ProductActivity, "SECONDARY UNIT (chhoti quantity, optional)", "ثانوی یونٹ (چھوٹی مقدار، اختیاری)"); textSize = 11.5f
            setTextColor(Color.parseColor(textGray))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })
        val secondaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val secondarySpinnerBox = LinearLayout(this).apply {
            background = strokedBg(border, "#FAFAFF", 12)
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
                secondarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("None") + units)
                secondarySpinner.setSelection((listOf("None") + units).indexOf(newUnit))
            }
        })
        body.addView(secondaryRow)
        body.addView(spacer(16))

        val qtyBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, "#FAFAFF", 12)
            setPadding(16, 4, 16, 4)
        }
        qtyBox.addView(TextView(this).apply { text = "🔁  "; textSize = 14f })
        val qtyField = EditText(this).apply {
            hint = Loc.t(this@ProductActivity, "1 Unit = kitne Secondary Units? (e.g. 1 box = 12 pcs)", "1 یونٹ = کتنے ثانوی یونٹس؟ (مثلاً 1 باکس = 12 پیس)")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (selectedSecondaryQty > 0) setText(selectedSecondaryQty.toString())
        }
        qtyBox.addView(qtyField)
        body.addView(qtyBox)

        content.addView(body)

        // ---- initial adapters + preselect current values ----
        primarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
        primarySpinner.setSelection(units.indexOf(selectedPrimaryUnit).coerceAtLeast(0))
        val secondaryOptions = listOf("None") + units
        secondarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, secondaryOptions)
        secondarySpinner.setSelection(secondaryOptions.indexOf(selectedSecondaryUnit).coerceAtLeast(0))

        // ---- Cancel / Save footer ----
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(28, 8, 28, 26)
        }
        content.addView(spacer(10))
        content.addView(footer)

        val dialog = AlertDialog.Builder(this).setView(content).create()

        footer.addView(TextView(this).apply {
            text = Loc.t(this@ProductActivity, "Cancel", "منسوخ کریں")
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.parseColor(textGray))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = strokedBg(border, "#FAFAFF", 14)
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
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            ).apply { cornerRadius = 14f }
            setPadding(0, 22, 0, 22)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 0) }
            setOnClickListener {
                selectedPrimaryUnit = primarySpinner.selectedItem?.toString() ?: "pcs"
                selectedSecondaryUnit = secondarySpinner.selectedItem?.toString() ?: "None"
                selectedSecondaryQty = qtyField.text.toString().toDoubleOrNull() ?: 0.0

                selectUnitBtn.text = if (selectedSecondaryUnit != "None")
                    "📏 $selectedPrimaryUnit / $selectedSecondaryUnit" else "📏 $selectedPrimaryUnit"

                updateSecondaryUnitPricing()
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
        background = ovalBg(primary)
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
    private fun updateSecondaryUnitPricing() {
        if (selectedSecondaryUnit == "None" || selectedSecondaryQty <= 0.0) return

        Toast.makeText(
            this,
            "1 $selectedPrimaryUnit = $selectedSecondaryQty $selectedSecondaryUnit noted",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun saveProduct() {
        val pname = name.text.toString().trim()
        if (pname.isEmpty()) {
            Toast.makeText(this, Loc.t(this, "Product Name zaroori hai", "پروڈکٹ کا نام ضروری ہے"), Toast.LENGTH_SHORT).show()
            return
        }
        val code = "P" + System.currentTimeMillis().toString()
        val openingQty = stock.text.toString().toIntOrNull() ?: 0
        val product = Product(
            barcode = code,
            name = pname,
            category = categorySpinner.selectedItem?.toString() ?: "General",
            cost = cost.text.toString().toDoubleOrNull() ?: 0.0,
            salePrice = salePrice.text.toString().toDoubleOrNull() ?: 0.0,
            wholesalePrice = wholesalePrice.text.toString().toDoubleOrNull() ?: 0.0,
            stock = openingQty,
            openingStock = openingQty,
            unit = selectedPrimaryUnit,
            secondaryUnit = if (selectedSecondaryUnit == "None") "" else selectedSecondaryUnit,
            secondaryUnitQty = selectedSecondaryQty
        )
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).productDao().upsert(product)
            Toast.makeText(this@ProductActivity, Loc.t(this@ProductActivity, "Product saved", "پروڈکٹ محفوظ ہو گئی"), Toast.LENGTH_SHORT).show()
            clearForm()
        }
    }

    // ---- Reset the whole form so it's ready for the next product ----
    private fun clearForm() {
        name.text.clear()
        cost.text.clear()
        wholesalePrice.text.clear()
        salePrice.text.clear()
        stock.text.clear()

        selectedPrimaryUnit = "pcs"
        selectedSecondaryUnit = "None"
        selectedSecondaryQty = 0.0
        selectUnitBtn.text = "📏 " + Loc.t(this, "Select Unit", "یونٹ منتخب کریں")

        if (categorySpinner.adapter != null && categorySpinner.adapter.count > 0) {
            categorySpinner.setSelection(0)
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).productDao().all().collectLatest { list ->
                listContainer.removeAllViews()
                for (p in list) {
                    listContainer.addView(LinearLayout(this@ProductActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(20, 18, 20, 18)
                        background = strokedBg(border, cardBg, 16)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, 10) }
                        applyElevation(this, 2f)

                        val top = LinearLayout(this@ProductActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        top.addView(circleIcon("🧴", purple, 30))
                        top.addView(View(this@ProductActivity).apply { layoutParams = LinearLayout.LayoutParams(12, 1) })
                        top.addView(TextView(this@ProductActivity).apply {
                            text = p.name; textSize = 15f
                            setTextColor(Color.parseColor(textDark))
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        top.addView(TextView(this@ProductActivity).apply {
                            text = p.category
                            setTextColor(Color.WHITE)
                            textSize = 11f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            background = roundedBg(purple, 20)
                            setPadding(18, 6, 18, 6)
                        })
                        addView(top)
                        addView(TextView(this@ProductActivity).apply {
                            text = "📊 ${Loc.t(this@ProductActivity, "Stock", "اسٹاک")}: ${p.stock} ${p.unit}"
                            textSize = 12.5f
                            setTextColor(Color.parseColor(textGray))
                            setPadding(42, 8, 0, 4)
                        })
                        addView(TextView(this@ProductActivity).apply {
                            text = "🛒 %s: %.2f   •   📦 %s: %.2f   •   🏪 %s: %.2f".format(
                                Loc.t(this@ProductActivity, "Purchase", "خریداری"), p.cost,
                                Loc.t(this@ProductActivity, "Wholesale", "تھوک"), p.wholesalePrice,
                                Loc.t(this@ProductActivity, "Retail", "پرچون"), p.salePrice
                            )
                            textSize = 12.5f
                            setTextColor(Color.parseColor(blue))
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setPadding(42, 0, 0, 0)
                        })
                    })
                }
            }
        }
    }
}
