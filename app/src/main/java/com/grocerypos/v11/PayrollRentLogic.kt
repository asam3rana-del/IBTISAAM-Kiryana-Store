package com.grocerypos.v11

import java.util.Calendar
import kotlin.math.roundToLong

// =====================================================================================
// NEW (Flutter parity, batch 2): PURE maths ported 1:1 from Flutter —
//   lib/db/worker_repository.dart       (attendanceCounts / computeEarned)
//   lib/db/shop_rent_repository.dart    (ym helpers, rentForMonth, allocateFifo, summarize)
//   lib/db/utility_bill_repository.dart (billDueDate / billStatus)
// No DB / UI here.
// =====================================================================================

object Ym {
    private fun two(v: Int) = v.toString().padStart(2, '0')
    fun key(year: Int, month: Int) = "$year-${two(month)}"
    fun of(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return key(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }
    fun year(ym: String) = ym.substring(0, 4).toInt()
    fun month(ym: String) = ym.substring(5, 7).toInt()
    fun add(ym: String, n: Int): String {
        val total = year(ym) * 12 + (month(ym) - 1) + n
        return key(Math.floorDiv(total, 12), Math.floorMod(total, 12) + 1)
    }
    /** b - a in months. */
    fun between(a: String, b: String): Int = (year(b) - year(a)) * 12 + (month(b) - month(a))
    fun range(start: String, end: String): List<String> {
        val out = mutableListOf<String>()
        var cur = start
        while (cur <= end) { out.add(cur); cur = add(cur, 1); if (out.size > 600) break }
        return out
    }
    fun daysIn(year: Int, month: Int): Int = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
    /** Local midnight of [day] in ym (day clamped to the month's last day). */
    fun dateOf(ym: String, day: Int): Long {
        val y = year(ym); val m = month(ym)
        val d = day.coerceIn(1, daysIn(y, m))
        return Calendar.getInstance().apply { clear(); set(y, m - 1, d) }.timeInMillis
    }
    fun dayKey(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return "${c.get(Calendar.YEAR)}-${two(c.get(Calendar.MONTH) + 1)}-${two(c.get(Calendar.DAY_OF_MONTH))}"
    }
    fun dayKey(year: Int, month: Int, day: Int) = "$year-${two(month)}-${two(day)}"
    fun label(ym: String): String {
        val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return "${names[month(ym) - 1]} ${year(ym)}"
    }
}

fun money0(v: Double): String = "Rs " + v.roundToLong().toString()

// ------------------------------------------------------------------------------ Workers

data class AttendanceCounts(val present: Double = 0.0, val absent: Double = 0.0, val half: Double = 0.0, val paidLeave: Double = 0.0, val unmarked: Int = 0)
data class EarnedResult(val earned: Double, val deduction: Double, val days: Double)

object Payroll {
    const val MONTHLY = "monthly"
    const val DAILY = "daily"
    val STATUSES = listOf("P", "A", "H", "L")

    fun counts(marks: Map<Int, String>, year: Int, month: Int): AttendanceCounts {
        var p = 0.0; var a = 0.0; var h = 0.0; var l = 0.0; var un = 0
        for (d in 1..Ym.daysIn(year, month)) {
            when (marks[d]) { "P" -> p++; "A" -> a++; "H" -> h++; "L" -> l++; else -> un++ }
        }
        return AttendanceCounts(p, a, h, l, un)
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    /** Monthly: rate − (rate / days) × (absent + half×0.5); unmarked = present. Daily: rate × (P + L + H×0.5). */
    fun earned(payType: String, rate: Double, year: Int, month: Int, c: AttendanceCounts): EarnedResult {
        if (payType == DAILY) {
            val days = c.present + c.paidLeave + c.half * 0.5
            return EarnedResult(round2(rate * days), 0.0, days)
        }
        val perDay = rate / Ym.daysIn(year, month)
        val cutDays = c.absent + c.half * 0.5
        var deduction = round2(perDay * cutDays)
        if (deduction > rate) deduction = rate
        return EarnedResult(round2(rate - deduction), deduction, cutDays)
    }
}

// ------------------------------------------------------------------------------ Shop rents

data class MonthDue(val ym: String, val due: Double)
data class Allocation(val ym: String, val amount: Double)

data class ShopRentSummary(
    val shop: RentedShop,
    val currentRent: Double,
    val lastTermYm: String,
    val charged: Double,
    val settled: Double,
    val advanceBalance: Double,
    val dues: List<MonthDue>,
    val nextDueDate: Long,     // 0 = nothing due
    val overdue: Boolean,
    val reviewDue: Boolean
) {
    val outstanding: Double get() = dues.sumOf { it.due }
    val isClear: Boolean get() = outstanding <= RentMath.EPS
}

data class MonthReportRow(val shop: RentedShop, val rent: Double, val paid: Double) {
    val due: Double get() = if (rent - paid > RentMath.EPS) rent - paid else 0.0
}

object RentMath {
    const val EPS = 0.005
    const val CATEGORY = "Rent"

