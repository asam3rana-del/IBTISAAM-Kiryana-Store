package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Autopilot
import com.grocerypos.v11.AutopilotItem
import com.grocerypos.v11.AutopilotQueue
import com.grocerypos.v11.CollectionRepository
import com.grocerypos.v11.DayUtil
import com.grocerypos.v11.DueFollowUp
import com.grocerypos.v11.DueParty
import com.grocerypos.v11.Dues
import com.grocerypos.v11.FollowUpMethod
import com.grocerypos.v11.FollowUpOutcome
import com.grocerypos.v11.FollowUps
import com.grocerypos.v11.PaySchedule
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Qist
import com.grocerypos.v11.QistPlan
import com.grocerypos.v11.QistStatus
import com.grocerypos.v11.R
import com.grocerypos.v11.TouchReason
import com.grocerypos.v11.rs0
import com.grocerypos.v11.ui.components.premiumHeader
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * NEW (Flutter parity, batch 1): Wasooli / Collection screen — port of Flutter's
 * collection_hub_screen + wasooli_queue_screen (Autopilot) + due_reminders follow-up sheets
 * + qist_sheet + pay-day picker.
 *
 *  Tab 1 "Aaj ki list" (Autopilot): late / pending / toote wade wali parties ki aaj ki list
 *        (TODO / DONE / WAITING). "Agla bhejo" har tap par agli party ko WhatsApp kholta hai aur
 *        follow-up log mein khud entry likhta hai, taake wo party aaj ke liye DONE ho jaye.
 *  Tab 2 "Sab baqi": saari customer dues, filter chips (late, toota wada, wada pe ...).
 *  Party sheet: follow-up timeline, nateeja / wada likhna, payment ka din, qist plan, "Paisa mila".
 *
 * Follow-up log (due_followups) aur customer ke paySchedule / qistPlan Flutter wali app ke saath
 * sync hote hain.
 */
class CollectionActivity : AppCompatActivity() {

    private lateinit var c: CuColors
    private lateinit var tabRow: LinearLayout
    private lateinit var content: LinearLayout

    private var tab = 0
    private var filter = 0
    private var role = "cashier"
    private var parties: List<DueParty> = emptyList()
    private var followUps: Map<Long, List<DueFollowUp>> = emptyMap()
    private var paySchedules: Map<Long, String> = emptyMap()
    private var qist: Map<Long, QistStatus> = emptyMap()
    private var queue: AutopilotQueue = AutopilotQueue.EMPTY
    private var collectedToday = 0.0
    private var paymentsToday = 0

    /** "Paisa mila" ke liye Party screen kholi — wapas aane par dekhte hain kitna mila. */
    private var awaiting: Triple<Long, String, Double>? = null

