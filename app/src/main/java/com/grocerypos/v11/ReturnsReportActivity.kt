package com.grocerypos.v11.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.DayUtil
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.R
import com.grocerypos.v11.ReturnReportRow
import com.grocerypos.v11.money0
import com.grocerypos.v11.ui.components.premiumHeader
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * NEW (Flutter parity, batch 4): Returns Report — port of Flutter's returns_report_screen.dart.
 * Sale returns / Purchase returns for Today / This week / This month / All, grouped per bill:
 * bill total, how much came back, and what is left (net).
 */
class ReturnsReportActivity : AppCompatActivity() {

    private lateinit var c: CuColors
    private lateinit var content: LinearLayout
    private var sale = true
    private var range = "month"

    private fun t(en: String, ur: String) = Loc.t(this, en, ur)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        c = CuColors(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(c.bg))
        }
        root.addView(premiumHeader(iconRes = R.drawable.ic_undo, title = t("Returns Report", "واپسی رپورٹ"),
            subtitle = t("Sale & purchase returns", "سیل اور خریداری کی واپسی")))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        root.addView(cuGap(30))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(c.bg)); addView(root) })
        load()
    }

    private fun rangeStart(now: Long): Long = when (range) {
        "today" -> DayUtil.today(now)
        "week" -> DayUtil.plusDays(now, -6)
        "month" -> Calendar.getInstance().apply { timeInMillis = DayUtil.today(now); set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        else -> 0L
    }

    private fun qty(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

    private fun load(): Job = lifecycleScope.launch {
        val now = System.currentTimeMillis()
        val dao = PosDatabase.get(this@ReturnsReportActivity).reportsExtraDao()
        val start = rangeStart(now)
        val end = Long.MAX_VALUE / 2
        val rows = if (sale) dao.saleReturns(start, end) else dao.purchaseReturns(start, end)
        render(rows)
    }

    private fun render(rows: List<ReturnReportRow>) {
        content.removeAllViews()
        val (s1, r1) = cuChipRow()
        r1.addView(cuChip(t("Sale returns", "سیل واپسی"), sale, c) { sale = true; load() })
        r1.addView(cuChip(t("Purchase returns", "خریداری واپسی"), !sale, c) { sale = false; load() })
        content.addView(s1)
        val (s2, r2) = cuChipRow()
        for ((k, l) in listOf("today" to t("Today", "آج"), "week" to t("7 days", "7 دن"), "month" to t("This month", "اس مہینے"), "all" to t("All", "سب")))
            r2.addView(cuChip(l, range == k, c) { range = k; load() })
        content.addView(s2)

        val total = rows.sumOf { it.amount }
        val groups = rows.groupBy { it.reference }
        val sum = cuCard(c)
        sum.addView(cuText(t("Total returned", "کل واپسی"), 12f, c.muted))
        sum.addView(cuText(money0(total), 22f, c.red, true))
        sum.addView(cuText("${groups.size} " + t("bills", "بل") + "  •  ${rows.size} " + t("items", "آئٹمز"), 12f, c.muted))
        content.addView(sum)

        if (rows.isEmpty()) {
            content.addView(cuText(t("No returns in this period.", "اس عرصے میں کوئی واپسی نہیں۔"), 13f, c.muted).apply { gravity = Gravity.CENTER; setPadding(0, cuDp(40), 0, cuDp(40)) })
            return
        }
        for ((ref, items) in groups) {
            val f = items.first()
            val card = cuCard(c)
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            col.addView(cuText(f.partyName ?: "—", 14f, c.text, true))
            col.addView(cuText("#$ref  •  " + DayUtil.fmt(items.maxOf { it.createdAt }) +
                (if (f.billStatus == "returned") "  •  " + t("FULL RETURN", "مکمل واپسی") else ""), 11.5f, c.muted))
            head.addView(col)
            val back = items.sumOf { it.amount }
            head.addView(cuText("-" + money0(back), 14f, c.red, true))
            card.addView(head)
            for (it in items) {
                val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, cuDp(4), 0, 0) }
                l.addView(cuText("• " + (it.itemName ?: "") + "  × " + qty(it.qty) + (if (!it.unit.isNullOrBlank()) " ${it.unit}" else ""), 12f, c.text).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                l.addView(cuText(money0(it.amount), 12f, c.muted))
                card.addView(l)
            }
            val billTotal = f.billTotal ?: 0.0
            if (billTotal > 0.009) {
                card.addView(cuText(t("Bill ", "بل ") + money0(billTotal) + "   " + t("Net ", "باقی ") + money0((billTotal - back).coerceAtLeast(0.0)), 11.5f, c.teal, true).apply { setPadding(0, cuDp(6), 0, 0) })
            }
            content.addView(card)
        }
    }
}
