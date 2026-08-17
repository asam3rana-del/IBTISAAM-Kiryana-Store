package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.util.Calendar

class ReportsActivity : AppCompatActivity() {

    private val bg = "#F3F4F9"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#1A1D2E"
    private val textMuted = "#8A8FA3"

    private lateinit var resultsBox: LinearLayout
    private var periodLabel: TextView? = null
    private val filterButtons = mutableListOf<Button>()

    private var rangeStart: Long = 0
    private var rangeEnd: Long = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 32)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(TextView(this).apply {
            text = Loc.t(this@ReportsActivity, "Reports", "Ø±Ù¾ÙˆØ±Ù¹Ø³")
            textSize = 21f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(4, 0, 0, 20)
        })

        root.addView(Button(this).apply {
            text = Loc.t(this@ReportsActivity, "VIEW SALE / PURCHASE HISTORY", "Ø³ÛŒÙ„ / Ø®Ø±ÛŒØ¯Ø§Ø±ÛŒ Ú©ÛŒ ØªØ§Ø±ÛŒØ® Ø¯ÛŒÚ©Ú¾ÛŒÚº")
            textSize = 12.5f
            setTextColor(Color.parseColor("#1565C0"))
            background = strokedBg("#1565C0", "#FFFFFF", 16)
            setPadding(0, 20, 0, 20)
            setOnClickListener {
                startActivity(android.content.Intent(this@ReportsActivity, HistoryActivity::class.java))
            }
        })
        root.addView(spacer(18))

        root.addView(Button(this).apply {
            text = Loc.t(this@ReportsActivity, "PARTY REPORTS", "Ù¾Ø§Ø±Ù¹ÛŒ Ø±Ù¾ÙˆØ±Ù¹Ø³")
            textSize = 12.5f
            setTextColor(Color.parseColor("#6A1B9A"))
            background = strokedBg("#6A1B9A", "#FFFFFF", 16)
            setPadding(0, 20, 0, 20)
            setOnClickListener {
                startActivity(android.content.Intent(this@ReportsActivity, PartyReportsActivity::class.java))
            }
        })
        root.addView(spacer(18))

        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val today = smallButton(Loc.t(this, "Today", "Ø¢Ø¬")) { setRangeToday(); loadReport() }
        val week = smallButton(Loc.t(this, "This Week", "Ø§Ø³ ÛÙØªÛ’")) { setRangeThisWeek(); loadReport() }
        val month = smallButton(Loc.t(this, "This Month", "Ø§Ø³ Ù…ÛÛŒÙ†Û’")) { setRangeThisMonth(); loadReport() }
        val all = smallButton(Loc.t(this, "All Time", "ØªÙ…Ø§Ù… ÙˆÙ‚Øª")) { setRangeAllTime(); loadReport() }
        filterButtons.addAll(listOf(today, week, month, all))
        filterRow.addView(today)
        filterRow.addView(week)
        filterRow.addView(month)
        filterRow.addView(all)
        root.addView(filterRow)

        periodLabel = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(4, 18, 0, 6)
        }
        root.addView(periodLabel)

        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultsBox)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scroll)

        setRangeToday()
        loadReport()
    }

    // ---- Date range helpers ----
    private fun setRangeToday() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis
        rangeEnd = rangeStart + 24 * 60 * 60 * 1000L
        periodLabel?.text = Loc.t(this, "SHOWING: TODAY", "Ø¯Ú©Ú¾Ø§ÛŒØ§ Ø¬Ø§ Ø±ÛØ§ ÛÛ’: Ø¢Ø¬")
        highlightFilter(0)
    }

    private fun setRangeThisWeek() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis
        rangeEnd = System.currentTimeMillis()
        periodLabel?.text = Loc.t(this, "SHOWING: THIS WEEK", "Ø¯Ú©Ú¾Ø§ÛŒØ§ Ø¬Ø§ Ø±ÛØ§ ÛÛ’: Ø§Ø³ ÛÙØªÛ’")
        highlightFilter(1)
    }

    private fun setRangeThisMonth() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis
        rangeEnd = System.currentTimeMillis()
        periodLabel?.text = Loc.t(this, "SHOWING: THIS MONTH", "Ø¯Ú©Ú¾Ø§ÛŒØ§ Ø¬Ø§ Ø±ÛØ§ ÛÛ’: Ø§Ø³ Ù…ÛÛŒÙ†Û’")
        highlightFilter(2)
    }

    private fun setRangeAllTime() {
        rangeStart = 0L
        rangeEnd = System.currentTimeMillis()
        periodLabel?.text = Loc.t(this, "SHOWING: ALL TIME", "Ø¯Ú©Ú¾Ø§ÛŒØ§ Ø¬Ø§ Ø±ÛØ§ ÛÛ’: ØªÙ…Ø§Ù… ÙˆÙ‚Øª")
        highlightFilter(3)
    }

    private fun highlightFilter(activeIndex: Int) {
        filterButtons.forEachIndexed { i, btn ->
            if (i == activeIndex) {
                btn.background = roundedBg("#1A237E", 14)
                btn.setTextColor(Color.WHITE)
            } else {
                btn.background = roundedBg(cardWhite, 14)
                btn.setTextColor(Color.parseColor(textMuted))
            }
        }
    }

    // ---- Load and display ----
    private fun loadReport() {
        resultsBox.removeAllViews()

        lifecycleScope.launch {
            val db = PosDatabase.get(this@ReportsActivity)

            val totalSales = db.saleDao().totalSalesBetween(rangeStart, rangeEnd)
            val totalProfit = db.saleDao().profitBetween(rangeStart, rangeEnd)
            val totalPurchases = db.purchaseDao().totalBetween(rangeStart, rangeEnd)
            val totalExpenses = db.expenseDao().totalBetween(rangeStart, rangeEnd)
            val saleCount = db.saleDao().countBetween(rangeStart, rangeEnd)
            val topProducts = db.saleDao().topProducts(rangeStart, rangeEnd)
            val dailySales = db.saleDao().dailySales(rangeStart, rangeEnd)

            // ---- Returns for this period ----
            val totalSaleReturns = db.returnDao().totalByTypeBetween("sale", rangeStart, rangeEnd)
            val totalPurchaseReturns = db.returnDao().totalByTypeBetween("purchase", rangeStart, rangeEnd)

            // ---- Profit & Loss inputs ----
            val cogs = db.saleDao().cogsBetween(rangeStart, rangeEnd)
            val grossProfit = totalSales - cogs
            val netProfit = grossProfit - totalExpenses

            resultsBox.removeAllViews()

            resultsBox.addView(summaryCard("ðŸ’°", Loc.t(this@ReportsActivity, "Total Sales", "Ú©Ù„ Ø³ÛŒÙ„"), "Rs %.2f".format(totalSales), "#1565C0", "#E3F2FD"))
            resultsBox.addView(summaryCard("ðŸ“ˆ", Loc.t(this@ReportsActivity, "Total Profit", "Ú©Ù„ Ù…Ù†Ø§ÙØ¹"), "Rs %.2f".format(totalProfit), "#2E7D32", "#E8F5E9"))
            resultsBox.addView(summaryCard("ðŸ§¾", Loc.t(this@ReportsActivity, "Total Purchases", "Ú©Ù„ Ø®Ø±ÛŒØ¯Ø§Ø±ÛŒ"), "Rs %.2f".format(totalPurchases), "#EF6C00", "#FFF3E0"))
            resultsBox.addView(summaryCard("ðŸ’¸", Loc.t(this@ReportsActivity, "Total Expenses", "Ú©Ù„ Ø§Ø®Ø±Ø§Ø¬Ø§Øª"), "Rs %.2f".format(totalExpenses), "#C62828", "#FFEBEE"))
            resultsBox.addView(summaryCard("â†©", Loc.t(this@ReportsActivity, "Sale Returns", "Ø³ÛŒÙ„ Ú©ÛŒ ÙˆØ§Ù¾Ø³ÛŒ"), "Rs %.2f".format(totalSaleReturns), "#AD1457", "#FCE4EC"))
            resultsBox.addView(summaryCard("â†©", Loc.t(this@ReportsActivity, "Purchase Returns", "Ø®Ø±ÛŒØ¯Ø§Ø±ÛŒ Ú©ÛŒ ÙˆØ§Ù¾Ø³ÛŒ"), "Rs %.2f".format(totalPurchaseReturns), "#00695C", "#E0F2F1"))
            resultsBox.addView(summaryCard("ðŸ§®", Loc.t(this@ReportsActivity, "Number of Sales", "Ø³ÛŒÙ„Ø² Ú©ÛŒ ØªØ¹Ø¯Ø§Ø¯"), "$saleCount", "#6A1B9A", "#F3E5F5"))

            resultsBox.addView(spacer(10))

            resultsBox.addView(
                profitLossCard(
                    revenue = totalSales,
                    cogs = cogs,
                    grossProfit = grossProfit,
                    expenses = totalExpenses,
                    netProfit = netProfit
                )
            )

            resultsBox.addView(spacer(14))

            resultsBox.addView(listCard(Loc.t(this@ReportsActivity, "Top Products", "Ù¹Ø§Ù¾ Ù¾Ø±ÙˆÚˆÚ©Ù¹Ø³"), topProducts.isEmpty(), Loc.t(this@ReportsActivity, "No sales in this period", "Ø§Ø³ Ù…Ø¯Øª Ù…ÛŒÚº Ú©ÙˆØ¦ÛŒ Ø³ÛŒÙ„ Ù†ÛÛŒÚº ÛÙˆØ¦ÛŒ")) { box ->
                topProducts.forEach { tp -> box.addView(rowText(tp.product, "${tp.totalQty} " + Loc.t(this@ReportsActivity, "sold", "ÙØ±ÙˆØ®Øª Ø´Ø¯Û"))) }
            })

            resultsBox.addView(spacer(14))

            resultsBox.addView(listCard(Loc.t(this@ReportsActivity, "Daily Sales", "Ø±ÙˆØ²Ø§Ù†Û Ø³ÛŒÙ„"), dailySales.isEmpty(), Loc.t(this@ReportsActivity, "No data", "Ú©ÙˆØ¦ÛŒ ÚˆÛŒÙ¹Ø§ Ù†ÛÛŒÚº")) { box ->
                dailySales.forEach { d -> box.addView(rowText(d.day, "Rs %.2f".format(d.total))) }
            })

            resultsBox.addView(spacer(30))
        }
    }

    // ---- UI helpers ----
    private fun summaryCard(emoji: String, label: String, value: String, accentHex: String, tintHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 20, 22, 20)
            background = roundedBg(cardWhite, 20)
            elevation = 4f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }

            addView(FrameLayout(this@ReportsActivity).apply {
                val size = (40 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(lighten(accentHex, 0.85f), Color.parseColor(tintHex))
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    orientation = GradientDrawable.Orientation.TL_BR
                }
                addView(TextView(this@ReportsActivity).apply {
                    text = emoji; textSize = 16f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            })

            val textCol = LinearLayout(this@ReportsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 0, 0, 0)
            }
            textCol.addView(TextView(this@ReportsActivity).apply {
                text = label; setTextColor(Color.parseColor(textMuted)); textSize = 12.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            textCol.addView(TextView(this@ReportsActivity).apply {
                text = value; setTextColor(Color.parseColor(accentHex)); textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 4, 0, 0)
            })
            addView(textCol)
        }
    }

    /**
     * Profit & Loss statement card:
     *   Sales Revenue
     *   (-) Cost of Goods Sold
     *   = Gross Profit
     *   (-) Operating Expenses
     *   = Net Profit / Loss
     */
    private fun profitLossCard(
        revenue: Double,
        cogs: Double,
        grossProfit: Double,
        expenses: Double,
        netProfit: Double
    ): LinearLayout {
        val netColor = if (netProfit >= 0) "#2E7D32" else "#C62828"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 20)
            background = roundedBg(cardWhite, 20)
            elevation = 4f

            val titleRow = LinearLayout(this@ReportsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 14)
            }
            titleRow.addView(TextView(this@ReportsActivity).apply {
                text = "ðŸ“Š  "
                textSize = 15f
            })
            titleRow.addView(TextView(this@ReportsActivity).apply {
                text = Loc.t(this@ReportsActivity, "Profit & Loss Statement", "Ù…Ù†Ø§ÙØ¹ Ø§ÙˆØ± Ù†Ù‚ØµØ§Ù† Ú©Ø§ Ø¨ÛŒØ§Ù†")
                textSize = 15f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(titleRow)

            addView(plRow(Loc.t(this@ReportsActivity, "Sales Revenue", "Ø³ÛŒÙ„ Ú©ÛŒ Ø¢Ù…Ø¯Ù†ÛŒ"), revenue, textDark, bold = false))
            addView(plRow(Loc.t(this@ReportsActivity, "(-) Cost of Goods Sold", "(-) ÙØ±ÙˆØ®Øª Ø´Ø¯Û Ø³Ø§Ù…Ø§Ù† Ú©ÛŒ Ù„Ø§Ú¯Øª"), cogs, "#C62828", bold = false, isDeduction = true))
            addView(plDivider())
            addView(plRow(Loc.t(this@ReportsActivity, "Gross Profit", "Ù…Ø¬Ù…ÙˆØ¹ÛŒ Ù…Ù†Ø§ÙØ¹"), grossProfit, "#1565C0", bold = true))
            addView(spacer(4))
            addView(plRow(Loc.t(this@ReportsActivity, "(-) Operating Expenses", "(-) Ø¢Ù¾Ø±ÛŒÙ¹Ù†Ú¯ Ø§Ø®Ø±Ø§Ø¬Ø§Øª"), expenses, "#C62828", bold = false, isDeduction = true))
            addView(plDivider())
            addView(plRow(if (netProfit >= 0) Loc.t(this@ReportsActivity, "Net Profit", "Ø®Ø§Ù„Øµ Ù…Ù†Ø§ÙØ¹") else Loc.t(this@ReportsActivity, "Net Loss", "Ø®Ø§Ù„Øµ Ù†Ù‚ØµØ§Ù†"), netProfit, netColor, bold = true, big = true))
        }
    }

    private fun plRow(label: String, amount: Double, colorHex: String, bold: Boolean, isDeduction: Boolean = false, big: Boolean = false): LinearLayout {
        val displayAmount = if (isDeduction) "Rs %.2f".format(amount) else "Rs %.2f".format(amount)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
            addView(TextView(this@ReportsActivity).apply {
                text = label
                textSize = if (big) 15f else 13.5f
                setTextColor(if (bold) Color.parseColor(textDark) else Color.parseColor(textMuted))
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@ReportsActivity).apply {
                text = displayAmount
                textSize = if (big) 16f else 13.5f
                setTextColor(Color.parseColor(colorHex))
                setTypeface(typeface, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                gravity = Gravity.END
            })
        }
    }

    private fun plDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor("#EDEEF5"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 8, 0, 8)
            }
        }
    }

    private fun listCard(title: String, isEmpty: Boolean, emptyMsg: String, fill: (LinearLayout) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 16)
            background = roundedBg(cardWhite, 20)
            elevation = 4f

            addView(TextView(this@ReportsActivity).apply {
                text = title; textSize = 15f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 8)
            })
            if (isEmpty) {
                addView(emptyText(emptyMsg))
            } else {
                fill(this)
            }
        }
    }

    private fun rowText(left: String, right: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 10, 4, 10)
            addView(TextView(this@ReportsActivity).apply {
                text = left; textSize = 14f
                setTextColor(Color.parseColor(textDark))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@ReportsActivity).apply {
                text = right; textSize = 14f; gravity = Gravity.END
                setTextColor(Color.parseColor("#1565C0"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
    }

    private fun emptyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(textMuted))
            textSize = 13f
            setPadding(0, 6, 0, 6)
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11.5f
            setTextColor(Color.parseColor(textMuted))
            background = roundedBg(cardWhite, 14)
            setPadding(6, 20, 6, 20)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener { onClick() }
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
        return Color.rgb(r.coerceIn(0,255), g.coerceIn(0,255), bl.coerceIn(0,255))
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }
}
