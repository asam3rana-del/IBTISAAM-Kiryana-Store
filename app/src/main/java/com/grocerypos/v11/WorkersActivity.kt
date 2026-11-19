package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.BooksRuleException
import com.grocerypos.v11.DayUtil
import com.grocerypos.v11.Payroll
import com.grocerypos.v11.R
import com.grocerypos.v11.ShopWorker
import com.grocerypos.v11.WorkerAdvance
import com.grocerypos.v11.WorkerRepository
import com.grocerypos.v11.WorkerSalaryPayment
import com.grocerypos.v11.Ym
import com.grocerypos.v11.money0
import com.grocerypos.v11.ui.components.premiumHeader
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * NEW (Flutter parity, batch 2): Workers — port of Flutter's workers_screen.dart. Admin / manager only.
 * Roz ki hazri (P / A / H / L), advance (Cash OUT, expense nahi) aur mahine ki salary
 * (Expense "Salaries" / "Wages" + Cash OUT sirf net). Sab Flutter app ke saath sync hota hai.
 */
class WorkersActivity : AppCompatActivity() {

    private lateinit var c: CuColors
    private lateinit var content: LinearLayout
    private var workers: List<ShopWorker> = emptyList()
    private var advBal: Map<Long, Double> = emptyMap()
    private var selected: ShopWorker? = null
    private var tab = 0 // 0 attendance, 1 advance, 2 salary
    private var year = 0
    private var month = 0

