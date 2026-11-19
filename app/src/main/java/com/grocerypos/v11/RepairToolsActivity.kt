package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.BooksRuleException
import com.grocerypos.v11.CostCheckRow
import com.grocerypos.v11.CostRepairLine
import com.grocerypos.v11.CostRepairSkipped
import com.grocerypos.v11.DayUtil
import com.grocerypos.v11.R
import com.grocerypos.v11.RepairTools
import com.grocerypos.v11.StockRebuildPlan
import com.grocerypos.v11.Ym
import com.grocerypos.v11.money0
import com.grocerypos.v11.ui.components.premiumHeader
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * NEW (Flutter parity, batch 3): Repair Tools — one screen with Flutter's four admin tools:
 *  1. Bill Cost Repair — sale lines whose saved cost is far above the sale (old unit ladder) → recompute.
 *  2. Cost Check — product average cost far from the last purchase rate → set to last rate.
 *  3. Stock Rebuild — stock = a month's purchases − sales since (STOCK_TAKE entries).
 *  4. Month Cleanup — delete every sale + purchase bill of one month (normal delete, fully reversed).
 * Cost Check: admin / manager. The other three: admin only.
 */
class RepairToolsActivity : AppCompatActivity() {

    private lateinit var c: CuColors
    private lateinit var content: LinearLayout
    private var tab = 0
    private var role = "cashier"
    private var year = 0
    private var month = 0

