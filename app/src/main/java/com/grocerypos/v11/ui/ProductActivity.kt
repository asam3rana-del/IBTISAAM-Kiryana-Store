package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
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
import kotlinx.coroutines.launch

class ProductActivity : AppCompatActivity() {

    private lateinit var name: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var unitSpinner: Spinner
    private lateinit var secondaryUnitSpinner: Spinner
    private lateinit var secondaryUnitQty: EditText
    private lateinit var cost: EditText
    private lateinit var salePrice: EditText
    private lateinit var wholesalePrice: EditText
    private lateinit var stock: EditText
    private lateinit var perUnitInfo: TextView
    private lateinit var listContainer: LinearLayout

    private var units = listOf("pcs", "kg", "box", "dozen")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
        }

        root.addView(TextView(this).apply { text = "Add / Edit Product"; textSize = 22f; setPadding(0,0,0,16) })

        name = field("Product Name")
        root.addView(name)

        // ---- Category: dropdown + small "+" button on the side ----
        categorySpinner = Spinner(this)
        root.addView(labeledWithAdd("Category", categorySpinner) { promptAddCategory() })

        // ---- Unit: dropdown + small "+" button on the side ----
        unitSpinner = Spinner(this)
        root.addView(labeledWithAdd("Unit", unitSpinner) { promptAddUnit() })

        // ---- Secondary unit (relation to main unit) ----
        secondaryUnitSpinner = Spinner(this)
        root.addView(labeled("Secondary Unit (optional)", secondaryUnitSpinner))
        secondaryUnitQty = field("1 Unit = how many Secondary Units? (e.g. 1 box = 12 pcs)")
        root.addView(secondaryUnitQty)

        perUnitInfo = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, 8, 0, 16)
            visibility = View.GONE
        }
        root.addView(perUnitInfo)

        cost = field("Purchase Rate")
        salePrice = field("Sale Rate (Retail)")
        wholesalePrice = field("Wholesale Rate")
        stock = field("Opening Stock")
        root.addView(cost); root.addView(salePrice); root.addView(wholesalePrice)
        root.addView(stock)

        root.addView(Button(this).apply {
            text = "SAVE PRODUCT"
            setOnClickListener { saveProduct() }
        })

        root.addView(TextView(this).apply { text = "\nProducts"; textSize = 20f })
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(ScrollView(this).apply { addView(root) })

        loadCategories()
        loadUnits()
        loadProducts()
        setupLivePricingWatchers()
    }

    private fun field(hint: String) = EditText(this).apply { this.hint = hint }

    private fun labeled(label: String, view: android.view.View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ProductActivity).apply { text = label })
            addView(view)
        }
    }

    // Dropdown on the left, small "+" button beside it on the right
    private fun labeledWithAdd(label: String, view: View, onAdd: () -> Unit): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this@ProductActivity).apply { text = label })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(view)
        row.addView(Button(this).apply {
            text = "+"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(12, 0, 0, 0) }
            setOnClickListener { onAdd() }
        })
        col.addView(row)
        return col
    }

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
                unitSpinner.adapter = ArrayAdapter(this@ProductActivity, android.R.layout.simple_spinner_dropdown_item, units)
                secondaryUnitSpinner.adapter = ArrayAdapter(this@ProductActivity, android.R.layout.simple_spinner_dropdown_item, listOf("None") + units)
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

    private fun promptAddUnit() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("New Unit")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) lifecycleScope.launch {
                    PosDatabase.get(this@ProductActivity).unitDao().insert(UnitType(v))
                    Toast.makeText(this@ProductActivity, "Unit added", Toast.LENGTH_SHORT).show()
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
        secondaryUnitQty.addTextChangedListener(watcher)

        secondaryUnitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updateSecondaryUnitPricing()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        unitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updateSecondaryUnitPricing()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun updateSecondaryUnitPricing() {
        val secUnit = secondaryUnitSpinner.selectedItem?.toString() ?: "None"
        val qty = secondaryUnitQty.text.toString().toDoubleOrNull() ?: 0.0

        if (secUnit == "None" || qty <= 0.0) {
            perUnitInfo.visibility = View.GONE
            return
        }

        val mainUnit = unitSpinner.selectedItem?.toString() ?: "unit"
        val sp = salePrice.text.toString().toDoubleOrNull() ?: 0.0
        val cp = cost.text.toString().toDoubleOrNull() ?: 0.0
        val wp = wholesalePrice.text.toString().toDoubleOrNull() ?: 0.0

        val sb = StringBuilder()
        sb.append("1 $mainUnit = $qty $secUnit\n")
        if (cp > 0) sb.append("Cost per $secUnit: Rs %.2f\n".format(cp / qty))
        if (sp > 0) sb.append("Sale price per $secUnit: Rs %.2f\n".format(sp / qty))
        if (wp > 0) sb.append("Wholesale price per $secUnit: Rs %.2f".format(wp / qty))

        perUnitInfo.text = sb.toString().trim()
        perUnitInfo.visibility = View.VISIBLE
    }

    private fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange()
    }

    private fun saveProduct() {
        val pname = name.text.toString().trim()
        if (pname.isEmpty()) {
            Toast.makeText(this, "Product Name zaroori hai", Toast.LENGTH_SHORT).show()
            return
        }
        // barcode auto-generated internally - not asked from user
        val code = "P" + System.currentTimeMillis().toString()
        val secUnit = secondaryUnitSpinner.selectedItem?.toString() ?: "None"
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
            unit = unitSpinner.selectedItem?.toString() ?: "pcs",
            secondaryUnit = if (secUnit == "None") "" else secUnit,
            secondaryUnitQty = secondaryUnitQty.text.toString().toDoubleOrNull() ?: 0.0
        )
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).productDao().upsert(product)
            Toast.makeText(this@ProductActivity, "Product saved", Toast.LENGTH_SHORT).show()
            name.text.clear()
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).productDao().all().collectLatest { list ->
                listContainer.removeAllViews()
                for (p in list) {
                    listContainer.addView(TextView(this@ProductActivity).apply {
                        text = "${p.name} - ${p.category}\n" +
                                "Stock: ${p.stock} ${p.unit}  |  Cost: ${p.cost}  Sale: ${p.salePrice}  Wholesale: ${p.wholesalePrice}"
                        setPadding(0, 12, 0, 12)
                    })
                }
            }
        }
    }
}
