
package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProductActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_EDIT_BARCODE = "edit_barcode"
    }
    private lateinit var nameField: EditText
    private lateinit var primaryUnitField: EditText
    private lateinit var secondaryUnitField: EditText
    private lateinit var secondaryQtyField: EditText
    private lateinit var tertiaryUnitField: EditText
    private lateinit var tertiaryQtyField: EditText
    private lateinit var costField: EditText
    private lateinit var salePriceField: EditText
    private lateinit var stockField: EditText
    private lateinit var saveButton: Button
    private lateinit var productList: LinearLayout
    private var editing: Product? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = ScrollView(this).apply {
            val col = LinearLayout(this@ProductActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 44, 24, 24)
                setBackgroundColor(Color.parseColor("#F4F6F8"))
            }
            col.addView(TextView(this@ProductActivity).apply {
                text = "Product - Ctn 50 Outer 10 Dabbi"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0,0,0,16)
            })
            nameField = EditText(this@ProductActivity).apply {
                hint = "Product Name"
                background = GradientDrawable().apply { setColor(Color.parseColor("#FFFFFF")); setStroke(2, Color.parseColor("#E3E8EE")); cornerRadius = 12f }
                setPadding(18,16,18,16)
            }
            col.addView(nameField)
            primaryUnitField = EditText(this@ProductActivity).apply {
                hint = "Ctn / Bag"
                background = GradientDrawable().apply { setColor(Color.parseColor("#FFFFFF")); setStroke(2, Color.parseColor("#E3E8EE")); cornerRadius = 12f }
                setPadding(18,16,18,16)
            }
            col.addView(primaryUnitField)
            secondaryUnitField = EditText(this@ProductActivity).apply {
                hint = "Outer / Lari"
                background = GradientDrawable().apply { setColor(Color.parseColor("#FFFFFF")); setStroke(2, Color.parseColor("#E3E8EE")); cornerRadius = 12f }
                setPadding(18,16,18,16)
            }
            col.addView(secondaryUnitField)
            secondaryQtyField = EditText(this@ProductActivity).apply {
                hint = "50"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                background = GradientDrawable().apply { setColor(Color.parseColor("#FFFFFF")); setStroke(2, Color.parseColor("#E3E8EE")); cornerRadius = 12f }
                setPadding(18,16,18,16)
            }
            col.addView(secondaryQtyField)
            tertiaryUnitField = EditText(this@ProductActivity).apply {
                hint = "Dabbi / Pcs"
                background = GradientDrawable().apply { setColor(Color.parseColor("#FFFFFF")); setStroke(2, Color.parseColor("#E3E8EE")); cornerRadius = 12f }
                setPadding(18,16,18,16)
            }
            col.addView(tertiaryUnitField)
            tertiaryQtyField = EditText(this@ProductActivity).apply {
                hint = "10 or 16"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                background = GradientDrawable().apply { setColor(Color.parseColor("#FFFFFF")); setStroke(2, Color.parseColor("#E3E8EE")); cornerRadius = 12f }
                setPadding(18,16,18,16)
            }
            col.addView(tertiaryQtyField)
            costField = EditText(this@ProductActivity).apply {
                hint = "Cost"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                background = GradientDrawable().apply { setColor(Color.parseColor("#FFFFFF")); setStroke(2, Color.parseColor("#E3E8EE")); cornerRadius = 12f }
                setPadding(18,16,18,16)
            }
            col.addView(costField)
            salePriceField = EditText(this@ProductActivity).apply {
                hint = "Sale Price"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                background = GradientDrawable().apply { setColor(Color.parseColor("#FFFFFF")); setStroke(2, Color.parseColor("#E3E8EE")); cornerRadius = 12f }
                setPadding(18,16,18,16)
            }
            col.addView(salePriceField)
            stockField = EditText(this@ProductActivity).apply {
                hint = "Stock - 768 pcs = 1 Ctn (48 Lari x 16)"
                inputType = InputType.TYPE_CLASS_NUMBER
                background = GradientDrawable().apply { setColor(Color.parseColor("#FFFFFF")); setStroke(2, Color.parseColor("#E3E8EE")); cornerRadius = 12f }
                setPadding(18,16,18,16)
            }
            col.addView(stockField)
            saveButton = Button(this@ProductActivity).apply {
                text = "Save"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(Color.parseColor("#0B2545")); cornerRadius = 16f }
                setOnClickListener { saveProduct() }
            }
            col.addView(saveButton)
            productList = LinearLayout(this@ProductActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(productList)
            addView(col)
        }
        setContentView(root)
        val editBarcode = intent.getStringExtra(EXTRA_EDIT_BARCODE)
        if (editBarcode != null) {
            lifecycleScope.launch {
                val p = PosDatabase.get(this@ProductActivity).productDao().find(editBarcode)
                if (p != null) {
                    editing = p
                    nameField.setText(p.name)
                    primaryUnitField.setText(p.unit)
                    secondaryUnitField.setText(p.secondaryUnit)
                    secondaryQtyField.setText(if(p.secondaryUnitQty==0.0) "" else p.secondaryUnitQty.toString())
                    tertiaryUnitField.setText(p.tertiaryUnit)
                    tertiaryQtyField.setText(if(p.tertiaryUnitQty==0.0) "" else p.tertiaryUnitQty.toString())
                    stockField.setText(p.stock.toString())
                    costField.setText(p.cost.toString())
                    salePriceField.setText(p.salePrice.toString())
                    saveButton.text = "Update - " + p.name
                }
            }
        }
        loadProducts()
    }

    private fun saveProduct() {
        val name = nameField.text.toString().trim()
        if (name.isEmpty()) return
        val primary = primaryUnitField.text.toString().trim().ifEmpty { "ctn" }
        val secondary = secondaryUnitField.text.toString().trim()
        val secQty = secondaryQtyField.text.toString().toDoubleOrNull() ?: 0.0
        val tertiary = tertiaryUnitField.text.toString().trim().ifEmpty { "pcs" }
        val terQty = tertiaryQtyField.text.toString().toDoubleOrNull() ?: 0.0
        val stock = stockField.text.toString().toIntOrNull() ?: 0
        val cost = costField.text.toString().toDoubleOrNull() ?: 0.0
        val sale = salePriceField.text.toString().toDoubleOrNull() ?: 0.0
        val barcode = editing?.barcode ?: "P${System.currentTimeMillis()}"
        val product = Product(barcode = barcode, name = name, unit = primary, secondaryUnit = secondary, secondaryUnitQty = secQty, tertiaryUnit = tertiary, tertiaryUnitQty = terQty, stock = stock, openingStock = stock, cost = cost, salePrice = sale)
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).productDao().upsert(product)
            Toast.makeText(this@ProductActivity, "Saved", Toast.LENGTH_SHORT).show()
            clearForm()
        }
    }

    private fun clearForm() {
        nameField.text.clear()
        stockField.text.clear()
        costField.text.clear()
        salePriceField.text.clear()
        editing = null
        saveButton.text = "Save"
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).productDao().all().collectLatest { list ->
                productList.removeAllViews()
                for (p in list.take(20)) {
                    val v = TextView(this@ProductActivity).apply {
                        text = p.name + " - " + p.stock + " " + p.tertiaryUnit
                        setPadding(12,12,12,12)
                        background = GradientDrawable().apply { setColor(Color.parseColor("#FFFFFF")); setStroke(2, Color.parseColor("#E3E8EE")); cornerRadius = 8f }
                    }
                    productList.addView(v)
                }
            }
        }
    }
}
