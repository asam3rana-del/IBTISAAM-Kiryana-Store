package com.grocerypos.v11.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CategoriesUnitsActivity : AppCompatActivity() {

    private val tealStart = "#14B8A6"
    private val tealEnd = "#0D9488"
    private val bg = "#F4F5F7"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#111827"
    private val textGray = "#6B7280"
    private val border = "#E5E7EB"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(bg)) }
        val header = LinearLayout(this).apply {
            setPadding(22,24,18,22)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(tealStart), Color.parseColor(tealEnd)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 8f; outlineProvider = ViewOutlineProvider.BACKGROUND }
        }
        header.addView(TextView(this).apply { text = "📂 Categories & Units"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        outer.addView(header)

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1,0,1f) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,16,16,24) }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(container)
        scroll.addView(root)
        outer.addView(scroll)
        setContentView(outer)

        lifecycleScope.launch {
            val db = PosDatabase.get(this@CategoriesUnitsActivity)
            val products = db.productDao().all().first()
            val categories = products.map { it.category.ifEmpty { "General" } }.distinct()

            container.removeAllViews()
            for (cat in categories) {
                val card = LinearLayout(this@CategoriesUnitsActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(16,14,16,14)
                    background = GradientDrawable().apply { setColor(Color.parseColor(cardWhite)); cornerRadius = 12f; setStroke(1, Color.parseColor(border)) }
                    layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,10) }
                }
                card.addView(TextView(this@CategoriesUnitsActivity).apply { text = "📂 $cat"; textSize = 14f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
                val count = products.count { (it.category.ifEmpty { "General" }) == cat }
                card.addView(TextView(this@CategoriesUnitsActivity).apply { text = "$count Items"; textSize = 11f; setTextColor(Color.parseColor(textGray)) })
                
                // FIXED LINE 112 - No Unresolved reference
                card.setOnClickListener {
                    val intent = Intent(this@CategoriesUnitsActivity, ItemsActivity::class.java)
                    intent.putExtra(ItemsActivity.EXTRA_EDIT_BARCODE, cat) // ✅ FIXED
                    intent.putExtra("CATEGORY_FILTER", cat)
                    startActivity(intent)
                }
                container.addView(card)
            }
        }
    }
}
