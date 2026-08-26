package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch

/**
 * Balance Sheet: Assets = Liabilities + Capital, built entirely from data the
 * app already tracks — nothing new to enter.
 *
 *   ASSETS
 *     Cash in Hand        = all-time cash_transactions IN(cash) - OUT(cash)
 *     Bank Balance         = all-time cash_transactions IN(bank) - OUT(bank)
 *     Stock in Hand (cost) = SUM(product.stock * product.cost)          [products.stockValueTotal()]
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
 * Stock is valued at cost (the same weighted-average `cost` PurchaseActivity/
 * SaleActivity already maintain per product), never at sale price — that keeps
 * this statement consistent with the COGS figure used in the P&L report.
 */
class BalanceSheetActivity : AppCompatActivity() {

    private val bg = "#F3F4F9"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#1A1D2E"
    private val textMuted = "#8A8FA3"

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
            text = Loc.t(this@BalanceSheetActivity, "Balance Sheet", "بیلنس شیٹ")
            textSize = 21f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(headerRow)

        root.addView(TextView(this).apply {
            text = Loc.t(this@BalanceSheetActivity, "As of today \u2022 all-time figures", "آج تک \u2022 تمام وقت کے اعداد و شمار")
            textSize = 12.5f
            setTextColor(Color.parseColor(textMuted))
            setPadding(4, 0, 0, 16)
        })

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
            val stockValue = db.productDao().stockValueTotal()
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
                setTextColor(Color.parseColor(textMuted))
                setPadding(6, 4, 6, 20)
            })
        }
    }

    // ---- UI helpers (mirrors ReportsActivity's card styling) ----

    private fun statementCard(fill: (LinearLayout) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 16)
            background = roundedBg(cardWhite, 20)
            elevation = 4f
            fill(this)
        }
    }

    private fun addStatementRow(box: LinearLayout, label: String, amount: Double, bold: Boolean = false, big: Boolean = false) {
        val color = if (amount < 0) "#C62828" else if (bold) "#1565C0" else textDark
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
            addView(TextView(this@BalanceSheetActivity).apply {
                text = label
                textSize = if (big) 15f else 13.5f
                setTextColor(Color.parseColor(if (bold) textDark else textMuted))
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@BalanceSheetActivity).apply {
                text = "Rs %.2f".format(amount)
                textSize = if (big) 16f else 13.5f
                setTextColor(Color.parseColor(color))
                setTypeface(typeface, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                gravity = Gravity.END
            })
        })
    }

    private fun addDivider(box: LinearLayout) {
        box.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#EDEEF5"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 8, 0, 8)
            }
        })
    }

    private fun sectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(4, 0, 0, 8)
        }
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }
}
