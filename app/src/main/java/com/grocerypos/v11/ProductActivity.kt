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
    private val bg = "#F0FDFA"
    private val textDark = "#0F172A"
    private val textGray = "#64748B"
    private val border = "#CCFBF1"
    private val lightTeal = "#F0FDFA"

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
    private var allCategories = mutableListOf("General","Grocery","Soap","Shampoo","Oil","Biscuit","Chips","Cold Drink","Detergent","Tea","Sugar","Rice","Flour","Spices","پان سامان")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(bg)) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22,28,22,28)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(tealStart), Color.parseColor("#0F766E"))).apply { cornerRadii = floatArrayOf(0f,0f,0f,0f,0f,0f,32f,32f) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 12f; outlineProvider = ViewOutlineProvider.BACKGROUND }
        }
        header.addView(TextView(this).apply { text = "✨ Product + Category Premium"; textSize = 20f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        header.addView(TextView(this).apply { text = "Ultra Attractive • Glass • Glow • Gradient"; textSize = 11f; setTextColor(Color.parseColor("#99F6E0")); setPadding(0,6,0,0) })
        outer.addView(header)

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1,0,1f) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,18,16,24) }

        val catCard = ultraCard("🏷️", "Category", "#14B8A6", "#06B6D4")
        val catRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,12,0,0) }
        categoryField = TextView(this).apply {
            text = "📂 Category: پان سامان"; textSize = 14f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD)
            setPadding(18,16,18,16)
            background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 14f; setStroke(2, Color.parseColor(border)) }
            layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,10,0) }
            setOnClickListener { openCategoryPicker() }
        }
        catRow.addView(categoryField)
        catRow.addView(TextView(this).apply {
            text = "＋"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            width = (48*resources.displayMetrics.density).toInt(); height = (48*resources.displayMetrics.density).toInt()
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(tealStart), Color.parseColor("#06B6D4"))).apply { shape = GradientDrawable.OVAL }
            setOnClickListener { addCategoryDialog() }
        })
        catCard.addView(catRow)
        root.addView(catCard)
        root.addView(spacer(16))

        val infoCard = ultraCard("📝", "Product Info", "#8B5CF6", "#EC4899")
        nameField = ultraInput("📝 Product Name * e.g. Sugar, Shampoo")
        barcodeField = ultraInput("🔢 Barcode (Optional)")
        infoCard.addView(nameField); infoCard.addView(spacer(10)); infoCard.addView(barcodeField)
        root.addView(infoCard); root.addView(spacer(16))

        val unitCard = ultraCard("📐", "Units - Ctn 50 Outer 10 Dabbi", "#F59E0B", "#EF4444")
        val badgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,8,0,8) }
        badgeRow.addView(badge("CTN")); badgeRow.addView(badge("OUTER")); badgeRow.addView(badge("DABBI"))
        unitCard.addView(badgeRow)
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        unitField = smallUltraInput("Unit ctn"); secondUnitField = smallUltraInput("Second outer"); tertiaryUnitField = smallUltraInput("Third dabbi")
        r1.addView(unitField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,8,0) } })
        r1.addView(secondUnitField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,8,0) } })
        r1.addView(tertiaryUnitField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
        unitCard.addView(r1); unitCard.addView(spacer(10))
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        secondQtyField = smallUltraInput("Qty 50"); tertiaryQtyField = smallUltraInput("Qty 10")
        r2.addView(secondQtyField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,8,0) } })
        r2.addView(tertiaryQtyField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
        unitCard.addView(r2)
        root.addView(unitCard); root.addView(spacer(16))

        val rateCard = ultraCard("💰", "Rates - Cost / Wholesale / Retail", "#10B981", "#06B6D4")
        val r3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,10,0,0) }
        costField = smallUltraInput("Cost 💵"); wholesaleField = smallUltraInput("Wholesale 📦"); retailField = smallUltraInput("Retail 🏪")
        r3.addView(costField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,8,0) } })
        r3.addView(wholesaleField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,8,0) } })
        r3.addView(retailField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
        rateCard.addView(r3)
        root.addView(rateCard); root.addView(spacer(16))

        val stockCard = ultraCard("📦", "Stock", "#6366F1", "#8B5CF6")
        stockField = ultraInput("📦 Opening Stock")
        stockCard.addView(stockField)
        root.addView(stockCard); root.addView(spacer(20))

        saveButton = TextView(this).apply {
            text = "✨ SAVE PRODUCT - ULTRA PREMIUM"; textSize = 15f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0,20,0,20)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(tealStart), Color.parseColor("#0F766E"))).apply { cornerRadius = 18f }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 10f; outlineProvider = ViewOutlineProvider.BACKGROUND }
            setOnClickListener { save() }
        }
        root.addView(saveButton); root.addView(spacer(20))

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply { text = "📋 All Products by Category - Ultra"; textSize = 13f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD); setPadding(0,0,0,10) })
        root.addView(listContainer)

        scroll.addView(root); outer.addView(scroll); setContentView(outer)
        load()
    }

    private fun ultraCard(icon: String, title: String, c1: String, c2: String): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(3,3,3,3)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(c1), Color.parseColor(c2))).apply { cornerRadius = 22f }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 6f; outlineProvider = ViewOutlineProvider.BACKGROUND }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(18,16,18,18)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 19f }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = icon; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(10,10,10,10)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(c1), Color.parseColor(c2))).apply { shape = GradientDrawable.OVAL }
            layoutParams = LinearLayout.LayoutParams((36*resources.displayMetrics.density).toInt(), (36*resources.displayMetrics.density).toInt())
        })
        head.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(10,1) })
        head.addView(TextView(this).apply { text = title; textSize = 12f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD) })
        inner.addView(head)
        outer.addView(inner)
        return inner
    }

    private fun badge(txt: String) = TextView(this).apply {
        text = txt; textSize = 9f; setTextColor(Color.parseColor("#0F766E")); setTypeface(typeface, Typeface.BOLD)
        setPadding(10,4,10,4); background = GradientDrawable().apply { setColor(Color.parseColor("#CCFBF1")); cornerRadius = 12f }
        layoutParams = LinearLayout.LayoutParams(-2,-2).apply { setMargins(0,0,6,0) }
    }
    private fun ultraInput(hint: String) = EditText(this).apply {
        this.hint = hint; textSize = 13.5f; setTextColor(Color.parseColor(textDark)); setHintTextColor(Color.parseColor("#94A3B8"))
        setPadding(18,16,18,16); background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 14f; setStroke(2, Color.parseColor(border)) }
    }
    private fun smallUltraInput(hint: String) = EditText(this).apply {
        this.hint = hint; textSize = 12f; setTextColor(Color.parseColor(textDark)); setHintTextColor(Color.parseColor("#94A3B8"))
        setPadding(16,14,16,14); background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 12f; setStroke(2, Color.parseColor(border)) }
    }
    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, (h*resources.displayMetrics.density).toInt()) }
    private fun openCategoryPicker() { AlertDialog.Builder(this).setTitle("📂 Select Category - Ultra").setItems(allCategories.toTypedArray()) { _, i -> selectedCategory = allCategories[i]; categoryField.text = "📂 Category: $selectedCategory" }.show() }
    private fun addCategoryDialog() { val ed = EditText(this).apply { hint = "New Category" }; AlertDialog.Builder(this).setTitle("＋ Add Category").setView(ed).setPositiveButton("Add") { _, _ -> val c = ed.text.toString().trim(); if (c.isNotEmpty()) { allCategories.add(c); selectedCategory = c; categoryField.text = "📂 Category: $selectedCategory" } }.setNegativeButton("Cancel", null).show() }
    private fun save() {
        val name = nameField.text.toString().trim(); if (name.isEmpty()) { Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val db = PosDatabase.get(this@ProductActivity)
            val p = Product(barcode = barcodeField.text.toString().ifEmpty { "P${System.currentTimeMillis()}" }, name = name, category = selectedCategory, unit = unitField.text.toString().ifEmpty { "ctn" }, secondaryUnit = secondUnitField.text.toString().ifEmpty { "outer" }, tertiaryUnit = tertiaryUnitField.text.toString().ifEmpty { "dabbi" }, secondaryUnitQty = secondQtyField.text.toString().toDoubleOrNull()?: 50.0, tertiaryUnitQty = tertiaryQtyField.text.toString().toDoubleOrNull()?: 10.0, stock = stockField.text.toString().toIntOrNull()?: 0, cost = costField.text.toString().toDoubleOrNull()?: 0.0, wholesalePrice = wholesaleField.text.toString().toDoubleOrNull()?: 0.0, salePrice = retailField.text.toString().toDoubleOrNull()?: 0.0)
            db.productDao().upsert(p); Toast.makeText(this@ProductActivity, "✨ Ultra Saved: $name", Toast.LENGTH_SHORT).show(); load()
        }
    }
    private fun load() {
        lifecycleScope.launch {
            val list = PosDatabase.get(this@ProductActivity).productDao().all().first()
            listContainer.removeAllViews()
            val grouped = list.groupBy { it.category.ifEmpty { "General" } }
            for ((cat, items) in grouped) {
                listContainer.addView(TextView(this@ProductActivity).apply { text = "📂 $cat (${items.size})"; textSize = 11f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); setPadding(14,8,14,8); background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#0F766E"), Color.parseColor(tealStart))).apply { cornerRadius = 20f }; layoutParams = LinearLayout.LayoutParams(-2,-2).apply { setMargins(0,14,0,8) } })
                for (p in items.take(20)) {
                    val row = LinearLayout(this@ProductActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(16,14,16,14); background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16f; setStroke(1, Color.parseColor(border)) }; layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,10) }; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 3f } }
                    row.addView(TextView(this@ProductActivity).apply { text = "✨ ${p.name} | ${p.category}"; textSize = 13f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
                    row.addView(TextView(this@ProductActivity).apply { text = "${p.unit} ${p.secondaryUnitQty.toInt()} ${p.secondaryUnit} ${p.tertiaryUnitQty.toInt()} ${p.tertiaryUnit} | Stock ${p.stock} | W:${p.wholesalePrice} R:${p.salePrice}"; textSize = 11f; setTextColor(Color.parseColor(textGray)); setPadding(0,4,0,0) })
                    listContainer.addView(row)
                }
            }
        }
    }
}
