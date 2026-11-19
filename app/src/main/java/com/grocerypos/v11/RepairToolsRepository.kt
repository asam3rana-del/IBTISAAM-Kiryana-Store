package com.grocerypos.v11

import android.content.Context
import androidx.room.withTransaction
import com.grocerypos.v11.data.RoomPurchaseRepository
import com.grocerypos.v11.data.RoomSaleRepository
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.round

// =====================================================================================
// NEW (Flutter parity, batch 3): admin repair tools — port of Flutter's
//   bill_cost_repair_repository.dart  (inflated line cost after a unit-ladder change)
//   cost_check_repository.dart        (average cost drifted far from the last purchase rate)
//   stock_rebuild_repository.dart     (stock = month's purchases − sales since, via STOCK_TAKE)
//   month_cleanup_repository.dart     (delete every sale + purchase of one month)
// Product cost = PRIMARY-unit rate, stock = SMALLEST unit (same as SaleCart.kt).
// =====================================================================================

object RepairMath {
    const val SUSPECT_RATIO = 1.5
    const val SUSPECT_MIN_GAP = 50.0
    const val DRIFT_RATIO = 1.5

    fun costPerSmallest(cost: Double, factor: Double): Double {
        if (cost.isNaN() || cost.isInfinite() || cost < 0) return 0.0
        if (factor.isNaN() || factor <= 0) return cost
        return cost / factor
    }

    fun isSuspiciousLineCost(cost: Double, amount: Double): Boolean {
        if (cost.isNaN() || amount.isNaN() || cost.isInfinite() || amount.isInfinite()) return false
        if (cost <= 0) return false
        return cost > amount * SUSPECT_RATIO && (cost - amount) > SUSPECT_MIN_GAP
    }

    /** Correct line cost from the product's CURRENT cost. [storedFactor] = line's frozen conversionFactor (0 = ladder). */
    fun repairedLineCost(p: Product, qty: Double, unit: String, storedFactor: Double): Double {
        val smallestQty = if (storedFactor > 0) qty * storedFactor else p.toSmallestUnits(qty, unit)
        return round(smallestQty * costPerSmallest(p.cost, p.smallestUnitFactor()) * 100) / 100
    }

    fun isCostDrifted(avgPrimary: Double, lastPrimary: Double): Boolean {
        if (lastPrimary.isNaN() || lastPrimary <= 0) return false
        if (avgPrimary.isNaN() || avgPrimary <= 0) return true
        return avgPrimary > lastPrimary * DRIFT_RATIO || avgPrimary * DRIFT_RATIO < lastPrimary
    }

    /** Purchase line rate (in that line's unit) -> product's primary-unit rate. */
    fun purchaseRateToPrimary(p: Product, unitCost: Double, unit: String, conversionFactor: Double): Double {
        val primaryFactor = p.smallestUnitFactor()
        if (conversionFactor > 0 && primaryFactor > 0) return unitCost / conversionFactor * primaryFactor
        return p.toPrimaryUnitRate(unitCost, if (unit.isBlank()) p.unit else unit)
    }
}

// ---- read models for the raw queries below
data class CostRepairRow(val id: Long, val invoice: String, val barcode: String, val product: String, val qty: Double,
                         val unit: String?, val cost: Double, val amount: Double, val cf: Double?, val at: Long, val party: String?)
data class LastPurchaseRow(val barcode: String, val unitCost: Double, val unit: String?, val cf: Double?, val at: Long)
data class QtyRow(val barcode: String, val qty: Double, val unit: String?, val cf: Double?, val at: Long)
data class BillTotalRow(val ref: String, val total: Double)

