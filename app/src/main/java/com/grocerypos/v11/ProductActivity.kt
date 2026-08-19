
package com.grocerypos.v11.ui

import android.app.AlertDialog
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
import com.grocerypos.v11.Category
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ProductActivity : AppCompatActivity() {

    private val bg = "#F4F6F8"
    private val navy = "#0B2545"
    private val teal = "#0F9B8E"
    private val cardWhite = "#FFFFFF"
    private val border = "#E3E8EE"

    private lateinit var nameField: EditText
    private lateinit var primaryUnitField: EditText
    private lateinit var secondaryUnitField: EditText
    private lateinit var secondaryQtyField: EditText
    private lateinit var tertiaryUnitField: EditText
    private lateinit var tertiaryQtyField: EditText
    private lateinit var costField: EditText
    private lateinit var salePriceField: EditText
    private lateinit var stockField: EditText
    private lateinit var stockUnitSpinner: Spinner
    private lateinit var saveButton: Button
    private lateinit var productList: LinearLayout

    private var editing: Product? = null

    // Urdu units suggestions
    private val urduUnits = listOf(
        "کارٹن", "بیرونی پیک", "ڈبی", "لڑی", "عدد", "گچھی",
        "بوری", "کلو", "گرام", "پاؤ", "باکس", "بوتل", "شیشی", "پیکٹ"
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = ScrollView(this).apply {
            val col = LinearLayout(this@ProductActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 44, 24, 24)
                setBackgroundColor(Color.parseColor(bg))
            }

            col.addView(TextView(this@ProductActivity).apply {
                text = "پروڈکٹ شامل کریں - اردو یونٹ 3 درجہ"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0,0,0,16)
            })

            // Name
            nameField = EditText(this@ProductActivity).apply {
                hint = "پروڈکٹ نام - Sugar / Shampoo"
                background = strokedBg(border, cardWhite, 12)
                setPadding(18,16,18,16)
            }
            col.addView(nameField)
            col.addView(spacer(10))

            // Primary Unit
            col.addView(label("بڑا یونٹ - Primary (مثال: کارٹن / بوری)"))
            primaryUnitField = EditText(this@ProductActivity).apply {
                hint = "مثال: کارٹن یا بوری یا ctn یا bag"
                background = strokedBg(border, cardWhite, 12)
                setPadding(18,16,18,16)
            }
            col.addView(primaryUnitField)
            col.addView(quickUnitChips(primaryUnitField))
            col.addView(spacer(10))

            // Secondary
            col.addView(label("درمیانہ یونٹ - Secondary + مقدار"))
            val secRow = LinearLayout(this@ProductActivity).apply { orientation = LinearLayout.HORIZONTAL }
            secondaryUnitField = EditText(this@ProductActivity).apply {
                hint = "بیرونی پیک / لڑی / کلو"
                background = strokedBg(border, cardWhite, 12)
                setPadding(18,16,18,16)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,6,0) }
            }
            secondaryQtyField = EditText(this@ProductActivity).apply {
                hint = "1 کارٹن = 50 بیرونی"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                background = strokedBg(border, cardWhite, 12)
                setPadding(18,16,18,16)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6,0,0,0) }
            }
            secRow.addView(secondaryUnitField)
            secRow.addView(secondaryQtyField)
            col.addView(secRow)
            col.addView(quickUnitChips(secondaryUnitField))
            col.addView(spacer(10))

            // Tertiary
            col.addView(label("چھوٹا یونٹ - Tertiary + مقدار (اسٹاک اسی میں)"))
            val terRow = LinearLayout(this@ProductActivity).apply { orientation = LinearLayout.HORIZONTAL }
            tertiaryUnitField = EditText(this@ProductActivity).apply {
                hint = "ڈبی / عدد / گرام"
                background = strokedBg(border, cardWhite, 12)
                setPadding(18,16,18,16)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,6,0) }
            }
            tertiaryQtyField = EditText(this@ProductActivity).apply {
                hint = "1 بیرونی = 10 ڈبی"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                background = strokedBg(border, cardWhite, 12)
                setPadding(18,16,18,16)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6,0,0,0) }
            }
            terRow.addView(tertiaryUnitField)
            terRow.addView(tertiaryQtyField)
            col.addView(terRow)
            col.addView(quickUnitChips(tertiaryUnitField))
            col.addView(spacer(12))

            // Cost, Sale, Stock
            costField = EditText(this@ProductActivity).apply {
                hint = "خرید قیمت فی چھوٹا یونٹ"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                background = strokedBg(border, cardWhite, 12)
                setPadding(18,16,18,16)
            }
            col.addView(costField)
            col.addView(spacer(8))
            salePriceField = EditText(this@ProductActivity).apply {
                hint = "فروخت قیمت فی چھوٹا یونٹ"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                background = strokedBg(border, cardWhite, 12)
                setPadding(18,16,18,16)
            }
            col.addView(salePriceField)
            col.addView(spacer(8))

            col.addView(label("ابتدائی اسٹاک - ہمیشہ چھوٹے یونٹ میں لکھیں"))
            val stockRow = LinearLayout(this@ProductActivity).apply { orientation = LinearLayout.HORIZONTAL }
            stockField = EditText(this@ProductActivity).apply {
                hint = "مثال: 500 ڈبی / 768 عدد"
                inputType = InputType.TYPE_CLASS_NUMBER
                background = strokedBg(border, cardWhite, 12)
                setPadding(18,16,18,16)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,6,0) }
            }
            stockUnitSpinner = Spinner(this@ProductActivity).apply {
                adapter = ArrayAdapter(this@ProductActivity, android.R.layout.simple_spinner_dropdown_item, listOf("ڈبی","عدد","گرام","کارٹن","بیرونی","لڑی"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply { setMargins(6,0,0,0) }
            }
            stockRow.addView(stockField)
            stockRow.addView(stockUnitSpinner)
            col.addView(stockRow)
            col.addView(TextView(this@ProductActivity).apply {
                text = "نوٹ: اسٹاک ہمیشہ سب سے چھوٹے یونٹ میں لکھیں تاکہ ctn پوائنٹ والا مسئلہ ختم ہو۔ مثال: 2 کارٹن (50×10) = 1000 ڈبی لکھیں"
                textSize = 11f
                setTextColor(Color.parseColor("#E5484D"))
                setPadding(0,8,0,0)
            })
            col.addView(spacer(16))

            saveButton = Button(this@ProductActivity).apply {
                text = "محفوظ کریں"
                setTextColor(Color.WHITE)
                background = roundedBg(navy, 16)
                setOnClickListener { saveProduct() }
            }
            col.addView(saveButton)
            col.addView(spacer(20))
            col.addView(TextView(this@ProductActivity).apply {
                text = "موجودہ پروڈکٹس"
                setTypeface(typeface, Typeface.BOLD)
            })
            productList = LinearLayout(this@ProductActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(productList)

            addView(col)
        }
        setContentView(root)
        loadProducts()
    }

    private fun quickUnitChips(target: EditText): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (u in urduUnits.take(6)) {
            val chip = TextView(this).apply {
                text = u
                setPadding(16,8,16,8)
                background = roundedBg("#E3E8EE", 20)
                setMargins(0,6,8,0)
                textSize = 11f
                setOnClickListener { target.setText(u) }
            }
            row.addView(chip)
        }
        return row
    }

    private fun saveProduct() {
        val name = nameField.text.toString().trim()
        if (name.isEmpty()) return
        val primary = primaryUnitField.text.toString().trim().ifEmpty { "کارٹن" }
        val secondary = secondaryUnitField.text.toString().trim()
        val secQty = secondaryQtyField.text.toString().toDoubleOrNull() ?: 0.0
        val tertiary = tertiaryUnitField.text.toString().trim().ifEmpty { "عدد" }
        val terQty = tertiaryQtyField.text.toString().toDoubleOrNull() ?: 0.0
        val stock = stockField.text.toString().toIntOrNull() ?: 0
        val cost = costField.text.toString().toDoubleOrNull() ?: 0.0
        val sale = salePriceField.text.toString().toDoubleOrNull() ?: 0.0
        val barcode = editing?.barcode ?: "P${System.currentTimeMillis()}"

        val product = Product(
            barcode = barcode,
            name = name,
            unit = primary,
            secondaryUnit = secondary,
            secondaryUnitQty = secQty,
            tertiaryUnit = tertiary,
            tertiaryUnitQty = terQty,
            stock = stock,
            openingStock = stock,
            cost = cost,
            salePrice = sale
        )
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).productDao().upsert(product)
            Toast.makeText(this@ProductActivity, "محفوظ ہو گیا اردو یونٹ میں", Toast.LENGTH_SHORT).show()
            clearForm()
        }
    }

    private fun clearForm() {
        nameField.text.clear()
        stockField.text.clear()
        costField.text.clear()
        salePriceField.text.clear()
        editing = null
        saveButton.text = "محفوظ کریں"
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            PosDatabase.get(this@ProductActivity).productDao().all().collectLatest { list ->
                productList.removeAllViews()
                for (p in list.take(20)) {
                    val v = TextView(this@ProductActivity).apply {
                        text = "${p.name} - ${p.stock} ${p.tertiaryUnit} (${p.unit} ${p.secondaryUnitQty.toInt()} ${p.secondaryUnit} ${p.tertiaryUnitQty.toInt()} ${p.tertiaryUnit})"
                        setPadding(12,12,12,12)
                        background = strokedBg(border, cardWhite, 8)
                        setOnClickListener {
                            editing = p
                            nameField.setText(p.name)
                            primaryUnitField.setText(p.unit)
                            secondaryUnitField.setText(p.secondaryUnit)
                            secondaryQtyField.setText(p.secondaryUnitQty.toString())
                            tertiaryUnitField.setText(p.tertiaryUnit)
                            tertiaryQtyField.setText(p.tertiaryUnitQty.toString())
                            stockField.setText(p.stock.toString())
                            saveButton.text = "اپڈیٹ کریں"
                        }
                    }
                    productList.addView(v)
                    productList.addView(spacer(6))
                }
            }
        }
    }

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 12f; setTextColor(Color.parseColor("#0F9B8E")); setTypeface(typeface, Typeface.BOLD); setPadding(0,4,0,4) }
    private fun roundedBg(c: String, r: Int) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = r.toFloat() }
    private fun strokedBg(s: String, f: String, r: Int) = GradientDrawable().apply { setColor(Color.parseColor(f)); setStroke(2, Color.parseColor(s)); cornerRadius = r.toFloat() }
    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (h*3)) }
}

fun View.setMargins(l: Int, t: Int, r: Int, b: Int) {
    if (layoutParams is LinearLayout.LayoutParams) {
        (layoutParams as LinearLayout.LayoutParams).setMargins(l,t,r,b)
    }
}
