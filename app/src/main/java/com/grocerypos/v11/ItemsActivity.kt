package com.grocerypos.v11.ui

import android.app.AlertDialog
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
import com.grocerypos.v11.Product
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ItemsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EDIT_BARCODE = "EXTRA_EDIT_BARCODE" // ✅ FIXED - Ab error nahi ayega
    }

    private val tealStart = "#14B8A6"
    private val tealEnd = "#0D9488"
    private val bg = "#F4F5F7"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#111827"
    private val textGray = "#6B7280"
    private val border = "#E5E7EB"
    private val teal = "#0FA89A"

    private lateinit var listContainer: LinearLayout
    private var allProducts: List<Product> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        // FIXED LINE 314 - Ab constant mil jayega
        val editBarcode = intent.getStringExtra(EXTRA_EDIT_BARCODE) // ✅ FIXED
        val categoryFilter = intent.getStringExtra("CATEGORY_FILTER")

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(bg)) }
        val header = LinearLayout(this).apply {
            setPadding(22,24,18,22)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(tealStart), Color.parseColor(tealEnd)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 8f; outlineProvider = ViewOutlineProvider.BACKGROUND }
        }
        header.addView(TextView(this).apply { 
            text = if (categoryFilter != null) "📦 $categoryFilter Items" else "📦 All Items Premium"
            textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) 
        })
        outer.addView(header)

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1,0,1f) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,16,16,24) }
        
        val searchField = EditText(this).apply {
            hint = "🔍 Search..."; setPadding(16,14,16,14)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 12f; setStroke(1, Color.parseColor(border)) }
        }
        root.addView(searchField)
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1,16) })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        scroll.addView(root)
        outer.addView(scroll)
        setContentView(outer)

        lifecycleScope.launch {
            allProducts = PosDatabase.get(this@ItemsActivity).productDao().all().first()
            var filtered = allProducts
            if (categoryFilter != null) {
                filtered = allProducts.filter { (it.category.ifEmpty { "General" }) == categoryFilter }
            }
            showProducts(filtered)

            searchField.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s.toString()
                    val f = allProducts.filter { it.name.contains(q, true) || it.barcode.contains(q, true) }
                    showProducts(f)
                }
            })
        }
    }

    private fun showProducts(list: List<Product>) {
        listContainer.removeAllViews()
        for (p in list.take(100)) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(14,12,14,12)
                background = GradientDrawable().apply { setColor(Color.parseColor(cardWhite)); cornerRadius = 10f; setStroke(1, Color.parseColor(border)) }
                layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,8) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 1f; outlineProvider = ViewOutlineProvider.BACKGROUND }
            }
            card.addView(TextView(this).apply { text = "📦 ${p.name}"; textSize = 13.5f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
            card.addView(TextView(this).apply { text = "📂 ${p.category} | ${p.barcode} | Stock: ${p.stock} | W:${p.wholesalePrice} R:${p.salePrice}"; textSize = 10.5f; setTextColor(Color.parseColor(textGray)) })
            card.addView(TextView(this).apply { text = "📐 ${p.unit} ${p.secondaryUnitQty.toInt()} ${p.secondaryUnit} ${p.tertiaryUnitQty.toInt()} ${p.tertiaryUnit}"; textSize = 10f; setTextColor(Color.parseColor(teal)) })
            
            card.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(p.name)
                    .setMessage("Edit this product?")
                    .setPositiveButton("Edit") { _, _ ->
                        val intent = android.content.Intent(this, ProductActivity::class.java)
                        intent.putExtra(EXTRA_EDIT_BARCODE, p.barcode)
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            listContainer.addView(card)
        }
    }
}
