package com.grocerypos.v11.ui

import android.app.DatePickerDialog
import android.content.Intent
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class DayBookEntry(
    val time: Long,
    val icon: String,
    val title: String,
    val subtitle: String,
    val amount: Double,
    val isInflow: Boolean,
    val refType: String? = null,   // "sale" | "purchase" — used for tap-to-open
    val refId: String? = null
)

class DayBookActivity : AppCompatActivity() {

    private val bg = "#F3F4F9"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#1A1D2E"
    private val textMuted = "#8A8FA3"
    private val green = "#2E7D32"
    private val red = "#C62828"
    private val teal = "#0F9B8E"
    private val navy = "#101B33"
    private val navyLight = "#1C2C4F"
    private val amber = "#C9A24B"

    private lateinit var dateValueText: TextView
    private lateinit var summaryRow: LinearLayout
    private lateinit var netText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyText: TextView

    private var dayMillis: Long = System.currentTimeMillis()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ---- Header ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 40, 22, 26)
            background = gradientBg(navy, navyLight)
        }
        header.addView(TextView(this).apply {
            text = Loc.t(this@DayBookActivity, "Day Book", "روزنامچہ")
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "\u2039"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = ovalBg("#22FFFFFF")
            val px = (36 * resources.displayMetrics.density).toInt(); width = px; height = px
            setOnClickListener { shiftDay(-1) }
        })
        header.addView(spacer(10).apply { layoutParams = LinearLayout.LayoutParams((10 * resources.displayMetrics.density).toInt(), 1) })
        header.addView(TextView(this).apply {
            text = "\u203A"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = ovalBg("#22FFFFFF")
            val px = (36 * resources.displayMetrics.density).toInt(); width = px; height = px
            setOnClickListener { shiftDay(1) }
        })
        root.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 0)
        }

        // ---- Date chip ----
        val dateChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 14, 22, 14)
            background = strokedBg("#E6E8F0", cardWhite, 30)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 18) }
            setOnClickListener { openDatePicker() }
        }
        dateChip.addView(TextView(this).apply { text = "\uD83D\uDCC5  "; textSize = 14f })
        dateValueText = TextView(this).apply {
            text = formatDate(dayMillis)
            textSize = 14f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        dateChip.addView(dateValueText)
        dateChip.addView(TextView(this).apply { text = "  \u203A"; textSize = 13f; setTextColor(Color.parseColor(teal)) })
        body.addView(dateChip)

        // ---- Summary cards row (wraps to 2 rows via nested layout) ----
        summaryRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(summaryRow)
        body.addView(spacer(10))

        // ---- Net total banner ----
        val netCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 18, 22, 18)
            background = roundedBg(cardWhite, 18)
            elevation = 3f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 22) }
        }
        netCard.addView(TextView(this).apply {
            text = Loc.t(this@DayBookActivity, "Net Cash Flow (Today)", "خالص کیش فلو (آج)")
            textSize = 12.5f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        netText = TextView(this).apply {
            text = "Rs 0.00"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        netCard.addView(netText)
        body.addView(netCard)

        body.addView(sectionLabel(Loc.t(this, "TRANSACTIONS", "لین دین")))
        body.addView(spacer(10))

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listContainer)

        emptyText = TextView(this).apply {
            text = Loc.t(this@DayBookActivity, "No transactions on this day", "اس دن کوئی لین دین نہیں")
            setTextColor(Color.parseColor(textMuted))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 40)
            visibility = View.GONE
        }
        body.addView(emptyText)
        body.addView(spacer(30))

        root.addView(body)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadDay()
    }

    private fun shiftDay(delta: Int) {
        val cal = Calendar.getInstance().apply { timeInMillis = dayMillis }
        cal.add(Calendar.DAY_OF_MONTH, delta)
        dayMillis = cal.timeInMillis
        dateValueText.text = formatDate(dayMillis)
        loadDay()
    }

    private fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = dayMillis }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            dayMillis = cal.timeInMillis
            dateValueText.text = formatDate(dayMillis)
            loadDay()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun dayBounds(millis: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        return Pair(start, start + 24 * 60 * 60 * 1000L)
    }

    private fun loadDay() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@DayBookActivity)
            val (start, end) = dayBounds(dayMillis)

            val sales = db.saleDao().salesBetween(start, end)
            val purchases = db.purchaseDao().purchasesBetween(start, end)
            val expenses = db.expenseDao().between(start, end)
            // Only manual cash entries (no reference) — sale/purchase payments are
            // already represented by the sale/purchase rows below, via their "Paid" line.
            val cashTx = db.cashTransactionDao().between(start, end).filter { it.reference.isBlank() }

            val entries = mutableListOf<DayBookEntry>()

            sales.forEach { s ->
                val statusNote = if (s.status == "returned") " (RETURNED)" else if (s.paid < s.total) " • Due Rs %.0f".format(s.total - s.paid) else ""
                entries.add(
                    DayBookEntry(
                        time = s.createdAt,
                        icon = "\uD83D\uDED2",
                        title = Loc.t(this@DayBookActivity, "Sale", "سیل") + " \u2022 ${s.customerName}",
                        subtitle = (Loc.t(this@DayBookActivity, "Paid", "ادا شدہ") + ": Rs %.0f".format(s.paid)) + statusNote,
                        amount = s.total,
                        isInflow = true,
                        refType = "sale",
                        refId = s.invoice
                    )
                )
            }
            purchases.forEach { p ->
                val statusNote = if (p.status == "returned") " (RETURNED)" else if (p.paid < p.total) " • Due Rs %.0f".format(p.total - p.paid) else ""
                entries.add(
                    DayBookEntry(
                        time = p.createdAt,
                        icon = "\uD83D\uDCE6",
                        title = Loc.t(this@DayBookActivity, "Purchase", "خریداری") + " \u2022 ${p.supplierName}",
                        subtitle = (Loc.t(this@DayBookActivity, "Paid", "ادا شدہ") + ": Rs %.0f".format(p.paid)) + statusNote,
                        amount = p.total,
                        isInflow = false,
                        refType = "purchase",
                        refId = p.billNo
                    )
                )
            }
            expenses.forEach { e ->
                entries.add(
                    DayBookEntry(
                        time = e.createdAt,
                        icon = "\uD83D\uDCB8",
                        title = e.category,
                        subtitle = e.description.ifBlank { Loc.t(this@DayBookActivity, "Expense", "خرچہ") },
                        amount = e.amount,
                        isInflow = false
                    )
                )
            }
            cashTx.forEach { c ->
                entries.add(
                    DayBookEntry(
                        time = c.createdAt,
                        icon = if (c.type == "IN") "\uD83D\uDCB0" else "\uD83D\uDCB8",
                        title = (if (c.type == "IN") Loc.t(this@DayBookActivity, "Cash In", "کیش ان") else Loc.t(this@DayBookActivity, "Cash Out", "کیش آؤٹ")) + " \u2022 ${c.method.uppercase()}",
                        subtitle = c.reason.ifBlank { Loc.t(this@DayBookActivity, "Manual entry", "دستی اندراج") },
                        amount = c.amount,
                        isInflow = c.type == "IN"
                    )
                )
            }

            entries.sortByDescending { it.time }

            val totalSales = sales.filter { it.status != "returned" }.sumOf { it.total }
            val totalPurchases = purchases.filter { it.status != "returned" }.sumOf { it.total }
            val totalExpenses = expenses.sumOf { it.amount }
            val cashIn = cashTx.filter { it.type == "IN" }.sumOf { it.amount } + sales.filter { it.status != "returned" }.sumOf { it.paid }
            val cashOut = cashTx.filter { it.type == "OUT" }.sumOf { it.amount } + purchases.filter { it.status != "returned" }.sumOf { it.paid }
            val net = cashIn - cashOut - totalExpenses

            renderSummary(totalSales, totalPurchases, totalExpenses, cashIn, cashOut)
            netText.text = "Rs %.2f".format(net)
            netText.setTextColor(Color.parseColor(if (net >= 0) green else red))

            renderList(entries)
        }
    }

    private fun renderSummary(sales: Double, purchases: Double, expenses: Double, cashIn: Double, cashOut: Double) {
        summaryRow.removeAllViews()
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(statCard("\uD83D\uDED2", Loc.t(this, "Sales", "سیل"), sales, green).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 10) }
        })
        row1.addView(statCard("\uD83D\uDCE6", Loc.t(this, "Purchases", "خریداری"), purchases, "#C77B00").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 10) }
        })
        summaryRow.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(statCard("\uD83D\uDCB0", Loc.t(this, "Cash In", "کیش ان"), cashIn, green).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 10) }
        })
        row2.addView(statCard("\uD83D\uDCB8", Loc.t(this, "Cash Out", "کیش آؤٹ"), cashOut + expenses, red).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 10) }
        })
        summaryRow.addView(row2)
    }

    private fun statCard(emoji: String, label: String, value: Double, accentHex: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = roundedBg(cardWhite, 16)
            elevation = 2f
        }
        card.addView(TextView(this).apply {
            text = "$emoji  $label"
            textSize = 11.5f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "Rs %.0f".format(value)
            textSize = 16f
            setTextColor(Color.parseColor(accentHex))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 6, 0, 0)
        })
        return card
    }

    private fun renderList(entries: List<DayBookEntry>) {
        listContainer.removeAllViews()
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        for (e in entries) {
            listContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18, 16, 18, 16)
                background = roundedBg(cardWhite, 16)
                elevation = 2f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
                if (e.refType != null && e.refId != null) {
                    setOnClickListener {
                        val intent = if (e.refType == "sale")
                            Intent(this@DayBookActivity, SaleActivity::class.java).putExtra(SaleActivity.EXTRA_INVOICE, e.refId)
                        else
                            Intent(this@DayBookActivity, PurchaseActivity::class.java).putExtra(PurchaseActivity.EXTRA_BILL_NO, e.refId)
                        startActivity(intent)
                    }
                }

                addView(TextView(this@DayBookActivity).apply {
                    text = e.icon
                    textSize = 18f
                    gravity = Gravity.CENTER
                    background = ovalBg(if (e.isInflow) "#E8F5E9" else "#FFEBEE")
                    val px = (40 * resources.displayMetrics.density).toInt(); width = px; height = px
                })

                val infoCol = LinearLayout(this@DayBookActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 0, 8, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                infoCol.addView(TextView(this@DayBookActivity).apply {
                    text = e.title
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(textDark))
                })
                infoCol.addView(TextView(this@DayBookActivity).apply {
                    text = e.subtitle
                    textSize = 11.5f
                    setTextColor(Color.parseColor(textMuted))
                    setPadding(0, 3, 0, 0)
                })
                infoCol.addView(TextView(this@DayBookActivity).apply {
                    text = fmt.format(Date(e.time))
                    textSize = 10.5f
                    setTextColor(Color.parseColor(textMuted))
                    setPadding(0, 3, 0, 0)
                })
                addView(infoCol)

                addView(TextView(this@DayBookActivity).apply {
                    text = (if (e.isInflow) "+ " else "- ") + "Rs %.0f".format(e.amount)
                    setTextColor(Color.parseColor(if (e.isInflow) green else red))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    textSize = 14f
                })
            })
        }
    }

    // ---- UI helpers ----
    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor(textMuted))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.04f
    }
    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = radius.toFloat() }
    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex)); setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex)); cornerRadius = radius.toFloat()
    }
    private fun ovalBg(colorHex: String) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(colorHex)) }
    private fun gradientBg(startHex: String, endHex: String) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
    )
    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }
    private fun formatDate(millis: Long) = SimpleDateFormat("dd MMM yyyy, EEEE", Locale.getDefault()).format(Date(millis))
}
