package com.grocerypos.v11

import com.grocerypos.v11.sync.SyncRepository
import com.grocerypos.v11.sync.SyncWorker
import org.json.JSONObject

/**
 * ---- FIX: sync_queue was never actually populated. SyncRepository (create/update helpers that
 * enqueue into sync_queue) existed but nothing called it — every real save path (SaleActivity,
 * PurchaseActivity, PartyActivity, PartyTransactionActivity, ExpenseActivity, CashActivity) wrote
 * straight to the DAOs. SyncWorker therefore ran every 15 minutes, found `pending()` empty every
 * time, and reported success while pushing nothing.
 *
 * Rather than route every activity's save through SyncRepository's transaction wrapper (a much
 * larger change touching every save flow), this is a small helper called right after each
 * existing DAO write already commits, so every real create/update actually lands a row in
 * sync_queue and wakes SyncWorker. Call `SyncQueueHelper.trigger(context)` once per user action
 * even if several enqueue() calls happened for it (e.g. a sale + its items), so we don't spam
 * WorkManager — enqueueUniqueWork with KEEP already coalesces this, but trigger() is still cheap
 * to call once at the end of a save. ----
 */
object SyncQueueHelper {

    suspend fun enqueue(
        db: PosDatabase,
        entityType: String,
        entityId: String,
        operation: String,
        payload: JSONObject
    ) {
        db.syncQueueDao().enqueue(
            SyncQueueEntry(
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payloadJson = payload.toString()
            )
        )
    }

    fun trigger(context: android.content.Context) {
        SyncRepository.appContextRef = context.applicationContext
        SyncWorker.triggerNow(context)
    }

    fun saleJson(sale: Sale, itemCount: Int) = JSONObject().apply {
        put("invoice", sale.invoice); put("customerId", sale.customerId)
        put("subtotal", sale.subtotal); put("discount", sale.discount); put("tax", sale.tax)
        put("total", sale.total); put("paid", sale.paid); put("paymentMethod", sale.paymentMethod)
        put("saleType", sale.saleType); put("createdAt", sale.createdAt); put("status", sale.status)
        put("itemCount", itemCount)
    }

    fun purchaseJson(purchase: Purchase, itemCount: Int) = JSONObject().apply {
        put("billNo", purchase.billNo); put("supplierId", purchase.supplierId)
        put("total", purchase.total); put("paid", purchase.paid); put("subtotal", purchase.subtotal)
        put("discount", purchase.discount); put("createdAt", purchase.createdAt)
        put("status", purchase.status); put("itemCount", itemCount)
    }

    fun customerJson(c: Customer) = JSONObject().apply {
        put("id", c.id); put("name", c.name); put("phone", c.phone)
        put("creditLimit", c.creditLimit); put("openingBalance", c.openingBalance); put("balance", c.balance)
    }

    fun supplierJson(s: Supplier) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("phone", s.phone)
        put("openingBalance", s.openingBalance); put("balance", s.balance)
    }

    fun paymentJson(p: Payment) = JSONObject().apply {
        put("reference", p.reference); put("partyType", p.partyType); put("partyId", p.partyId)
        put("amount", p.amount); put("method", p.method); put("note", p.note)
    }

    fun expenseJson(e: Expense) = JSONObject().apply {
        put("category", e.category); put("description", e.description); put("amount", e.amount)
    }

    fun cashTransactionJson(t: CashTransaction) = JSONObject().apply {
        put("type", t.type); put("method", t.method); put("amount", t.amount)
        put("reason", t.reason); put("reference", t.reference)
    }
}
