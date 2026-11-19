package com.grocerypos.v11

import java.util.Calendar

// =====================================================================================
// NEW (Flutter parity, batch 4): PURE logic ported 1:1 from Flutter —
//   lib/models/receivable_aging.dart   (paisa kahan phansa hai: 6 dibbe)
//   lib/utils/expiry.dart              (expiry parse + jald / guzar chuki list)
//   lib/db/returns_report_repository   (returns report rows)
// =====================================================================================

enum class AgingBucket { NOT_DUE, LATE_1_7, LATE_8_30, LATE_31_60, LATE_60_PLUS, NO_DATE }

data class AgingRow(val party: DueParty, val byBucket: Map<AgingBucket, Double>, val maxDaysLate: Int) {
    fun amountIn(b: AgingBucket): Double = byBucket[b] ?: 0.0
    val total: Double get() = byBucket.values.sum()
    val worst: AgingBucket
        get() = when {
            maxDaysLate > 0 -> Aging.bucketForDays(maxDaysLate)
            amountIn(AgingBucket.NO_DATE) > 0.009 -> AgingBucket.NO_DATE
            else -> AgingBucket.NOT_DUE
        }
}

data class AgingSummary(
    val amount: Map<AgingBucket, Double>,
    val parties: Map<AgingBucket, Int>,
    val totalDue: Double,
    val rows: List<AgingRow>
) {
    fun amountIn(b: AgingBucket) = amount[b] ?: 0.0
    fun partiesIn(b: AgingBucket) = parties[b] ?: 0
    val lateAmount: Double get() = Aging.LATE.sumOf { amountIn(it) }
    val lateShare: Double get() = if (totalDue <= 0.009) 0.0 else (lateAmount / totalDue).coerceIn(0.0, 1.0)
    fun rowsIn(b: AgingBucket): List<AgingRow> = rows.filter { it.amountIn(b) > 0.009 }
        .sortedWith(compareByDescending<AgingRow> { it.amountIn(b) }.thenBy { it.party.partyName.lowercase() })
}

object Aging {
    val ORDER = listOf(AgingBucket.NOT_DUE, AgingBucket.LATE_1_7, AgingBucket.LATE_8_30, AgingBucket.LATE_31_60, AgingBucket.LATE_60_PLUS, AgingBucket.NO_DATE)
    val LATE = setOf(AgingBucket.LATE_1_7, AgingBucket.LATE_8_30, AgingBucket.LATE_31_60, AgingBucket.LATE_60_PLUS)

    fun bucketForDays(daysLate: Int): AgingBucket = when {
        daysLate <= 0 -> AgingBucket.NOT_DUE
        daysLate <= 7 -> AgingBucket.LATE_1_7
        daysLate <= 30 -> AgingBucket.LATE_8_30
        daysLate <= 60 -> AgingBucket.LATE_31_60
        else -> AgingBucket.LATE_60_PLUS
    }

    fun build(parties: Iterable<DueParty>, now: Long = System.currentTimeMillis()): AgingSummary {
        val amount = HashMap<AgingBucket, Double>()
        val count = HashMap<AgingBucket, Int>()
        val rows = mutableListOf<AgingRow>()
        var total = 0.0
        for (p in parties) {
            if (p.isAdvance) continue
            val by = HashMap<AgingBucket, Double>()
            var maxLate = 0
            for (b in p.bills) {
                if (b.isAdvance || b.due <= 0.009) continue
                val bucket = if (b.dueDate <= 0) AgingBucket.NO_DATE else {
                    val days = DayUtil.daysBetween(b.dueDate, now)
                    if (days > maxLate) maxLate = days
                    bucketForDays(days)
                }
                by[bucket] = (by[bucket] ?: 0.0) + b.due
            }
            if (by.isEmpty()) continue
            for ((k, v) in by) { amount[k] = (amount[k] ?: 0.0) + v; count[k] = (count[k] ?: 0) + 1; total += v }
            rows.add(AgingRow(p, by, maxLate))
        }
        return AgingSummary(amount, count, total,
            rows.sortedWith(compareByDescending<AgingRow> { it.total }.thenBy { it.party.partyName.lowercase() }))
    }
}

// ------------------------------------------------------------------------------ Expiry

enum class ExpiryStatus { EXPIRED, SOON, OK }
data class ExpiryRow(val product: Product, val expiry: Long, val daysLeft: Int, val status: ExpiryStatus)

object Expiry {
    const val WARN_DAYS = 30

