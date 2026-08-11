package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Category
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.UnitType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ProductActivity : AppCompatActivity() {

    private val bg = "#F4F3FB"
    private val blue = "#1565C0"
    private val darkBlue = "#0D47A1"
    private val green = "#2E7D32"
    private val purple = "#6A1B9A"

    private lateinit var name: EditText
    private lateinit var selectUnitBtn: Button
    private lateinit var categorySpinner: Spinner
    private lateinit var cost: EditText
    private lateinit var wholesalePrice: EditText
    private lateinit var salePrice: EditText
    private lateinit var stock: EditText
    private lateinit var perUnitInfo: TextView
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
            setPadding(24, 32, 24, 32)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            background = roundedBg(blue, 20)
            elevation = 8f
        }
        header.addView(TextView(this).apply {
            text = "Add / Edit Product"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(header)
        root.addView(spacer(20))

        // ================= NAME + embedded "Select Unit" pill =================
        val nameCard = cardContainer()
        nameCard.addView(sectionLabel("Product Name"))

        // Outer bordered box holding BOTH the name field and the unit pill (single "field" look)
        val nameBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 6, 10, 6)
            background = strokedBg("#BDBDBD", "#FFFFFF", 14)
        }
        name = EditText(this).apply {
            hint = "Product Name"
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        selectUnitBtn = Button(this).apply {
            text = "Select Unit"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBg(blue, 30)
            setPadding(28, 10, 28, 10)
            minWidth = 0; minHeight = 0
            setOnClickListener { openUnitDialog() }
        }
        nameBox.addView(name)
        nameBox.addView(selectUnitBtn)
        nameCard.addView(nameBox)

        perUnitInfo = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor(blue))
            setPadding(0, 12, 0, 0)
            visibility = View.GONE
        }
        nameCard.addView(perUnitInfo)

        root.addView(nameCard)
        root.addView(spacer(20))

        // ================= CATEGORY =================
        val categoryCard = cardContainer()
        categoryCard.addView(sectionLabel("Category"))
        categorySpinner = Spinner(this)
        categoryCard.addView(categorySpinner)
        categoryCard.addView(TextView(this).apply {
            text = "+ Add New Category"
            textSize = 12f
            setTextColor(Color.parseColor(blue))
            setPadding(0, 10, 0, 0)
            setOnClickListener { promptAddCategory() }
        })
        root.addView(categoryCard)
        root.addView(spacer(20))

        // ================= RATES =================
        val ratesCard = cardContainer()
        ratesCard.addView(sectionLabel("Pricing"))

        cost = rateField("Purchase Rate")
        ratesCard.addView(cost)
        ratesCard.addView(spacer(12))

        wholesalePrice = rateField("Wholesale Sale Rate")
        ratesCard.addView(wholesalePrice)
        ratesCard.addView(spacer(12))

        salePrice = rateField("Retail Sale Rate")
        ratesCard.addView(salePrice)
        ratesCard.addView(spacer(12))

        stock = EditText(this).apply {
            hint = "Opening Stock (optional)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        ratesCard.addView(stock)

        root.addView(ratesCard)
        root.addView(spacer(24))

        // ================= SAVE =================
        root.addView(Button(this).apply {
            text = "SAVE PRODUCT"
            setTextColor(Color.WHITE)
            textSize = 16f
            background = roundedBg(green, 16)
            setPadding(0, 28, 0, 28)
            setOnClickListener { saveProduct() }
        })
        root.addView(spacer(28))

        root.addView(sectionLabel("Products"))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadCategories()
        loadUnits()
        loadProducts()
        setupLivePricingWatchers()
    }

    // ---- UI helpers ----
    private fun cardContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 22, 24, 22)
        background = roundedBg("#FFFFFF", 20)
        elevation = 4f
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.parseColor("#424242"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, 10)
    }

    private fun rateField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
    }

    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.5 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
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
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("New Category")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                                val v = input.text.toString().trim()
                if (v.isNotEmpty()) lifecycleScope.launch {
                    PosDatabase.get(this@ProductActivity).categoryDao().insert(Category(v))
                    Toast.makeText(this@ProductActivity, "Category added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= "Add Item Unit" dialog: Primary Unit + Secondary Unit =================
    private fun openUnitDialog() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val dialogHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(Color.parseColor(darkBlue))
        }
        dialogHeader.addView(TextView(this).apply {
            text = "Add Item Unit"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(dialogHeader)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 8)
        }

        // ---- Primary Unit ----
        body.addView(TextView(this).apply { text = "Primary Unit"; textSize = 13f; setTextColor(Color.GRAY) })
        val primaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val primarySpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        primaryRow.addView(primarySpinner)
        primaryRow.addView(smallAddButton {
            promptAddUnitInline { newUnit ->
                units = (units + newUnit).distinct()
                primarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
                primarySpinner.setSelection(units.indexOf(newUnit))
            }
        })
        body.addView(primaryRow)
        body.addView(spacer(18))

        // ---- Secondary Unit ----
        body.addView(TextView(this).apply { text = "Secondary Unit (chhoti quantity, optional)"; textSize = 13f; setTextColor(Color.GRAY) })
        val secondaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val secondarySpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        secondaryRow.addView(secondarySpinner)
        secondaryRow.addView(smallAddButton {
            promptAddUnitInline { newUnit ->
                units = (units + newUnit).distinct()
                secondarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("None") + units)
                secondarySpinner.setSelection((listOf("None") + units).indexOf(newUnit))
            }
        })
        body.addView(secondaryRow)
        body.addView(spacer(14))

        val qtyField = EditText(this).apply {
            hint = "1 Unit = kitne Secondary Units? (e.g. 1 box = 12 pcs)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (selectedSecondaryQty > 0) setText(selectedSecondaryQty.toString())
        }
        body.addView(qtyField)

        content.addView(body)

        // ---- initial adapters + preselect current values ----
        primarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
        primarySpinner.setSelection(units.indexOf(selectedPrimaryUnit).coerceAtLeast(0))
        val secondaryOptions = listOf("None") + units
        secondarySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, secondaryOptions)
        secondarySpinner.setSelection(secondaryOptions.indexOf(selectedSecondaryUnit).coerceAtLeast(0))

        // ---- Cancel / Save footer ----
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(spacer(16))
        content.addView(footer)

        val dialog = AlertDialog.Builder(this).setView(content).create()

        footer.addView(Button(this).apply {
            text = "Cancel"
            setTextColor(Color.parseColor(darkBlue))
            background = ColorDrawableFlat()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { dialog.dismiss() }
        })
        footer.addView(Button(this).apply {
            text = "Save"
            setTextColor(Color.WHITE)
            background = roundedBg(blue, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                selectedPrimaryUnit = primarySpinner.selectedItem?.toString() ?: "pcs"
                selectedSecondaryUnit = secondarySpinner.selectedItem?.toString() ?: "None"
                selectedSecondaryQty = qtyField.text.toString().toDoubleOrNull() ?: 0.0

                selectUnitBtn.text = if (selectedSecondaryUnit != "None")
                    "$selectedPrimaryUnit / $selectedSecondaryUnit" else selectedPrimaryUnit

                updateSecondaryUnitPricing()
                dialog.dismiss()
            }
        })

        dialog.show()
    }

    private fun ColorDrawableFlat() = GradientDrawable().apply { setColor(Color.TRANSPARENT) }

    private fun smallAddButton(onClick: () -> Unit) = Button(this).apply {
        text = "+"
        setTextColor(Color.WHITE)
        background = roundedBg(blue, 10)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(10, 0, 0, 0) }
        setOnClickListener { onClick() }
    }

    private fun promptAddUnitInline(onAdded: (String) -> Unit) {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("New Unit")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) lifecycleScope.launch {
                    PosDatabase.get(this@ProductActivity).unitDao().insert(UnitType(v))
                    Toast.makeText(this@ProductActivity, "Unit added", Toast.LENGTH_SHORT).show()
                    onAdded(v)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Live calculation: secondary unit price = main unit price / how many secondary units in 1 main unit ----
    private fun setupLivePricingWatchers() {
        val watcher = simpleWatcher { updateSecondaryUnitPricing() }
        cost.addTextChangedListener(watcher)
        salePrice.addTextChangedListener(watcher)
        wholesalePrice.addTextChangedListener(watcher)
    }

    private fun updateSecondaryUnitPricing() {
        if (selectedSecondaryUnit == "None" || selectedSecondaryQty <= 0.0) {
            perUnitInfo.visibility = View.GONE
            return
        }

        val sp = salePrice.text.toString().toDoubleOrNull() ?: 0.0
        val cp = cost.text.toString().toDoubleOrNull() ?: 0.0
        val wp = wholesalePrice.text.toString().toDoubleOrNull() ?: 0.0

        val sb = StringBuilder()
        sb.append("1 $selectedPrimaryUnit = $selectedSecondaryQty $selectedSecondaryUnit\n")
        if (cp > 0) sb.append("Purchase rate per $selectedSecondaryUnit: Rs %.2f\n".format(cp / selectedSecondaryQty))
        if (wp > 0) sb.append("Wholesale rate per $selectedSecondaryUnit: Rs %.2f\n".format(wp / selectedSecondaryQty))
        if (sp > 0) sb.append("Retail rate per $selectedSecondaryUnit: Rs %.2f".format(sp / selectedSecondaryQty))

        perUnitInfo.text = sb.toString().trim()
        perUnitInfo.visibility = View.VISIBLE
    }

    private fun saveProduct() {
        val pname = name.text.toString().trim()
        if (pname.isEmpty()) {
            Toast.makeText(this, "Product Name zaroori hai", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this@ProductActivity, "Product saved", Toast.LENGTH_SHORT).show()
            name.text.clear()
            stock.text.clear()
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).productDao().all().collectLatest { list ->
                listContainer.removeAllViews()
                for (p in list) {
                    listContainer.addView(LinearLayout(this@ProductActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(20, 16, 20, 16)
                        background = roundedBg("#FFFFFF", 14)
                        elevation = 2f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, 10) }

                        val top = LinearLayout(this@ProductActivity).apply { orientation = LinearLayout.HORIZONTAL }
                        top.addView(TextView(this@ProductActivity).apply {
                            text = p.name; textSize = 15f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        top.addView(TextView(this@ProductActivity).apply {
                            text = p.category
                            setTextColor(Color.parseColor(purple))
                            textSize = 12f
                        })
                        addView(top)
                        addView(TextView(this@ProductActivity).apply {
                            text = "Stock: ${p.stock} ${p.unit}"
                            textSize = 13f
                            setTextColor(Color.GRAY)
                        })
                        addView(TextView(this@ProductActivity).apply {
                            text = "Purchase: %.2f  •  Wholesale: %.2f  •  Retail: %.2f".format(p.cost, p.wholesalePrice, p.salePrice)
                            textSize = 13f
                            setTextColor(Color.parseColor(blue))
                        })
                    })
                }
            }
        }
    }
}