    private fun t(en: String, ur: String) = Loc.t(this, en, ur)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val role = getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        if (role != "admin" && role != "manager") {
            cuToast(t("Only admin / manager can open Workers", "ورکرز صرف ایڈمن / مینیجر کھول سکتے ہیں")); finish(); return
        }
        c = CuColors(this)
        val cal = Calendar.getInstance()
        year = cal.get(Calendar.YEAR); month = cal.get(Calendar.MONTH) + 1
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(c.bg))
        }
        root.addView(premiumHeader(iconRes = R.drawable.ic_people, title = t("Workers", "ورکرز"),
            subtitle = t("Salary, advance and leave record", "تنخواہ، ایڈوانس اور چھٹی کا ریکارڈ"),
            onBack = { goBack() }))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        root.addView(cuGap(30))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(c.bg)); addView(root) })
        load()
    }

    private fun goBack() { if (selected != null) { selected = null; load() } else finish() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (selected != null) { selected = null; load() } else super.onBackPressed() }

    private fun doAction(block: suspend () -> Unit) = lifecycleScope.launch {
        try { block() } catch (e: BooksRuleException) { cuToast(e.message ?: "") } catch (e: Exception) { cuToast(t("Could not save", "محفوظ نہیں ہو سکا")) }
    }

    private fun load(): kotlinx.coroutines.Job = lifecycleScope.launch {
        workers = WorkerRepository.list(this@WorkersActivity)
        advBal = WorkerRepository.allAdvanceBalances(this@WorkersActivity)
        selected = selected?.let { s -> workers.firstOrNull { it.id == s.id } }
        val s = selected
        if (s == null) renderList() else renderDetail(s)
    }

    private fun rateText(w: ShopWorker) = money0(w.rate) + " / " + (if (w.payType == Payroll.DAILY) t("day", "دن") else t("month", "ماہ"))

    // ------------------------------------------------------------------ list

    private fun renderList() {
        content.removeAllViews()
        content.addView(cuButton("+  " + t("Add Worker", "ورکر شامل کریں"), c.purple) { editWorker(null) })
        val total = advBal.values.sum()
        if (total > 0.009) content.addView(cuCard(c).apply {
            addView(cuText(t("Total advance outstanding", "کل باقی ایڈوانس"), 12f, c.muted, true))
            addView(cuText(money0(total), 18f, c.amber, true))
        })
        if (workers.isEmpty()) {
            content.addView(cuText(t("No workers yet. Tap \"Add Worker\".", "ابھی کوئی ورکر نہیں۔ \"ورکر شامل کریں\" دبائیں۔"), 13f, c.muted).apply {
                gravity = Gravity.CENTER; setPadding(0, cuDp(40), 0, cuDp(40))
            })
            return
        }
        for (w in workers) {
            val card = cuCard(c)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            col.addView(cuText(w.name + (if (w.active) "" else "  (" + t("Left", "فارغ") + ")"), 14.5f, if (w.active) c.text else c.muted, true))
            col.addView(cuText(rateText(w) + (if (w.phone.isNotBlank()) "  •  ${w.phone}" else ""), 11.5f, c.muted))
            row.addView(col)
            val adv = advBal[w.id] ?: 0.0
            if (adv > 0.009) row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                addView(cuText(t("Advance", "ایڈوانس"), 10.5f, c.muted))
                addView(cuText(money0(adv), 13.5f, c.amber, true))
            })
            card.addView(row)
            card.setOnClickListener { selected = w; tab = 0; load() }
            content.addView(card)
        }
    }

    private fun editWorker(w: ShopWorker?) {
        val (scroll, body) = cuDialogBody()
        val name = cuField(t("Name", "نام"), c, initial = w?.name ?: "")
        val phone = cuField(t("Phone (optional)", "فون (اختیاری)"), c, initial = w?.phone ?: "").apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
        body.addView(name); body.addView(phone)
        val type = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rM = RadioButton(this).apply { text = t("Monthly salary", "ماہانہ تنخواہ"); id = 7001 }
        val rD = RadioButton(this).apply { text = t("Daily wage", "روزانہ اجرت"); id = 7002 }
        type.addView(rM); type.addView(rD)
        type.check(if (w?.payType == Payroll.DAILY) 7002 else 7001)
        body.addView(type)
        val rate = cuField(t("Salary per month / daily wage", "ماہانہ تنخواہ / روزانہ اجرت"), c, numeric = true,
            initial = w?.rate?.takeIf { it > 0 }?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() } ?: "")
        body.addView(rate)
        val active = CheckBox(this).apply { text = t("Currently working", "ابھی کام کر رہا ہے"); isChecked = w?.active ?: true }
        if (w != null) body.addView(active)
        AlertDialog.Builder(this).setTitle(if (w == null) t("Add Worker", "ورکر شامل کریں") else t("Edit Worker", "ورکر میں تبدیلی"))
            .setView(scroll).setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Save", "محفوظ کریں")) { _, _ ->
                if (name.text.toString().isBlank()) { cuToast(t("Enter the name", "نام لکھیں")); return@setPositiveButton }
                val r = cuParseAmount(rate.text.toString())
                if (r == null) { cuToast(t("Enter a valid salary / daily rate", "صحیح تنخواہ / روزانہ اجرت لکھیں")); return@setPositiveButton }
                val base = w ?: ShopWorker(name = "")
                doAction {
                    WorkerRepository.save(this, base.copy(name = name.text.toString(), phone = phone.text.toString(),
                        payType = if (type.checkedRadioButtonId == 7002) Payroll.DAILY else Payroll.MONTHLY, rate = r,
                        active = if (w == null) true else active.isChecked))
                    cuToast(t("Saved", "محفوظ ہو گیا")); load()
                }
            }.show()
    }

    // ------------------------------------------------------------------ detail

    private fun renderDetail(w: ShopWorker): kotlinx.coroutines.Job = lifecycleScope.launch {
        val ctx = this@WorkersActivity
        val marks = WorkerRepository.monthAttendance(ctx, w.id, year, month)
        val paid = WorkerRepository.payments(ctx, w.id)
        val advances = WorkerRepository.advances(ctx, w.id)
        val bal = WorkerRepository.advanceBalance(ctx, w.id)
        content.removeAllViews()

        val head = cuCard(c)
        val hr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        hc.addView(cuText(w.name, 16f, c.text, true))
        hc.addView(cuText(rateText(w) + (if (bal > 0.009) "  •  " + t("Advance", "ایڈوانس") + " " + money0(bal) else ""), 12f, c.muted))
        hr.addView(hc)
        if (w.phone.isNotBlank()) hr.addView(cuRound(R.drawable.ic_phone, c.purple, c.purpleBg) { cuDial(w.phone) })
        hr.addView(cuRound(R.drawable.ic_more_vert, c.muted, c.bg) { workerMenu(w) })
        head.addView(hr)
        content.addView(head)

        // month navigator
        val nav = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(cuChip("‹", false, c) { val p = Ym.add(Ym.key(year, month), -1); year = Ym.year(p); month = Ym.month(p); load() })
        nav.addView(cuText(Ym.label(Ym.key(year, month)), 15f, c.text, true).apply {
            gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        nav.addView(cuChip("›", false, c) { val n = Ym.add(Ym.key(year, month), 1); year = Ym.year(n); month = Ym.month(n); load() })
        content.addView(nav)

        val tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(t("Attendance", "حاضری"), t("Advance", "ایڈوانس"), t("Salary", "تنخواہ")).forEachIndexed { i, label ->
            tabs.addView(cuChip(label, tab == i, c) { tab = i; load() })
        }
        content.addView(tabs)

        val counts = Payroll.counts(marks, year, month)
        val calc = Payroll.earned(w.payType, w.rate, year, month, counts)
        val paidThis = paid.firstOrNull { it.month == Ym.key(year, month) }
        when (tab) {
            0 -> renderAttendance(w, marks, counts, paidThis != null)
            1 -> renderAdvance(w, advances, bal)
            else -> renderSalary(w, counts, calc.earned, calc.days, paidThis, paid, bal)
        }
    }

    private fun statusColor(s: String?): Pair<String, String> = when (s) {
        "P" -> c.teal to c.tealBg
        "A" -> c.red to c.bg
        "H" -> c.amber to c.amberBg
        "L" -> c.purple to c.purpleBg
        else -> c.muted to c.card
    }

    private fun renderAttendance(w: ShopWorker, marks: Map<Int, String>, counts: com.grocerypos.v11.AttendanceCounts, locked: Boolean) {
        val sum = cuCard(c)
        sum.addView(cuText("${t("Present", "حاضر")} ${fmt(counts.present)}  •  ${t("Absent", "غیر حاضر")} ${fmt(counts.absent)}  •  " +
            "${t("Half day", "آدھا دن")} ${fmt(counts.half)}  •  ${t("Paid leave", "پیڈ چھٹی")} ${fmt(counts.paidLeave)}", 12f, c.text, true))
        content.addView(sum)
        val grid = cuCard(c)
        val days = Ym.daysIn(year, month)
        val first = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
        // Monday-first offset (Calendar: SUNDAY=1)
        val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val names = listOf(t("Mon", "پیر"), t("Tue", "منگل"), t("Wed", "بدھ"), t("Thu", "جمعرات"), t("Fri", "جمعہ"), t("Sat", "ہفتہ"), t("Sun", "اتوار"))
        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in names) hdr.addView(cuText(n, 10f, c.muted, true).apply { gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        grid.addView(hdr)
        var cell = 0
        var line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun pushCell(v: android.view.View) {
            line.addView(v); cell++
            if (cell % 7 == 0) { grid.addView(line); line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL } }
        }
        repeat(offset) { pushCell(TextView(this).apply { layoutParams = LinearLayout.LayoutParams(0, cuDp(44), 1f) }) }
        for (d in 1..days) {
            val st = marks[d]
            val (fg, bg) = statusColor(st)
            pushCell(TextView(this).apply {
                text = "$d\n${st ?: "·"}"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(fg))
                setTypeface(typeface, Typeface.BOLD)
                background = cuFill(bg, 10)
                layoutParams = LinearLayout.LayoutParams(0, cuDp(44), 1f).apply { setMargins(cuDp(2), cuDp(2), cuDp(2), cuDp(2)) }
                setOnClickListener {
                    if (locked) { cuToast(t("Salary for this month is paid, so attendance is locked.", "اس مہینے کی تنخواہ ادا ہو چکی، حاضری بند ہے۔")); return@setOnClickListener }
                    val next = when (st) { null -> "P"; "P" -> "A"; "A" -> "H"; "H" -> "L"; else -> null }
                    doAction { WorkerRepository.setAttendance(this@WorkersActivity, w.id, year, month, d, next); load() }
                }
            })
        }
        while (cell % 7 != 0) pushCell(TextView(this).apply { layoutParams = LinearLayout.LayoutParams(0, cuDp(44), 1f) })
        content.addView(grid)
        content.addView(cuText(if (w.payType == Payroll.DAILY)
            t("Tap a day: Present → Absent → Half → Paid leave → clear. Unmarked days are not counted.", "دن دبائیں: حاضر ← غیر حاضر ← آدھا ← پیڈ چھٹی ← خالی۔ خالی دن نہیں گنے جاتے۔")
            else t("Tap a day: Present → Absent → Half → Paid leave → clear. Unmarked days count as present.", "دن دبائیں: حاضر ← غیر حاضر ← آدھا ← پیڈ چھٹی ← خالی۔ خالی دن حاضر گنے جاتے ہیں۔"),
            11f, c.muted).apply { setPadding(cuDp(4), 0, cuDp(4), cuDp(10)) })
        if (locked) content.addView(cuText(t("Salary for this month is paid, so attendance is locked.", "اس مہینے کی تنخواہ ادا ہو چکی، حاضری بند ہے۔"), 12f, c.red, true))
        else content.addView(cuButton(t("Mark unmarked days present (till today)", "باقی خالی دن (آج تک) حاضر لگائیں"), c.tealBg, c.teal) {
            val now = Calendar.getInstance()
            val nowKey = Ym.key(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)
            val cur = Ym.key(year, month)
            val upTo = when {
                cur < nowKey -> Ym.daysIn(year, month)
                cur == nowKey -> now.get(Calendar.DAY_OF_MONTH)
                else -> 0
            }
            if (upTo <= 0) { cuToast(t("This month has not started yet", "یہ مہینہ ابھی شروع نہیں ہوا")); return@cuButton }
            doAction { WorkerRepository.markUnmarkedPresent(this, w.id, year, month, upTo); load() }
        })
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun renderAdvance(w: ShopWorker, advances: List<WorkerAdvance>, bal: Double) {
        content.addView(cuCard(c).apply {
            addView(cuText(t("Advance balance", "باقی ایڈوانس"), 12f, c.muted, true))
            addView(cuText(money0(bal), 18f, c.amber, true))
        })
        content.addView(cuButton(t("GIVE ADVANCE", "ایڈوانس دیں"), c.amber) { giveAdvance(w) })
        if (advances.isEmpty()) { content.addView(cuText(t("No advance given yet", "ابھی تک کوئی ایڈوانس نہیں دیا"), 12.5f, c.muted)); return }
        val list = cuCard(c)
        for (a in advances) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, cuDp(6), 0, cuDp(6)) }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            col.addView(cuText(money0(a.amount) + "  •  " + (if (a.method == "bank") t("Bank", "بینک") else t("Cash", "کیش")), 13.5f, c.text, true))
            col.addView(cuText(DayUtil.fmt(a.createdAt) + (if (a.note.isNotBlank()) "  •  ${a.note}" else ""), 11f, c.muted))
            row.addView(col)
            row.addView(cuRound(R.drawable.ic_delete, c.red, c.bg) {
                AlertDialog.Builder(this).setTitle(t("Delete advance", "ایڈوانس حذف کریں")).setMessage(money0(a.amount))
                    .setNegativeButton(t("Cancel", "منسوخ"), null)
                    .setPositiveButton(t("Delete", "حذف کریں")) { _, _ -> doAction { WorkerRepository.deleteAdvance(this, a); load() } }.show()
            })
            list.addView(row)
        }
        content.addView(list)
    }

    private fun methodGroup(): RadioGroup = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        addView(RadioButton(this@WorkersActivity).apply { text = t("Cash", "کیش"); id = 7101 })
        addView(RadioButton(this@WorkersActivity).apply { text = t("Bank", "بینک"); id = 7102 })
        check(7101)
    }

    private fun giveAdvance(w: ShopWorker) {
        val (scroll, body) = cuDialogBody()
        val amount = cuField(t("Amount", "رقم"), c, numeric = true)
        val m = methodGroup()
        val note = cuField(t("Note (optional)", "نوٹ (اختیاری)"), c)
        body.addView(amount); body.addView(m); body.addView(note)
        body.addView(cuText(t("Cash/Bank OUT, not an expense — it is cut from salary later.", "کیش/بینک سے باہر، خرچ نہیں — بعد میں تنخواہ سے کٹتا ہے۔"), 11f, c.muted))
        AlertDialog.Builder(this).setTitle(t("Give Advance", "ایڈوانس دیں") + " • ${w.name}").setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Give", "دیں")) { _, _ ->
                val a = cuParseAmount(amount.text.toString())
                if (a == null) { cuToast(t("Enter a valid amount", "صحیح رقم لکھیں")); return@setPositiveButton }
                doAction { WorkerRepository.addAdvance(this, w, a, if (m.checkedRadioButtonId == 7102) "bank" else "cash", note.text.toString()); load() }
            }.show()
    }

    private fun renderSalary(w: ShopWorker, counts: com.grocerypos.v11.AttendanceCounts, earned: Double, days: Double,
                             paidThis: WorkerSalaryPayment?, history: List<WorkerSalaryPayment>, bal: Double) {
        val card = cuCard(c)
        fun line(k: String, v: String, color: String = c.text, bold: Boolean = false) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cuText(k, 12.5f, c.muted).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            addView(cuText(v, 13.5f, color, bold))
        }
        if (paidThis != null) {
            card.addView(cuText(t("Salary paid", "تنخواہ ادا ہو چکی"), 14f, c.teal, true))
            card.addView(line(t("Earned", "کمائی"), money0(paidThis.earned)))
            card.addView(line(t("Advance cut", "ایڈوانس کٹوتی"), "- " + money0(paidThis.advanceCut)))
            card.addView(line(t("Paid", "ادا کیا"), money0(paidThis.net), c.teal, true))
            content.addView(card)
            content.addView(cuButton(t("Undo this payment", "یہ ادائیگی واپس کریں"), c.bg, c.red) {
                AlertDialog.Builder(this).setTitle(t("Undo salary", "تنخواہ واپس کریں"))
                    .setMessage(t("This removes the salary expense and its cash entry; the advance comes back.", "تنخواہ کا خرچ اور کیش انٹری ہٹ جائے گی؛ ایڈوانس واپس باقی میں آ جائے گا۔"))
                    .setNegativeButton(t("Cancel", "منسوخ"), null)
                    .setPositiveButton(t("Undo", "واپس کریں")) { _, _ -> doAction { WorkerRepository.deletePayment(this, paidThis); load() } }.show()
            })
        } else {
            if (w.payType == Payroll.DAILY) {
                card.addView(line(t("Days counted", "گنے گئے دن"), fmt(days)))
                card.addView(line(t("Daily rate", "روزانہ اجرت"), money0(w.rate)))
            } else {
                card.addView(line(t("Monthly salary", "ماہانہ تنخواہ"), money0(w.rate)))
                if (days > 0) card.addView(line(t("Cut days", "کٹوتی کے دن"), fmt(days)))
            }
            card.addView(line(t("Earned this month", "اس مہینے کی کمائی"), money0(earned), c.text, true))
            if (counts.unmarked > 0) card.addView(cuText(if (w.payType == Payroll.DAILY)
                t("${counts.unmarked} days are unmarked and are not counted.", "${counts.unmarked} دن خالی ہیں اور نہیں گنے گئے۔")
                else t("${counts.unmarked} days are unmarked and count as present.", "${counts.unmarked} دن خالی ہیں اور حاضر گنے گئے۔"), 11f, c.muted))
            card.addView(line(t("Advance balance", "باقی ایڈوانس"), money0(bal)))
            content.addView(card)
            val cut = cuField(t("Advance to deduct", "کتنا ایڈوانس کاٹنا ہے"), c, numeric = true,
                initial = if (bal > 0.009) Math.round(minOf(bal, earned)).toString() else "")
            val m = methodGroup()
            content.addView(cut); content.addView(m)
            content.addView(cuButton(t("PAY SALARY", "تنخواہ دیں"), c.teal) {
                val cv = cut.text.toString().trim().replace(",", "").toDoubleOrNull() ?: 0.0
                if (cv < 0 || cv > bal + 0.005 || cv > earned + 0.005) {
                    cuToast(t("Deduction is more than the advance balance or the earned amount.", "کٹوتی باقی ایڈوانس یا کمائی سے زیادہ ہے۔")); return@cuButton
                }
                val net = earned - cv
                AlertDialog.Builder(this).setTitle(t("Pay salary", "تنخواہ دیں"))
                    .setMessage("${t("Earned", "کمائی")}: ${money0(earned)}\n${t("Advance cut", "ایڈوانس کٹوتی")}: ${money0(cv)}\n${t("Pay now", "ادا کریں")}: ${money0(net)}")
                    .setNegativeButton(t("Cancel", "منسوخ"), null)
                    .setPositiveButton(t("Pay", "ادا کریں")) { _, _ ->
                        doAction {
                            WorkerRepository.paySalary(this, w, year, month, cv, if (m.checkedRadioButtonId == 7102) "bank" else "cash")
                            cuToast(t("Salary paid", "تنخواہ ادا ہو گئی")); load()
                        }
                    }.show()
            })
        }
        content.addView(cuText(t("SALARY HISTORY", "تنخواہ کی ہسٹری"), 11.5f, c.muted, true).apply { setPadding(cuDp(4), cuDp(10), 0, cuDp(6)) })
        if (history.isEmpty()) { content.addView(cuText(t("No salary paid yet", "ابھی تک کوئی تنخواہ ادا نہیں ہوئی"), 12.5f, c.muted)); return }
        val list = cuCard(c)
        for (x in history) list.addView(cuText("${Ym.label(x.month)}  •  ${money0(x.net)}" +
            (if (x.advanceCut > 0) "  (${t("Advance", "ایڈوانس")} ${money0(x.advanceCut)})" else ""), 12.5f, c.text).apply { setPadding(0, cuDp(4), 0, cuDp(4)) })
        content.addView(list)
    }

    private fun workerMenu(w: ShopWorker) {
        val items = arrayOf(t("Edit Worker", "ورکر میں تبدیلی"), t("Delete worker", "ورکر حذف کریں"))
        AlertDialog.Builder(this).setTitle(w.name).setItems(items) { _, i ->
            if (i == 0) editWorker(w) else AlertDialog.Builder(this).setTitle(t("Delete worker", "ورکر حذف کریں"))
                .setMessage(t("Delete ${w.name} and their attendance permanently (on all devices)? Workers with advance/salary records cannot be deleted — mark them as Left.",
                    "${w.name} اور اس کی حاضری ہمیشہ کے لیے (سب ڈیوائسز سے) حذف کریں؟ جس کا ایڈوانس/تنخواہ کا ریکارڈ ہو وہ حذف نہیں ہوتا — اسے فارغ کریں۔"))
                .setNegativeButton(t("Cancel", "منسوخ"), null)
                .setPositiveButton(t("Delete", "حذف کریں")) { _, _ -> doAction { WorkerRepository.delete(this, w.id); selected = null; load() } }.show()
        }.show()
    }
}