    fun rentForMonth(terms: Map<String, Double>, ym: String): Double {
        if (terms.isEmpty()) return 0.0
        var best: String? = null
        var first: String? = null
        for (k in terms.keys) {
            if (first == null || k < first) first = k
            if (k <= ym && (best == null || k > best)) best = k
        }
        return terms[best ?: first] ?: 0.0
    }

    fun needsReview(lastTermYm: String, nowYm: String, months: Int = 12) = Ym.between(lastTermYm, nowYm) >= months
    fun increaseByPercent(rent: Double, percent: Double): Double = Math.round(rent * (1 + percent / 100)).toDouble()

    /** Oldest due first; extra = what did not fit any month. */
    fun allocateFifo(dues: List<MonthDue>, amount: Double): Pair<List<Allocation>, Double> {
        val out = mutableListOf<Allocation>()
        var left = amount
        for (d in dues) {
            if (left <= EPS) break
            if (d.due <= EPS) continue
            val a = if (left < d.due) left else d.due
            out.add(Allocation(d.ym, a)); left -= a
        }
        return Pair(out, if (left > EPS) left else 0.0)
    }

    fun summarize(shop: RentedShop, terms: Map<String, Double>, ledger: List<RentLedgerEntry>, now: Long): ShopRentSummary {
        val nowYm = Ym.of(now)
        val charges = HashMap<String, Double>()
        val settledBy = HashMap<String, Double>()
        var advance = 0.0; var applied = 0.0; var settled = 0.0
        for (e in ledger) {
            when (e.kind) {
                "charge" -> charges[e.ym] = (charges[e.ym] ?: 0.0) + e.amount
                "payment" -> { settledBy[e.ym] = (settledBy[e.ym] ?: 0.0) + e.amount; settled += e.amount }
                "applied" -> { settledBy[e.ym] = (settledBy[e.ym] ?: 0.0) + e.amount; settled += e.amount; applied += e.amount }
                "advance" -> advance += e.amount
            }
        }
        val dues = mutableListOf<MonthDue>()
        var charged = 0.0
        for (m in charges.keys.sorted()) {
            charged += charges[m]!!
            val d = charges[m]!! - (settledBy[m] ?: 0.0)
            if (d > EPS) dues.add(MonthDue(m, d))
        }
        var next = 0L
        var overdue = false
        if (dues.isNotEmpty()) {
            next = Ym.dateOf(dues.first().ym, shop.dueDay)
            overdue = next < DayUtil.start(now)
        }
        var lastTerm = shop.startYm
        for (k in terms.keys) if (k <= nowYm && k > lastTerm) lastTerm = k
        val bal = advance - applied
        return ShopRentSummary(shop, rentForMonth(terms, nowYm), lastTerm, charged, settled,
            if (bal < EPS) 0.0 else bal, dues, next, overdue, needsReview(lastTerm, nowYm))
    }
}

// ------------------------------------------------------------------------------ Utility bills

data class BillStatus(val month: String, val due: Long, val paid: Boolean, val daysLeft: Int) {
    val overdue: Boolean get() = !paid && daysLeft < 0
    val dueToday: Boolean get() = !paid && daysLeft == 0
    val dueTomorrow: Boolean get() = !paid && daysLeft == 1
}

object Bills {
    const val CATEGORY = "Utility Bills"
    val KINDS = listOf("electricity", "gas", "water", "internet", "phone", "other")

    /** Pichla mahina unpaid (aur bill tab maujood tha) => OVERDUE; phir is mahine ka; warna agla. */
    fun status(dueDay: Int, createdAt: Long, paidMonths: Set<String>, now: Long): BillStatus {
        val today = DayUtil.start(now)
        val created = DayUtil.start(createdAt)
        val nowYm = Ym.of(now)
        val prev = Ym.add(nowYm, -1)
        val next = Ym.add(nowYm, 1)
        fun unpaid(ym: String): BillStatus {
            val due = Ym.dateOf(ym, dueDay)
            return BillStatus(ym, due, false, DayUtil.daysBetween(today, due))
        }
        val prevDue = Ym.dateOf(prev, dueDay)
        if (prevDue >= created && prev !in paidMonths) return unpaid(prev)
        val thisDue = Ym.dateOf(nowYm, dueDay)
        if (nowYm in paidMonths) return BillStatus(nowYm, thisDue, true, DayUtil.daysBetween(today, thisDue))
        if (thisDue >= created) return unpaid(nowYm)
        return unpaid(next)
    }
}
