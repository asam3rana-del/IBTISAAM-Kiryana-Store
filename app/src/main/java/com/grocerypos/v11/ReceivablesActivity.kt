package com.grocerypos.v11.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Aging
import com.grocerypos.v11.AgingBucket
import com.grocerypos.v11.AgingRow
import com.grocerypos.v11.AgingSummary
import com.grocerypos.v11.CollectionRepository
import com.grocerypos.v11.R
import com.grocerypos.v11.money0
import com.grocerypos.v11.ui.components.premiumHeader
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * NEW (Flutter parity, batch 4): Receivables aging — port of Flutter's receivables_screen.dart
 * ("Paisa kahan phansa hai"). Customer udhaar split into 6 boxes by how late it is (based on each
 * bill's due date). Tap a box to see its parties, tap a party to open the ledger.
 */
class ReceivablesActivity : AppCompatActivity() {

    private lateinit var c: CuColors
    private lateinit var content: LinearLayout
    private var selected: AgingBucket? = null

    private fun t(en: String, ur: String) = Loc.t(this, en, ur)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        c = CuColors(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(c.bg))
        }
        root.addView(premiumHeader(iconRes = R.drawable.ic_chart, title = t("Receivables", "وصولیاں"),
            subtitle = t("Where is the money stuck?", "پیسہ کہاں پھنسا ہے؟")))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        root.addView(cuGap(30))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(c.bg)); addView(root) })
    }

    override fun onResume() { super.onResume(); load() }

    private fun label(b: AgingBucket) = when (b) {
        AgingBucket.NOT_DUE -> t("Not due yet", "ابھی وقت باقی")
        AgingBucket.LATE_1_7 -> t("1–7 days late", "1–7 دن لیٹ")
        AgingBucket.LATE_8_30 -> t("8–30 days late", "8–30 دن لیٹ")
        AgingBucket.LATE_31_60 -> t("31–60 days late", "31–60 دن لیٹ")
        AgingBucket.LATE_60_PLUS -> t("60+ days late", "60+ دن لیٹ")
        AgingBucket.NO_DATE -> t("No due date", "تاریخ نہیں")
    }

    private fun color(b: AgingBucket): Pair<String, String> = when (b) {
        AgingBucket.NOT_DUE -> c.teal to c.tealBg
        AgingBucket.LATE_1_7 -> c.amber to c.amberBg
        AgingBucket.LATE_8_30 -> c.amber to c.amberBg
        AgingBucket.LATE_31_60 -> c.red to c.bg
        AgingBucket.LATE_60_PLUS -> c.red to c.bg
        AgingBucket.NO_DATE -> c.purple to c.purpleBg
    }

    private fun load(): Job = lifecycleScope.launch {
        val s = Aging.build(CollectionRepository.dueParties(this@ReceivablesActivity))
        render(s)
    }

    private fun render(s: AgingSummary) {
        content.removeAllViews()
        val top = cuCard(c)
        top.addView(cuText(t("Total udhaar", "کل ادھار"), 12f, c.muted))
        top.addView(cuText(money0(s.totalDue), 24f, c.text, true))
        top.addView(cuText(t("Late: ", "لیٹ: ") + money0(s.lateAmount) + "  (" + Math.round(s.lateShare * 100) + "%)", 12.5f, if (s.lateAmount > 0.009) c.red else c.teal, true))
        content.addView(top)
        if (s.rows.isEmpty()) {
            content.addView(cuText(t("No customer owes anything. 🎉", "کسی کسٹمر کا کوئی ادھار نہیں۔ 🎉"), 13f, c.muted).apply { gravity = Gravity.CENTER; setPadding(0, cuDp(40), 0, cuDp(40)) })
            return
        }
        // 6 boxes, 2 per row
        for (pair in Aging.ORDER.chunked(2)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (b in pair) {
                val (fg, bg) = color(b)
                val box = cuCard(c).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(cuDp(3), cuDp(3), cuDp(3), cuDp(3)) }
                    background = if (selected == b) cuFill(bg, 18) else cuCardBg(c)
                    isClickable = true
                    setOnClickListener { selected = if (selected == b) null else b; render(s) }
                }
                box.addView(cuText(label(b), 11.5f, fg, true))
                box.addView(cuText(money0(s.amountIn(b)), 16f, c.text, true))
                box.addView(cuText("${s.partiesIn(b)} " + t("parties", "پارٹیاں"), 11f, c.muted))
                line.addView(box)
            }
            content.addView(line)
        }
        content.addView(cuGap(8))
        val sel = selected
        val rows: List<AgingRow> = if (sel == null) s.rows else s.rowsIn(sel)
        content.addView(cuText(if (sel == null) t("All parties", "تمام پارٹیاں") else label(sel), 13f, c.text, true).apply { setPadding(cuDp(4), cuDp(6), 0, cuDp(6)) })
        for (r in rows) content.addView(partyRow(r, sel))
    }

    private fun partyRow(r: AgingRow, sel: AgingBucket?): LinearLayout {
        val card = cuCard(c)
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(cuText(r.party.partyName, 14f, c.text, true))
        val (fg, _) = color(r.worst)
        val late = if (r.maxDaysLate > 0) t("${r.maxDaysLate} days late", "${r.maxDaysLate} دن لیٹ") else label(r.worst)
        col.addView(cuText(late + "  •  " + r.party.billCount + " " + t("bills", "بل"), 11.5f, fg))
        line.addView(col)
        line.addView(cuText(money0(if (sel == null) r.total else r.amountIn(sel)), 14f, c.text, true))
        card.addView(line)
        card.isClickable = true
        card.setOnClickListener {
            val id = r.party.partyId ?: return@setOnClickListener
            startActivity(Intent(this, PartyTransactionActivity::class.java)
                .putExtra("partyId", id).putExtra("partyName", r.party.partyName).putExtra("isCustomer", true))
        }
        return card
    }
}
