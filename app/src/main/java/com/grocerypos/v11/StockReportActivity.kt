package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.formatStockBreakdown
import com.grocerypos.v11.smallestUnitFactor
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class StockReportActivity : AppCompatActivity() {

    private val bg = "#F3F4F9"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#1A1D2E"
    private val textMuted = "#8A8FA3"
    private val red = "#C62828"

    private lateinit var resultsBox: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var lowStockToggle: Button
    private lateinit var summaryBox: LinearLayout

    private var allProducts: List<Product> = emptyList()
    private var lowStockOnly = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 32)
            setBackgroundColor(Color.parseColor(bg))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 20)
        }
        headerRow.addView(TextView(this).apply {
            text = "\u2039"
            textSize = 20f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 16, 0)
            setOnClickListener { finish() }
        })
        headerRow.addView(TextView(this).apply {
            text = Loc.t(this@StockReportActivity, "Stock Report", "اسٹاک رپورٹ")
            textSize = 21f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(headerRow)

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 4, 20, 4)
            background = strokedBg("#E7EAF0", cardWhite, 16)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 14) }
        }
        searchBox.addView(TextView(this).apply { text = "\uD83D\uDD0D  "; textSize = 14f })
        searchField = EditText(this).apply {
            hint = Loc.t(this@StockReportActivity, "Search item or category…", "آئٹم یا کیٹیگری تلاش کریں…")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 14.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchBox.addView(searchField)
        root.addView(searchBox)

        lowStockToggle = Button(this).apply {
            text = Loc.t(this@StockReportActivity, "LOW STOCK ONLY", "صرف کم اسٹاک")
            textSize = 11.5f
            isAllCaps = false
            setTextColor(Color.parseColor(textMuted))
            background = roundedBg(cardWhite, 14)
            setPadding(20, 18, 20, 18)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
            setOnClickListener {
                lowStockOnly = !lowStockOnly
                refreshToggleStyle()
                renderList(searchField.text?.toString().orEmpty())
            }
        }
        root.addView(lowStockToggle)

        summaryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(summaryBox)
        root.addView(spacer(14))

        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultsBox)
        root.addView(spacer(30))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scroll)

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = renderList(s?.toString().orEmpty())
        })

        refreshToggleStyle()
        loadReport()
    }

    private fun refreshToggleStyle() {
        if (lowStockOnly) {
            lowStockToggle.background = roundedBg(red, 14)
            lowStockToggle.setTextColor(Color.WHITE)
        } else {
            lowStockToggle.background = roundedBg(cardWhite, 14)
            lowStockToggle.setTextColor(Color.parseColor(textMuted))
        }
    }

    private fun costPerSmallestUnit(p: Product): Double {
        val factor = p.smallestUnitFactor()
        return if (factor > 0) p.cost / factor else p.cost
    }
    private fun salePerSmallestUnit(p: Product): Double {
        val factor = p.smallestUnitFactor()
        return if (factor > 0) p.salePrice / factor else p.salePrice
    }

    private fun loadReport() = lifecycleScope.launch {
        val db = PosDatabase.get(this@StockReportActivity)
        allProducts = db.productDao().all().first().sortedBy { it.name.lowercase() }
        renderSummary()
        renderList(searchField.text?.toString().orEmpty())
    }

    private fun renderSummary() {
        summaryBox.removeAllViews()
        val totalProducts = allProducts.size
        val lowStockCount = allProducts.count { it.stock <= it.reorderLevel }
        val totalStockValue = allProducts.sumOf { it.stock * costPerSmallestUnit(it) }
        val totalSaleValue = allProducts.sumOf { it.stock * salePerSmallestUnit(it) }

        summaryBox.addView(summaryCard("\uD83D\uDCE6", Loc.t(this, "Total Products", "کل آئٹمز"), "$totalProducts", "#1565C0", "#E3F2FD"))
        summaryBox.addView(summaryCard("\u26A0\uFE0F", Loc.t(this, "Low Stock Items", "کم اسٹاک آئٹمز"), "$lowStockCount", red, "#FFEBEE"))
        summaryBox.addView(summaryCard("\uD83D\uDCB0", Loc.t(this, "Stock Value (Cost)", "اسٹاک ویلیو (لاگت)"), "Rs %.2f".format(totalStockValue), "#EF6C00", "#FFF3E0"))
        summaryBox.addView(summaryCard("\uD83D\uDCC8", Loc.t(this, "Stock Value (Sale)", "اسٹاک ویلیو (سیل)"), "Rs %.2f".format(totalSaleValue), "#2E7D32", "#E8F5E9"))
    }

    private fun renderList(query: String) {
        resultsBox.removeAllViews()
        val q = query.trim().lowercase()
        var filtered = allProducts.filter { p ->
            q.isEmpty() || p.name.lowercase().contains(q) || p.category.lowercase().contains(q) || p.barcode.lowercase().contains(q)
        }
        if (lowStockOnly) filtered = filtered.filter { it.stock <= it.reorderLevel }

        if (filtered.isEmpty()) {
            resultsBox.addView(TextView(this).apply {
                text = Loc.t(this@StockReportActivity, "No items found", "کوئی آئٹم نہیں ملا")
                setTextColor(Color.parseColor(textMuted))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 40, 0, 0)
            })
            return
        }

        filtered.forEach { p ->
            val isLow = p.stock <= p.reorderLevel
            val stockValue = p.stock * costPerSmallestUnit(p)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
                background = roundedBg(cardWhite, 18)
                elevation = 3f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
            }
            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            nameCol.addView(TextView(this).apply { text = p.name; textSize = 14.5f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor(textDark)) })
            if (p.category.isNotBlank()) {
                nameCol.addView(TextView(this).apply { text = p.category; textSize = 12f; setTextColor(Color.parseColor(textMuted)); setPadding(0, 2, 0, 0) })
            }
            topRow.addView(nameCol)
            if (isLow) {
                topRow.addView(TextView(this).apply {
                    text = Loc.t(this@StockReportActivity, "LOW", "کم")
                    textSize = 10.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = roundedBg(red, 8)
                    setPadding(14, 5, 14, 5)
                })
            }
            card.addView(topRow)
            card.addView(spacer(8))
            val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            bottomRow.addView(TextView(this).apply {
                text = p.formatStockBreakdown()
                textSize = 13f
                setTextColor(Color.parseColor(if (isLow) red else textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            bottomRow.addView(TextView(this).apply {
                text = "Rs %.2f".format(stockValue)
                textSize = 13f
                setTextColor(Color.parseColor("#1565C0"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(bottomRow)
            resultsBox.addView(card)
        }
    }

    private fun summaryCard(emoji: String, label: String, value: String, accentHex: String, tintHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 20, 22, 20)
            background = roundedBg(cardWhite, 20)
            elevation = 4f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }

            addView(FrameLayout(this@StockReportActivity).apply {
                val size = (40 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(lighten(accentHex, 0.85f), Color.parseColor(tintHex))
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    orientation = GradientDrawable.Orientation.TL_BR
                }
                addView(TextView(this@StockReportActivity).apply {
                    text = emoji; textSize = 16f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            })

            val textCol = LinearLayout(this@StockReportActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 0, 0, 0)
            }
            textCol.addView(TextView(this@StockReportActivity).apply {
                text = label; setTextColor(Color.parseColor(textMuted)); textSize = 12.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            textCol.addView(TextView(this@StockReportActivity).apply {
                text = value; setTextColor(Color.parseColor(accentHex)); textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 4, 0, 0)
            })
            addView(textCol)
        }
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
    }

    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }

    private fun lighten(hex: String, factor: Float): Int {
        val base = Color.parseColor(hex)
        val r = (Color.red(base) + (255 - Color.red(base)) * factor).toInt()
        val g = (Color.green(base) + (255 - Color.green(base)) * factor).toInt()
        val bl = (Color.blue(base) + (255 - Color.blue(base)) * factor).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), bl.coerceIn(0, 255))
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }
}