@androidx.room.Dao interface RepairToolsDao {
    @androidx.room.Query("""SELECT si.id AS id, si.invoice AS invoice, si.barcode AS barcode, si.product AS product, si.qty AS qty,
        si.unit AS unit, si.cost AS cost, si.amount AS amount, si.conversionFactor AS cf, s.createdAt AS at, c.name AS party
        FROM sale_items si JOIN sales s ON s.invoice = si.invoice LEFT JOIN customers c ON c.id = s.customerId
        WHERE s.status = 'active' AND si.cost > si.amount * :ratio AND (si.cost - si.amount) > :gap
        ORDER BY s.createdAt DESC, si.id""")
    suspend fun suspectLines(ratio: Double, gap: Double): List<CostRepairRow>

    @androidx.room.Query("UPDATE sale_items SET cost = :newCost WHERE id = :id AND ABS(cost - :oldCost) < 0.01")
    suspend fun setLineCost(id: Long, oldCost: Double, newCost: Double): Int

    @androidx.room.Query("UPDATE sales SET dirty = 1, updatedAt = :now WHERE invoice = :invoice")
    suspend fun touchSale(invoice: String, now: Long)

    @androidx.room.Query("""SELECT pi.barcode AS barcode, pi.unitCost AS unitCost, pi.unit AS unit, pi.conversionFactor AS cf, pu.createdAt AS at
        FROM purchase_items pi JOIN purchases pu ON pi.billNo = pu.billNo
        WHERE pi.unitCost > 0 AND pi.barcode != '' ORDER BY pu.createdAt DESC""")
    suspend fun purchaseRatesNewestFirst(): List<LastPurchaseRow>

    @androidx.room.Query("UPDATE products SET cost = :cost, dirty = 1, updatedAt = :now WHERE barcode = :barcode")
    suspend fun setCost(barcode: String, cost: Double, now: Long): Int

    @androidx.room.Query("""SELECT pi.barcode AS barcode, pi.qty AS qty, pi.unit AS unit, pi.conversionFactor AS cf, p.createdAt AS at
        FROM purchase_items pi JOIN purchases p ON p.billNo = pi.billNo
        WHERE p.status != 'returned' AND p.createdAt >= :from AND p.createdAt < :to AND pi.barcode != ''""")
    suspend fun purchasedBetween(from: Long, to: Long): List<QtyRow>

    @androidx.room.Query("""SELECT si.barcode AS barcode, si.qty AS qty, si.unit AS unit, si.conversionFactor AS cf, s.createdAt AS at
        FROM sale_items si JOIN sales s ON s.invoice = si.invoice WHERE s.status != 'returned' AND s.createdAt >= :from""")
    suspend fun soldSince(from: Long): List<QtyRow>

    @androidx.room.Query("SELECT invoice AS ref, total AS total FROM sales WHERE createdAt >= :from AND createdAt < :to ORDER BY createdAt DESC")
    suspend fun salesBetween(from: Long, to: Long): List<BillTotalRow>

    @androidx.room.Query("SELECT billNo AS ref, total AS total FROM purchases WHERE createdAt >= :from AND createdAt < :to ORDER BY createdAt DESC")
    suspend fun purchasesBetween(from: Long, to: Long): List<BillTotalRow>
}

data class CostRepairLine(val itemId: Long, val invoice: String, val createdAt: Long, val party: String, val productName: String,
                          val unit: String, val qty: Double, val amount: Double, val oldCost: Double, val newCost: Double) {
    val profitGain: Double get() = oldCost - newCost
}
data class CostRepairSkipped(val invoice: String, val productName: String, val reason: String)
data class CostCheckRow(val product: Product, val lastRate: Double, val lastAt: Long)
data class StockRebuildLine(val product: Product, val purchased: Double, val soldAfter: Double, val target: Double, val clamped: Boolean)
data class StockRebuildPlan(val from: Long, val to: Long, val lines: List<StockRebuildLine>, val unchanged: Int, val skippedInvalid: Int)
data class MonthCleanupPreview(val saleIds: List<String>, val purchaseIds: List<String>, val saleTotal: Double, val purchaseTotal: Double)

object RepairTools {

    // ------------------------------------------------------------------ Bill Cost Repair

    suspend fun buildCostRepairPlan(ctx: Context): Pair<List<CostRepairLine>, List<CostRepairSkipped>> {
        val db = PosDatabase.get(ctx)
        val products = db.productDao().allList().associateBy { it.barcode }
        val lines = mutableListOf<CostRepairLine>()
        val skipped = mutableListOf<CostRepairSkipped>()
        for (r in db.repairToolsDao().suspectLines(RepairMath.SUSPECT_RATIO, RepairMath.SUSPECT_MIN_GAP)) {
            val p = products[r.barcode]
            if (p == null) { skipped.add(CostRepairSkipped(r.invoice, r.product, "Product ab list mein nahi")); continue }
            if (p.cost <= 0) { skipped.add(CostRepairSkipped(r.invoice, r.product, "Product ki cost 0 hai — pehle product mein cost likhen")); continue }
            val newCost = RepairMath.repairedLineCost(p, r.qty, r.unit ?: "", r.cf ?: 0.0)
            if (abs(newCost - r.cost) < 0.01) continue
            if (RepairMath.isSuspiciousLineCost(newCost, r.amount)) {
                skipped.add(CostRepairSkipped(r.invoice, r.product, "Product ki maujooda cost bhi sale se bohat zyada hai — product check karen")); continue
            }
            lines.add(CostRepairLine(r.id, r.invoice, r.at, r.party?.trim()?.ifBlank { null } ?: "Walk-in", r.product, r.unit ?: "", r.qty, r.amount, r.cost, newCost))
        }
        return Pair(lines, skipped)
    }

