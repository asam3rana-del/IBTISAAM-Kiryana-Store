package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
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

    // ================= PREMIUM PALETTE (shared with Items / Categories / Reports) =================
    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val purple = "#8B5CF6"
    private val amber = "#F5A524"
    private val teal = "#0F9B8E"
    private val red = "#E5484D"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private lateinit var resultsBox: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var lowStockToggle: TextView
    private lateinit var summaryBox: LinearLayout

    private var allProducts: List<Product> = emptyList()
    private var lowStockOnly = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(premiumHeader("📦", Loc.t(this, "Stock Report", "اسٹاک رپورٹ"), Loc.t(this, "Current inventory levels", "موجودہ انوینٹری کی سطح")))

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 4, 18, 4)
            background = strokedBg(border, "#FAFAFF", 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 14) }
        }
        searchBox.addView(TextView(this).apply { text = "\uD83D\uDD0D  "; textSize = 14f })
        searchField = EditText(this).apply {
            hint = Loc.t(this@StockReportActivity, "Search item or category…", "آئٹم یا کیٹیگری تلاش کریں…")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 14.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchBox.addView(searchField)
        root.addView(searchBox)

        lowStockToggle = TextView(this).apply {
            text = "⚠️  " + Loc.t(this@StockReportActivity, "LOW STOCK ONLY", "صرف کم اسٹاک")
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(24, 14, 24, 14)
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
            lowStockToggle.background = strokedBg(border, cardBg, 14)
            lowStockToggle.setTextColor(Color.parseColor(textGray))
        }
    }

    // FIX: stock value everywhere in this screen is computed per SMALLEST unit
    // (cost/salePrice divided by the product's unit-ladder factor) before being
    // multiplied by `stock`, since `stock` is stored in smallest units while
    // `cost`/`salePrice` are per PRIMARY unit. Multiplying them directly (as the
    // old raw SQL SUM(stock*cost) used to do elsewhere) overstates value by the
    // unit-conversion factor — this is the same fix applied to BalanceSheetActivity.
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

        summaryBox.addView(summaryCard("\uD83D\uDCE6", Loc.t(this, "Total Products", "کل آئٹمز"), "$totalProducts", primary, "#E9E6FF"))
        summaryBox.addView(summaryCard("\u26A0\uFE0F", Loc.t(this, "Low Stock Items", "کم اسٹاک آئٹمز"), "$lowStockCount", red, "#FDE8E8"))
        summaryBox.addView(summaryCard("\uD83D\uDCB0", Loc.t(this, "Stock Value (Cost)", "اسٹاک ویلیو (لاگت)"), "Rs %.2f".format(totalStockValue), amber, "#FFF3E0"))
        summaryBox.addView(summaryCard("\uD83D\uDCC8", Loc.t(this, "Stock Value (Sale)", "اسٹاک ویلیو (سیل)"), "Rs %.2f".format(totalSaleValue), teal, "#E0F2F1"))
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
                setTextColor(Color.parseColor(textGray))
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
                background = strokedBg(border, cardBg, 18)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
                applyElevation(this, 2f)
            }
            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            nameCol.addView(TextView(this).apply { text = p.name; textSize = 14.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor(textDark)) })
            if (p.category.isNotBlank()) {
                nameCol.addView(TextView(this).apply { text = p.category; textSize = 12f; setTextColor(Color.parseColor(textGray)); setPadding(0, 2, 0, 0) })
            }
            topRow.addView(nameCol)
            if (isLow) {
                topRow.addView(TextView(this).apply {
                    text = Loc.t(this@StockReportActivity, "LOW", "کم")
                    textSize = 10.5f
                    setTypeface(typeface, Typeface.BOLD)
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
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            bottomRow.addView(TextView(this).apply {
                text = "Rs %.2f".format(stockValue)
                textSize = 13f
                setTextColor(Color.parseColor(primary))
                setTypeface(typeface, Typeface.BOLD)
            })
            card.addView(bottomRow)
            resultsBox.addView(card)
        }
    }

    // ================= PREMIUM HEADER (matches Items/Categories/Reports) =================
    private fun premiumHeader(icon: String, title: String, subtitle: String): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 26, 22)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            ).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
            applyElevation(this, 10f)
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = ovalBg("#33FFFFFF")
            val px = (36 * resources.displayMetrics.density).toInt()
            width = px; height = px
            setOnClickListener { finish() }
        })
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(14, 1) })
        header.addView(circleIcon(icon, "#5C4DFF", 42))
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Color.parseColor("#D8D3FF"))
            setPadding(0, 4, 0, 0)
        })
        header.addView(headerCol)
        return header
    }

    private fun summaryCard(emoji: String, label: String, value: String, accentHex: String, tintHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 20, 22, 20)
            background = strokedBg(border, cardBg, 18)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
            applyElevation(this, 2f)

            addView(FrameLayout(this@StockReportActivity).apply {
                val size = (40 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(tintHex))
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
                text = label; setTextColor(Color.parseColor(textGray)); textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
            })
            textCol.addView(TextView(this@StockReportActivity).apply {
                text = value; setTextColor(Color.parseColor(accentHex)); textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 4, 0, 0)
            })
            addView(textCol)
        }
    }

    // ================= SHARED UI HELPERS (matches Items/Categories/Reports) =================
    private fun circleIcon(label: String, colorHex: String, sizeDp: Int) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        width = px; height = px
    }

    private fun ovalBg(colorHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
    }

    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.4 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }

    private fun applyElevation(view: View, dp: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.elevation = dp * resources.displayMetrics.density
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }
}
