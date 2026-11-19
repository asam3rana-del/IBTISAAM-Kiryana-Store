package com.grocerypos.v11

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// =====================================================================================
// NEW (Flutter parity, batch 1): PURE logic ported 1:1 from the Flutter app (Hanna POS /
// AH-Developer, branch Raffey):
//   lib/models/return_entry.dart      -> Amanat / Loan entries + log
//   lib/models/due_followup.dart      -> follow-up log rules (wada, toota wada, shiddat)
//   lib/models/pay_schedule.dart      -> customer ka payment din
//   lib/models/qist_plan.dart         -> qist (installment) plan
//   lib/models/due_renewal.dart       -> partial payment par due date aage
//   lib/db/due_reminders_repository   -> DueBill / DueParty grouping
//   lib/db/due_followup_repository    -> classifyCollection
//   lib/models/collection_autopilot   -> Wasooli Autopilot queue
//   lib/models/collection_plan        -> itemized reminder
// No DB / UI here. JSON shapes and field names are IDENTICAL to Flutter so the same
// customer doc (returnEntries / returnLog / qistPlan / paySchedule) works on both apps.
// =====================================================================================

const val DAY_MS = 86_400_000L

object DayUtil {
    /** Local midnight of [millis]. */
    fun start(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Local midnight of (day of [millis]) + [days] calendar days (DST-safe). */
    fun plusDays(millis: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = start(millis)
        add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis

    fun today(now: Long = System.currentTimeMillis()): Long = start(now)

    /** Whole calendar days from [from] to [to] (both snapped to midnight). */
    fun daysBetween(from: Long, to: Long): Int = ((start(to) - start(from)).toDouble() / DAY_MS).roundToInt()

    fun fmt(millis: Long): String =
        java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date(millis))

    fun fmtShort(millis: Long): String =
        java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH).format(java.util.Date(millis))
}

fun rs0(v: Double): String = "Rs ${v.roundToLong()}"

// ------------------------------------------------------------------------------ Amanat / Loan

/** Ek Amanat / Loan entry. [amount] hamesha musbat; asar `kind` se (amanat = minus, loan = plus). */
data class ReturnEntry(
    val id: Int,
    val amount: Double,
    val date: Long = 0L,
    val returnDate: Long = 0L,
    val note: String = ""
)

object ReturnEntries {
    fun decode(raw: String?): MutableList<ReturnEntry> {
        val out = mutableListOf<ReturnEntry>()
        if (raw.isNullOrBlank()) return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                if (!m.has("id") || !m.has("amount")) continue
                val id = m.optDouble("id", Double.NaN)
                val amount = m.optDouble("amount", Double.NaN)
                if (id.isNaN() || amount.isNaN()) continue
                out.add(
                    ReturnEntry(
                        id = id.toInt(),
                        amount = abs(amount),
                        date = m.optLong("date", 0L),
                        returnDate = m.optLong("returnDate", 0L),
                        note = m.optString("note", "")
                    )
                )
            }
        } catch (_: Exception) {
            return mutableListOf()
        }
        return out
    }

    fun encode(list: List<ReturnEntry>): String {
        if (list.isEmpty()) return ""
        val arr = JSONArray()
        for (e in list) arr.put(
            JSONObject().put("id", e.id).put("amount", e.amount).put("date", e.date)
                .put("returnDate", e.returnDate).put("note", e.note)
        )
        return arr.toString()
    }

    fun signed(kind: String, amount: Double): Double = if (kind == "amanat") -abs(amount) else abs(amount)
    fun signedTotal(kind: String, list: List<ReturnEntry>): Double = list.sumOf { signed(kind, it.amount) }
    fun total(list: List<ReturnEntry>): Double = list.sumOf { it.amount }
    fun nextId(list: List<ReturnEntry>): Int = (list.maxOfOrNull { it.id } ?: 0) + 1

    fun earliestReturnDate(list: List<ReturnEntry>): Long {
        var best = 0L
        for (e in list) if (e.returnDate > 0 && (best == 0L || e.returnDate < best)) best = e.returnDate
        return best
    }

    /** FIFO wapsi / wasooli (sab se purani wapsi-tarikh pehle). Bachi entries + asal settle raqam. */
    fun settleFifo(entries: List<ReturnEntry>, amount: Double): Pair<List<ReturnEntry>, Double> {
        val order = entries.sortedWith(compareBy<ReturnEntry>(
            { if (it.returnDate > 0) it.returnDate else Long.MAX_VALUE },
            { it.date },
            { it.id }
        ))
        var left = amount
        var settled = 0.0
        val byId = LinkedHashMap<Int, ReturnEntry?>()
        for (e in entries) byId[e.id] = e
        for (e in order) {
            if (left <= 0.004) break
            if (left + 0.004 >= e.amount) {
                left -= e.amount; settled += e.amount; byId[e.id] = null
            } else {
                byId[e.id] = e.copy(amount = e.amount - left); settled += left; left = 0.0
            }
        }
        return Pair(entries.mapNotNull { byId[it.id] }, settled)
    }
}

