package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.smallestUnitFactor
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Balance Sheet: Assets = Liabilities + Capital, built entirely from data the
 * app already tracks — nothing new to enter.
 *
 *   ASSETS
 *     Cash in Hand        = all-time cash_transactions IN(cash) - OUT(cash)
 *     Bank Balance         = all-time cash_transactions IN(bank) - OUT(bank)
 *     Stock in Hand (cost) = SUM(product.stock * cost-per-SMALLEST-unit)     [see FIX note below]
 *     Accounts Receivable  = SUM(customer.balance) where balance > 0    [customer owes us]
 *     Advance Paid to Suppliers = SUM(-supplier.balance) where balance < 0
 *
 *   LIABILITIES
 *     Accounts Payable     = SUM(supplier.balance) where balance > 0    [we owe supplier]
 *     Advance from Customers = SUM(-customer.balance) where balance < 0
 *
 *   CAPITAL / EQUITY
 *     Net Profit (all-time) = Total Sales − COGS − Total Expenses       [same formula as ReportsActivity's P&L]
 *     Capital (calculated)  = Total Assets − Total Liabilities − Net Profit
 *       (the balancing figure — this app has no separate "owner's capital injected"
 *        entry point, so whatever isn't accounted for by accumulated profit is shown
 *        here as Capital, and Assets will always equal Liabilities + Capital by
 *        construction)
 *
 * FIX (stock value was showing absurdly large numbers): this screen used to call
 * productDao().stockValueTotal(), a raw SQL SUM(stock*cost). That's wrong because
 * `stock` is stored in the product's SMALLEST unit (e.g. pcs inside a carton) while
 * `cost` is the rate per PRIMARY unit (e.g. Rs per carton) — multiplying them
 * directly overstates value by the unit-conversion factor (sometimes hundreds of
 * times). Stock value is now computed here in Kotlin the same way StockReportActivity
 * already does it correctly: cost is divided by the product's smallestUnitFactor()
 * before multiplying by stock, so it's always a true "cost per smallest unit".
 */
class BalanceSheetActivity : AppCompatActivity() {

    // ================= PREMIUM PALETTE (shared with Items / Categories / Reports) =================
    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val teal = "#0F9B8E"
    private val red = "#E5484D"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private lateinit var resultsBox: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

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

        root.addView(premiumHeader("⚖️", Loc.t(this, "Balance Sheet", "بیلنس شیٹ"), Loc.t(this, "As of today • all-time figures", "آج تک • تمام وقت کے اعداد و شمار")))

        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultsBox)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scroll)

        loadBalanceSheet()
    }

    private fun loadBalanceSheet() {
        resultsBox.removeAllViews()
        lifecycleScope.launch {
            val db = PosDatabase.get(this@BalanceSheetActivity)

            // ---- Assets ----
            val cashInHand = db.cashTransactionDao().totalAll("IN", "cash") - db.cashTransactionDao().totalAll("OUT", "cash")
            val bankBalance = db.cashTransactionDao().totalAll("IN", "bank") - db.cashTransactionDao().totalAll("OUT", "bank")

            // FIX: was db.productDao().stockValueTotal() (raw SQL stock*cost, wrong
            // unit basis — see class doc comment). Now computed per-product with the
            // same cost-per-smallest-unit conversion StockReportActivity uses.
            val allProducts = db.productDao().all().first()
            val stockValue = allProducts.sumOf { p ->
                val factor = p.smallestUnitFactor()
                val costPerSmallestUnit = if (factor > 0) p.cost / factor else p.cost
                p.stock * costPerSmallestUnit
            }

            val receivables = db.customerDao().receivablesTotal()
            val advancePaidToSuppliers = db.supplierDao().advancesPaidTotal()
            val totalAssets = cashInHand + bankBalance + stockValue + receivables + advancePaidToSuppliers

            // ---- Liabilities ----
            val payables = db.supplierDao().payablesTotal()
            val advanceFromCustomers = db.customerDao().advancesReceivedTotal()
            val totalLiabilities = payables + advanceFromCustomers

            // ---- Capital / Equity (same P&L formula as ReportsActivity, all-time) ----
            val now = System.currentTimeMillis()
            val totalSales = db.saleDao().totalSalesBetween(0L, now)
            val cogs = db.saleDao().cogsBetween(0L, now)
            val totalExpenses = db.expenseDao().total()
            val netProfit = (totalSales - cogs) - totalExpenses
            val capital = totalAssets - totalLiabilities - netProfit

            resultsBox.removeAllViews()

            resultsBox.addView(sectionHeader(Loc.t(this@BalanceSheetActivity, "ASSETS", "اثاثے")))
            resultsBox.addView(statementCard {
                addStatementRow(it, Loc.t(this@BalanceSheetActivity, "Cash in Hand", "نقد رقم"), cashInHand)
                addStatementRow(it, Loc.t(this@BalanceSheetActivity, "Bank Balance", "بینک بیلنس"), bankBalance)
                addStatementRow(it, Loc.t(this@BalanceSheetActivity, "Stock in Hand (at cost)", "اسٹاک (لاگت پر)"), stockValue)
                addStatementRow(it, Loc.t(this@BalanceSheetActivity, "Accounts Receivable", "قابل وصول رقم"), receivables)
                if (advancePaidToSuppliers > 0) addStatementRow(it, Loc.t(this@BalanceSheetActivity, "Advance Paid to Suppliers", "سپلائرز کو ایڈوانس"), advancePaidToSuppliers)
                addDivider(it)
                addStatementRow(it, Loc.t(this@BalanceSheetActivity, "Total Assets", "کل اثاثے"), totalAssets, bold = true, big = true)
            })

            resultsBox.addView(spacer(16))

            resultsBox.addView(sectionHeader(Loc.t(this@BalanceSheetActivity, "LIABILITIES", "واجبات")))
            resultsBox.addView(statementCard {
                addStatementRow(it, Loc.t(this@BalanceSheetActivity, "Accounts Payable", "قابل ادائیگی رقم"), payables)
                if (advanceFromCustomers > 0) addStatementRow(it, Loc.t(this@BalanceSheetActivity, "Advance from Customers", "کسٹمرز سے ایڈوانس"), advanceFromCustomers)
                addDivider(it)
                addStatementRow(it, Loc.t(this@BalanceSheetActivity, "Total Liabilities", "کل واجبات"), totalLiabilities, bold = true, big = true)
            })

            resultsBox.addView(spacer(16))

            resultsBox.addView(sectionHeader(Loc.t(this@BalanceSheetActivity, "CAPITAL / EQUITY", "سرمایہ")))
            resultsBox.addView(statementCard {
                addStatementRow(it, Loc.t(this@BalanceSheetActivity, "Net Profit (all-time)", "خالص منافع (تمام وقت)"), netProfit)
                addStatementRow(it, Loc.t(this@BalanceSheetActivity, "Capital (calculated)", "سرمایہ (حساب شدہ)"), capital)
                addDivider(it)
                addStatementRow(it, Loc.t(this@BalanceSheetActivity, "Total Liabilities + Capital", "کل واجبات + سرمایہ"), totalLiabilities + capital + netProfit, bold = true, big = true)
            })

            resultsBox.addView(spacer(10))
            resultsBox.addView(TextView(this@BalanceSheetActivity).apply {
                text = Loc.t(
                    this@BalanceSheetActivity,
                    "Note: Capital is a calculated balancing figure (Total Assets \u2212 Total Liabilities \u2212 Net Profit), since owner's injected capital isn't entered separately in this app.",
                    "نوٹ: سرمایہ ایک حساب شدہ balancing figure ہے، کیونکہ مالک کا لگایا گیا سرمایہ اس ایپ میں الگ سے درج نہیں ہوتا۔"
                )
                textSize = 11.5f
                setTextColor(Color.parseColor(textGray))
                setPadding(6, 4, 6, 20)
            })
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

    // ---- UI helpers (mirrors Reports/StockReport card styling) ----

    private fun statementCard(fill: (LinearLayout) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 16)
            background = strokedBg(border, cardBg, 18)
            applyElevation(this, 2f)
            fill(this)
        }
    }

    private fun addStatementRow(box: LinearLayout, label: String, amount: Double, bold: Boolean = false, big: Boolean = false) {
        val color = if (amount < 0) red else if (bold) primary else textDark
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
            addView(TextView(this@BalanceSheetActivity).apply {
                text = label
                textSize = if (big) 15f else 13.5f
                setTextColor(Color.parseColor(if (bold) textDark else textGray))
                if (bold) setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@BalanceSheetActivity).apply {
                text = "Rs %.2f".format(amount)
                textSize = if (big) 16f else 13.5f
                setTextColor(Color.parseColor(color))
                setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.END
            })
        })
    }

    private fun addDivider(box: LinearLayout) {
        box.addView(View(this).apply {
            setBackgroundColor(Color.parseColor(border))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 8, 0, 8)
            }
        })
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