    private fun make(y: Int, m: Int, d: Int): Long? {
        if (y < 2000 || y > 2100 || m < 1 || m > 12 || d < 1 || d > 31) return null
        val c = Calendar.getInstance().apply { clear(); isLenient = false; set(y, m - 1, d) }
        return try { c.timeInMillis } catch (e: Exception) { null }
    }

    /** yyyy-MM-dd, dd/MM/yyyy, dd-MM-yyyy, MM/yyyy (month's last day). Anything else => null. */
    fun parse(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(s)?.let { return make(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        Regex("^(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4})$").find(s)?.let { return make(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
        Regex("^(\\d{1,2})[/\\-.](\\d{4})$").find(s)?.let {
            val mo = it.groupValues[1].toInt(); val y = it.groupValues[2].toInt()
            if (mo < 1 || mo > 12 || y < 2000 || y > 2100) return null
            return make(y, mo, Ym.daysIn(y, mo))
        }
        return null
    }

    fun daysUntil(expiry: Long, now: Long = System.currentTimeMillis()) = DayUtil.daysBetween(now, expiry)

    fun statusOf(daysLeft: Int, warnDays: Int = WARN_DAYS) = when {
        daysLeft < 0 -> ExpiryStatus.EXPIRED
        daysLeft <= warnDays -> ExpiryStatus.SOON
        else -> ExpiryStatus.OK
    }

    /** Only products with stock > 0 that are expired or expire within [warnDays]. Soonest first. */
    fun build(products: Iterable<Product>, now: Long = System.currentTimeMillis(), warnDays: Int = WARN_DAYS): List<ExpiryRow> {
        val out = mutableListOf<ExpiryRow>()
        for (p in products) {
            if (p.stock <= 1e-9) continue
            val e = parse(p.expiry) ?: continue
            val left = daysUntil(e, now)
            val st = statusOf(left, warnDays)
            if (st == ExpiryStatus.OK) continue
            out.add(ExpiryRow(p, e, left, st))
        }
        return out.sortedWith(compareBy<ExpiryRow> { it.daysLeft }.thenBy { it.product.name.lowercase() })
    }
}

// ------------------------------------------------------------------------------ Returns report

data class ReturnReportRow(
    val id: Long, val reference: String, val qty: Double, val amount: Double, val createdAt: Long,
    val itemName: String?, val partyName: String?, val unit: String?, val billTotal: Double?, val billStatus: String?
)

@androidx.room.Dao interface ReportsExtraDao {
    @androidx.room.Query("""SELECT r.id AS id, r.reference AS reference, r.qty AS qty, r.amount AS amount, r.createdAt AS createdAt,
        COALESCE((SELECT name FROM products WHERE barcode = r.barcode), r.barcode) AS itemName,
        COALESCE((SELECT c.name FROM sales s JOIN customers c ON c.id = s.customerId WHERE s.invoice = r.reference), 'Walk-in') AS partyName,
        COALESCE((SELECT unit FROM sale_items WHERE invoice = r.reference AND barcode = r.barcode LIMIT 1), '') AS unit,
        COALESCE((SELECT total FROM sales WHERE invoice = r.reference LIMIT 1), 0) AS billTotal,
        COALESCE((SELECT status FROM sales WHERE invoice = r.reference LIMIT 1), 'active') AS billStatus
        FROM returns r WHERE r.type = 'sale' AND r.createdAt BETWEEN :start AND :end ORDER BY r.createdAt DESC, r.id DESC""")
    suspend fun saleReturns(start: Long, end: Long): List<ReturnReportRow>

    @androidx.room.Query("""SELECT r.id AS id, r.reference AS reference, r.qty AS qty, r.amount AS amount, r.createdAt AS createdAt,
        COALESCE((SELECT name FROM products WHERE barcode = r.barcode), r.barcode) AS itemName,
        COALESCE((SELECT sp.name FROM purchases p JOIN suppliers sp ON sp.id = p.supplierId WHERE p.billNo = r.reference), 'Cash Purchase') AS partyName,
        COALESCE((SELECT unit FROM purchase_items WHERE billNo = r.reference AND barcode = r.barcode LIMIT 1), '') AS unit,
        COALESCE((SELECT total FROM purchases WHERE billNo = r.reference LIMIT 1), 0) AS billTotal,
        COALESCE((SELECT status FROM purchases WHERE billNo = r.reference LIMIT 1), 'active') AS billStatus
        FROM returns r WHERE r.type = 'purchase' AND r.createdAt BETWEEN :start AND :end ORDER BY r.createdAt DESC, r.id DESC""")
    suspend fun purchaseReturns(start: Long, end: Long): List<ReturnReportRow>
}