const val LOG_GIVEN = "given"
const val LOG_RECOVERED = "recovered"
const val LOG_ADJUSTED = "adjusted"
const val LOG_REMOVED = "removed"

/** Amanat / Loan history row (sirf dikhane ke liye; balance openingBalance mein hai). */
data class ReturnLogRow(
    val id: Int,
    val type: String,
    val kind: String,
    val amount: Double,
    val date: Long = 0L,
    val note: String = "",
    val entryId: Int = 0
) {
    val balanceEffect: Double
        get() {
            val sign = if (kind == "amanat") -1.0 else 1.0
            return when (type) {
                LOG_GIVEN, LOG_ADJUSTED -> sign * amount
                LOG_RECOVERED, LOG_REMOVED -> -sign * abs(amount)
                else -> 0.0
            }
        }
}

object ReturnLog {
    private val TYPES = setOf(LOG_GIVEN, LOG_RECOVERED, LOG_ADJUSTED, LOG_REMOVED)

    fun decode(raw: String?): MutableList<ReturnLogRow> {
        val out = mutableListOf<ReturnLogRow>()
        if (raw.isNullOrBlank()) return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val type = m.optString("type", "")
                if (type !in TYPES || !m.has("id") || !m.has("amount")) continue
                val amount = m.optDouble("amount", Double.NaN)
                if (amount.isNaN()) continue
                val k = if (m.optString("kind", "loan") == "amanat") "amanat" else "loan"
                out.add(
                    ReturnLogRow(
                        id = m.optDouble("id", 0.0).toInt(),
                        type = type,
                        kind = k,
                        amount = if (type == LOG_ADJUSTED) amount else abs(amount),
                        date = m.optLong("date", 0L),
                        note = m.optString("note", ""),
                        entryId = m.optDouble("entryId", 0.0).toInt()
                    )
                )
            }
        } catch (_: Exception) {
            return mutableListOf()
        }
        return out
    }

    fun encode(list: List<ReturnLogRow>): String {
        if (list.isEmpty()) return ""
        val arr = JSONArray()
        for (r in list) arr.put(
            JSONObject().put("id", r.id).put("type", r.type).put("kind", r.kind).put("amount", r.amount)
                .put("date", r.date).put("note", r.note).put("entryId", r.entryId)
        )
        return arr.toString()
    }

    fun nextId(list: List<ReturnLogRow>): Int = (list.maxOfOrNull { it.id } ?: 0) + 1

    fun addGiven(log: List<ReturnLogRow>, kind: String, e: ReturnEntry, atMillis: Long): List<ReturnLogRow> =
        log + ReturnLogRow(nextId(log), LOG_GIVEN, kind, abs(e.amount), if (e.date > 0) e.date else atMillis, e.note, e.id)

    fun editEntry(log: List<ReturnLogRow>, kind: String, old: ReturnEntry, updated: ReturnEntry, atMillis: Long): List<ReturnLogRow> {
        val out = log.map {
            if (it.type == LOG_GIVEN && it.entryId == old.id)
                it.copy(date = if (updated.date > 0) updated.date else it.date, note = updated.note)
            else it
        }.toMutableList()
        val delta = updated.amount - old.amount
        if (abs(delta) > 0.004) out.add(ReturnLogRow(nextId(out), LOG_ADJUSTED, kind, delta, atMillis, updated.note, old.id))
        return out
    }

    fun removeEntry(log: List<ReturnLogRow>, kind: String, old: ReturnEntry, atMillis: Long): List<ReturnLogRow> =
        log + ReturnLogRow(nextId(log), LOG_REMOVED, kind, abs(old.amount), atMillis, old.note, old.id)

    fun recovered(log: List<ReturnLogRow>, kind: String, amount: Double, atMillis: Long, note: String): List<ReturnLogRow> =
        log + ReturnLogRow(nextId(log), LOG_RECOVERED, kind, abs(amount), atMillis, note)

    fun backfill(kind: String, entries: List<ReturnEntry>, atMillis: Long): List<ReturnLogRow> {
        var log: List<ReturnLogRow> = emptyList()
        for (e in entries) log = addGiven(log, kind, e, atMillis)
        return log
    }

    /** NEW (batch 9): entries' real effect minus the log's; ~0 => the log is complete. */
    fun gap(kind: String, entries: List<ReturnEntry>, log: List<ReturnLogRow>): Double {
        val g = ReturnEntries.signedTotal(kind, entries) - log.sumOf { it.balanceEffect }
        return if (abs(g) < 0.009) 0.0 else g
    }

    /**
     * NEW (batch 9 — Flutter ledgerLogRows): rows for the party ledger = log + (when the log does
     * not add up to the entries, e.g. an old device changed them) one "untracked" adjusted row
     * (id = -1) so the ledger always matches the opening balance.
     */
    fun ledgerRows(kind: String, entries: List<ReturnEntry>, log: List<ReturnLogRow>): List<ReturnLogRow> {
        val k = if (kind == "amanat") "amanat" else "loan"
        val g = gap(k, entries, log)
        if (g == 0.0) return log
        val sign = if (k == "amanat") -1.0 else 1.0
        return log + ReturnLogRow(-1, LOG_ADJUSTED, k, g * sign, 0L, "untracked")
    }
}

