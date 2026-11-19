package com.grocerypos.v11

import androidx.room.Dao
import androidx.room.Query
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

// =====================================================================================
// NEW (Flutter parity, batch 8): "Pending Cost" — port of Flutter's db/cost_backfill.dart +
// db/pending_cost_repository.dart.
//
// When an item is sold on minus stock (or while its cost is 0) the sale line's real cost is not
// known yet. The sale stores the estimate in `cost` as always, plus:
//   pendingQty  — smallest units of that line whose real cost is unknown
//   pendingEst  — the estimated cost of that part that `cost` currently contains
//   backfillLog — JSON [{"b": billNo, "q": qty, "r": rate, "e": est}]
// When a purchase of the item is saved, its qty fills the oldest pending lines first at the
// purchase rate (cost = cost − estShare + qty × rate). Deleting / editing that purchase takes
// exactly its share back out (reverse). An admin can also fill lines from the item's current
// cost (Settings → Pending Cost) when the cost was typed in later without a purchase.
// Same columns + JSON as Flutter, so both apps agree after sync.
// =====================================================================================

data class PendingLine(
    val id: Long,
    val invoice: String,
    val cost: Double,
    val pendingQty: Double,
    val pendingEst: Double,
    val backfillLog: String
)

data class BackfillResult(
    val id: Long,
    val invoice: String,
    val cost: Double,
    val pendingQty: Double,
    val pendingEst: Double,
    val backfillLog: String
)

data class BackfillEntry(val b: String = "", val q: Double = 0.0, val r: Double = 0.0, val e: Double = 0.0)

/** Pure planning (unit-tested). */
object CostBackfillMath {
    const val EPS = 1e-6
    /** backfillLog 'b' when the cost came from the item's own cost (not a purchase bill). */
    const val PRODUCT_COST_TAG = "product-cost"

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val listType = object : TypeToken<List<BackfillEntry>>() {}.type

    fun round2(v: Double) = Math.round(v * 100.0) / 100.0

