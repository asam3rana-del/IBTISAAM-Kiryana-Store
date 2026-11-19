package com.grocerypos.v11.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.DayUtil
import com.grocerypos.v11.Expiry
import com.grocerypos.v11.ExpiryRow
import com.grocerypos.v11.ExpiryStatus
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.R
import com.grocerypos.v11.money0
import com.grocerypos.v11.ui.components.premiumHeader
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * NEW (Flutter parity, batch 4): Expiry Report — port of Flutter's expiry_report_screen.dart.
 * Items in stock whose expiry has passed or comes within 30 days, soonest first, with the
 * stock value (at cost) that is at risk.
 */
class ExpiryReportActivity : AppCompatActivity() {

    private lateinit var c: CuColors
    private lateinit var content: LinearLayout
    private var filter: ExpiryStatus? = null

    private fun t(en: String, ur: String) = Loc.t(this, en, ur)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        c = CuColors(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(c.bg))
        }
        root.addView(premiumHeader(iconRes = R.drawable.ic_warning, title = t("Expiry Report", "ایکسپائری رپورٹ"),
            subtitle = t("Expired & expiring soon (30 days)", "ختم شدہ اور جلد ختم ہونے والی (30 دن)")))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        root.addView(cuGap(30))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(c.bg)); addView(root) })
    }

    override fun onResume() { super.onResume(); load() }

    private fun qty(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

    private fun load(): Job = lifecycleScope.launch {
        val all = Expiry.build(PosDatabase.get(this@ExpiryReportActivity).productDao().allList())
        render(all)
    }

    private fun render(all: List<ExpiryRow>) {
        content.removeAllViews()
        val expired = all.filter { it.status == ExpiryStatus.EXPIRED }
        val soon = all.filter { it.status == ExpiryStatus.SOON }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun box(title: String, list: List<ExpiryRow>, fg: String, bg: String, st: ExpiryStatus) {
            val b = cuCard(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(cuDp(3), cuDp(3), cuDp(3), cuDp(3)) }
                background = if (filter == st) cuFill(bg, 18) else cuCardBg(c)
                isClickable = true
                setOnClickListener { filter = if (filter == st) null else st; render(all) }
            }
            b.addView(cuText(title, 11.5f, fg, true))
            b.addView(cuText("${list.size} " + t("items", "آئٹمز"), 16f, c.text, true))
            b.addView(cuText(money0(list.sumOf { it.product.stock * it.product.cost }) + " " + t("at cost", "لاگت"), 11f, c.muted))
            line.addView(b)
        }
        box(t("Expired", "ختم شدہ"), expired, c.red, c.bg, ExpiryStatus.EXPIRED)
        box(t("Expiring ≤30 days", "30 دن میں"), soon, c.amber, c.amberBg, ExpiryStatus.SOON)
        content.addView(line)
        val shown = filter?.let { f -> all.filter { it.status == f } } ?: all
        if (shown.isEmpty()) {
            content.addView(cuText(t("Nothing is expired or expiring soon. Items need an expiry date (e.g. 2026-12-31 or 12/2026) to show here.",
                "کچھ بھی ختم یا جلد ختم ہونے والا نہیں۔ آئٹم پر ایکسپائری تاریخ (مثلاً 2026-12-31 یا 12/2026) ہونی چاہیے۔"), 13f, c.muted).apply {
                gravity = Gravity.CENTER; setPadding(cuDp(12), cuDp(40), cuDp(12), cuDp(40)) })
            return
        }
        for (r in shown) {
            val card = cuCard(c)
            val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            col.addView(cuText(r.product.name, 14f, c.text, true))
            col.addView(cuText(t("Stock ", "اسٹاک ") + qty(r.product.stock) + " ${r.product.unit}  •  " + DayUtil.fmt(r.expiry), 11.5f, c.muted))
            l.addView(col)
            val (txt, fg) = when {
                r.daysLeft < 0 -> t("${-r.daysLeft}d ago", "${-r.daysLeft} دن پہلے") to c.red
                r.daysLeft == 0 -> t("Today", "آج") to c.red
                else -> t("${r.daysLeft}d left", "${r.daysLeft} دن باقی") to c.amber
            }
            l.addView(cuText(txt, 13f, fg, true))
            card.addView(l)
            content.addView(card)
        }
    }
}
