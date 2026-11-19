package com.grocerypos.v11.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.grocerypos.v11.CostBackfill
import com.grocerypos.v11.DayUtil
import com.grocerypos.v11.PendingCostRow
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.R
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.money0
import com.grocerypos.v11.smallestUnitName
import com.grocerypos.v11.ui.components.premiumHeader
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * NEW (Flutter parity, batch 8): Pending Cost — port of Flutter's pending_cost_screen.dart (admin).
 * Sale lines sold while the item's cost was 0 (or on short stock) whose real cost no purchase
 * has filled yet. A purchase of the item fills them by itself; this screen is for items whose
 * cost was typed in later without a purchase: tick lines → fill from the item's CURRENT cost.
 */
class PendingCostActivity : AppCompatActivity() {

    private lateinit var c: CuColors
    private lateinit var content: LinearLayout
    private val selected = HashSet<Long>()
    private var rows: List<PendingCostRow> = emptyList()
    private var products: Map<String, Product> = emptyMap()

    private fun t(en: String, ur: String) = Loc.t(this, en, ur)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        c = CuColors(this)
        val role = getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        if (role != "admin") { cuToast(t("Only admin can open Pending Cost", "پینڈنگ لاگت صرف ایڈمن کھول سکتا ہے")); finish(); return }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(c.bg))
        }
        root.addView(premiumHeader(iconRes = R.drawable.ic_wrench, title = t("Pending Cost", "پینڈنگ لاگت"),
            subtitle = t("Sales whose real cost is not known yet", "وہ سیل جن کی اصل لاگت ابھی معلوم نہیں")))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        root.addView(cuGap(30))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(c.bg)); addView(root) })
        load()
    }

    private fun perSmallest(p: Product?): Double = if (p == null) 0.0 else CostBackfill.costPerSmallest(p)
    private fun fillable(r: PendingCostRow) = perSmallest(products[r.barcode]) > 0
    private fun change(r: PendingCostRow) = r.pendingQty * perSmallest(products[r.barcode]) - r.pendingEst
    private fun qty(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

    private fun load(): Job = lifecycleScope.launch {
        val db = PosDatabase.get(this@PendingCostActivity)
        val list = db.costBackfillDao().allPending()
        products = list.map { it.barcode }.toSet().mapNotNull { bc -> db.productDao().find(bc)?.let { bc to it } }.toMap()
        rows = list.sortedBy { if (fillable(it)) 0 else 1 }
        selected.retainAll(rows.filter { fillable(it) }.map { it.id }.toSet())
        render()
    }

    private fun render() {
        content.removeAllViews()
        val info = cuCard(c)
        info.addView(cuText(t("A purchase of the item fills these by itself (oldest sale first). Use this only when you typed the item's cost without a purchase.",
            "آئٹم کی خریداری آنے پر یہ خود بھر جاتی ہیں (پرانی سیل پہلے)۔ یہ تب استعمال کریں جب آئٹم کی لاگت بغیر خریداری کے لکھی ہو۔"), 12f, c.muted))
        content.addView(info)
        if (rows.isEmpty()) {
            content.addView(cuText(t("No pending cost. All sale costs are known. ✅", "کوئی پینڈنگ لاگت نہیں۔ ✅"), 13f, c.muted).apply {
                gravity = Gravity.CENTER; setPadding(0, cuDp(40), 0, cuDp(40)) })
            return
        }
        val fill = rows.filter { fillable(it) }
        val (s, chips) = cuChipRow()
        chips.addView(cuChip(t("Select all (${fill.size})", "سب چنیں (${fill.size})"), fill.isNotEmpty() && selected.size == fill.size, c) {
            if (selected.size == fill.size) selected.clear() else { selected.clear(); selected.addAll(fill.map { it.id }) }
            render()
        })
        content.addView(s)
        val chosen = fill.filter { it.id in selected }
        val delta = chosen.sumOf { change(it) }
        content.addView(cuButton(t("Fill ${chosen.size} from item cost", "${chosen.size} لائنیں آئٹم لاگت سے بھریں") +
            if (chosen.isNotEmpty()) "  (" + (if (delta >= 0) "+" else "−") + money0(kotlin.math.abs(delta)) + ")" else "", c.teal) { apply() })
        content.addView(cuGap(6))
        for (r in rows) content.addView(rowView(r))
    }

    private fun rowView(r: PendingCostRow): LinearLayout {
        val card = cuCard(c)
        val p = products[r.barcode]
        val ok = fillable(r)
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (ok) line.addView(CheckBox(this).apply {
            isChecked = r.id in selected
            setOnCheckedChangeListener { _, on -> if (on) selected.add(r.id) else selected.remove(r.id); render() }
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(cuText(r.product.ifBlank { p?.name ?: r.barcode }, 14f, c.text, true))
        col.addView(cuText("#${r.invoice}  •  " + (r.party?.trim()?.ifBlank { null } ?: "Walk-in") + "  •  " + DayUtil.fmt(r.createdAt), 11.5f, c.muted))
        col.addView(cuText(t("Pending ", "پینڈنگ ") + qty(r.pendingQty) + " " + (p?.smallestUnitName() ?: "") +
            "  •  " + t("estimate ", "اندازہ ") + money0(r.pendingEst), 11.5f, c.muted))
        if (ok) {
            val ch = change(r)
            col.addView(cuText(t("At item cost: ", "آئٹم لاگت پر: ") + money0(r.pendingQty * perSmallest(p)) + "  (" +
                (if (ch >= 0) t("profit −", "منافع −") else t("profit +", "منافع +")) + money0(kotlin.math.abs(ch)) + ")",
                11.5f, if (ch > 0.009) c.red else c.teal, true))
        } else {
            col.addView(cuText(t("Item cost is 0 — enter the cost or make a purchase", "آئٹم کی لاگت 0 ہے — لاگت لکھیں یا خریداری کریں"), 11.5f, c.amber, true))
        }
        line.addView(col)
        card.addView(line)
        return card
    }

    private fun apply() {
        val ids = selected.toSet()
        if (ids.isEmpty()) { cuToast(t("Select at least one line", "کم از کم ایک لائن چنیں")); return }
        AlertDialog.Builder(this)
            .setTitle(t("Fill ${ids.size} lines?", "${ids.size} لائنیں بھریں؟"))
            .setMessage(t("Their cost (and profit) will use each item's current cost. The bills sync again.",
                "ان کی لاگت (اور منافع) ہر آئٹم کی موجودہ لاگت سے بنے گی۔ بل دوبارہ سنک ہوں گے۔"))
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Fill", "بھریں")) { _, _ ->
                lifecycleScope.launch {
                    val db = PosDatabase.get(this@PendingCostActivity)
                    val n = try { db.withTransaction { CostBackfill.fillFromProductCost(db, ids) } } catch (e: Exception) { cuToast(e.message ?: "Error"); return@launch }
                    try { SyncQueueHelper.logAudit(db, this@PendingCostActivity, "pending_cost_fill", "lines=$n", "product cost se pending lines bhari gayin") } catch (e: Exception) { }
                    SyncQueueHelper.trigger(this@PendingCostActivity)
                    selected.clear()
                    cuToast(t("$n lines filled", "$n لائنیں بھر دی گئیں"))
                    load()
                }
            }.show()
    }
}