// ------------------------------------------------------------------------------ Follow-ups

object FollowUpMethod {
    const val WHATSAPP = "whatsapp"
    const val CALL = "call"
    const val VISIT = "visit"
    const val SMS = "sms"
    const val NOTE = "note"
    val ALL = listOf(WHATSAPP, CALL, VISIT, SMS, NOTE)
}

object FollowUpOutcome {
    const val SENT = "sent"
    const val PAID = "paid"
    const val PROMISED = "promised"
    const val PARTIAL = "partial"
    const val NO_ANSWER = "no_answer"
    const val REFUSED = "refused"
    const val DISPUTE = "dispute"
    const val NOTE = "note"
    val CHOICES = listOf(PAID, PROMISED, PARTIAL, NO_ANSWER, REFUSED, DISPUTE, NOTE)
}

data class FollowUpState(
    val last: DueFollowUp?,
    val contactCount: Int,
    val promise: DueFollowUp?,
    val promiseBroken: Boolean,
    val nextFollowUp: Long,
    val dispute: Boolean,
    val today: Long
) {
    val never: Boolean get() = last == null
    val onPromise: Boolean get() = promise != null && !promiseBroken
    val promiseToday: Boolean get() = onPromise && promise!!.promiseDate == today
    val followUpDue: Boolean get() = !dispute && nextFollowUp > 0 && nextFollowUp <= today
    val needsActionToday: Boolean get() = promiseBroken || followUpDue || promiseToday
}

object FollowUps {
    fun newestFirst(entries: Iterable<DueFollowUp>): List<DueFollowUp> =
        entries.sortedWith(compareByDescending<DueFollowUp> { it.createdAt }.thenByDescending { it.id })

    /** Sirf maujooda qarz ke rabte (purana qarz chuka kar naya udhaar => pehle ke wade laagu nahi). */
    fun forCurrentDebt(entries: List<DueFollowUp>, debtSince: Long): List<DueFollowUp> =
        if (debtSince <= 0) entries else entries.filter { it.createdAt >= debtSince }

    fun state(entries: Iterable<DueFollowUp>, now: Long = System.currentTimeMillis()): FollowUpState {
        val today = DayUtil.start(now)
        val all = newestFirst(entries)
        val real = all.filter { it.outcome != FollowUpOutcome.SENT }
        var promise: DueFollowUp? = null
        for (i in real.indices) {
            if (real[i].promiseDate <= 0) continue
            var resolved = false
            for (j in 0 until i) {
                val o = real[j].outcome
                if (o == FollowUpOutcome.PAID || o == FollowUpOutcome.REFUSED || o == FollowUpOutcome.DISPUTE) { resolved = true; break }
            }
            if (!resolved) promise = real[i]
            break
        }
        val governing = real.firstOrNull()
        return FollowUpState(
            last = all.firstOrNull(),
            contactCount = all.size,
            promise = promise,
            promiseBroken = promise != null && promise.promiseDate < today,
            nextFollowUp = governing?.nextFollowUp ?: 0L,
            dispute = governing?.outcome == FollowUpOutcome.DISPUTE,
            today = today
        )
    }

    /** (made, kept, broken) wade. */
    fun promiseRecord(entries: Iterable<DueFollowUp>, now: Long = System.currentTimeMillis()): Triple<Int, Int, Int> {
        val today = DayUtil.start(now)
        val all = newestFirst(entries).reversed()
        var kept = 0; var broken = 0; var made = 0
        for (i in all.indices) {
            val p = all[i]
            if (p.promiseDate <= 0 || p.outcome == FollowUpOutcome.SENT) continue
            val deadline = p.promiseDate + 2 * DAY_MS
            var ok = false
            for (j in i + 1 until all.size) {
                val e = all[j]
                if (e.createdAt > deadline) break
                if (e.outcome == FollowUpOutcome.PAID || (e.outcome == FollowUpOutcome.PARTIAL && e.amount > 0)) { ok = true; break }
            }
            if (ok) { made++; kept++ } else if (p.promiseDate < today - 2 * DAY_MS) { made++; broken++ }
        }
        return Triple(made, kept, broken)
    }

    /** Pichle 45 din ke WhatsApp/SMS reminders: 0 / 1 / 2+. */
    fun reminderLevel(entries: Iterable<DueFollowUp>, now: Long = System.currentTimeMillis()): Int {
        val since = now - 45 * DAY_MS
        var n = 0
        for (e in entries) {
            if (e.createdAt < since) continue
            if (e.method == FollowUpMethod.WHATSAPP || e.method == FollowUpMethod.SMS) n++
        }
        return if (n > 2) 2 else n
    }

