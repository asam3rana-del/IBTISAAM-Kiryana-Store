package com.grocerypos.v11

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson

// =====================================================================================
// NEW (Flutter parity, batch 3): Recycle Bin — port of Flutter's recycle_bin_repository.dart
// for Expenses, Customers, Suppliers, Workers and Shop Rents. A delete stores a full snapshot
// (record + rows deleted with it); an admin can restore it (same ids, cash rows back, cloud doc
// un-tombstoned) or remove it for good. Batch 7 adds whole Sale / Purchase bills (BillRecycle.kt).
// =====================================================================================

/** Snapshot holder (all defaults => Gson gets a no-arg constructor; missing lists stay empty). */
data class RecycleBinData(
    val expenses: List<Expense> = emptyList(),
    val cash: List<CashTransaction> = emptyList(),
    val customers: List<Customer> = emptyList(),
    val suppliers: List<Supplier> = emptyList(),
    val workers: List<ShopWorker> = emptyList(),
    val attendance: List<WorkerAttendance> = emptyList(),
    val shops: List<RentedShop> = emptyList(),
    val terms: List<RentedShopTerm> = emptyList(),
    val ledger: List<RentLedgerEntry> = emptyList(),
    // NEW (batch 7): bills
    val sales: List<Sale> = emptyList(),
    val saleItems: List<SaleItem> = emptyList(),
    val purchases: List<Purchase> = emptyList(),
    val purchaseItems: List<PurchaseItem> = emptyList(),
    val payments: List<Payment> = emptyList(),
    /** Exactly how much stock (smallest units) the delete moved, per line, so restore undoes it 1:1. */
    val stock: List<BinStock> = emptyList()
)

data class BinStock(val barcode: String = "", val qty: Double = 0.0, val amount: Double = 0.0)

object RecycleBin {
    const val EXPENSE = "expense"
    const val CUSTOMER = "customer"
    const val SUPPLIER = "supplier"
    const val WORKER = "worker"
    const val SHOP_RENT = "shop_rent"
    const val SALE = "sale"
    const val PURCHASE = "purchase"

    private val gson = Gson()

    fun encode(d: RecycleBinData): String = gson.toJson(d)
    fun decode(json: String): RecycleBinData? = try { gson.fromJson(json, RecycleBinData::class.java) } catch (e: Exception) { null }

    private fun user(ctx: Context?): String =
        ctx?.getSharedPreferences("session", Context.MODE_PRIVATE)?.getString("username", null) ?: "unknown"

    /** Delete hooks call this INSIDE their delete transaction (failed delete => no snapshot). */
    suspend fun record(db: PosDatabase, ctx: Context?, kind: String, refKey: String, title: String, subtitle: String, amount: Double, data: RecycleBinData) {
        db.recycleBinDao().insert(RecycleBinEntry(kind = kind, refKey = refKey, title = title, subtitle = subtitle, amount = amount,
            deletedBy = user(ctx), deletedAt = System.currentTimeMillis(), dataJson = encode(data)))
    }

    suspend fun list(ctx: Context): List<RecycleBinEntry> = PosDatabase.get(ctx).recycleBinDao().all()
    suspend fun purge(ctx: Context, id: Long) = PosDatabase.get(ctx).recycleBinDao().delete(id)
    suspend fun purgeAll(ctx: Context) = PosDatabase.get(ctx).recycleBinDao().deleteAll()

    /** Restore. Throws BooksRuleException (message for the user) — the entry then stays in the bin. */
    suspend fun restore(ctx: Context, id: Long) {
        val db = PosDatabase.get(ctx)
        val bin = db.recycleBinDao()
        val entry = bin.find(id) ?: throw BooksRuleException("Ye entry bin mein nahi mili")
        val d = decode(entry.dataJson) ?: throw BooksRuleException("Is entry ka data kharab hai, restore nahi ho sakta")
        try {
            db.withTransaction {
                when (entry.kind) {
                    EXPENSE -> restoreExpense(db, d)
                    CUSTOMER -> restoreCustomer(db, d)
                    SUPPLIER -> restoreSupplier(db, d)
                    WORKER -> restoreWorker(db, d)
                    SHOP_RENT -> restoreShop(db, d)
                    SALE -> BillRecycle.restoreSale(db, d)
                    PURCHASE -> BillRecycle.restorePurchase(db, d)
                    else -> throw BooksRuleException("Is qism (${entry.kind}) ka restore abhi mumkin nahi")
                }
                bin.delete(id)
            }
        } catch (e: BooksRuleException) {
            throw e
        } catch (e: Exception) {
            throw BooksRuleException("Restore nahi ho saka (record pehle se maujood ya data mismatch): ${e.message ?: ""}")
        }
        SyncQueueHelper.trigger(ctx)
    }