    private fun t(en: String, ur: String) = Loc.t(this, en, ur)
    private val isAdmin get() = role == "admin"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        role = getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        if (role != "admin" && role != "manager") {
            cuToast(t("Only admin / manager can open Repair Tools", "مرمت کے ٹولز صرف ایڈمن / مینیجر کھول سکتے ہیں")); finish(); return
        }
        c = CuColors(this)
        val now = Calendar.getInstance()
        val prev = Ym.add(Ym.key(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1), -1)
        year = Ym.year(prev); month = Ym.month(prev)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(c.bg))
        }
        root.addView(premiumHeader(iconRes = R.drawable.ic_wrench, title = t("Repair Tools", "مرمت کے ٹولز"),
            subtitle = t("Fix bill cost, product cost, stock; clean a month", "بل کی لاگت، آئٹم لاگت، اسٹاک درست کریں")))
        val (tabScroll, tabRow) = cuChipRow()
        listOf(t("Bill Cost Repair", "بل لاگت"), t("Cost Check", "لاگت چیک"), t("Stock Rebuild", "اسٹاک دوبارہ"), t("Month Cleanup", "مہینہ صفائی"))
            .forEachIndexed { i, label -> tabRow.addView(cuChip(label, false, c) { tab = i; render() }.also { it.tag = i }) }
        root.addView(tabScroll)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        root.addView(cuGap(30))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(c.bg)); addView(root) })
        this.tabRow = tabRow
        render()
    }

    private lateinit var tabRow: LinearLayout

    private fun doAction(block: suspend () -> Unit): Job = lifecycleScope.launch {
        try { block() } catch (e: BooksRuleException) { cuToast(e.message ?: "") } catch (e: Exception) { cuToast(t("Something went wrong", "کچھ غلط ہو گیا") + ": ${e.message ?: ""}") }
    }

    private fun render() {
        for (i in 0 until tabRow.childCount) {
            val v = tabRow.getChildAt(i) as? TextView ?: continue
            val sel = v.tag == tab
            v.background = if (sel) cuFill(c.purple, 16) else cuCardBg(c, 16)
            v.setTextColor(if (sel) Color.WHITE else Color.parseColor(c.muted))
        }
        content.removeAllViews()
        val needsAdmin = tab != 1
        if (needsAdmin && !isAdmin) {
            content.addView(cuText(t("Only admin can use this tool.", "یہ ٹول صرف ایڈمن استعمال کر سکتا ہے۔"), 13f, c.red, true).apply { setPadding(0, cuDp(20), 0, 0) })
            return
        }
        when (tab) { 0 -> costRepair(); 1 -> costCheck(); 2 -> stockRebuild(); else -> monthCleanup() }
    }

    private fun info(text: String) = content.addView(cuText(text, 11.5f, c.muted).apply { setPadding(cuDp(4), cuDp(4), cuDp(4), cuDp(10)) })

    private fun monthPicker(onChange: () -> Unit) {
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(cuChip("‹", false, c) { val p = Ym.add(Ym.key(year, month), -1); year = Ym.year(p); month = Ym.month(p); onChange() })
        nav.addView(cuText(Ym.label(Ym.key(year, month)), 15f, c.text, true).apply { gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        nav.addView(cuChip("›", false, c) { val n = Ym.add(Ym.key(year, month), 1); year = Ym.year(n); month = Ym.month(n); onChange() })
        content.addView(nav)
    }

    // ------------------------------------------------------------------ 1. Bill Cost Repair

    private fun costRepair(): Job = lifecycleScope.launch {
        info(t("Bills made before a unit change can carry an old, inflated item cost (e.g. a carton's cost on one packet), so profit shows a big loss. These lines are recomputed from the item's current cost.",
            "یونٹ بدلنے سے پہلے کے بلوں میں پرانی بڑی لاگت رہ جاتی ہے (مثلاً کارٹن کی لاگت ایک پیکٹ پر)، اس لیے منافع بڑا نقصان دکھاتا ہے۔ یہ لائنیں آئٹم کی موجودہ لاگت سے دوبارہ نکالی جاتی ہیں۔"))
        val (lines, skipped) = try { RepairTools.buildCostRepairPlan(this@RepairToolsActivity) } catch (e: Exception) { Pair(emptyList<CostRepairLine>(), emptyList<CostRepairSkipped>()) }
        if (lines.isEmpty()) content.addView(cuText(t("No suspicious bill lines found.", "کوئی مشکوک بل لائن نہیں ملی۔"), 13f, c.teal, true))
        val chosen = LinkedHashSet<Long>(lines.map { it.itemId })
        if (lines.isNotEmpty()) {
            content.addView(cuText(t("${lines.size} lines • profit improves by ${money0(lines.sumOf { it.profitGain })}",
                "${lines.size} لائنیں • منافع ${money0(lines.sumOf { it.profitGain })} بہتر"), 13f, c.navy, true))
            val list = cuCard(c)
            for (l in lines) {
                list.addView(CheckBox(this@RepairToolsActivity).apply {
                    isChecked = true
                    text = "${l.invoice} • ${l.productName} (${formatQ(l.qty)} ${l.unit})\n${DayUtil.fmt(l.createdAt)} • ${l.party} • ${t("Sale", "فروخت")} ${money0(l.amount)}\n" +
                        "${t("Cost", "لاگت")} ${money0(l.oldCost)} → ${money0(l.newCost)}"
                    textSize = 12f
                    setOnCheckedChangeListener { _, on -> if (on) chosen.add(l.itemId) else chosen.remove(l.itemId) }
                })
            }
            content.addView(list)
            content.addView(cuButton(t("Apply to selected lines", "چنی ہوئی لائنوں پر لگائیں"), c.teal) {
                val pick = lines.filter { it.itemId in chosen }
                AlertDialog.Builder(this@RepairToolsActivity).setTitle(t("Repair ${pick.size} lines?", "${pick.size} لائنیں درست کریں؟"))
                    .setMessage(t("Bills are re-synced so profit is fixed on every device.", "بل دوبارہ سنک ہوں گے تاکہ ہر ڈیوائس پر منافع درست ہو۔"))
                    .setNegativeButton(t("Cancel", "منسوخ"), null)
                    .setPositiveButton(t("Apply", "لگائیں")) { _, _ ->
                        doAction { val n = RepairTools.applyCostRepair(this@RepairToolsActivity, pick); cuToast(t("$n lines repaired", "$n لائنیں درست")); render() }
                    }.show()
            })
        }
        if (skipped.isNotEmpty()) {
            content.addView(cuText(t("SKIPPED", "چھوڑی گئیں") + " (${skipped.size})", 11.5f, c.muted, true).apply { setPadding(cuDp(4), cuDp(10), 0, cuDp(4)) })
            val box = cuCard(c)
            for (s in skipped.take(50)) box.addView(cuText("${s.invoice} • ${s.productName}: ${s.reason}", 11.5f, c.text))
            content.addView(box)
        }
    }

    private fun formatQ(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    // ------------------------------------------------------------------ 2. Cost Check

    private fun costCheck(): Job = lifecycleScope.launch {
        info(t("Average cost can go wrong after bad purchases were edited / deleted. Items whose cost is more than 1.5× away from their LAST purchase rate are listed; set them to that rate.",
            "غلط خریداری ایڈٹ/ڈیلیٹ ہونے سے اوسط لاگت بگڑ سکتی ہے۔ جن آئٹمز کی لاگت آخری خریداری کے ریٹ سے 1.5 گنا دور ہے وہ یہاں ہیں؛ انہیں آخری ریٹ پر لگائیں۔"))
        val rows = try { RepairTools.scanCosts(this@RepairToolsActivity) } catch (e: Exception) { emptyList<CostCheckRow>() }
        if (rows.isEmpty()) { content.addView(cuText(t("All item costs look fine.", "تمام آئٹمز کی لاگت ٹھیک ہے۔"), 13f, c.teal, true)); return@launch }
        val chosen = LinkedHashSet<String>(rows.map { it.product.barcode })
        val list = cuCard(c)
        for (r in rows) {
            list.addView(CheckBox(this@RepairToolsActivity).apply {
                isChecked = true
                text = "${r.product.name} (${r.product.unit})\n${t("Avg cost", "اوسط لاگت")} ${money0(r.product.cost)} • ${t("Last purchase", "آخری خریداری")} ${money0(r.lastRate)} (${DayUtil.fmt(r.lastAt)})"
                textSize = 12f
                setOnCheckedChangeListener { _, on -> if (on) chosen.add(r.product.barcode) else chosen.remove(r.product.barcode) }
            })
        }
        content.addView(list)
        content.addView(cuButton(t("Set selected to last purchase rate", "چنے ہوئے آئٹمز آخری ریٹ پر"), c.teal) {
            val map = rows.filter { it.product.barcode in chosen }.associate { it.product.barcode to it.lastRate }
            doAction { val n = RepairTools.applyCosts(this@RepairToolsActivity, map); cuToast(t("$n items updated", "$n آئٹمز اپ ڈیٹ")); render() }
        })
    }

    // ------------------------------------------------------------------ 3. Stock Rebuild

    private fun stockRebuild() {
        info(t("For items bought in the chosen month: stock = that month's purchases − sales since the first of those purchases (never below 0). Items not bought that month are not touched. Saved as Stock Take entries.",
            "چنے ہوئے مہینے میں خریدے گئے آئٹمز کا اسٹاک = اس مہینے کی خریداری − اس کے بعد کی فروخت (0 سے کم نہیں)۔ باقی آئٹمز کو ہاتھ نہیں لگتا۔ اسٹاک گنتی کی انٹریاں بنتی ہیں۔"))
        monthPicker { render() }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(box)
        lifecycleScope.launch {
            val plan: StockRebuildPlan = try { RepairTools.buildStockRebuild(this@RepairToolsActivity, year, month) } catch (e: Exception) {
                cuToast(e.message ?: ""); return@launch
            }
            box.addView(cuText(t("${plan.lines.size} items will change • ${plan.unchanged} already right" + (if (plan.skippedInvalid > 0) " • ${plan.skippedInvalid} skipped (fraction)" else ""),
                "${plan.lines.size} آئٹمز بدلیں گے • ${plan.unchanged} پہلے سے درست" + (if (plan.skippedInvalid > 0) " • ${plan.skippedInvalid} چھوڑے (کسر)" else "")), 13f, c.navy, true))
            if (plan.lines.isEmpty()) return@launch
            val list = cuCard(c)
            for (l in plan.lines.take(200)) {
                list.addView(cuText(l.product.name, 13f, c.text, true))
                list.addView(cuText("${t("Bought", "خریدا")} ${formatQ(l.purchased)} − ${t("sold", "بیچا")} ${formatQ(l.soldAfter)} = ${formatQ(l.target)}" +
                    "   (${t("now", "ابھی")} ${formatQ(l.product.stock)})" + (if (l.clamped) " • " + t("set to 0", "0 کیا") else ""), 11.5f,
                    if (l.clamped) c.amber else c.muted).apply { setPadding(0, 0, 0, cuDp(6)) })
            }
            box.addView(list)
            box.addView(cuButton(t("Apply stock rebuild", "اسٹاک ری بلڈ لگائیں"), c.teal) {
                AlertDialog.Builder(this@RepairToolsActivity).setTitle(t("Change stock of ${plan.lines.size} items?", "${plan.lines.size} آئٹمز کا اسٹاک بدلیں؟"))
                    .setNegativeButton(t("Cancel", "منسوخ"), null)
                    .setPositiveButton(t("Apply", "لگائیں")) { _, _ ->
                        doAction { val sid = RepairTools.applyStockRebuild(this@RepairToolsActivity, plan); cuToast(t("Saved", "محفوظ") + " ($sid)"); render() }
                    }.show()
            })
        }
    }

    // ------------------------------------------------------------------ 4. Month Cleanup

    private fun monthCleanup() {
        info(t("Deletes EVERY sale and purchase bill of the chosen month using the normal Delete (stock, party balance, payments and cash are reversed and the delete syncs to all devices). This cannot be undone.",
            "چنے ہوئے مہینے کے تمام سیل اور پرچیز بل عام ڈیلیٹ سے ہٹاتا ہے (اسٹاک، پارٹی بیلنس، ادائیگیاں اور کیش واپس، اور سب ڈیوائسز پر سنک)۔ یہ واپس نہیں ہو سکتا۔"))
        monthPicker { render() }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(box)
        lifecycleScope.launch {
            val p = RepairTools.previewMonthCleanup(this@RepairToolsActivity, year, month)
            box.addView(cuCard(c).apply {
                addView(cuText("${t("Sales", "سیلز")}: ${p.saleIds.size} • ${money0(p.saleTotal)}", 13.5f, c.text, true))
                addView(cuText("${t("Purchases", "خریداری")}: ${p.purchaseIds.size} • ${money0(p.purchaseTotal)}", 13.5f, c.text, true))
            })
            if (p.saleIds.isEmpty() && p.purchaseIds.isEmpty()) return@launch
            box.addView(cuButton(t("Delete all bills of ${Ym.label(Ym.key(year, month))}", "${Ym.label(Ym.key(year, month))} کے تمام بل حذف کریں"), c.red) { confirmCleanup(p.saleIds.size, p.purchaseIds.size) })
        }
    }

    private fun confirmCleanup(sales: Int, purchases: Int) {
        val label = Ym.label(Ym.key(year, month))
        val input = cuField(t("Type DELETE to confirm", "تصدیق کے لیے DELETE لکھیں"), c)
        val (scroll, body) = cuDialogBody()
        body.addView(cuText(t("$sales sales and $purchases purchases of $label will be deleted on all devices.", "$label کی $sales سیلز اور $purchases خریداریاں سب ڈیوائسز سے حذف ہوں گی۔"), 13f, c.red, true))
        body.addView(input)
        AlertDialog.Builder(this).setTitle(t("Month Cleanup", "مہینہ صفائی")).setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Delete", "حذف کریں")) { _, _ ->
                if (input.text.toString().trim().uppercase() != "DELETE") { cuToast(t("Not confirmed", "تصدیق نہیں ہوئی")); return@setPositiveButton }
                doAction {
                    val (sd, pd, failures) = RepairTools.runMonthCleanup(this@RepairToolsActivity, year, month)
                    AlertDialog.Builder(this@RepairToolsActivity).setTitle(t("Done", "مکمل"))
                        .setMessage(t("Sales deleted: $sd\nPurchases deleted: $pd", "سیلز حذف: $sd\nخریداری حذف: $pd") +
                            (if (failures.isNotEmpty()) "\n\n" + failures.take(10).joinToString("\n") else "") +
                            "\n\n" + t("Check Stock Audit afterwards.", "بعد میں اسٹاک آڈٹ ضرور دیکھیں۔"))
                        .setPositiveButton("OK", null).show()
                    render()
                }
            }.show()
    }
}