    fun decodeLog(raw: String?): List<BackfillEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try { gson.fromJson<List<BackfillEntry>>(raw, listType) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    fun encodeLog(log: List<BackfillEntry>): String = if (log.isEmpty()) "" else gson.toJson(log)

    /** The LIKE needle that finds a bill in a backfillLog (same as Flutter: '%' + jsonEncode(billNo) + '%'). */
    fun likeNeedle(billNo: String): String = "%" + gson.toJson(billNo) + "%"

    /** Sale save: [needed] smallest units are sold, [stockBefore] was on hand, item cost per smallest unit. */
    fun pendingForSale(needed: Double, stockBefore: Double, costPerSmallest: Double, lineCost: Double): Pair<Double, Double> {
        if (needed <= EPS) return 0.0 to 0.0
        val covered = if (costPerSmallest <= 0) 0.0 else if (stockBefore <= 0) 0.0 else minOf(stockBefore, needed)
        val pending = needed - covered
        if (pending <= EPS) return 0.0 to 0.0
        return pending to lineCost * (pending / needed)
    }

    /** Spread a purchase's [smallestQty] at [ratePerSmallest] over [lines] (oldest sale first). Only changed lines are returned. */
    fun planBackfill(lines: List<PendingLine>, billNo: String, smallestQty: Double, ratePerSmallest: Double): List<BackfillResult> {
        val out = mutableListOf<BackfillResult>()
        var remaining = smallestQty
        if (remaining <= EPS || ratePerSmallest.isNaN() || ratePerSmallest.isInfinite() || ratePerSmallest < 0) return out
        for (l in lines) {
            if (remaining <= EPS) break
            if (l.pendingQty <= EPS) continue
            val a = minOf(remaining, l.pendingQty)
            val estShare = if (l.pendingQty > 0) l.pendingEst * (a / l.pendingQty) else 0.0
            var newCost = l.cost - estShare + a * ratePerSmallest
            if (newCost < 0) newCost = 0.0
            var newPending = l.pendingQty - a
            var newEst = l.pendingEst - estShare
            if (newPending <= EPS) { newPending = 0.0; newEst = 0.0 }
            val log = decodeLog(l.backfillLog) + BackfillEntry(billNo, a, ratePerSmallest, estShare)
            out.add(BackfillResult(l.id, l.invoice, round2(newCost), newPending, newEst, encodeLog(log)))
            remaining -= a
        }
        return out
    }

    /** Take [billNo]'s filled cost back out (line becomes pending again). Only changed lines are returned. */
    fun planReverse(lines: List<PendingLine>, billNo: String): List<BackfillResult> {
        val out = mutableListOf<BackfillResult>()
        for (l in lines) {
            val log = decodeLog(l.backfillLog)
            if (log.none { it.b == billNo }) continue
            var cost = l.cost
            var pending = l.pendingQty
            var est = l.pendingEst
            val keep = mutableListOf<BackfillEntry>()
            for (e in log) {
                if (e.b != billNo) { keep.add(e); continue }
                cost = cost - e.q * e.r + e.e
                pending += e.q
                est += e.e
            }
            if (cost < 0) cost = 0.0
            out.add(BackfillResult(l.id, l.invoice, round2(cost), pending, est, encodeLog(keep)))
        }
        return out
    }

    /** Sale line qty changed by [ratio]: pending columns scale with it (cost scales the same way). */
    fun scaled(pendingQty: Double, pendingEst: Double, backfillLog: String, ratio: Double): Triple<Double, Double, String> {
        if (pendingQty <= EPS && backfillLog.isEmpty()) return Triple(pendingQty, pendingEst, backfillLog)
        val log = decodeLog(backfillLog).map { it.copy(q = it.q * ratio, e = it.e * ratio) }
        val q = pendingQty * ratio
        return Triple(if (q <= EPS) 0.0 else q, if (q <= EPS) 0.0 else pendingEst * ratio, encodeLog(log))
    }
}

/** Row for the Pending Cost screen. */
data class PendingCostRow(
    val id: Long,
    val invoice: String,
    val barcode: String,
    val product: String,
    val cost: Double,
    val pendingQty: Double,
    val pendingEst: Double,
    val backfillLog: String,
    val createdAt: Long,
    val party: String?
)

@Dao
interface CostBackfillDao {
    @Query("SELECT si.id AS id, si.invoice AS invoice, si.cost AS cost, si.pendingQty AS pendingQty, si.pendingEst AS pendingEst, si.backfillLog AS backfillLog " +
        "FROM sale_items si JOIN sales s ON s.invoice = si.invoice " +
        "WHERE si.barcode = :barcode AND si.pendingQty > 0.000001 AND s.status != 'returned' " +
        "ORDER BY s.createdAt ASC, si.id ASC")
    suspend fun pendingFor(barcode: String): List<PendingLine>

    @Query("SELECT id, invoice, cost, pendingQty, pendingEst, backfillLog FROM sale_items WHERE backfillLog LIKE :needle")
    suspend fun filledBy(needle: String): List<PendingLine>

    @Query("SELECT id, invoice, cost, pendingQty, pendingEst, backfillLog FROM sale_items WHERE backfillLog LIKE :needle AND barcode = :barcode")
    suspend fun filledByForItem(needle: String, barcode: String): List<PendingLine>

    @Query("UPDATE sale_items SET cost = :cost, pendingQty = :pendingQty, pendingEst = :pendingEst, backfillLog = :log WHERE id = :id")
    suspend fun write(id: Long, cost: Double, pendingQty: Double, pendingEst: Double, log: String)

    @Query("UPDATE sales SET updatedAt = :now, dirty = 1 WHERE invoice = :invoice")
    suspend fun touchSale(invoice: String, now: Long)

    @Query("SELECT si.id AS id, si.invoice AS invoice, si.barcode AS barcode, si.product AS product, si.cost AS cost, " +
        "si.pendingQty AS pendingQty, si.pendingEst AS pendingEst, si.backfillLog AS backfillLog, s.createdAt AS createdAt, c.name AS party " +
        "FROM sale_items si JOIN sales s ON s.invoice = si.invoice LEFT JOIN customers c ON c.id = s.customerId " +
        "WHERE si.pendingQty > 0.000001 AND s.status != 'returned' ORDER BY s.createdAt ASC, si.id ASC")
    suspend fun allPending(): List<PendingCostRow>

    @Query("SELECT COUNT(*) FROM sale_items si JOIN sales s ON s.invoice = si.invoice WHERE si.pendingQty > 0.000001 AND s.status != 'returned'")
    suspend fun pendingCount(): Int
}

/** DB side. Every call must run inside the caller's transaction (purchase / sale save etc.). */
object CostBackfill {