    internal suspend fun restoreCash(db: PosDatabase, cash: List<CashTransaction>) {
        for (c in cash) {
            if (db.recycleBinDao().cashExists(c.id) > 0) continue
            db.recycleBinDao().insertCash(c.copy(dirty = true))
            SyncQueueHelper.enqueueCashTransaction(db, c)
            SyncQueueHelper.enqueueUndelete(db, "cash_transaction", SyncQueueHelper.cashTransactionEntityId(c))
        }
    }

    private suspend fun restoreExpense(db: PosDatabase, d: RecycleBinData) {
        val e = d.expenses.firstOrNull() ?: throw BooksRuleException("Kharche ka data nahi mila")
        if (db.recycleBinDao().expenseExists(e.id) > 0) throw BooksRuleException("Ye kharcha pehle se maujood hai")
        db.recycleBinDao().insertExpense(e.copy(dirty = true))
        SyncQueueHelper.enqueueExpense(db, e)
        SyncQueueHelper.enqueueUndelete(db, "expense", SyncQueueHelper.expenseEntityId(e))
        restoreCash(db, d.cash)
    }

    private suspend fun restoreCustomer(db: PosDatabase, d: RecycleBinData) {
        val c = d.customers.firstOrNull() ?: throw BooksRuleException("Customer ka data nahi mila")
        if (db.recycleBinDao().customerExists(c.id) > 0) throw BooksRuleException("Ye customer pehle se maujood hai")
        db.recycleBinDao().insertCustomer(c.copy(dirty = true, updatedAt = System.currentTimeMillis()))
        SyncQueueHelper.enqueueCustomer(db, c)
        SyncQueueHelper.enqueueUndelete(db, "customer", SyncQueueHelper.customerEntityId(c))
    }

    private suspend fun restoreSupplier(db: PosDatabase, d: RecycleBinData) {
        val s = d.suppliers.firstOrNull() ?: throw BooksRuleException("Supplier ka data nahi mila")
        if (db.recycleBinDao().supplierExists(s.id) > 0) throw BooksRuleException("Ye supplier pehle se maujood hai")
        db.recycleBinDao().insertSupplier(s.copy(dirty = true, updatedAt = System.currentTimeMillis()))
        SyncQueueHelper.enqueueSupplier(db, s)
        SyncQueueHelper.enqueueUndelete(db, "supplier", SyncQueueHelper.supplierEntityId(s))
    }

    private suspend fun restoreWorker(db: PosDatabase, d: RecycleBinData) {
        val w = d.workers.firstOrNull() ?: throw BooksRuleException("Worker ka data nahi mila")
        if (db.recycleBinDao().workerExists(w.id) > 0) throw BooksRuleException("Ye worker pehle se maujood hai")
        val x = db.shopExtrasDao()
        x.insertWorker(w)
        for (a in d.attendance) x.upsertAttendance(a)
        SyncQueueHelper.enqueueWorker(db, w)      // payload already carries _deleted = false
        for (a in d.attendance) SyncQueueHelper.enqueueWorkerAttendance(db, a.workerId, a.day)
    }

    private suspend fun restoreShop(db: PosDatabase, d: RecycleBinData) {
        val s = d.shops.firstOrNull() ?: throw BooksRuleException("Dukaan ka data nahi mila")
        if (db.recycleBinDao().shopExists(s.id) > 0) throw BooksRuleException("Ye dukaan pehle se maujood hai")
        val x = db.shopExtrasDao()
        x.insertShop(s)
        for (t in d.terms) x.upsertTerm(t)
        for (l in d.ledger) x.insertEntry(l)
        SyncQueueHelper.enqueueShopRent(db, s)
        for (l in d.ledger) SyncQueueHelper.enqueueShopRentEntry(db, l)
    }
}