    suspend fun applyCostRepair(ctx: Context, chosen: List<CostRepairLine>): Int {
        if (chosen.isEmpty()) throw BooksRuleException("Koi line nahi chuni")
        val db = PosDatabase.get(ctx)
        var changed = 0
        val invoices = LinkedHashSet<String>()
        val details = StringBuilder()
        db.withTransaction {
            for (l in chosen) {
                val n = db.repairToolsDao().setLineCost(l.itemId, l.oldCost, l.newCost)
                if (n > 0) {
                    changed += n; invoices.add(l.invoice)
                    if (details.length < 600) details.append("${l.invoice}/${l.productName}: ${l.oldCost.toLong()}->${l.newCost.toLong()}; ")
                }
            }
            val now = System.currentTimeMillis()
            for (inv in invoices) {
                db.repairToolsDao().touchSale(inv, now)
                db.saleDao().findSale(inv)?.let { SyncQueueHelper.enqueueSale(db, it) }
            }
        }
        SyncQueueHelper.logAudit(db, ctx, "bill_cost_repair", "lines=$changed bills=${invoices.size}", details.toString())
        if (invoices.isNotEmpty()) SyncQueueHelper.trigger(ctx)
        return changed
    }

    // ------------------------------------------------------------------ Cost Check

    suspend fun scanCosts(ctx: Context): List<CostCheckRow> {
        val db = PosDatabase.get(ctx)
        val latest = LinkedHashMap<String, LastPurchaseRow>()
        for (r in db.repairToolsDao().purchaseRatesNewestFirst()) if (!latest.containsKey(r.barcode)) latest[r.barcode] = r
        val out = mutableListOf<CostCheckRow>()
        for (p in db.productDao().allList()) {
            val r = latest[p.barcode] ?: continue
            val rate = RepairMath.purchaseRateToPrimary(p, r.unitCost, r.unit ?: "", r.cf ?: 0.0)
            if (RepairMath.isCostDrifted(p.cost, rate)) out.add(CostCheckRow(p, rate, r.at))
        }
        return out.sortedBy { it.product.name.lowercase() }
    }

    suspend fun applyCosts(ctx: Context, costByBarcode: Map<String, Double>): Int {
        if (costByBarcode.isEmpty()) return 0
        val db = PosDatabase.get(ctx)
        val now = System.currentTimeMillis()
        var changed = 0
        val details = StringBuilder()
        db.withTransaction {
            for ((bc, cost) in costByBarcode) {
                if (cost.isNaN() || cost <= 0) continue
                val before = db.productDao().find(bc) ?: continue
                if (db.repairToolsDao().setCost(bc, cost, now) > 0) {
                    changed++
                    db.productDao().find(bc)?.let { SyncQueueHelper.enqueueProduct(db, it) }
                    if (details.length < 600) details.append("${before.name}: ${"%.2f".format(before.cost)}->${"%.2f".format(cost)}; ")
                }
            }
        }
        SyncQueueHelper.logAudit(db, ctx, "cost_check_fix", "products=$changed", details.toString())
        if (changed > 0) SyncQueueHelper.trigger(ctx)
        return changed
    }

    // ------------------------------------------------------------------ Stock Rebuild

    private fun smallest(p: Product, qty: Double, unit: String?, cf: Double?): Double =
        if ((cf ?: 0.0) > 0) qty * cf!! else p.toSmallestUnits(qty, unit ?: "")