    fun message(level: Int, name: String, rs: String, oldestDays: Int = 0, brokenPromiseDate: Long = 0L, brokenPromiseAmount: Double = 0.0): String {
        val age = if (oldestDays > 0) " (sab se purana bill $oldestDays din ka)" else ""
        val promise = if (brokenPromiseDate <= 0L) "" else {
            val c = Calendar.getInstance().apply { timeInMillis = brokenPromiseDate }
            " Aap ne ${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH) + 1} tak" +
                (if (brokenPromiseAmount > 0) " Rs ${brokenPromiseAmount.roundToLong()}" else "") +
                " ada karne ka wada kiya tha, lekin abhi tak ada nahi hua."
        }
        return when (level) {
            0 -> "Assalam o Alaikum $name, aapke hisab mein Rs $rs abhi baaki hai$age.$promise Barah-e-karam jald ada karein. Shukriya!"
            1 -> "Assalam o Alaikum $name, pehle bhi yaad dihani ki thi: aapke hisab mein Rs $rs baaki hai$age.$promise Barah-e-karam aaj ya kal tak ada kar dein. Shukriya!"
            else -> "Assalam o Alaikum $name, aapke hisab mein Rs $rs kaafi arse se baaki hai$age.$promise Mehrbani kar ke is hafte ke andar ada kar dein, ya ada karne ki tareekh bata dein. Shukriya."
        }
    }

    /** Entry "koshish" hai (note ke ilawa kuch). */
    fun isTouch(e: DueFollowUp): Boolean = e.method != FollowUpMethod.NOTE || e.outcome != FollowUpOutcome.NOTE

    fun outcomeLabel(ctx: android.content.Context, o: String): String = when (o) {
        FollowUpOutcome.SENT -> com.grocerypos.v11.util.Loc.t(ctx, "Sent (no result yet)", "بھیجا (نتیجہ نہیں لکھا)")
        FollowUpOutcome.PAID -> com.grocerypos.v11.util.Loc.t(ctx, "Paid", "ادا کر دیا")
        FollowUpOutcome.PROMISED -> com.grocerypos.v11.util.Loc.t(ctx, "Promised", "وعدہ کیا")
        FollowUpOutcome.PARTIAL -> com.grocerypos.v11.util.Loc.t(ctx, "Part payment", "کچھ ادا کیا")
        FollowUpOutcome.NO_ANSWER -> com.grocerypos.v11.util.Loc.t(ctx, "No answer", "جواب نہیں")
        FollowUpOutcome.REFUSED -> com.grocerypos.v11.util.Loc.t(ctx, "Refused / excuse", "انکار / بہانہ")
        FollowUpOutcome.DISPUTE -> com.grocerypos.v11.util.Loc.t(ctx, "Dispute", "تنازع")
        else -> com.grocerypos.v11.util.Loc.t(ctx, "Note", "نوٹ")
    }

    fun methodLabel(ctx: android.content.Context, m: String): String = when (m) {
        FollowUpMethod.WHATSAPP -> "WhatsApp"
        FollowUpMethod.CALL -> com.grocerypos.v11.util.Loc.t(ctx, "Call", "کال")
        FollowUpMethod.VISIT -> com.grocerypos.v11.util.Loc.t(ctx, "Visit", "ملاقات")
        FollowUpMethod.SMS -> "SMS"
        else -> com.grocerypos.v11.util.Loc.t(ctx, "Note", "نوٹ")
    }
}

// ------------------------------------------------------------------------------ Pay day

object PaySchedule {
    private val RX = Regex("^([wm]):(\\d{1,2})$")
    val CHOICES = listOf("", "w:5", "w:1", "w:2", "w:3", "w:4", "w:6", "w:7",
        "m:1", "m:5", "m:7", "m:10", "m:15", "m:20", "m:25", "m:31")
    private val EN = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val UR = listOf("پیر", "منگل", "بدھ", "جمعرات", "جمعہ", "ہفتہ", "اتوار")

    /** (weekly, day) ya null. weekly day: 1 = Monday .. 7 = Sunday (Dart weekday). */
    fun parse(raw: String): Pair<Boolean, Int>? {
        val m = RX.find(raw.trim()) ?: return null
        val weekly = m.groupValues[1] == "w"
        val d = m.groupValues[2].toInt()
        if (weekly && (d < 1 || d > 7)) return null
        if (!weekly && (d < 1 || d > 31)) return null
        return Pair(weekly, d)
    }

    fun isPayDay(raw: String, millis: Long): Boolean {
        val s = parse(raw) ?: return false
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return if (s.first) {
            // Calendar: SUNDAY=1..SATURDAY=7 -> Dart: MONDAY=1..SUNDAY=7
            val dow = c.get(Calendar.DAY_OF_WEEK)
            val dartWeekday = if (dow == Calendar.SUNDAY) 7 else dow - 1
            dartWeekday == s.second
        } else {
            val last = c.getActualMaximum(Calendar.DAY_OF_MONTH)
            c.get(Calendar.DAY_OF_MONTH) == (if (s.second > last) last else s.second)
        }
    }

