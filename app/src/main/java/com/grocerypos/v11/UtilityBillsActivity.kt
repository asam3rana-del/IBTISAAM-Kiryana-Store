package com.grocerypos.v11.ui

import com.grocerypos.v11.RadioIds
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.BillView
import com.grocerypos.v11.Bills
import com.grocerypos.v11.BooksRuleException
import com.grocerypos.v11.DayUtil
import com.grocerypos.v11.R
import com.grocerypos.v11.UtilityBill
import com.grocerypos.v11.UtilityBillRepository
import com.grocerypos.v11.Ym
import com.grocerypos.v11.money0
import com.grocerypos.v11.ui.components.premiumHeader
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch

/**
 * NEW (Flutter parity, batch 2): Utility Bills — port of Flutter's utility_bills_screen.dart.
 * Admin / manager only. Bijli / gas / pani / internet / phone ke mahana bills: due date, status
 * (overdue / aaj / baqi din / paid), payment (Expense "Utility Bills" + Cash/Bank OUT), undo, history.
 */
class UtilityBillsActivity : AppCompatActivity() {

    private lateinit var c: CuColors
    private lateinit var content: LinearLayout
    private var views: List<BillView> = emptyList()

    private fun t(en: String, ur: String) = Loc.t(this, en, ur)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val role = getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        if (role != "admin" && role != "manager") {
            cuToast(t("Only admin / manager can open Utility Bills", "یوٹیلیٹی بل صرف ایڈمن / مینیجر کھول سکتے ہیں")); finish(); return
        }
        c = CuColors(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(c.bg))
        }
        root.addView(premiumHeader(iconRes = R.drawable.ic_receipt, title = t("Utility Bills", "یوٹیلیٹی بل"),
            subtitle = t("Electricity, gas, water, internet, phone", "بجلی، گیس، پانی، انٹرنیٹ، فون")))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        root.addView(cuGap(30))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(c.bg)); addView(root) })
        load()
    }

    private fun doAction(block: suspend () -> Unit) = lifecycleScope.launch {
        try { block() } catch (e: BooksRuleException) { cuToast(e.message ?: "") } catch (e: Exception) { cuToast(t("Could not save", "محفوظ نہیں ہو سکا")) }
    }

    private fun load(): kotlinx.coroutines.Job = lifecycleScope.launch {
        views = UtilityBillRepository.views(this@UtilityBillsActivity)
        render()
    }

    private fun kindLabel(k: String) = when (k) {
        "electricity" -> t("Electricity", "بجلی")
        "gas" -> t("Gas", "گیس")
        "water" -> t("Water", "پانی")
        "internet" -> t("Internet", "انٹرنیٹ")
        "phone" -> t("Phone / Mobile", "فون / موبائل")
        else -> t("Other", "دیگر")
    }

    private fun render() {
        content.removeAllViews()
        content.addView(cuButton("+  " + t("Add Bill", "بل شامل کریں"), c.purple) { editBill(null) })
        val unpaid = views.filter { it.bill.active && !it.status.paid }
        if (unpaid.isNotEmpty()) content.addView(cuCard(c).apply {
            addView(cuText(t("Unpaid bills", "باقی بل") + " (${unpaid.size})", 12f, c.muted, true))
            addView(cuText(money0(unpaid.sumOf { it.suggestedAmount }), 18f, if (unpaid.any { it.status.overdue }) c.red else c.amber, true))
        })
        if (views.isEmpty()) {
            content.addView(cuText(t("No bills yet. Tap \"Add Bill\".", "ابھی کوئی بل نہیں۔ \"بل شامل کریں\" دبائیں۔"), 13f, c.muted).apply {
                gravity = Gravity.CENTER; setPadding(0, cuDp(40), 0, cuDp(40))
            })
            return
        }
        for (v in views) content.addView(card(v))
    }

    private fun statusText(v: BillView): Pair<String, String> {
        val s = v.status
        return when {
            !v.bill.active -> t("Stopped", "بند") to c.muted
            s.paid -> t("Paid", "ادا") + " • " + Ym.label(s.month) to c.teal
            s.overdue -> t("Overdue ${-s.daysLeft} days", "${-s.daysLeft} دن لیٹ") + " • " + Ym.label(s.month) to c.red
            s.dueToday -> t("Due today", "آج ادائیگی") to c.red
            s.dueTomorrow -> t("Due tomorrow", "کل ادائیگی") to c.amber
            else -> t("Due in ${s.daysLeft} days", "${s.daysLeft} دن باقی") to c.muted
        }
    }

    private fun card(v: BillView): LinearLayout {
        val b = v.bill
        val card = cuCard(c)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(cuText(b.name, 14.5f, if (b.active) c.text else c.muted, true))
        col.addView(cuText(kindLabel(b.kind) + "  •  " + t("Due", "تاریخ") + " " + DayUtil.fmt(v.status.due) +
            (if (v.suggestedAmount > 0) "  •  ~" + money0(v.suggestedAmount) else ""), 11.5f, c.muted))
        val (st, color) = statusText(v)
        col.addView(cuText(st, 12f, color, true))
        top.addView(col)
        if (b.active && !v.status.paid) top.addView(cuRound(R.drawable.ic_check, c.teal, c.tealBg) { payDialog(v) })
        top.addView(cuRound(R.drawable.ic_more_vert, c.muted, c.bg) { billMenu(v) })
        card.addView(top)
        return card
    }

    private fun billMenu(v: BillView) {
        val b = v.bill
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (b.active) items.add(t("Pay a month", "کسی مہینے کی ادائیگی") to { payDialog(v, pickMonth = true) })
        items.add(t("Payment history", "ادائیگی کی ہسٹری") to { history(b) })
        items.add(t("Edit bill", "بل میں ترمیم") to { editBill(b) })
        items.add((if (b.active) t("Stop this bill", "یہ بل بند کریں") else t("Start again", "دوبارہ شروع")) to {
            doAction { UtilityBillRepository.save(this, b.copy(active = !b.active)); load() }
        })
        items.add(t("Delete bill", "بل حذف کریں") to {
            AlertDialog.Builder(this).setTitle(t("Delete bill", "بل حذف کریں"))
                .setMessage(t("${b.name} and its payment history will be deleted. Expenses already recorded stay.", "${b.name} اور اس کی ادائیگی کی ہسٹری حذف ہو جائے گی۔ درج شدہ خرچ رہیں گے۔"))
                .setNegativeButton(t("Cancel", "منسوخ"), null)
                .setPositiveButton(t("Delete", "حذف کریں")) { _, _ -> doAction { UtilityBillRepository.deleteBill(this, b.id); load() } }.show()
        })
        AlertDialog.Builder(this).setTitle(b.name).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun payDialog(v: BillView, pickMonth: Boolean = false) {
        val b = v.bill
        var month = v.status.month
        val (scroll, body) = cuDialogBody()
        val monthBtn = cuButton(t("Month", "مہینہ") + ": " + Ym.label(month), c.purpleBg, c.purple) {}
        if (pickMonth) {
            monthBtn.setOnClickListener {
                val nowYm = Ym.of(System.currentTimeMillis())
                val months = (-1 until 12).map { Ym.add(nowYm, -it) }
                AlertDialog.Builder(this).setItems(months.map { Ym.label(it) }.toTypedArray()) { _, i ->
                    month = months[i]; monthBtn.text = t("Month", "مہینہ") + ": " + Ym.label(month)
                }.show()
            }
        }
        body.addView(monthBtn)
        val amount = cuField(t("Amount", "رقم"), c, numeric = true, initial = if (v.suggestedAmount > 0) Math.round(v.suggestedAmount).toString() else "")
        val m = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@UtilityBillsActivity).apply { text = t("Cash", "کیش"); id = RadioIds.of(9101) })
            addView(RadioButton(this@UtilityBillsActivity).apply { text = t("Bank", "بینک"); id = RadioIds.of(9102) })
            check(RadioIds.of(9101))
        }
        val note = cuField(t("Note (optional)", "نوٹ (اختیاری)"), c)
        val rec = CheckBox(this).apply { text = t("Also record as Expense (Cash/Bank out)", "خرچ میں بھی لکھیں (کیش/بینک سے باہر)"); isChecked = true }
        body.addView(amount); body.addView(m); body.addView(note); body.addView(rec)
        AlertDialog.Builder(this).setTitle(t("Pay", "ادا کریں") + " • " + b.name).setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Pay", "ادا کریں")) { _, _ ->
                val a = cuParseAmount(amount.text.toString())
                if (a == null) { cuToast(t("Enter a valid amount", "صحیح رقم لکھیں")); return@setPositiveButton }
                doAction {
                    UtilityBillRepository.pay(this, b, month, a, if (m.checkedRadioButtonId == RadioIds.of(9102)) "bank" else "cash", note.text.toString(), rec.isChecked)
                    cuToast(t("Paid", "ادا ہو گیا")); load()
                }
            }.show()
    }

    private fun history(b: UtilityBill): kotlinx.coroutines.Job = lifecycleScope.launch {
        val pays = UtilityBillRepository.payments(this@UtilityBillsActivity, b.id)
        val (scroll, body) = cuDialogBody()
        var dialog: AlertDialog? = null
        if (pays.isEmpty()) body.addView(cuText(t("No payments yet", "ابھی کوئی ادائیگی نہیں"), 12.5f, c.muted))
        for (p in pays) {
            val row = LinearLayout(this@UtilityBillsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, cuDp(4), 0, cuDp(4)) }
            val col = LinearLayout(this@UtilityBillsActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            col.addView(cuText("${Ym.label(p.month)}  •  ${money0(p.amount)}", 13f, c.text, true))
            col.addView(cuText(DayUtil.fmt(p.paidAt) + "  •  " + (if (p.method == "bank") t("Bank", "بینک") else t("Cash", "کیش")) +
                (if (p.note.isNotBlank()) "  •  ${p.note}" else ""), 11f, c.muted))
            row.addView(col)
            row.addView(cuRound(R.drawable.ic_undo, c.red, c.bg) {
                AlertDialog.Builder(this@UtilityBillsActivity).setTitle(t("Undo this payment?", "یہ ادائیگی واپس کریں؟"))
                    .setMessage(t("Its expense and cash entry are removed too.", "اس کا خرچ اور کیش انٹری بھی ہٹ جائے گی۔"))
                    .setNegativeButton(t("Cancel", "منسوخ"), null)
                    .setPositiveButton(t("Undo", "واپس کریں")) { _, _ -> dialog?.dismiss(); doAction { UtilityBillRepository.undoPayment(this@UtilityBillsActivity, p); load() } }.show()
            })
            body.addView(row)
        }
        dialog = AlertDialog.Builder(this@UtilityBillsActivity).setTitle(b.name).setView(scroll).setNegativeButton(t("Close", "بند"), null).show()
    }

    private fun editBill(b: UtilityBill?) {
        val (scroll, body) = cuDialogBody()
        val name = cuField(t("Bill name (e.g. LESCO shop meter)", "بل کا نام (مثلاً دکان کا میٹر)"), c, initial = b?.name ?: "")
        body.addView(name)
        var kind = b?.kind ?: "electricity"
        val (kScroll, kRow) = cuChipRow()
        fun drawKinds() {
            kRow.removeAllViews()
            Bills.KINDS.forEach { k -> kRow.addView(cuChip(kindLabel(k), kind == k, c) { kind = k; drawKinds() }) }
        }
        drawKinds()
        body.addView(kScroll)
        val day = cuField(t("Due day of month (1-31)", "مہینے کی آخری تاریخ (1-31)"), c, numeric = true, initial = (b?.dueDay ?: 10).toString())
        val avg = cuField(t("Usual amount (optional)", "عام رقم (اختیاری)"), c, numeric = true,
            initial = b?.avgAmount?.takeIf { it > 0 }?.let { Math.round(it).toString() } ?: "")
        val rem = CheckBox(this).apply { text = t("Remind me", "یاد دلائیں"); isChecked = b?.reminder ?: true }
        body.addView(day); body.addView(avg); body.addView(rem)
        AlertDialog.Builder(this).setTitle(if (b == null) t("Add Bill", "بل شامل کریں") else t("Edit bill", "بل میں ترمیم")).setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Save", "محفوظ کریں")) { _, _ ->
                val d = day.text.toString().trim().toIntOrNull() ?: 0
                val a = avg.text.toString().trim().replace(",", "").toDoubleOrNull() ?: 0.0
                val base = b ?: UtilityBill(name = "", dueDay = 1)
                doAction {
                    UtilityBillRepository.save(this, base.copy(name = name.text.toString(), kind = kind, dueDay = d, avgAmount = a, reminder = rem.isChecked))
                    cuToast(t("Saved", "محفوظ ہو گیا")); load()
                }
            }.show()
    }
}
