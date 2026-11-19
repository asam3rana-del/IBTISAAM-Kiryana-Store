package com.grocerypos.v11

import android.content.Context

// =====================================================================================
// NEW (Flutter parity, batch 7): Sale / Purchase bills in the Recycle Bin — port of the
// sale / purchase part of Flutter's recycle_bin_repository.dart.
//
// Every WHOLE-bill delete path calls recordSale / recordPurchase INSIDE its delete
// transaction, BEFORE anything is removed (failed delete => no snapshot). The snapshot holds
// the bill, its lines, its own payments + cash rows, the bill-linked payments (+ their cash
// rows) that the delete voids, and exactly how much stock each line moved.
//
// Restore is the exact mirror of the delete (same ids, same invoice / billNo):
//   sale:     lines' stock goes out again (blocked if stock is now short), customer balance
//             gets the outstanding (total - paid) back, payments / cash rows come back.
//   purchase: lines' stock comes back with the same weighted-average cost rule the delete used
//             in reverse, supplier balance gets the outstanding back, payments / cash back.
// Cloud: the bill / payment / cash docs are re-pushed and un-tombstoned (enqueueUndelete).
// Item-level deletes (last line removed in Party ledger) are not binned (Flutter neither).
// =====================================================================================

object BillRecycle {

    private suspend fun collectPayments(db: PosDatabase, ref: String): Pair<List<Payment>, List<CashTransaction>> {
        val pays = LinkedHashMap<Long, Payment>()
        for (p in db.paymentDao().allByReference(ref)) pays[p.id] = p
        for (p in db.paymentDao().linkedPayments(ref)) pays[p.id] = p
        val cash = LinkedHashMap<Long, CashTransaction>()
        for (c in db.cashTransactionDao().allByReference(ref)) cash[c.id] = c
        for (p in pays.values) if (p.reference != ref) for (c in db.cashTransactionDao().allByReference(p.reference)) cash[c.id] = c
        return pays.values.toList() to cash.values.toList()
    }

    suspend fun recordSale(db: PosDatabase, ctx: Context?, invoice: String) {
        try {
            val sale = db.saleDao().findSale(invoice) ?: return
            val items = db.saleDao().itemsForInvoice(invoice)
            val (pays, cash) = collectPayments(db, invoice)
            val stock = items.map { si -> BinStock(si.barcode, si.smallestQty(db.productDao().find(si.barcode)), si.amount) }
            val party = sale.customerId?.let { db.customerDao().find(it)?.name } ?: "Walk-in"
            RecycleBin.record(db, ctx, RecycleBin.SALE, invoice, "Sale #$invoice", party, sale.total,
                RecycleBinData(sales = listOf(sale), saleItems = items, payments = pays, cash = cash, stock = stock))
        } catch (e: Exception) {
            // A snapshot must never block a delete.
            android.util.Log.w("BillRecycle", "recordSale failed", e)
        }
    }

    suspend fun recordPurchase(db: PosDatabase, ctx: Context?, billNo: String) {
        try {
            val pu = db.purchaseDao().findPurchase(billNo) ?: return
            val items = db.purchaseDao().itemsForBill(billNo)
            val (pays, cash) = collectPayments(db, billNo)
            val stock = items.mapNotNull { pi ->
                val p = db.productDao().find(pi.barcode) ?: return@mapNotNull null
                BinStock(pi.barcode, pi.smallestQty(p), pi.amount)
            }
            val party = pu.supplierId?.let { db.supplierDao().find(it)?.name } ?: "—"
            RecycleBin.record(db, ctx, RecycleBin.PURCHASE, billNo, "Purchase #$billNo", party, pu.total,
                RecycleBinData(purchases = listOf(pu), purchaseItems = items, payments = pays, cash = cash, stock = stock))
        } catch (e: Exception) {
            android.util.Log.w("BillRecycle", "recordPurchase failed", e)
        }
    }

    private suspend fun restorePayments(db: PosDatabase, d: RecycleBinData) {
        val bin = db.recycleBinDao()
        for (p in d.payments) {
            if (bin.paymentExists(p.id) > 0) continue
            bin.insertPayment(p.copy(dirty = true, updatedAt = System.currentTimeMillis()))
            val back = db.paymentDao().allByReference(p.reference).firstOrNull { it.id == p.id } ?: p
            SyncQueueHelper.enqueuePayment(db, back)
            SyncQueueHelper.enqueueUndelete(db, "payment", back.serverId ?: SyncQueueHelper.paymentEntityId(back))
        }
        RecycleBin.restoreCash(db, d.cash)
    }