    /** Month [year]/[month]: stock = that month's purchases − sales since the first of those purchases (min 0). */
    suspend fun buildStockRebuild(ctx: Context, year: Int, month: Int): StockRebuildPlan {
        val db = PosDatabase.get(ctx)
        val from = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }.timeInMillis
        val to = Calendar.getInstance().apply { clear(); set(year, month - 1, 1); add(Calendar.MONTH, 1) }.timeInMillis
        val products = db.productDao().allList().associateBy { it.barcode }
        val purchased = HashMap<String, Double>()
        val firstAt = HashMap<String, Long>()
        for (r in db.repairToolsDao().purchasedBetween(from, to)) {
            val p = products[r.barcode] ?: continue
            purchased[r.barcode] = (purchased[r.barcode] ?: 0.0) + smallest(p, r.qty, r.unit, r.cf)
            val prev = firstAt[r.barcode]
            if (prev == null || r.at < prev) firstAt[r.barcode] = r.at
        }
        val sold = HashMap<String, Double>()
        if (firstAt.isNotEmpty()) {
            for (r in db.repairToolsDao().soldSince(from)) {
                val start = firstAt[r.barcode] ?: continue
                val p = products[r.barcode] ?: continue
                if (r.at < start) continue
                sold[r.barcode] = (sold[r.barcode] ?: 0.0) + smallest(p, r.qty, r.unit, r.cf)
            }
        }
        val lines = mutableListOf<StockRebuildLine>()
        var unchanged = 0; var invalid = 0
        for ((bc, bought) in purchased) {
            val p = products[bc] ?: continue
            val raw = bought - (sold[bc] ?: 0.0)
            val clamped = raw < 0
            val target = round((if (clamped) 0.0 else raw) * 1000) / 1000
            if (!p.isValidSmallestQty(target)) { invalid++; continue }
            if (abs(target - p.stock) <= 0.0001) { unchanged++; continue }
            lines.add(StockRebuildLine(p, bought, sold[bc] ?: 0.0, target, clamped))
        }
        return StockRebuildPlan(from, to, lines.sortedBy { it.product.name.lowercase() }, unchanged, invalid)
    }

    /** Same path as StockTakingActivity.commit(): STOCK_TAKE ledger + stock delta sync. Returns session id. */
    suspend fun applyStockRebuild(ctx: Context, plan: StockRebuildPlan): String {
        val db = PosDatabase.get(ctx)
        val sessionId = "SR" + java.text.SimpleDateFormat("yyMMddHHmmss", java.util.Locale.US).format(java.util.Date())
        db.withTransaction {
            for (l in plan.lines) {
                val delta = l.target - l.product.stock
                val note = "rebuild: purchased=${l.purchased} sold=${l.soldAfter} target=${l.target}"
                if (delta > 0) SyncQueueHelper.increaseProductStock(db, l.product.barcode, delta, "STOCK_TAKE", sessionId, note = note)
                else if (delta < 0) SyncQueueHelper.decreaseProductStockForce(db, l.product.barcode, -delta, "STOCK_TAKE", sessionId, note = note)
            }
        }
        SyncQueueHelper.logAudit(db, ctx, "stock_rebuild", sessionId, "items=${plan.lines.size}")
        SyncQueueHelper.trigger(ctx)
        return sessionId
    }

    // ------------------------------------------------------------------ Month Cleanup

    private fun monthRange(year: Int, month: Int): Pair<Long, Long> {
        val s = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }.timeInMillis
        val e = Calendar.getInstance().apply { clear(); set(year, month - 1, 1); add(Calendar.MONTH, 1) }.timeInMillis
        return Pair(s, e)
    }

    suspend fun previewMonthCleanup(ctx: Context, year: Int, month: Int): MonthCleanupPreview {
        val dao = PosDatabase.get(ctx).repairToolsDao()
        val (s, e) = monthRange(year, month)
        val sales = dao.salesBetween(s, e)
        val purchases = dao.purchasesBetween(s, e)
        return MonthCleanupPreview(sales.map { it.ref }, purchases.map { it.ref }, sales.sumOf { it.total }, purchases.sumOf { it.total })
    }

    /** Admin only (checked by the screen). Uses the normal bill delete (stock, balance, cash, sync all reversed). */
    suspend fun runMonthCleanup(ctx: Context, year: Int, month: Int): Triple<Int, Int, List<String>> {
        val db = PosDatabase.get(ctx)
        val p = previewMonthCleanup(ctx, year, month)
        val saleRepo = RoomSaleRepository(db, ctx.applicationContext)
        val purchaseRepo = RoomPurchaseRepository(db, ctx.applicationContext)
        var sd = 0; var pd = 0
        val failures = mutableListOf<String>()
        for (id in p.saleIds) {
            try { saleRepo.deleteSale(id, null, emptyList()); sd++ } catch (e: Exception) { failures.add("Sale $id: ${e.message}") }
        }
        for (id in p.purchaseIds) {
            try { purchaseRepo.deletePurchase(id, null, emptyList()); pd++ } catch (e: Exception) { failures.add("Purchase $id: ${e.message}") }
        }
        SyncQueueHelper.logAudit(db, ctx, "month_cleanup", "${year}-${month.toString().padStart(2, '0')}", "sales=$sd purchases=$pd failures=${failures.size}")
        SyncQueueHelper.trigger(ctx)
        return Triple(sd, pd, failures)
    }
}