    private fun t(en: String, ur: String) = Loc.t(this, en, ur)
    private val isAdminOrManager get() = role == "admin" || role == "manager"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        c = CuColors(this)
        role = getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(c.bg))
        }
        root.addView(premiumHeader(iconRes = R.drawable.ic_alarm, title = t("Collection (Wasooli)", "وصولی"),
            subtitle = t("Remind today + all pending dues", "آج کی یاد دہانی اور تمام بقایا")))
        // NEW (Flutter parity, batch 6): LED board for the shop tablet.
        root.addView(cuButton(t("📺  LED board (Ticker)", "📺  ایل ای ڈی بورڈ"), c.navy) {
            startActivity(android.content.Intent(this, CollectionTickerActivity::class.java))
        })
        root.addView(cuGap(8))
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabRow)
        root.addView(cuGap(8))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        root.addView(cuGap(30))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(c.bg)); addView(root) })
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) load()
    }

    private fun load(): kotlinx.coroutines.Job = lifecycleScope.launch {
        val ctx = this@CollectionActivity
        try {
            val db = PosDatabase.get(ctx)
            val now = System.currentTimeMillis()
            parties = CollectionRepository.dueParties(ctx)
            followUps = CollectionRepository.allFollowUpsByParty(db)
            paySchedules = CollectionRepository.paySchedules(db)
            qist = CollectionRepository.qistActive(db, now)
            queue = Autopilot.build(parties, followUps, now, paySchedules, qist)
            val start = DayUtil.start(now)
            val end = DayUtil.plusDays(now, 1)
            collectedToday = db.paymentDao().customerCollectedBetween(start, end)
            paymentsToday = db.paymentDao().customerPaymentCountBetween(start, end)
        } catch (e: Exception) {
            cuToast(t("Could not load dues", "بقایا لوڈ نہیں ہو سکے"))
        }
        render()
        checkAwaitingPayment()
    }

    private fun render() {
        tabRow.removeAllViews()
        fun tabPill(label: String, v: Int) = cuChip(label, tab == v, c) { tab = v; render() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, cuDp(6), 0) }
            setPadding(0, cuDp(12), 0, cuDp(12))
        }
        tabRow.addView(tabPill(t("Today (${queue.todo.size})", "آج (${queue.todo.size})"), 0))
        tabRow.addView(tabPill(t("All dues (${parties.size})", "سب بقایا (${parties.size})"), 1))
        content.removeAllViews()
        if (tab == 0) renderAutopilot() else renderAll()
    }

    // ------------------------------------------------------------------ Autopilot tab

    private fun renderAutopilot() {
        val q = queue
        val card = cuCard(c)
        val planned = q.planned
        val doneN = q.done.size
        card.addView(cuText(when {
            planned == 0 -> t("Nobody needs a reminder today", "آج کسی کو یاد دہانی کی ضرورت نہیں")
            q.isClear -> t("All reminders done for today", "آج کی سب یاد دہانیاں ہو گئیں")
            else -> t("$doneN of $planned reminders done today", "آج $planned میں سے $doneN یاد دہانیاں ہو گئیں")
        }, 14.5f, c.text, true))
        card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = if (planned == 0) 100 else (doneN * 100 / planned)
            progressTintList = ColorStateList.valueOf(Color.parseColor(if (q.isClear) c.teal else c.amber))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cuDp(10)).apply { setMargins(0, cuDp(8), 0, cuDp(8)) }
        })
        card.addView(kv(t("Collected today", "آج وصول ہوا"),
            rs0(collectedToday) + (if (paymentsToday > 0) "  ($paymentsToday)" else ""), if (collectedToday > 0) c.teal else c.muted))
        if (q.todo.isNotEmpty()) card.addView(kv(t("Still to remind", "یاد دہانی باقی"), "${q.todo.size} • ${rs0(q.todoAmount)}", c.red))
        content.addView(card)

        val next = q.todo.firstOrNull { it.party.partyPhone.isNotBlank() }
        if (next != null) {
            content.addView(cuButton(t("Send next: ${next.party.partyName} (${rs0(next.total)})",
                "اگلا بھیجیں: ${next.party.partyName} (${rs0(next.total)})"), c.whatsapp) { sendReminder(next.party) })
        }
        section(t("TO REMIND TODAY", "آج یاد دہانی باقی") + " (${q.todo.size})", q.todo, actions = true)
        section(t("DONE TODAY", "آج ہو چکا") + " (${q.done.size})", q.done, actions = false)
        section(t("LATER", "بعد میں") + " (${q.waiting.size})", q.waiting, actions = false)
    }

    private fun kv(k: String, v: String, vColor: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(cuText(k, 12f, c.muted, true).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        addView(cuText(v, 14f, vColor, true))
    }

    private fun section(title: String, items: List<AutopilotItem>, actions: Boolean) {
        if (items.isEmpty()) return
        content.addView(cuText(title, 11.5f, c.muted, true).apply { setPadding(cuDp(4), cuDp(10), 0, cuDp(6)) })
        val card = cuCard(c)
        for (e in items) card.addView(autoRow(e, actions))
        content.addView(card)
    }

    private fun reasonText(e: AutopilotItem): Pair<String, String> = when (e.reason) {
        TouchReason.BROKEN_PROMISE -> t("Promise broken", "وعدہ ٹوٹا") to c.red
        TouchReason.PROMISE_TODAY -> t("Promised for today", "آج کا وعدہ") to c.amber
        TouchReason.QIST_DUE -> t("Installment due", "قسط کی تاریخ") to c.red
        TouchReason.PAY_DAY -> t("Payment day today", "آج ادائیگی کا مقررہ دن") to c.teal
        TouchReason.FOLLOW_UP_DUE -> t("Follow-up due today", "آج فالو اپ کا دن") to c.amber
        TouchReason.DUE_TODAY -> t("Due today", "آج ادائیگی کا دن") to c.amber
        TouchReason.LATE -> (if (e.oldestDays > 0) t("${e.oldestDays} days late", "${e.oldestDays} دن لیٹ") else t("Late", "لیٹ")) to c.red
    }

    private fun contactText(e: AutopilotItem): String {
        if (e.party.partyPhone.isBlank()) return t("No phone number", "فون نمبر نہیں")
        if (e.daysUntilTouch > 0) return t("Remind again in ${e.daysUntilTouch} day(s)", "${e.daysUntilTouch} دن بعد دوبارہ")
        val d = e.daysSinceTouch ?: return t("Never contacted", "ابھی تک بات نہیں ہوئی")
        return if (d == 0) t("Contacted today", "آج بات ہو چکی") else t("Last contact $d day(s) ago", "آخری بات $d دن پہلے")
    }

    private fun autoRow(e: AutopilotItem, actions: Boolean): LinearLayout {
        val (reason, color) = reasonText(e)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, cuDp(7), 0, cuDp(7))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(cuText(e.party.partyName, 13.5f, c.text, true).apply { maxLines = 1 })
        col.addView(cuText("${rs0(e.total)} • $reason", 11.5f, color, true).apply { maxLines = 1 })
        col.addView(cuText(contactText(e), 11f, c.muted).apply { maxLines = 1 })
        row.addView(col)
        if (actions) {
            if (e.party.partyPhone.isNotBlank()) {
                row.addView(cuRound(R.drawable.ic_send, c.whatsapp, c.whatsappBg) { sendReminder(e.party) })
                row.addView(cuRound(R.drawable.ic_phone, c.purple, c.purpleBg) { call(e.party) })
            }
            row.addView(cuRound(R.drawable.ic_check, c.teal, c.tealBg) { received(e.party) })
        }
        row.setOnClickListener { openPartySheet(e.party) }
        return row
    }

    // ------------------------------------------------------------------ All dues tab

    private fun renderAll() {
        val now = System.currentTimeMillis()
        val filters = listOf(
            t("All", "سب"), t("Today's work", "آج کا کام"), t("Late", "لیٹ"), t("Promise broken", "وعدہ ٹوٹا"),
            t("On promise", "وعدے پر"), t("Never contacted", "کبھی بات نہیں"), t("Dispute", "تنازع")
        )
        val (scroll, chipRow) = cuChipRow()
        filters.forEachIndexed { i, label -> chipRow.addView(cuChip(label, filter == i, c) { filter = i; render() }) }
        content.addView(scroll)

        val shown = parties.filter { p ->
            val st = FollowUps.state(FollowUps.forCurrentDebt(followUps[p.partyId] ?: emptyList(), p.debtSince), now)
            when (filter) {
                1 -> Autopilot.classify(p, st, now).inFocus
                2 -> Autopilot.classify(p, st, now).late
                3 -> st.promiseBroken
                4 -> st.onPromise
                5 -> st.never
                6 -> st.dispute
                else -> true
            }
        }
        val summary = cuCard(c)
        summary.addView(kv(t("Parties", "پارٹیاں"), "${shown.size}", c.text))
        summary.addView(kv(t("Total due", "کل بقایا"), rs0(shown.sumOf { it.total }), c.red))
        content.addView(summary)
        if (shown.isEmpty()) {
            content.addView(cuText(t("Nothing here", "یہاں کچھ نہیں"), 13f, c.muted).apply { gravity = Gravity.CENTER; setPadding(0, cuDp(30), 0, cuDp(30)) })
            return
        }
        val list = cuCard(c)
        for (p in shown) list.addView(dueRow(p, now))
        content.addView(list)
    }

    private fun dueRow(p: DueParty, now: Long): LinearLayout {
        val id = p.partyId ?: 0L
        val st = FollowUps.state(FollowUps.forCurrentDebt(followUps[id] ?: emptyList(), p.debtSince), now)
        val b = Autopilot.classify(p, st, now)
        val (badge, color) = when {
            st.promiseBroken -> t("Promise broken", "وعدہ ٹوٹا") to c.red
            st.dispute -> t("Dispute", "تنازع") to c.muted
            st.onPromise -> (t("Promised: ", "وعدہ: ") + DayUtil.fmtShort(st.promise!!.promiseDate)) to c.teal
            b.late -> (if (p.oldestAgeDays(now) > 0) t("${p.oldestAgeDays(now)} days late", "${p.oldestAgeDays(now)} دن لیٹ") else t("Late", "لیٹ")) to c.red
            b.dueToday -> t("Due today", "آج ادائیگی") to c.amber
            p.earliestDue > 0 -> (t("Due: ", "تاریخ: ") + DayUtil.fmtShort(p.earliestDue)) to c.muted
            else -> t("No date set", "تاریخ مقرر نہیں") to c.muted
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, cuDp(8), 0, cuDp(8))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(cuText(p.partyName, 13.5f, c.text, true).apply { maxLines = 1 })
        val bills = p.billCount
        col.addView(cuText("$bills ${t(if (bills == 1) "bill" else "bills", "بل")} • $badge", 11.5f, color, true).apply { maxLines = 1 })
        val extra = mutableListOf<String>()
        PaySchedule.parse(paySchedules[id] ?: "")?.let { extra.add(PaySchedule.label(paySchedules[id] ?: "", Loc.isUrdu(this))) }
        qist[id]?.let { if (!it.completed) extra.add(t("Qist ${it.paidCount}/${it.plan.count}", "قسط ${it.paidCount}/${it.plan.count}")) }
        if (st.last != null) extra.add(t("Last contact: ", "آخری بات: ") + DayUtil.fmtShort(st.last.createdAt))
        if (extra.isNotEmpty()) col.addView(cuText(extra.joinToString("  •  "), 11f, c.muted).apply { maxLines = 1 })
        row.addView(col)
        row.addView(cuText(rs0(p.total), 14f, c.red, true))
        row.setOnClickListener { openPartySheet(p) }
        return row
    }

    // ------------------------------------------------------------------ actions

    private fun sendReminder(p: DueParty) {
        val id = p.partyId ?: return
        if (p.partyPhone.isBlank()) { cuToast(t("No phone number", "فون نمبر نہیں")); return }
        val msg = Dues.reminderText(p, followUps[id] ?: emptyList())
        if (!cuOpenWhatsApp(p.partyPhone, msg)) return
        lifecycleScope.launch {
            CollectionRepository.addFollowUp(this@CollectionActivity, id, p.partyName, FollowUpMethod.WHATSAPP, FollowUpOutcome.SENT)
            load()
        }
    }

    private fun call(p: DueParty) {
        val id = p.partyId ?: return
        if (!cuDial(p.partyPhone)) return
        lifecycleScope.launch {
            CollectionRepository.addFollowUp(this@CollectionActivity, id, p.partyName, FollowUpMethod.CALL, FollowUpOutcome.SENT)
            load()
        }
    }

    /** "Paisa mila": Party screen ka payment dialog kholo; wapas aane par follow-up khud likhte hain. */
    private fun received(p: DueParty) {
        val id = p.partyId ?: return
        awaiting = Triple(id, p.partyName, p.total)
        startActivity(Intent(this, PartyTransactionActivity::class.java)
            .putExtra("partyId", id).putExtra("partyName", p.partyName)
            .putExtra("isCustomer", true).putExtra("openPayment", true))
    }

    private fun checkAwaitingPayment() {
        val (id, name, before) = awaiting ?: return
        awaiting = null
        val after = parties.firstOrNull { it.partyId == id }?.total ?: 0.0
        val got = before - after
        if (got <= 0.009) return
        fun save(promise: Long) {
            lifecycleScope.launch {
                CollectionRepository.addFollowUp(
                    this@CollectionActivity, id, name, FollowUpMethod.NOTE,
                    if (after <= 0.009) FollowUpOutcome.PAID else FollowUpOutcome.PARTIAL,
                    got, promise, 0L, if (promise > 0) "Paisa mila · baqi ka wada" else "Paisa mila"
                )
                load()
            }
        }
        if (after <= 0.009) { save(0L); return }
        val choices = arrayOf(t("Tomorrow", "کل"), t("In 3 days", "3 دن میں"), t("In 7 days", "7 دن میں"), t("Pick date…", "تاریخ چنیں…"), t("Not now", "ابھی نہیں"))
        AlertDialog.Builder(this)
            .setTitle(t("Remaining ${rs0(after)} — when will $name pay?", "باقی ${rs0(after)} — $name کب دے گا؟"))
            .setItems(choices) { _, i ->
                when (i) {
                    0 -> save(DayUtil.plusDays(System.currentTimeMillis(), 1))
                    1 -> save(DayUtil.plusDays(System.currentTimeMillis(), 3))
                    2 -> save(DayUtil.plusDays(System.currentTimeMillis(), 7))
                    3 -> cuPickDate(0L) { d -> save(d) }
                    else -> save(0L)
                }
            }
            .setOnCancelListener { save(0L) }
            .show()
    }

    // ------------------------------------------------------------------ party sheet

    private fun openPartySheet(p: DueParty) {
        val id = p.partyId ?: return
        lifecycleScope.launch {
            val db = PosDatabase.get(this@CollectionActivity)
            val entries = db.dueFollowUpDao().forParty(id)
            val customer = db.customerDao().find(id)
            val qs = customer?.let { CollectionRepository.qistStatus(db, it) }
            showPartySheet(p, entries, customer?.paySchedule ?: "", qs)
        }
    }

    private fun showPartySheet(p: DueParty, entries: List<DueFollowUp>, paySchedule: String, qs: QistStatus?) {
        val id = p.partyId ?: return
        val now = System.currentTimeMillis()
        val (scroll, body) = cuDialogBody()
        var dialog: AlertDialog? = null
        val cur = FollowUps.forCurrentDebt(entries, p.debtSince)
        val st = FollowUps.state(cur, now)
        val rec = FollowUps.promiseRecord(cur, now)
        body.addView(cuText("${rs0(p.total)} ${t("due", "واجب الادا")}", 18f, c.red, true))
        if (rec.first > 0) body.addView(cuText(t("Promises: ${rec.second} of ${rec.first} kept", "وعدے: ${rec.first} میں سے ${rec.second} پورے"), 12f, c.muted))
        if (st.promiseBroken) body.addView(cuText(t("Promise broken: ", "وعدہ ٹوٹا: ") + DayUtil.fmt(st.promise!!.promiseDate), 12f, c.red, true))
        else if (st.onPromise) body.addView(cuText(t("Promised: ", "وعدہ: ") + DayUtil.fmt(st.promise!!.promiseDate), 12f, c.teal, true))
        body.addView(cuText(t("Payment day: ", "ادائیگی کا دن: ") + PaySchedule.label(paySchedule, Loc.isUrdu(this)), 12f, c.muted))
        if (qs != null) {
            body.addView(cuText(
                if (qs.completed) t("Installments: all paid", "قسطیں: سب ادا")
                else t("Installment ${qs.nextIndex + 1}/${qs.plan.count}: ${rs0(qs.nextRemaining)} on ${DayUtil.fmt(qs.nextDate)}",
                    "قسط ${qs.nextIndex + 1}/${qs.plan.count}: ${rs0(qs.nextRemaining)} — ${DayUtil.fmt(qs.nextDate)}") +
                    (if (qs.overdue > 0.009) t(" • late ${rs0(qs.overdue)}", " • لیٹ ${rs0(qs.overdue)}") else ""),
                12f, if (qs.overdue > 0.009) c.red else c.muted, qs.overdue > 0.009))
        }
        // Bills
        body.addView(cuText(t("BILLS", "بل"), 11.5f, c.muted, true).apply { setPadding(0, cuDp(10), 0, cuDp(4)) })
        for (bl in p.bills) {
            val dateTxt = when {
                bl.isBalance -> ""
                bl.dueDate > 0 -> "  •  " + t("due ", "تاریخ ") + DayUtil.fmtShort(bl.dueDate)
                bl.createdAt > 0 -> "  •  " + DayUtil.fmtShort(bl.createdAt)
                else -> ""
            }
            body.addView(cuText("${bl.id}: ${rs0(bl.due)}$dateTxt", 12f,
                if (bl.dueDate in 1L until DayUtil.today()) c.red else c.text).apply { setPadding(0, cuDp(1), 0, cuDp(1)) })
        }
        // Actions
        body.addView(cuGap(8))
        body.addView(cuButton(t("Add note / result", "نوٹ / نتیجہ شامل کریں"), c.purple) {
            dialog?.dismiss(); openResultEditor(p, null)
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (p.partyPhone.isNotBlank()) {
            actions.addView(cuRound(R.drawable.ic_send, c.whatsapp, c.whatsappBg) { dialog?.dismiss(); sendReminder(p) })
            actions.addView(cuRound(R.drawable.ic_phone, c.purple, c.purpleBg) { dialog?.dismiss(); call(p) })
        }
        actions.addView(cuRound(R.drawable.ic_check, c.teal, c.tealBg) { dialog?.dismiss(); received(p) })
        actions.addView(cuRound(R.drawable.ic_calendar, c.amber, c.amberBg) { dialog?.dismiss(); openPayDay(id, paySchedule) })
        if (isAdminOrManager) actions.addView(cuRound(R.drawable.ic_list, c.navy, c.bg) { dialog?.dismiss(); openQist(p, qs) })
        body.addView(actions)
        body.addView(cuText(t("Send • Call • Payment received • Payment day" + (if (isAdminOrManager) " • Installments" else ""),
            "بھیجیں • کال • رقم ملی • ادائیگی کا دن" + (if (isAdminOrManager) " • قسطیں" else "")), 10.5f, c.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, cuDp(4), 0, cuDp(8))
        })
        // Timeline
        body.addView(cuText(t("FOLLOW-UP HISTORY", "فالو اپ ہسٹری"), 11.5f, c.muted, true).apply { setPadding(0, cuDp(6), 0, cuDp(4)) })
        if (entries.isEmpty()) body.addView(cuText(t("No follow-up recorded yet", "ابھی کوئی فالو اپ درج نہیں"), 12f, c.muted))
        for (e in entries) {
            val box = cuCard(c)
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(cuText("${FollowUps.methodLabel(this, e.method)} • ${FollowUps.outcomeLabel(this, e.outcome)}", 12.5f,
                if (e.outcome == FollowUpOutcome.SENT) c.amber else c.text, true))
            val bits = mutableListOf(DayUtil.fmt(e.createdAt))
            if (e.amount > 0) bits.add(rs0(e.amount))
            if (e.promiseDate > 0) bits.add(t("Promise: ", "وعدہ: ") + DayUtil.fmtShort(e.promiseDate))
            if (e.nextFollowUp > 0) bits.add(t("Next: ", "اگلا: ") + DayUtil.fmtShort(e.nextFollowUp))
            if (e.staff.isNotBlank()) bits.add(e.staff)
            col.addView(cuText(bits.joinToString("  •  "), 11f, c.muted))
            if (e.note.isNotBlank()) col.addView(cuText(e.note, 11.5f, c.text))
            line.addView(col)
            line.addView(cuRound(R.drawable.ic_edit, c.purple, c.purpleBg) { dialog?.dismiss(); openResultEditor(p, e) })
            // Delete sirf admin / manager (live Firestore rules cashier device ko delete nahi karne dete).
            if (isAdminOrManager) line.addView(cuRound(R.drawable.ic_delete, c.red, c.bg) {
                AlertDialog.Builder(this).setTitle(t("Delete this entry?", "یہ اندراج ہٹائیں؟"))
                    .setNegativeButton(t("Cancel", "منسوخ"), null)
                    .setPositiveButton(t("Delete", "ہٹائیں")) { _, _ ->
                        dialog?.dismiss()
                        lifecycleScope.launch { CollectionRepository.deleteFollowUp(this@CollectionActivity, e.id); load() }
                    }.show()
            })
            box.addView(line)
            body.addView(box)
        }
        dialog = AlertDialog.Builder(this).setTitle(p.partyName).setView(scroll)
            .setNegativeButton(t("Close", "بند"), null)
            .setNeutralButton(t("Open ledger", "کھاتہ کھولیں")) { _, _ ->
                startActivity(Intent(this, PartyTransactionActivity::class.java)
                    .putExtra("partyId", id).putExtra("partyName", p.partyName).putExtra("isCustomer", true))
            }
            .show()
    }

    // ------------------------------------------------------------------ result editor

    /** [existing] ho (jaise "sent" entry) to wahi update; warna nayi note entry. */
    private fun openResultEditor(p: DueParty, existing: DueFollowUp?) {
        val id = p.partyId ?: return
        val (scroll, body) = cuDialogBody()
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val ids = HashMap<Int, String>()
        FollowUpOutcome.CHOICES.forEachIndexed { i, o ->
            val rb = RadioButton(this).apply { text = FollowUps.outcomeLabel(this@CollectionActivity, o); this.id = 5000 + i }
            ids[5000 + i] = o
            group.addView(rb)
        }
        body.addView(cuText(t("Result", "نتیجہ"), 12f, c.muted, true))
        body.addView(group)
        val amount = cuField(t("Amount (optional)", "رقم (اختیاری)"), c, numeric = true,
            initial = if (existing != null && existing.amount > 0) existing.amount.roundToLong().toString() else "")
        body.addView(amount)
        val promise = CuDateField(this, c, t("Promised date", "وعدے کی تاریخ"), existing?.promiseDate ?: 0L)
        val nextF = CuDateField(this, c, t("Next follow-up", "اگلا فالو اپ"), existing?.nextFollowUp ?: 0L)
        body.addView(promise.view)
        val (pScroll, pRow) = cuChipRow()
        listOf(1 to t("Tomorrow", "کل"), 3 to t("3 days", "3 دن"), 7 to t("7 days", "7 دن")).forEach { (d, label) ->
            pRow.addView(cuChip(label, false, c) { promise.update(DayUtil.plusDays(System.currentTimeMillis(), d)) })
        }
        body.addView(pScroll)
        body.addView(nextF.view)
        val (nScroll, nRow) = cuChipRow()
        listOf(1 to t("Tomorrow", "کل"), 3 to t("3 days", "3 دن"), 7 to t("7 days", "7 دن")).forEach { (d, label) ->
            nRow.addView(cuChip(label, false, c) { nextF.update(DayUtil.plusDays(System.currentTimeMillis(), d)) })
        }
        body.addView(nScroll)
        val note = cuField(t("Note (optional) — e.g. said he will pay on Friday", "نوٹ (اختیاری) — مثلاً: جمعہ کو دینے کا کہا"), c, initial = existing?.note ?: "")
        body.addView(note)
        body.addView(cuText(t("This is only a note. Record the payment itself with \"Payment Received\".",
            "یہ صرف نوٹ ہے۔ اصل رقم \"رقم ملی\" سے درج کریں۔"), 11f, c.muted))

        val initialOutcome = when {
            existing == null -> FollowUpOutcome.NOTE
            existing.outcome == FollowUpOutcome.SENT -> ""
            else -> existing.outcome
        }
        ids.entries.firstOrNull { it.value == initialOutcome }?.let { group.check(it.key) }
        group.setOnCheckedChangeListener { _, checked ->
            val now = System.currentTimeMillis()
            when (ids[checked]) {
                FollowUpOutcome.PROMISED -> {
                    if (promise.value <= 0) promise.update(DayUtil.plusDays(now, 1))
                    nextF.update(promise.value)
                }
                FollowUpOutcome.PARTIAL -> { promise.update(0L); nextF.update(DayUtil.plusDays(now, 3)) }
                FollowUpOutcome.NO_ANSWER -> { promise.update(0L); nextF.update(DayUtil.plusDays(now, 1)) }
                FollowUpOutcome.REFUSED -> { promise.update(0L); nextF.update(DayUtil.plusDays(now, 3)) }
                else -> { promise.update(0L); nextF.update(0L) }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing != null) "${FollowUps.methodLabel(this, existing.method)} • " + t("What happened?", "کیا ہوا؟")
                else t("Follow-up note", "فالو اپ نوٹ") + " — ${p.partyName}")
            .setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Save", "محفوظ کریں")) { _, _ ->
                val outcome = ids[group.checkedRadioButtonId] ?: ""
                if (outcome.isEmpty()) { cuToast(t("Pick what happened", "بتائیں کیا ہوا")); return@setPositiveButton }
                val isPromise = outcome == FollowUpOutcome.PROMISED
                if (isPromise && promise.value <= 0) { cuToast(t("Pick the promised date", "وعدے کی تاریخ چنیں")); return@setPositiveButton }
                val amt = amount.text.toString().trim().replace(",", "").toDoubleOrNull() ?: 0.0
                val pd = if (isPromise || outcome == FollowUpOutcome.PARTIAL) promise.value else 0L
                val nx = if (outcome == FollowUpOutcome.PAID) 0L else nextF.value
                lifecycleScope.launch {
                    val ctx = this@CollectionActivity
                    if (existing != null) CollectionRepository.setFollowUpResult(ctx, existing.id, outcome, amt, pd, nx, note.text.toString())
                    else CollectionRepository.addFollowUp(ctx, id, p.partyName, FollowUpMethod.NOTE, outcome, amt, pd, nx, note.text.toString())
                    cuToast(t("Saved", "محفوظ ہو گیا"))
                    load()
                }
            }.show()
    }

    // ------------------------------------------------------------------ pay day + qist

    private fun openPayDay(customerId: Long, current: String) {
        val urdu = Loc.isUrdu(this)
        val labels = PaySchedule.CHOICES.map { PaySchedule.label(it, urdu) }.toTypedArray()
        val sel = PaySchedule.CHOICES.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(t("Payment day (when this customer has money)", "ادائیگی کا دن (جب کسٹمر کے پاس پیسہ ہوتا ہے)"))
            .setSingleChoiceItems(labels, sel) { d, i ->
                d.dismiss()
                lifecycleScope.launch {
                    CollectionRepository.setPaySchedule(this@CollectionActivity, customerId, PaySchedule.CHOICES[i])
                    cuToast(t("Saved", "محفوظ ہو گیا"))
                    load()
                }
            }
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .show()
    }

    private fun openQist(p: DueParty, current: QistStatus?) {
        val id = p.partyId ?: return
        if (!isAdminOrManager) { cuToast(t("Only admin / manager can make an installment plan", "قسط پلان صرف ایڈمن / مینیجر بنا سکتا ہے")); return }
        val (scroll, body) = cuDialogBody()
        if (current != null) {
            body.addView(cuText(t("Current plan: ${rs0(current.plan.total)} in ${current.plan.count} installments • paid ${rs0(current.paid)}",
                "موجودہ پلان: ${rs0(current.plan.total)} — ${current.plan.count} قسطیں • ادا ${rs0(current.paid)}"), 12f, c.muted, true))
            for (i in 0 until current.plan.count) {
                val done = i < current.paidCount
                body.addView(cuText("${i + 1}. ${DayUtil.fmt(current.plan.dateOf(i))}  —  ${rs0(current.plan.amountOf(i))}" +
                    (if (done) "  ✓" else ""), 11.5f, if (done) c.teal else if (current.plan.dateOf(i) < DayUtil.today()) c.red else c.text))
            }
            body.addView(cuGap(10))
        }
        val total = cuField(t("Total amount", "کل رقم"), c, numeric = true, initial = p.total.roundToLong().toString())
        body.addView(total)
        val counts = listOf(2, 3, 4, 5, 6, 8, 10, 12)
        var count = 4
        var every = 30
        body.addView(cuText(t("Number of installments", "قسطوں کی تعداد"), 12f, c.muted, true))
        val (cScroll, cRow) = cuChipRow()
        fun drawCounts() {
            cRow.removeAllViews()
            counts.forEach { n -> cRow.addView(cuChip("$n", count == n, c) { count = n; drawCounts() }) }
        }
        drawCounts()
        body.addView(cScroll)
        body.addView(cuText(t("Every", "ہر"), 12f, c.muted, true))
        val (eScroll, eRow) = cuChipRow()
        fun drawEvery() {
            eRow.removeAllViews()
            listOf(7 to t("Weekly", "ہر ہفتے"), 15 to t("Every 15 days", "ہر 15 دن"), 30 to t("Monthly (30 days)", "ہر مہینے (30 دن)")).forEach { (d, label) ->
                eRow.addView(cuChip(label, every == d, c) { every = d; drawEvery() })
            }
        }
        drawEvery()
        body.addView(eScroll)
        val first = CuDateField(this, c, t("First installment", "پہلی قسط"), DayUtil.plusDays(System.currentTimeMillis(), 7), clearable = false)
        body.addView(first.view)
        val b = AlertDialog.Builder(this).setTitle(t("Installment plan", "قسط پلان") + " — ${p.partyName}").setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Save plan", "پلان محفوظ")) { _, _ ->
                val amt = cuParseAmount(total.text.toString())
                if (amt == null) { cuToast(t("Enter a valid amount", "درست رقم لکھیں")); return@setPositiveButton }
                val plan = QistPlan(amt, count, first.value, every, System.currentTimeMillis())
                if (!plan.valid) { cuToast(t("Enter a valid amount", "درست رقم لکھیں")); return@setPositiveButton }
                lifecycleScope.launch {
                    CollectionRepository.saveQistPlan(this@CollectionActivity, id, plan)
                    cuToast(t("Saved", "محفوظ ہو گیا"))
                    load()
                }
            }
        if (current != null) {
            b.setNeutralButton(t("Remove plan", "پلان ہٹائیں")) { _, _ ->
                lifecycleScope.launch {
                    CollectionRepository.saveQistPlan(this@CollectionActivity, id, null)
                    cuToast(t("Removed", "ہٹا دیا"))
                    load()
                }
            }
        }
        b.show()
    }
}
