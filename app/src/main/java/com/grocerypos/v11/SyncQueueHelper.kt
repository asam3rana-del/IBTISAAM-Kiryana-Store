package com.grocerypos.v11

import android.content.Context
import com.google.gson.Gson
import com.grocerypos.v11.sync.SyncWorker

/**
 * Bridges normal Room writes (customer/supplier/product inserts & updates) to the
 * offline-first sync pipeline (sync_queue table -> SyncApi.push -> Firestore).
 *
 * Usage pattern already used in PartyActivity.kt:
 *   val newId = db.customerDao().insert(customer)
 *   SyncQueueHelper.enqueue(db, "customer", "customer:$newId", "create", SyncQueueHelper.customerJson(customer.copy(id = newId)))
 *   SyncQueueHelper.trigger(this)
 */
object SyncQueueHelper {

    private val gson = Gson()

    /** Adds one row to the local sync_queue table. Does NOT talk to the network itself — call trigger() for that. */
    suspend fun enqueue(db: PosDatabase, entityType: String, entityId: String, operation: String, payloadJson: String) {
        db.syncQueueDao().enqueue(
            SyncQueueEntry(
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payloadJson = payloadJson
            )
        )
    }

    /** Kicks off an immediate background sync attempt (push queued rows + pull server changes). */
    fun trigger(context: Context) {
        SyncWorker.syncNowOnce(context)
    }

    /**
     * serverId is set to "customer:<localId>" so it's stable and matches what
     * CustomerDao.findByServerId() looks up when pulling this same row back down
     * (see SyncApi.applyServerChanges in SyncApi.kt).
     */
    fun customerJson(c: Customer): String {
        val map = mapOf(
            "serverId" to "customer:${c.id}",
            "name" to c.name,
            "phone" to c.phone,
            "balance" to c.balance,
            "creditLimit" to c.creditLimit,
            "openingBalance" to c.openingBalance,
            "updatedAt" to System.currentTimeMillis()
        )
        return gson.toJson(map)
    }

    fun supplierJson(s: Supplier): String {
        val map = mapOf(
            "serverId" to "supplier:${s.id}",
            "name" to s.name,
            "phone" to s.phone,
            "balance" to s.balance,
            "openingBalance" to s.openingBalance,
            "updatedAt" to System.currentTimeMillis()
        )
        return gson.toJson(map)
    }

    /**
     * Not called anywhere yet — confirmDeleteCustomer()/confirmDeleteSupplier() in
     * PartyActivity.kt currently only delete locally and don't propagate the delete
     * to Firestore. If you want deletes to sync too, call this right before the
     * local db.customerDao().delete(c) / db.supplierDao().delete(s) call:
     *   SyncQueueHelper.enqueue(db, "customer", "customer:${c.id}", "delete", "")
     *   SyncQueueHelper.trigger(this)
     */
    fun productJson(p: Product): String {
        val map = mapOf(
            "barcode" to p.barcode,
            "name" to p.name,
            "stock" to p.stock,
            "updatedAt" to System.currentTimeMillis()
        )
        return gson.toJson(map)
    }
}