    fun label(raw: String, urdu: Boolean): String {
        val s = parse(raw) ?: return if (urdu) "مقرر نہیں" else "Not set"
        return if (s.first) (if (urdu) "ہر ${UR[s.second - 1]}" else "Every ${EN[s.second - 1]}")
        else (if (urdu) "ہر مہینے کی ${s.second} تاریخ" else "Every month on the ${s.second}")
    }
}

// ------------------------------------------------------------------------------ Due renewal

object DueRenewal {
    const val DEFAULT_DAYS = 7

    fun billTermDays(createdAt: Long, dueDate: Long): Int {
        if (dueDate <= 0 || createdAt <= 0) return 0
        return DayUtil.daysBetween(createdAt, dueDate)
    }

    /** Nayi due date (0 = kuch na badlo). Date kabhi peeche nahi jati. */
    fun renewedDueDate(now: Long, oldDue: Long, createdAt: Long, customerDays: Int = 0): Long {
        if (oldDue <= 0) return 0L
        var term = if (customerDays > 0) customerDays else billTermDays(createdAt, oldDue)
        if (customerDays <= 0) term = if (term <= 0) DEFAULT_DAYS else term.coerceIn(3, 30)
        val next = DayUtil.plusDays(now, term)
        return if (next > oldDue) next else oldDue
    }
}

// ------------------------------------------------------------------------------ Qist plan

data class QistPlan(
    val total: Double,
    val count: Int,
    val firstDate: Long,
    val everyDays: Int,
    val createdAt: Long
) {
    val valid: Boolean get() = total > 0.009 && count in 1..60 && everyDays >= 1 && firstDate > 0

    fun amountOf(i: Int): Double {
        val each = floor(total / count)
        return if (i == count - 1) total - each * (count - 1) else each
    }

    fun dateOf(i: Int): Long = DayUtil.plusDays(firstDate, i * everyDays)

    fun toJson(): String = JSONObject().put("total", total).put("count", count).put("firstDate", firstDate)
        .put("everyDays", everyDays).put("createdAt", createdAt).toString()

    companion object {
        fun fromJson(raw: String?): QistPlan? {
            if (raw.isNullOrBlank()) return null
            return try {
                val m = JSONObject(raw)
                val p = QistPlan(
                    total = m.getDouble("total"),
                    count = m.getDouble("count").toInt(),
                    firstDate = m.getLong("firstDate"),
                    everyDays = m.getDouble("everyDays").toInt(),
                    createdAt = m.getLong("createdAt")
                )
                if (p.valid) p else null
            } catch (_: Exception) { null }
        }
    }
}

data class QistStatus(
    val plan: QistPlan,
    val paid: Double,
    val dueByToday: Double,
    val paidCount: Int,
    val nextIndex: Int,
    val nextDate: Long,
    val nextRemaining: Double,
    val overdue: Double
) {
    val completed: Boolean get() = nextIndex >= plan.count
    val remaining: Double get() = (plan.total - paid).coerceIn(0.0, plan.total)
}

object Qist {
    fun status(plan: QistPlan, paidSince: Double, now: Long = System.currentTimeMillis()): QistStatus {
        val today = DayUtil.start(now)
        var left = if (paidSince < 0) 0.0 else paidSince
        var paidCount = 0
        var nextIndex = plan.count
        var nextRemaining = 0.0
        var dueByToday = 0.0
        var overdue = 0.0
        for (i in 0 until plan.count) {
            val amt = plan.amountOf(i)
            val covered = if (left >= amt - 0.009) amt else left
            left -= covered
            val rem = amt - covered
            val date = plan.dateOf(i)
            if (date <= today) {
                dueByToday += amt
                if (date < today) overdue += rem
            }
            if (rem <= 0.009) {
                if (nextIndex == plan.count) paidCount++
            } else if (nextIndex == plan.count) {
                nextIndex = i; nextRemaining = rem
            }
        }
        return QistStatus(plan, paidSince, dueByToday, paidCount, nextIndex,
            if (nextIndex < plan.count) plan.dateOf(nextIndex) else 0L, nextRemaining, overdue)
    }

    fun needsTouch(s: QistStatus, now: Long = System.currentTimeMillis()): Boolean {
        if (s.completed) return false
        return s.overdue > 0.009 || s.nextDate == DayUtil.start(now)
    }
}

// ------------------------------------------------------------------------------ Dues grouping

/** Ek baqi bill (ya "Previous balance" row). */
data class DueBill(
    val id: String,
    val partyId: Long?,
    val partyName: String,
    val partyPhone: String,
    val total: Double,
    val paid: Double,
    val dueDate: Long,
    val createdAt: Long,
    val isSale: Boolean = true,
    val isBalance: Boolean = false,
    val isAdvance: Boolean = false
) {
    val due: Double get() = total - paid
}

