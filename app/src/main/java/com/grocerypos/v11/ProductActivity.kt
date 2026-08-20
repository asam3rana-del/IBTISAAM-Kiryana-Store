package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ProductActivity : AppCompatActivity() {

    private val tealStart = "#14B8A6"
    private val tealEnd = "#0D9488"
    private val darkBlue = "#0B2D4D"
    private val bg = "#F4F5F7"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#111827"
    private val textGray = "#6B7280"
    private val border = "#E5E7EB"
    private val lightGrayBg = "#F9FAFB"
    private val teal = "#0FA89A"

    private lateinit var nameField: EditText
    private lateinit var barcodeField: EditText
    private lateinit var categoryField: TextView
    private lateinit var unitField: EditText
    private lateinit var secondUnitField: EditText
    private lateinit var tertiaryUnitField: EditText
    private lateinit var secondQtyField: EditText
    private lateinit var tertiaryQtyField: EditText
    private lateinit var costField: EditText
    private lateinit var wholesaleField: EditText
    private lateinit var retailField: EditText
    private lateinit var stockField: EditText
    private lateinit var saveButton: TextView
    private lateinit var listContainer: LinearLayout

    private var selectedCategory = "General"
    private var allCategories = mutableListOf("General","Grocery","Soap","Shampoo","Oil","Biscuit","Chips","Cold Drink","Detergent","Tea","Sugar","Rice","Flour","Spices")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(bg)) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(22,24,18,22)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(tealStart), Color.parseColor(tealEnd)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 8f; outlineProvider = ViewOutlineProvider.BACKGROUND }
        }
        header.addView(TextView(this).apply { text = "📦 Product + Category Premium"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        outer.addView(header)

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1,0,1f) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,16,16,24) }

        // CATEGORY CARD
        val catCard = premiumCard()
        catCard.addView(label("🏷️ Category"))
        val catRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        categoryField = TextView(this).apply {
            text = "📂 Category: General"; textSize = 13.5f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD)
            setPadding(16,14,16,14); background = GradientDrawable().apply { setColor(Color.parseColor(lightGrayBg)); cornerRadius = 10f; setStroke(1, Color.parseColor(border)) }
            layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,8,0) }
            setOnClickListener { openCategoryPicker() }
        }
        catRow.addView(categoryField)
        catRow.addView(TextView(this).apply { text = "＋"; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(14,12,14,12); background = GradientDrawable().apply { setColor(Color.parseColor(teal)); cornerRadius = 20f }; setOnClickListener { addCategoryDialog() } })
        catCard.addView(catRow)
        root.addView(catCard)
        root.addView(spacer(12))

        // PRODUCT INFO
        val infoCard = premiumCard()
        infoCard.addView(label("📝 Product Info"))
        nameField = input("Product Name * e.g. Sugar")
        barcodeField = input("Barcode Optional")
        infoCard.addView(nameField); infoCard.addView(spacer(8)); infoCard.addView(barcodeField)
        root.addView(infoCard); root.addView(spacer(12))

        // UNITS
        val unitCard = premiumCard()
        unitCard.addView(label("📐 Units - Ctn 50 Outer 10 Dabbi"))
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        unitField = smallInput("Unit ctn"); secondUnitField = smallInput("Second outer"); tertiaryUnitField = smallInput("Third dabbi")
        r1.addView(unitField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,6,0) } })
        r1.addView(secondUnitField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(6,0,6,0) } })
        r1.addView(tertiaryUnitField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(6,0,0,0) } })
        unitCard.addView(r1); unitCard.addView(spacer(8))
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        secondQtyField = smallInput("Qty 50"); tertiaryQtyField = smallInput("Qty 10")
        r2.addView(secondQtyField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,6,0) } })
        r2.addView(tertiaryQtyField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(6,0,0,0) } })
        unitCard.addView(r2)
        root.addView(unitCard); root.addView(spacer(12))

        // RATES
        val rateCard = premiumCard()
        rateCard.addView(label("💰 Rates - Cost / Wholesale / Retail"))
        val r3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        costField = smallInput("Cost"); wholesaleField = smallInput("Wholesale"); retailField = smallInput("Retail")
        r3.addView(costField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,6,0) } })
        r3.addView(wholesaleField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(6,0,6,0) } })
        r3.addView(retailField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(6,0,0,0) } })
        rateCard.addView(r3)
        root.addView(rateCard); root.addView(spacer(12))

        val stockCard = premiumCard()
        stockCard.addView(label("📦 Stock"))
        stockField = input("Opening Stock")
        stockCard.addView(stockField)
        root.addView(stockCard); root.addView(spacer(16))

        saveButton = TextView(this).apply {
            text = "✅ SAVE PRODUCT"; textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0,18,0,18); background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor(tealStart), Color.parseColor(tealEnd))).apply { cornerRadius = 14f }
            setOnClickListener { save() }
        }
        root.addView(saveButton); root.addView(spacer(16))

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply { text = "📋 All Products by Category"; textSize = 13f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
        root.addView(listContainer)

        scroll.addView(root); outer.addView(scroll); setContentView(outer)
        load()
    }

    private fun premiumCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(16,14,16,14)
        background = GradientDrawable().apply { setColor(Color.parseColor(cardWhite)); cornerRadius = 12f; setStroke(1, Color.parseColor(border)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 2f; outlineProvider = ViewOutlineProvider.BACKGROUND }
    }
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 11f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); setPadding(0,0,0,8) }
    private fun input(h: String) = EditText(this).apply { hint = h; textSize = 13.5f; setPadding(16,14,16,14); background = GradientDrawable().apply { setColor(Color.parseColor(lightGrayBg)); cornerRadius = 10f; setStroke(1, Color.parseColor(border)) } }
    private fun smallInput(h: String) = EditText(this).apply { hint = h; textSize = 12f; setPadding(14,12,14,12); background = GradientDrawable().apply { setColor(Color.parseColor(lightGrayBg)); cornerRadius = 10f; setStroke(1, Color.parseColor(border)) } }
    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, (h*resources.displayMetrics.density).toInt()) }

    private fun openCategoryPicker() {
        AlertDialog.Builder(this).setTitle("📂 Select Category").setItems(allCategories.toTypedArray()) { _, i ->
            selectedCategory = allCategories[i]; categoryField.text = "📂 Category: $selectedCategory"
        }.show()
    }
    private fun addCategoryDialog() {
        val ed = EditText(this).apply { hint = "New Category e.g. Chocolate" }
        AlertDialog.Builder(this).setTitle("＋ Add Category").setView(ed).setPositiveButton("Add") { _, _ ->
            val c = ed.text.toString().trim(); if (c.isNotEmpty()) { allCategories.add(c); selectedCategory = c; categoryField.text = "📂 Category: $selectedCategory" }
        }.setNegativeButton("Cancel", null).show()
    }
    private fun save() {
        val name = nameField.text.toString().trim(); if (name.isEmpty()) { Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val db = PosDatabase.get(this@ProductActivity)
            val p = Product(
                barcode = barcodeField.text.toString().ifEmpty { "P${System.currentTimeMillis()}" },
                name = name,
                category = selectedCategory,
                unit = unitField.text.toString().ifEmpty { "ctn" },
                secondaryUnit = secondUnitField.text.toString().ifEmpty { "outer" },
                tertiaryUnit = tertiaryUnitField.text.toString().ifEmpty { "dabbi" },
                secondaryUnitQty = secondQtyField.text.toString().toDoubleOrNull()?: 50.0,
                tertiaryUnitQty = tertiaryQtyField.text.toString().toDoubleOrNull()?: 10.0,
                stock = stockField.text.toString().toIntOrNull()?: 0,
                cost = costField.text.toString().toDoubleOrNull()?: 0.0,
                wholesalePrice = wholesaleField.text.toString().toDoubleOrNull()?: 0.0,
                salePrice = retailField.text.toString().toDoubleOrNull()?: 0.0
            )
            db.productDao().upsert(p)
            Toast.makeText(this@ProductActivity, "✅ Saved: $name - $selectedCategory", Toast.LENGTH_SHORT).show()
            load()
        }
    }
    private fun load() {
        lifecycleScope.launch {
            val list = PosDatabase.get(this@ProductActivity).productDao().all().first()
            listContainer.removeAllViews()
            val grouped = list.groupBy { it.category.ifEmpty { "General" } }
            for ((cat, items) in grouped) {
                listContainer.addView(TextView(this@ProductActivity).apply { text = "📂 $cat (${items.size})"; textSize = 12f; setTextColor(Color.WHITE); setPadding(12,6,12,6); background = GradientDrawable().apply { setColor(Color.parseColor(darkBlue)); cornerRadius = 16f }; layoutParams = LinearLayout.LayoutParams(-2,-2).apply { setMargins(0,12,0,6) } })
                for (p in items.take(30)) {
                    val row = LinearLayout(this@ProductActivity).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(14,12,14,12)
                        background = GradientDrawable().apply { setColor(Color.parseColor(cardWhite)); cornerRadius = 10f; setStroke(1, Color.parseColor(border)) }
                        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,8) }
                    }
                    row.addView(TextView(this@ProductActivity).apply { text = "${p.name} | ${p.category}"; textSize = 13f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
                    row.addView(TextView(this@ProductActivity).apply { text = "${p.unit} ${p.secondaryUnitQty.toInt()} ${p.secondaryUnit} ${p.tertiaryUnitQty.toInt()} ${p.tertiaryUnit} | W:${p.wholesalePrice} R:${p.salePrice}"; textSize = 11f; setTextColor(Color.parseColor(textGray)) })
                    listContainer.addView(row)
                }
            }
        }
    }
}