    private suspend fun write(db: PosDatabase, results: List<BackfillResult>) {
        if (results.isEmpty()) return
        val dao = db.costBackfillDao()
        val now = System.currentTimeMillis()
        val invoices = LinkedHashSet<String>()
        for (r in results) {
            dao.write(r.id, r.cost, r.pendingQty, r.pendingEst, r.backfillLog)
            invoices.add(r.invoice)
        }
        for (inv in invoices) {
            dao.touchSale(inv, now)
            // The other device needs the bill's new cost / pending state too.
            db.saleDao().findSale(inv)?.let { SyncQueueHelper.enqueueSale(db, it) }
        }
    }

    /** Call AFTER a purchase line's stock was added: fill this item's pending sale lines (oldest first). */
    suspend fun apply(db: PosDatabase, billNo: String, barcode: String, smallestQty: Double, ratePerSmallest: Double) {
        if (smallestQty <= CostBackfillMath.EPS) return
        val lines = db.costBackfillDao().pendingFor(barcode)
        if (lines.isEmpty()) return
        write(db, CostBackfillMath.planBackfill(lines, billNo, smallestQty, ratePerSmallest))
    }

    /** Purchase delete / return / edit: take that bill's filled cost back out ([barcode] = only that item). */
    suspend fun reverse(db: PosDatabase, billNo: String, barcode: String? = null) {
        val needle = CostBackfillMath.likeNeedle(billNo)
        val dao = db.costBackfillDao()
        val lines = if (barcode == null) dao.filledBy(needle) else dao.filledByForItem(needle, barcode)
        if (lines.isEmpty()) return
        write(db, CostBackfillMath.planReverse(lines, billNo))
    }

    /**
     * Redo [billNo]'s backfill for [barcode]: reverse what it filled before, then fill again from
     * the bill's CURRENT lines. Purchase save / edit / line edit call this after stock changed;
     * bill or line gone (or bill returned) => only the reverse happens.
     */
    suspend fun reallocateBill(db: PosDatabase, billNo: String, barcode: String) {
        reverse(db, billNo, barcode)
        val pu = db.purchaseDao().findPurchase(billNo) ?: return
        if (pu.status == "returned") return
        val product = db.productDao().find(barcode)
        for (it in db.purchaseDao().itemsForBill(billNo)) {
            if (it.barcode != barcode) continue
            val smallest = it.smallestQty(product)
            if (smallest <= CostBackfillMath.EPS) continue
            apply(db, billNo, barcode, smallest, it.amount / smallest)
        }
    }

    /** All barcodes of a purchase (old + new lines) — convenience for save / edit. */
    suspend fun reallocateBarcodes(db: PosDatabase, billNo: String, barcodes: Collection<String>) {
        for (bc in barcodes.toSet()) reallocateBill(db, billNo, bc)
    }

    /** Item cost per smallest unit (Product.cost is per the item's base unit). */
    fun costPerSmallest(p: Product): Double {
        val f = p.smallestUnitFactor()
        return if (f > 0) p.cost / f else p.cost
    }

    /** Pending (qty, est) for a NEW sale line. [stockBefore] = stock before this line was deducted. */
    fun pendingForLine(p: Product, needed: Double, stockBefore: Double, lineCost: Double): Pair<Double, Double> =
        CostBackfillMath.pendingForSale(needed, stockBefore, costPerSmallest(p), lineCost)

    /** Admin: fill chosen pending lines from the item's CURRENT cost (items with cost 0 are skipped). */
    suspend fun fillFromProductCost(db: PosDatabase, ids: Set<Long>? = null): Int {
        val rows = db.costBackfillDao().allPending()
        val cache = HashMap<String, Product?>()
        val plan = mutableListOf<BackfillResult>()
        for (r in rows) {
            if (ids != null && r.id !in ids) continue
            val p = cache.getOrPut(r.barcode) { db.productDao().find(r.barcode) }
            if (p == null || p.cost <= 0) continue
            val line = PendingLine(r.id, r.invoice, r.cost, r.pendingQty, r.pendingEst, r.backfillLog)
            plan.addAll(CostBackfillMath.planBackfill(listOf(line), CostBackfillMath.PRODUCT_COST_TAG, line.pendingQty, costPerSmallest(p)))
        }
        write(db, plan)
        return plan.size
    }
}
