package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.os.Bundle
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
    private lateinit var listContainer: LinearLayout

    private var units = listOf("pcs", "kg", "box", "dozen")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
        }

        root.addView(TextView(this).apply { text = "Add / Edit Product"; textSize = 22f })

        name = field("Product Name")
        root.addView(name)

        categorySpinner = Spinner(this)
        root.addView(labeled("Category", categorySpinner))
        root.addView(smallButton("+ Add New Category") { promptAddCategory() })

        unitSpinner = Spinner(this)
        root.addView(labeled("Unit", unitSpinner))
        root.addView(smallButton("+ Add New Unit") { promptAddUnit() })

        secondaryUnitSpinner = Spinner(this)
        root.addView(labeled("Secondary Unit (optional)", secondaryUnitSpinner))
        secondaryUnitQty = field("1 Unit = how many Secondary Units? (e.g. 1 box = 12 pcs)")
        root.addView(secondaryUnitQty)

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
    }

    private fun field(hint: String) = EditText(this).apply { this.hint = hint }

    private fun labeled(label: String, view: android.view.View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ProductActivity).apply { text = label })
            addView(view)
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
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
