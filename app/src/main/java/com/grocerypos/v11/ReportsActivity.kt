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
import kotlinx.coroutines.launch
import java.util.Calendar

class ReportsActivity : AppCompatActivity() {

    private lateinit var resultsBox: LinearLayout
    private var periodLabel: TextView? = null

    // period range currently selected, defaults to Today
    private var rangeStart: Long = 0
    private var rangeEnd: Long = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Reports"
            textSize = 24f
            setPadding(0, 0, 0, 24)
        })

        root.addView(Button(this).apply {
            text = "VIEW SALE / PURCHASE HISTORY"
            setOnClickListener {
                startActivity(android.content.Intent(this@ReportsActivity, HistoryActivity::class.java))
            }
        })

        // ---- Period filter buttons ----
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        filterRow.addView(smallButton("Today") { setRangeToday(); loadReport() })
        filterRow.addView(smallButton("This Week") { setRangeThisWeek(); loadReport() })
        filterRow.addView(smallButton("This Month") { setRangeThisMonth(); loadReport() })
        filterRow.addView(smallButton("All Time") { setRangeAllTime(); loadReport() })
        root.addView(filterRow)

        periodLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, 16, 0, 16)
        }
        root.addView(periodLabel)

        root.addView(divider())

        resultsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(resultsBox)

        val scroll = ScrollView(this).apply { addView(root) }
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
        periodLabel?.text = "Showing: Today"
    }

    private fun setRangeThisWeek() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis
        rangeEnd = System.currentTimeMillis()
        periodLabel?.text = "Showing: This Week"
    }

    private fun setRangeThisMonth() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis
        rangeEnd = System.currentTimeMillis()
        periodLabel?.text = "Showing: This Month"
    }

    private fun setRangeAllTime() {
        rangeStart = 0L
        rangeEnd = System.currentTimeMillis()
        periodLabel?.text = "Showing: All Time"
    }

    // ---- Load and display ----
    private fun loadReport() {
        resultsBox.removeAllViews()
        resultsBox.addView(TextView(this).apply { text = "Loading..." })

        lifecycleScope.launch {
            val db = PosDatabase.get(this@ReportsActivity)

            val totalSales = db.saleDao().totalSalesBetween(rangeStart, rangeEnd)
            val totalProfit = db.saleDao().profitBetween(rangeStart, rangeEnd)
            val totalPurchases = db.purchaseDao().totalBetween(rangeStart, rangeEnd)
            val totalExpenses = db.expenseDao().totalBetween(rangeStart, rangeEnd)
            val saleCount = db.saleDao().countBetween(rangeStart, rangeEnd)
            val topProducts = db.saleDao().topProducts(rangeStart, rangeEnd)
            val dailySales = db.saleDao().dailySales(rangeStart, rangeEnd)

            resultsBox.removeAllViews()

            // Summary cards
            resultsBox.addView(summaryCard("Total Sales", "Rs %.2f".format(totalSales), "#1565C0"))
            resultsBox.addView(summaryCard("Total Profit", "Rs %.2f".format(totalProfit), "#2E7D32"))
            resultsBox.addView(summaryCard("Total Purchases", "Rs %.2f".format(totalPurchases), "#EF6C00"))
            resultsBox.addView(summaryCard("Total Expenses", "Rs %.2f".format(totalExpenses), "#C62828"))
            resultsBox.addView(summaryCard("Number of Sales", "$saleCount", "#6A1B9A"))

            resultsBox.addView(divider())

            // Top products
            resultsBox.addView(sectionTitle("Top Products"))
            if (topProducts.isEmpty()) {
                resultsBox.addView(emptyText("Is period mein koi sale nahi hui"))
            } else {
                topProducts.forEach { tp ->
                    resultsBox.addView(rowText("${tp.product}", "${tp.totalQty} sold"))
                }
            }

            resultsBox.addView(divider())

            // Daily breakdown
            resultsBox.addView(sectionTitle("Daily Sales"))
            if (dailySales.isEmpty()) {
                resultsBox.addView(emptyText("Koi data nahi"))
            } else {
                dailySales.forEach { d ->
                    resultsBox.addView(rowText(d.day, "Rs %.2f".format(d.total)))
                }
            }
        }
    }

    // ---- UI helpers ----
    private fun summaryCard(label: String, value: String, colorHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            background = roundedBackground(colorHex, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
            addView(TextView(this@ReportsActivity).apply {
                text = label; setTextColor(Color.WHITE); textSize = 14f
            })
            addView(TextView(this@ReportsActivity).apply {
                text = value; setTextColor(Color.WHITE); textSize = 22f
            })
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, 16, 0, 8)
        }
    }

    private fun rowText(left: String, right: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 12, 8, 12)
            addView(TextView(this@ReportsActivity).apply {
                text = left; textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@ReportsActivity).apply {
                text = right; textSize = 15f; gravity = Gravity.END
            })
        }
    }

    private fun emptyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.GRAY)
            setPadding(8, 8, 8, 8)
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBackground("#37474F", 12)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun roundedBackground(colorHex: String, cornerRadius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            this.cornerRadius = cornerRadius.toFloat()
        }
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(0xFFDDDDDD.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { setMargins(0, 16, 0, 16) }
        }
    }
}