data class DueParty(
    val partyId: Long?,
    val partyName: String,
    val partyPhone: String,
    val isSale: Boolean,
    val bills: List<DueBill>
) {
    val total: Double get() = bills.sumOf { it.due }
    val isAdvance: Boolean get() = bills.isNotEmpty() && bills.all { it.isAdvance }
    val billCount: Int get() = bills.count { !it.isBalance && !it.isAdvance }

    val debtSince: Long
        get() {
            var since = 0L
            for (b in bills) {
                if (b.isAdvance) continue
                if (b.isBalance || b.createdAt <= 0) return 0L
                if (since == 0L || b.createdAt < since) since = b.createdAt
            }
            return since
        }

    val earliestDue: Long
        get() {
            var m = 0L
            for (b in bills) {
                if (b.isBalance || b.dueDate <= 0) continue
                if (m == 0L || b.dueDate < m) m = b.dueDate
            }
            return m
        }

    val earliestCreated: Long get() = bills.minOfOrNull { it.createdAt } ?: 0L

    fun undatedAgeDays(now: Long): Int {
        val today = DayUtil.start(now)
        var oldest = 0
        for (b in bills) {
            if (b.isBalance || b.isAdvance || b.dueDate > 0 || b.createdAt <= 0) continue
            val d = ((today - DayUtil.start(b.createdAt)).toDouble() / DAY_MS).roundToInt()
            if (d > oldest) oldest = d
        }
        return oldest
    }

    fun hasStaleUndated(now: Long): Boolean = undatedAgeDays(now) >= UNDATED_GRACE_DAYS

    fun hasOverdue(now: Long): Boolean {
        val today = DayUtil.start(now)
        return bills.any { it.dueDate > 0 && it.dueDate < today }
    }

    fun oldestAgeDays(now: Long): Int {
        val today = DayUtil.start(now)
        var oldest = 0
        if (hasOverdue(now) && earliestDue > 0) {
            oldest = ((today - DayUtil.start(earliestDue)).toDouble() / DAY_MS).roundToInt()
            if (oldest < 0) oldest = 0
        }
        if (hasStaleUndated(now)) {
            val u = undatedAgeDays(now)
            if (u > oldest) oldest = u
        }
        return oldest
    }

    companion object { const val UNDATED_GRACE_DAYS = 7 }
}

object Dues {
    fun sortBills(bills: Iterable<DueBill>): List<DueBill> =
        bills.sortedWith(compareBy<DueBill>({ if (it.dueDate == 0L) 1 else 0 }, { it.dueDate }, { it.createdAt }))

    /** Party ke bills ka kul baqi uske asli balance se zyada na dikhe (FIFO, purane bill pehle katte hain). */
    fun capByBalance(bills: Iterable<DueBill>, balances: Map<Long, Double>): List<DueBill> {
        val out = mutableListOf<DueBill>()
        val byParty = LinkedHashMap<Long, MutableList<DueBill>>()
        for (b in bills) {
            val id = b.partyId
            if (id == null || !balances.containsKey(id)) out.add(b) else byParty.getOrPut(id) { mutableListOf() }.add(b)
        }
        for ((pid, list0) in byParty) {
            val balance = balances[pid] ?: 0.0
            val owed = if (balance > 0) balance else 0.0
            val list = list0.sortedBy { it.createdAt }
            var excess = list.sumOf { it.due } - owed
            for (b in list) {
                if (excess <= 0.009) { out.add(b); continue }
                val cut = if (b.due < excess) b.due else excess
                excess -= cut
                val left = b.due - cut
                if (left > 0.009) out.add(b.copy(paid = b.total - left))
            }
        }
        return sortBills(out)
    }

    /** Bills se na dhaka hua balance (opening / purana hisaab) => "Previous balance" row. */
    fun addUnbilledBalance(bills: List<DueBill>, closings: Map<Long, Triple<String, String, Double>>): List<DueBill> {
        val billed = HashMap<Long, Double>()
        for (b in bills) b.partyId?.let { billed[it] = (billed[it] ?: 0.0) + b.due }
        val out = bills.toMutableList()
        for ((id, c) in closings) {
            val rest = c.third - (billed[id] ?: 0.0)
            if (c.third <= 0.009 || rest <= 0.009) continue
            out.add(DueBill("Previous balance", id, c.first, c.second, rest, 0.0, 0L, 0L, isSale = true, isBalance = true))
        }
        return sortBills(out)
    }

