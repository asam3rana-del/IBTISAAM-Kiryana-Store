package com.grocerypos.v11.data

import android.content.Context
import com.grocerypos.v11.Customer
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Purchase
import com.grocerypos.v11.Sale
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.SyncQueueHelper
import kotlinx.coroutines.flow.Flow

/**
 * Repository for Customer/Supplier data, plus the sale/purchase history shown
 * on their ledgers (PartyActivity's "tap for history" dialogs).
 *
 * This is the only place in the app that should call CustomerDao/SupplierDao/
 * SaleDao/PurchaseDao for party-related work — UseCases and the ViewModel go
 * through here instead of touching PosDatabase directly, and every write stays
 * paired with the matching SyncQueueHelper call (exactly as PartyActivity used
 * to do inline), so a screen can never insert/update/delete a party and forget
 * to queue it for sync.
 *
 * [appContext] should be an application Context (not an Activity one) since
 * this repository is expected to outlive any single screen.
 */
class PartyRepository(
    private val db: PosDatabase,
    private val appContext: Context
) {

    fun observeCustomers(): Flow<List<Customer>> = db.customerDao().all()

    fun observeSuppliers(): Flow<List<Supplier>> = db.supplierDao().all()

    suspend fun saveCustomer(customer: Customer): Long {
        val newId = db.customerDao().insert(customer)
        SyncQueueHelper.enqueueCustomer(db, customer.copy(id = newId))
        SyncQueueHelper.trigger(appContext)
        return newId
    }

    suspend fun saveSupplier(supplier: Supplier): Long {
        val newId = db.supplierDao().insert(supplier)
        SyncQueueHelper.enqueueSupplier(db, supplier.copy(id = newId))
        SyncQueueHelper.trigger(appContext)
        return newId
    }

    suspend fun updateCustomer(customer: Customer) {
        db.customerDao().update(customer)
        SyncQueueHelper.enqueueCustomer(db, customer)
        SyncQueueHelper.trigger(appContext)
    }

    suspend fun updateSupplier(supplier: Supplier) {
        db.supplierDao().update(supplier)
        SyncQueueHelper.enqueueSupplier(db, supplier)
        SyncQueueHelper.trigger(appContext)
    }

    suspend fun deleteCustomer(customer: Customer) {
        db.customerDao().delete(customer)
        // Prefer the id already stamped on this row (matches whatever was actually
        // pushed, even pre-DeviceTag data) — only compute fresh if this customer
        // was somehow never synced at all. (Preserved from the original inline logic.)
        SyncQueueHelper.enqueue(
            db, "customer",
            customer.serverId ?: SyncQueueHelper.customerEntityId(customer),
            "delete", "{}"
        )
        SyncQueueHelper.trigger(appContext)
    }

    suspend fun deleteSupplier(supplier: Supplier) {
        db.supplierDao().delete(supplier)
        SyncQueueHelper.enqueue(
            db, "supplier",
            supplier.serverId ?: SyncQueueHelper.supplierEntityId(supplier),
            "delete", "{}"
        )
        SyncQueueHelper.trigger(appContext)
    }

    suspend fun salesByCustomer(customerId: Long): List<Sale> =
        db.saleDao().salesByCustomer(customerId)

    suspend fun purchasesBySupplier(supplierId: Long): List<Purchase> =
        db.purchaseDao().purchasesBySupplier(supplierId)

    // NEW (Recalculate Balances): `customer.balance` / `supplier.balance` are running
    // totals — nudged up/down by SyncQueueHelper.adjustCustomerBalance/
    // adjustSupplierBalance on every sale, purchase, payment, edit, return, and delete
    // (see those functions' comments) — rather than something computed fresh from the
    // visible transaction list each time it's shown. That makes them fast to read, but
    // it also means any adjustment that ever fired without a matching real transaction
    // (a duplicate bill saved and deleted before the double-entry guard existed, an
    // interrupted multi-device sync, etc.) leaves a permanent drift that the ledger
    // itself never shows — the party's "You'll Give/Get" stops matching the sum of
    // their own bills. This recomputes each party's balance from scratch, straight
    // from their actual active (non-returned) bills and recorded payments, and nudges
    // the stored field back in line with only the *difference* — via the same
    // adjustCustomerBalance/adjustSupplierBalance path every other write already
    // uses — so the correction is a normal, sync-safe increment like any other,
    // not a raw overwrite that could clobber another device's not-yet-synced change.
    suspend fun recalculateBalances(): RecalcResult {
        var customersFixed = 0
        var suppliersFixed = 0
        val customers = db.customerDao().allList()
        for (c in customers) {
            val sales = db.saleDao().salesByCustomer(c.id).filter { it.status != "returned" }
            val payments = db.paymentDao().listByParty("customer", c.id)
            val trueBalance = sales.sumOf { it.total - it.paid } - payments.sumOf { it.amount }
            val delta = trueBalance - c.balance
            if (Math.abs(delta) > 0.009) {
                SyncQueueHelper.adjustCustomerBalance(db, c.id, delta)
                customersFixed++
            }
        }
        val suppliers = db.supplierDao().allList()
        for (s in suppliers) {
            val purchases = db.purchaseDao().purchasesBySupplier(s.id).filter { it.status != "returned" }
            val payments = db.paymentDao().listByParty("supplier", s.id)
            val trueBalance = purchases.sumOf { it.total - it.paid } - payments.sumOf { it.amount }
            val delta = trueBalance - s.balance
            if (Math.abs(delta) > 0.009) {
                SyncQueueHelper.adjustSupplierBalance(db, s.id, delta)
                suppliersFixed++
            }
        }
        if (customersFixed > 0 || suppliersFixed > 0) SyncQueueHelper.trigger(appContext)
        return RecalcResult(customersFixed, suppliersFixed)
    }
}

/** Result of [PartyRepository.recalculateBalances] — how many customers/suppliers
 * actually had a drifted balance corrected (0/0 means everything already matched). */
data class RecalcResult(val customersFixed: Int, val suppliersFixed: Int)