    /** Lines that need stock but the shop doesn't have it any more (sale restore is then blocked). */
    suspend fun saleShortages(db: PosDatabase, d: RecycleBinData): List<String> {
        if (d.sales.isEmpty()) return emptyList()
        val need = LinkedHashMap<String, Double>()
        for (s in d.stock) if (s.qty > 0) need[s.barcode] = (need[s.barcode] ?: 0.0) + s.qty
        val out = mutableListOf<String>()
        for ((bc, q) in need) {
            val p = db.productDao().find(bc) ?: continue      // item deleted: only the bill comes back
            if (p.stock + 0.0001 < q) out.add("${p.name} (stock ${fmt(p.stock)}, chahiye ${fmt(q)})")
        }
        return out
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)

    suspend fun restoreSale(db: PosDatabase, d: RecycleBinData) {
        val sale = d.sales.firstOrNull() ?: throw BooksRuleException("Bill ka data nahi mila")
        val bin = db.recycleBinDao()
        if (bin.saleExists(sale.invoice) > 0) throw BooksRuleException("Ye bill (#${sale.invoice}) pehle se maujood hai")
        val short = saleShortages(db, d)
        if (short.isNotEmpty()) throw BooksRuleException("Stock kam hai, bill restore nahi ho sakta: " + short.joinToString(", "))
        val now = System.currentTimeMillis()
        val custExists = sale.customerId?.let { db.customerDao().find(it) != null } ?: false
        bin.insertSale(sale.copy(dirty = true, updatedAt = now, customerId = if (custExists) sale.customerId else null))
        if (d.saleItems.isNotEmpty()) bin.insertSaleItems(d.saleItems)
        // The delete gave this stock back to the shelf (every delete path does that, also for a
        // returned bill) — take exactly the same amount out again.
        for (s in d.stock) {
            if (s.qty <= 0 || db.productDao().find(s.barcode) == null) continue
            SyncQueueHelper.decreaseProductStock(db, s.barcode, s.qty, "SALE_RESTORE", sale.invoice)
        }
        val outstanding = sale.total - sale.paid
        if (custExists && kotlin.math.abs(outstanding) > 0.009) SyncQueueHelper.adjustCustomerBalance(db, sale.customerId!!, outstanding)
        restorePayments(db, d)
        val saved = db.saleDao().findSale(sale.invoice) ?: return
        SyncQueueHelper.enqueueSale(db, saved)
        SyncQueueHelper.enqueueUndelete(db, "sale", SyncQueueHelper.saleEntityId(saved))
    }

    suspend fun restorePurchase(db: PosDatabase, d: RecycleBinData) {
        val pu = d.purchases.firstOrNull() ?: throw BooksRuleException("Purchase ka data nahi mila")
        val bin = db.recycleBinDao()
        if (bin.purchaseExists(pu.billNo) > 0) throw BooksRuleException("Ye purchase bill (#${pu.billNo}) pehle se maujood hai")
        val now = System.currentTimeMillis()
        val supExists = pu.supplierId?.let { db.supplierDao().find(it) != null } ?: false
        bin.insertPurchase(pu.copy(dirty = true, updatedAt = now, supplierId = if (supExists) pu.supplierId else null))
        if (d.purchaseItems.isNotEmpty()) bin.insertPurchaseItems(d.purchaseItems)
        // Inverse of RoomPurchaseRepository.reverseStockAndCostForItems(): add the line's qty and
        // its amount back into the item's stock value => new weighted-average cost.
        for (s in d.stock) {
            if (s.qty <= 0) continue
            val p = db.productDao().find(s.barcode) ?: continue
            val newCost = PurchaseRestoreMath.costAfterAddBack(p.stock, p.cost, p.smallestUnitFactor(), s.qty, s.amount)
            SyncQueueHelper.increaseProductStock(db, s.barcode, s.qty, "PURCHASE_RESTORE", pu.billNo, newCost)
            SyncQueueHelper.updateProductCost(db, s.barcode, newCost)
        }
        // NEW (batch 8): the restored stock fills pending sale costs again.
        CostBackfill.reallocateBarcodes(db, pu.billNo, d.purchaseItems.map { it.barcode })
        val outstanding = pu.total - pu.paid
        if (supExists && kotlin.math.abs(outstanding) > 0.009) SyncQueueHelper.adjustSupplierBalance(db, pu.supplierId!!, outstanding)
        restorePayments(db, d)
        val saved = db.purchaseDao().findPurchase(pu.billNo) ?: return
        SyncQueueHelper.enqueuePurchase(db, saved)
        SyncQueueHelper.enqueueUndelete(db, "purchase", SyncQueueHelper.purchaseEntityId(saved))
    }
}

/** Pure (unit-tested). */
object PurchaseRestoreMath {
    /** cost is per the item's base unit; factor = smallest units per base unit. */
    fun costAfterAddBack(stock: Double, cost: Double, factor: Double, addQty: Double, addAmount: Double): Double {
        val f = if (factor > 0) factor else 1.0
        val perSmallest = cost / f
        val value = (stock.coerceAtLeast(0.0) * perSmallest) + addAmount
        val newStock = stock.coerceAtLeast(0.0) + addQty
        if (newStock <= 0) return cost
        return value / newStock * f
    }
}