    fun groupByParty(bills: Iterable<DueBill>): List<DueParty> {
        val map = LinkedHashMap<String, MutableList<DueBill>>()
        for (b in bills) {
            val k = if (b.partyId == null) "x${b.id}" else "${b.partyId}:${if (b.isAdvance) "a" else "b"}"
            map.getOrPut(k) { mutableListOf() }.add(b)
        }
        val out = map.values.map { l ->
            DueParty(l.first().partyId, l.first().partyName, l.first().partyPhone, l.first().isSale, sortBills(l))
        }
        return out.sortedWith(Comparator { a, b ->
            val ae = a.earliestDue; val be = b.earliestDue
            val aNo = if (ae == 0L) 1 else 0; val bNo = if (be == 0L) 1 else 0
            if (aNo != bNo) return@Comparator aNo.compareTo(bNo)
            if (ae != be) return@Comparator ae.compareTo(be)
            val c = a.earliestCreated.compareTo(b.earliestCreated)
            if (c != 0) c else a.partyName.lowercase().compareTo(b.partyName.lowercase())
        })
    }

    fun whatsAppDigits(phone: String): String {
        var digits = phone.replace(Regex("[^0-9]"), "")
        if (digits.isEmpty()) return ""
        if (digits.startsWith("0")) digits = "92" + digits.substring(1)
        else if (!digits.startsWith("92") && digits.length <= 10) digits = "92$digits"
        return digits
    }

    fun openBills(rows: Iterable<DueBill>): List<DueBill> =
        rows.filter { !it.isBalance && !it.isAdvance && it.due > 0.009 }
            .sortedWith(compareBy<DueBill>({ it.createdAt }, { it.id }))

    fun previousBalanceOf(rows: Iterable<DueBill>): Double =
        rows.filter { it.isBalance && !it.isAdvance }.sumOf { if (it.due > 0) it.due else 0.0 }

    fun itemizedReminder(partyName: String, bills: List<DueBill>, total: Double, previousBalance: Double, now: Long): String {
        val today = DayUtil.start(now)
        val sb = StringBuilder("Assalam o Alaikum $partyName, aapke ye bills abhi baaki hain:\n")
        for (x in bills) {
            var line = "• ${x.id}"
            if (x.createdAt > 0) line += " (${DayUtil.fmtShort(x.createdAt)})"
            line += ": Rs ${x.due.roundToLong()}"
            if (x.dueDate > 0 && x.dueDate < today) {
                val late = ((today - DayUtil.start(x.dueDate)).toDouble() / DAY_MS).roundToInt()
                if (late > 0) line += " — $late din late"
            } else if (x.dueDate <= 0 && x.createdAt > 0) {
                val age = ((today - DayUtil.start(x.createdAt)).toDouble() / DAY_MS).roundToInt()
                if (age > 0) line += " — $age din purana"
            }
            sb.append(line).append('\n')
        }
        if (previousBalance > 0.009) sb.append("• Previous balance: Rs ${previousBalance.roundToLong()}\n")
        sb.append("Kul baqi: Rs ${total.roundToLong()}. Barah-e-karam jald ada karein. Shukriya!")
        return sb.toString()
    }

    /** Reminder ka matn (Flutter reminderUriFor): pehli baar naram/itemized, baad mein shiddat. */
    fun reminderText(g: DueParty, allEntries: List<DueFollowUp>, now: Long = System.currentTimeMillis()): String {
        val entries = FollowUps.forCurrentDebt(allEntries, g.debtSince)
        val level = FollowUps.reminderLevel(entries, now)
        val st = FollowUps.state(entries, now)
        if (level == 0 && !st.promiseBroken) {
            val single = if (g.bills.size == 1 && !g.bills.first().isBalance) g.bills.first() else null
            if (single != null) {
                return "Assalam o Alaikum ${g.partyName}, aapka bill (Invoice ${single.id}) mein Rs ${single.due.roundToLong()} abhi baaki hai. Barah-e-karam jald ada karein. Shukriya!"
            }
            val open = openBills(g.bills)
            if (open.size >= 2) return itemizedReminder(g.partyName, open, g.total, previousBalanceOf(g.bills), now)
            return "Assalam o Alaikum ${g.partyName}, aapke hisab mein Rs ${g.total.roundToLong()} abhi baaki hai. Barah-e-karam jald ada karein. Shukriya!"
        }
        return FollowUps.message(
            level, g.partyName, g.total.roundToLong().toString(), g.oldestAgeDays(now),
            if (st.promiseBroken) st.promise?.promiseDate ?: 0L else 0L,
            if (st.promiseBroken) st.promise?.amount ?: 0.0 else 0.0
        )
    }
}

// ------------------------------------------------------------------------------ Autopilot

data class CollectionBuckets(val broken: Boolean, val late: Boolean, val dueToday: Boolean, val needsAction: Boolean) {
    val inFocus: Boolean get() = needsAction || late || dueToday
}

enum class TouchReason { BROKEN_PROMISE, PROMISE_TODAY, QIST_DUE, PAY_DAY, FOLLOW_UP_DUE, LATE, DUE_TODAY }
enum class TouchStatus { TODO, DONE_TODAY, WAITING }

