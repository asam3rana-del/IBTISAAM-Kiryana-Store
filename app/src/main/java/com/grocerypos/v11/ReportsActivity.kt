package com.grocerypos.v11.ui

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
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.util.Calendar

class ReportsActivity : AppCompatActivity() {

    // ================= PREMIUM PALETTE (shared with Items / Categories) =================
    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val purple = "#8B5CF6"
    private val amber = "#F5A524"
    private val teal = "#0F9B8E"
    private val gold = "#C9A24B"
    private val red = "#E5484D"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private lateinit var resultsBox: LinearLayout
    private var periodLabel: TextView? = null
    private val filterButtons = mutableListOf<TextView>()

    private var rangeStart: Long = 0
    private var rangeEnd: Long = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        // FIX (Phase 4 - Security): role check enforced here — MainActivity only hides
        // the "Reports" tile from cashiers, it doesn't stop them opening this Activity
        // another way.
        val myRole = getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        if (myRole != "admin" && myRole != "manager") {
            Toast.makeText(this, "Sirf Admin/Manager is screen ko access kar sakte hain", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(premiumHeader("📊", Loc.t(this, "Reports", "رپورٹس"), Loc.t(this, "Sales, stock & financial overview", "سیل، اسٹاک اور مالیاتی جائزہ")))

        root.addView(sectionHeader(Loc.t(this, "Sale reports", "سیل رپورٹس")))
        val navCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = strokedBg(border, cardBg, 18)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, 18) }
            applyElevation(this, 3f)
        }
        navCard.addView(navRow(
            icon = "🧾", accentHex = primary, tintHex = "#E9E6FF",
            title = Loc.t(this, "Sale / Purchase History", "سیل / خریداری کی تاریخ"),
            subtitle = Loc.t(this, "View all transactions", "تمام لین دین دیکھیں")
        ) { startActivity(android.content.Intent(this@ReportsActivity, HistoryActivity::class.java)) })

        navCard.addView(navDivider())

        navCard.addView(navRow(
            icon = "👥", accentHex = purple, tintHex = "#F0EBFF",
            title = Loc.t(this, "Party Reports", "پارٹی رپورٹس"),
            subtitle = Loc.t(this, "Customer & supplier balances", "کسٹمر اور سپلائر کا بیلنس")
        ) { startActivity(android.content.Intent(this@ReportsActivity, PartyReportsActivity::class.java)) })

        navCard.addView(navDivider())

        navCard.addView(navRow(
            icon = "📦", accentHex = amber, tintHex = "#FFF3E0",
            title = Loc.t(this, "Stock Report", "اسٹاک رپورٹ"),
            subtitle = Loc.t(this, "Current inventory levels", "موجودہ انوینٹری کی سطح")
        ) { startActivity(android.content.Intent(this@ReportsActivity, StockReportActivity::class.java)) })

        navCard.addView(navDivider())

        // ADDED (Inventory Accounting upgrade — stock_movements table): per-product
        // ledger of every purchase/sale/reversal/edit, and a cost-only view of the
        // same ledger — both backed by StockMovementActivity (see that file).
        navCard.addView(navRow(
            icon = "📜", accentHex = primary, tintHex = "#E9E6FF",
            title = Loc.t(this, "Stock History", "اسٹاک کی تاریخ"),
            subtitle = Loc.t(this, "Every purchase, sale & adjustment per item", "ہر آئٹم کی خریداری، سیل اور ایڈجسٹمنٹ")
        ) {
            val i = android.content.Intent(this@ReportsActivity, StockMovementActivity::class.java)
            i.putExtra(StockMovementActivity.EXTRA_MODE, StockMovementActivity.MODE_STOCK)
            startActivity(i)
        })

        navCard.addView(navDivider())

        navCard.addView(navRow(
            icon = "📈", accentHex = teal, tintHex = "#E0F2F1",
            title = Loc.t(this, "Cost History", "لاگت کی تاریخ"),
            subtitle = Loc.t(this, "How a product's cost changed over time", "پروڈکٹ کی لاگت وقت کے ساتھ کیسے بدلی")
        ) {
            val i = android.content.Intent(this@ReportsActivity, StockMovementActivity::class.java)
            i.putExtra(StockMovementActivity.EXTRA_MODE, StockMovementActivity.MODE_COST)
            startActivity(i)
        })

        navCard.addView(navDivider())

        navCard.addView(navRow(
            icon = "⚖️", accentHex = teal, tintHex = "#E0F2F1",
            title = Loc.t(this, "Balance Sheet", "بیلنس شیٹ"),
            subtitle = Loc.t(this, "Assets, liabilities & capital", "اثاثے، واجبات اور سرمایہ")
        ) { startActivity(android.content.Intent(this@ReportsActivity, BalanceSheetActivity::class.java)) })

        navCard.addView(navDivider())

        // NEW: Zakat tracker — Ramadan-to-Ramadan year, auto-calculated from the same
        // asset figures Balance Sheet uses, with monthly-installment support.
        navCard.addView(navRow(
            icon = "\u262A", accentHex = gold, tintHex = "#F4EEDD",
            title = Loc.t(this, "Zakat", "زکوٰۃ"),
            subtitle = Loc.t(this, "Track & pay this year's Zakat", "اس سال کی زکوٰۃ ٹریک اور ادا کریں")
        ) { startActivity(android.content.Intent(this@ReportsActivity, ZakatActivity::class.java)) })

        root.addView(navCard)

        // ================= PERIOD FILTER PILLS (matches Items tab-row style) =================
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = strokedBg(border, cardBg, 14)
            setPadding(6, 6, 6, 6)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, 4) }
        }
        val today = filterPill(Loc.t(this, "Today", "آج")) { setRangeToday(); loadReport() }
        val week = filterPill(Loc.t(this, "This Week", "اس ہفتے")) { setRangeThisWeek(); loadReport() }
        val month = filterPill(Loc.t(this, "This Month", "اس مہینے")) { setRangeThisMonth(); loadReport() }
        val all = filterPill(Loc.t(this, "All Time", "تمام وقت")) { setRangeAllTime(); loadReport() }
        filterButtons.addAll(listOf(today, week, month, all))
        filterRow.addView(today)
        filterRow.addView(week)
        filterRow.addView(month)
        filterRow.addView(all)
        root.addView(filterRow)

        periodLabel = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor(textGray))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(6, 14, 0, 6)
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
        periodLabel?.text = Loc.t(this, "SHOWING: TODAY", "دکھایا جا رہا ہے: آج")
        highlightFilter(0)
    }

    private fun setRangeThisWeek() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis
        rangeEnd = System.currentTimeMillis()
        periodLabel?.text = Loc.t(this, "SHOWING: THIS WEEK", "دکھایا جا رہا ہے: اس ہفتے")
        highlightFilter(1)
    }

    private fun setRangeThisMonth() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis
        rangeEnd = System.currentTimeMillis()
        periodLabel?.text = Loc.t(this, "SHOWING: THIS MONTH", "دکھایا جا رہا ہے: اس مہینے")
        highlightFilter(2)
    }

    private fun setRangeAllTime() {
        rangeStart = 0L
        rangeEnd = System.currentTimeMillis()
        periodLabel?.text = Loc.t(this, "SHOWING: ALL TIME", "دکھایا جا رہا ہے: تمام وقت")
        highlightFilter(3)
    }

    private fun highlightFilter(activeIndex: Int) {
        filterButtons.forEachIndexed { i, btn ->
            if (i == activeIndex) {
                btn.background = roundedBg(primary, 10)
                btn.setTextColor(Color.WHITE)
            } else {
                btn.setBackgroundColor(Color.TRANSPARENT)
                btn.setTextColor(Color.parseColor(textGray))
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

            resultsBox.addView(summaryCard("💰", Loc.t(this@ReportsActivity, "Total Sales", "کل سیل"), "Rs %.2f".format(totalSales), primary, "#E9E6FF"))
            resultsBox.addView(summaryCard("📈", Loc.t(this@ReportsActivity, "Total Profit", "کل منافع"), "Rs %.2f".format(totalProfit), teal, "#E0F2F1"))
            resultsBox.addView(summaryCard("🧾", Loc.t(this@ReportsActivity, "Total Purchases", "کل خریداری"), "Rs %.2f".format(totalPurchases), amber, "#FFF3E0"))
            resultsBox.addView(summaryCard("💸", Loc.t(this@ReportsActivity, "Total Expenses", "کل اخراجات"), "Rs %.2f".format(totalExpenses), red, "#FDE8E8"))
            resultsBox.addView(summaryCard("↩", Loc.t(this@ReportsActivity, "Sale Returns", "سیل کی واپسی"), "Rs %.2f".format(totalSaleReturns), "#AD1457", "#FCE4EC"))
            resultsBox.addView(summaryCard("↩", Loc.t(this@ReportsActivity, "Purchase Returns", "خریداری کی واپسی"), "Rs %.2f".format(totalPurchaseReturns), teal, "#E0F2F1"))
            resultsBox.addView(summaryCard("🧮", Loc.t(this@ReportsActivity, "Number of Sales", "سیلز کی تعداد"), "$saleCount", purple, "#F0EBFF"))

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

            resultsBox.addView(listCard(Loc.t(this@ReportsActivity, "Top Products", "ٹاپ پروڈکٹس"), topProducts.isEmpty(), Loc.t(this@ReportsActivity, "No sales in this period", "اس مدت میں کوئی سیل نہیں ہوئی")) { box ->
                topProducts.forEach { tp -> box.addView(rowText(tp.product, "${tp.totalQty} " + Loc.t(this@ReportsActivity, "sold", "فروخت شدہ"))) }
            })

            resultsBox.addView(spacer(14))

            resultsBox.addView(listCard(Loc.t(this@ReportsActivity, "Daily Sales", "روزانہ سیل"), dailySales.isEmpty(), Loc.t(this@ReportsActivity, "No data", "کوئی ڈیٹا نہیں")) { box ->
                dailySales.forEach { d -> box.addView(rowText(d.day, "Rs %.2f".format(d.total))) }
            })

            resultsBox.addView(spacer(30))
        }
    }

    // ================= PREMIUM HEADER (matches Items/Categories) =================
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

    // ---- UI helpers ----
    private fun summaryCard(emoji: String, label: String, value: String, accentHex: String, tintHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 20, 22, 20)
            background = strokedBg(border, cardBg, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
            applyElevation(this, 2f)

            addView(FrameLayout(this@ReportsActivity).apply {
                val size = (40 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(tintHex))
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
                text = label; setTextColor(Color.parseColor(textGray)); textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
            })
            textCol.addView(TextView(this@ReportsActivity).apply {
                text = value; setTextColor(Color.parseColor(accentHex)); textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
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
        val netColor = if (netProfit >= 0) teal else red
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 20)
            background = strokedBg(border, cardBg, 18)
            applyElevation(this, 2f)

            val titleRow = LinearLayout(this@ReportsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 14)
            }
            titleRow.addView(TextView(this@ReportsActivity).apply {
                text = "📊  "
                textSize = 15f
            })
            titleRow.addView(TextView(this@ReportsActivity).apply {
                text = Loc.t(this@ReportsActivity, "Profit & Loss Statement", "منافع اور نقصان کا بیان")
                textSize = 15f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(titleRow)

            addView(plRow(Loc.t(this@ReportsActivity, "Sales Revenue", "سیل کی آمدنی"), revenue, textDark, bold = false))
            addView(plRow(Loc.t(this@ReportsActivity, "(-) Cost of Goods Sold", "(-) فروخت شدہ سامان کی لاگت"), cogs, red, bold = false, isDeduction = true))
            addView(plDivider())
            addView(plRow(Loc.t(this@ReportsActivity, "Gross Profit", "مجموعی منافع"), grossProfit, primary, bold = true))
            addView(spacer(4))
            addView(plRow(Loc.t(this@ReportsActivity, "(-) Operating Expenses", "(-) آپریٹنگ اخراجات"), expenses, red, bold = false, isDeduction = true))
            addView(plDivider())
            addView(plRow(if (netProfit >= 0) Loc.t(this@ReportsActivity, "Net Profit", "خالص منافع") else Loc.t(this@ReportsActivity, "Net Loss", "خالص نقصان"), netProfit, netColor, bold = true, big = true))
        }
    }

    private fun plRow(label: String, amount: Double, colorHex: String, bold: Boolean, isDeduction: Boolean = false, big: Boolean = false): LinearLayout {
        val displayAmount = "Rs %.2f".format(amount)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
            addView(TextView(this@ReportsActivity).apply {
                text = label
                textSize = if (big) 15f else 13.5f
                setTextColor(if (bold) Color.parseColor(textDark) else Color.parseColor(textGray))
                if (bold) setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@ReportsActivity).apply {
                text = displayAmount
                textSize = if (big) 16f else 13.5f
                setTextColor(Color.parseColor(colorHex))
                setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.END
            })
        }
    }

    private fun plDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor(border))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 8, 0, 8)
            }
        }
    }

    private fun listCard(title: String, isEmpty: Boolean, emptyMsg: String, fill: (LinearLayout) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 16)
            background = strokedBg(border, cardBg, 18)
            applyElevation(this, 2f)

            addView(TextView(this@ReportsActivity).apply {
                text = title; textSize = 15f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, Typeface.BOLD)
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
                setTextColor(Color.parseColor(primary))
                setTypeface(typeface, Typeface.BOLD)
            })
        }
    }

    private fun emptyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(textGray))
            textSize = 13f
            setPadding(0, 6, 0, 6)
        }
    }

    private fun sectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 12.5f
            setTextColor(Color.parseColor(textGray))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(4, 0, 0, 10)
        }
    }

    // ---- Nav row (Sale/Purchase History, Party Reports, Stock Report, Balance Sheet) ----
    private fun navRow(
        icon: String,
        accentHex: String,
        tintHex: String,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 18, 20, 18)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(FrameLayout(this@ReportsActivity).apply {
                val size = (42 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(tintHex))
                }
                addView(TextView(this@ReportsActivity).apply {
                    text = icon; textSize = 17f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            })

            val textCol = LinearLayout(this@ReportsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 0, 8, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this@ReportsActivity).apply {
                text = title
                textSize = 14.5f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, Typeface.BOLD)
            })
            textCol.addView(TextView(this@ReportsActivity).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(Color.parseColor(textGray))
                setPadding(0, 3, 0, 0)
            })
            addView(textCol)

            addView(TextView(this@ReportsActivity).apply {
                text = "\u203A"
                textSize = 18f
                setTextColor(Color.parseColor(accentHex))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(8, 0, 4, 0)
            })
        }
    }

    private fun navDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor(border))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(20, 0, 20, 0)
            }
        }
    }

    private fun filterPill(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    // ================= SHARED UI HELPERS (matches Items/Categories) =================
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
