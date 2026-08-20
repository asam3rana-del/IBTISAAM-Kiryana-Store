
package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CategoriesUnitsActivity : AppCompatActivity() {

    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val purple = "#8B5CF6"
    private val amber = "#F5A524"
    private val red = "#E5484D"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyText: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 26, 22)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,20) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 10f; outlineProvider = ViewOutlineProvider.BACKGROUND }
        }
        header.addView(TextView(this).apply { text = "🗂️"; textSize = 18f; gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#5C4DFF")) }; val px = (42*resources.displayMetrics.density).toInt(); width = px; height = px })
        val headerCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        headerCol.addView(TextView(this).apply { text = "Categories & Units"; textSize = 20f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        header.addView(headerCol)
        root.addView(header)

        emptyText = TextView(this).apply { text = "No products"; textSize = 13.5f; setTextColor(Color.parseColor(textGray)); gravity = Gravity.CENTER; setPadding(0,60,0,0); visibility = View.GONE }
        root.addView(emptyText)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(bg)); addView(root) })
        loadCategorized()
    }

    private fun loadCategorized() {
        lifecycleScope.launch {
            PosDatabase.get(this@CategoriesUnitsActivity).productDao().all().collectLatest { products ->
                listContainer.removeAllViews()
                if (products.isEmpty()) { emptyText.visibility = View.VISIBLE; return@collectLatest }
                emptyText.visibility = View.GONE
                val grouped = products.groupBy { it.category.ifBlank { "General" } }.toSortedMap(compareBy { it })
                for ((category, items) in grouped) {
                    listContainer.addView(categoryCard(category, items))
                }
            }
        }
    }

    private fun categoryCard(category: String, items: List<Product>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 20, 22, 20)
        background = GradientDrawable().apply { setColor(Color.parseColor(cardBg)); setStroke((1.4*resources.displayMetrics.density).toInt(), Color.parseColor(border)); cornerRadius = 18f }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,16) }
        val head = LinearLayout(this@CategoriesUnitsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,0,0,14) }
        head.addView(TextView(this@CategoriesUnitsActivity).apply { text = category; textSize = 15f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
        addView(head)
        for (p in items) {
            addView(productRow(p))
        }
    }

    private fun productRow(p: Product) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 14, 16, 14)
        background = GradientDrawable().apply { setColor(Color.parseColor("#FAFAFF")); setStroke((1.4*resources.displayMetrics.density).toInt(), Color.parseColor(border)); cornerRadius = 14f }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,10) }
        addView(TextView(this@CategoriesUnitsActivity).apply { text = p.name; textSize = 13.5f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
        addView(TextView(this@CategoriesUnitsActivity).apply { text = "📏 Unit: ${p.unit}  ${if(p.secondaryUnit.isNotBlank()) "• 1 ${p.unit} = ${p.secondaryUnitQty.toInt()} ${p.secondaryUnit}" else ""}"; textSize = 12f; setTextColor(Color.parseColor(amber)) })
        addView(TextView(this@CategoriesUnitsActivity).apply { text = "📊 Stock: ${p.stock} ${p.tertiaryUnit}"; textSize = 12f; setTextColor(Color.parseColor(textGray)) })
        val actionsRow = LinearLayout(this@CategoriesUnitsActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,10,0,0) }
        actionsRow.addView(TextView(this@CategoriesUnitsActivity).apply {
            text = "✏️ Edit"; textSize = 12f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); background = GradientDrawable().apply { setColor(Color.parseColor(primary)); cornerRadius = 30f }; setPadding(24,10,24,10)
            setOnClickListener { startActivity(Intent(this@CategoriesUnitsActivity, ProductActivity::class.java).apply { putExtra(ProductActivity.EXTRA_EDIT_BARCODE, p.barcode) }) }
        })
        addView(actionsRow)
    }
}