data class AutopilotItem(
    val party: DueParty,
    val state: FollowUpState,
    val reason: TouchReason,
    val status: TouchStatus,
    val oldestDays: Int,
    val daysSinceTouch: Int?,
    val daysUntilTouch: Int = 0,
    val touchesToday: Int = 0
) {
    val total: Double get() = party.total
    val neverTouched: Boolean get() = daysSinceTouch == null
}

data class AutopilotQueue(val todo: List<AutopilotItem>, val done: List<AutopilotItem>, val waiting: List<AutopilotItem>) {
    val todoAmount: Double get() = todo.sumOf { it.total }
    val planned: Int get() = todo.size + done.size
    val isClear: Boolean get() = todo.isEmpty()
    val brokenCount: Int get() = todo.count { it.reason == TouchReason.BROKEN_PROMISE }
    companion object { val EMPTY = AutopilotQueue(emptyList(), emptyList(), emptyList()) }
}

object Autopilot {
    fun classify(p: DueParty, st: FollowUpState, now: Long = System.currentTimeMillis()): CollectionBuckets {
        val today = DayUtil.start(now)
        val late = (p.hasOverdue(now) || p.hasStaleUndated(now)) && !st.onPromise
        val hasDueToday = p.bills.any { !it.isBalance && !it.isAdvance && it.dueDate > 0 && DayUtil.start(it.dueDate) == today }
        val dueToday = !late && !st.promiseBroken && (hasDueToday || st.promiseToday)
        return CollectionBuckets(st.promiseBroken, late, dueToday, st.needsActionToday)
    }

    fun gapDays(oldestDays: Int, broken: Boolean): Int = when {
        broken -> 1
        oldestDays >= 30 -> 2
        else -> 3
    }

    private fun rank(r: TouchReason): Int = r.ordinal

    fun build(
        parties: Iterable<DueParty>,
        followUps: Map<Long, List<DueFollowUp>>,
        now: Long = System.currentTimeMillis(),
        paySchedules: Map<Long, String> = emptyMap(),
        qist: Map<Long, QistStatus> = emptyMap()
    ): AutopilotQueue {
        val today = DayUtil.start(now)
        val todo = mutableListOf<AutopilotItem>()
        val done = mutableListOf<AutopilotItem>()
        val waiting = mutableListOf<AutopilotItem>()
        for (p in parties) {
            val id = p.partyId ?: continue
            if (p.isAdvance || p.total <= 0.009) continue
            val entries = FollowUps.forCurrentDebt(followUps[id] ?: emptyList(), p.debtSince)
            val st = FollowUps.state(entries, now)
            val b = classify(p, st, now)
            val q = qist[id]
            val reason = when {
                st.promiseBroken -> TouchReason.BROKEN_PROMISE
                st.promiseToday -> TouchReason.PROMISE_TODAY
                q != null && Qist.needsTouch(q, now) -> TouchReason.QIST_DUE
                PaySchedule.isPayDay(paySchedules[id] ?: "", now) -> TouchReason.PAY_DAY
                st.followUpDue -> TouchReason.FOLLOW_UP_DUE
                b.late -> TouchReason.LATE
                b.dueToday -> TouchReason.DUE_TODAY
                else -> null
            } ?: continue
            val touches = entries.filter { FollowUps.isTouch(it) }.sortedByDescending { it.createdAt }
            val touchesToday = touches.count { it.createdAt >= today }
            val since: Int? = if (touches.isEmpty()) null
                else ((today - DayUtil.start(touches.first().createdAt)).toDouble() / DAY_MS).roundToInt()
            val oldest = p.oldestAgeDays(now)
            fun item(s: TouchStatus, wait: Int = 0) = AutopilotItem(p, st, reason, s, oldest, since, wait, touchesToday)
            if (st.dispute) { waiting.add(item(TouchStatus.WAITING)); continue }
            if (touchesToday > 0) { done.add(item(TouchStatus.DONE_TODAY)); continue }
            val scheduled = reason == TouchReason.PROMISE_TODAY || reason == TouchReason.FOLLOW_UP_DUE ||
                reason == TouchReason.PAY_DAY || reason == TouchReason.QIST_DUE
            if (since == null || scheduled) { todo.add(item(TouchStatus.TODO)); continue }
            val gap = gapDays(oldest, reason == TouchReason.BROKEN_PROMISE)
            if (since >= gap) todo.add(item(TouchStatus.TODO)) else waiting.add(item(TouchStatus.WAITING, gap - since))
        }
        val byPriority = Comparator<AutopilotItem> { a, b ->
            val r = rank(a.reason).compareTo(rank(b.reason))
            if (r != 0) return@Comparator r
            val t = b.total.compareTo(a.total)
            if (t != 0) t else a.party.partyName.lowercase().compareTo(b.party.partyName.lowercase())
        }
        return AutopilotQueue(
            todo.sortedWith(byPriority),
            done.sortedWith(byPriority),
            waiting.sortedWith(Comparator { a, b ->
                val w = a.daysUntilTouch.compareTo(b.daysUntilTouch)
                if (w != 0) w else byPriority.compare(a, b)
            })
        )
    }
}
