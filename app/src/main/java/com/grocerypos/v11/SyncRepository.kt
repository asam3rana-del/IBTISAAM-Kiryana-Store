package com.grocerypos.v11.sync

import android.content.Context
import com.grocerypos.v11.PosDatabase

/**
 * Ties SyncApi (Firestore) to the local Room database.
 * Call SyncRepository.syncNow(context) to run one full sync cycle:
 *   1. Push every pending row in sync_queue up to Firestore
 *   2. Pull anything changed on the server since the last successful sync
 *   3. Apply pulled changes into the local Room tables
 */
object SyncRepository {

    private const val PREFS_NAME = "sync_prefs"
    private const val KEY_LAST_SYNC = "last_sync_time"

    // Held so background workers / other classes (e.g. App.onCreate) can trigger
    // sync-related work without needing to pass a Context around everywhere.
    var appContextRef: Context? = null

    suspend fun syncNow(context: Context): SyncResult {
        val db = PosDatabase.get(context)
        val queueDao = db.syncQueueDao()

        var pushedCount = 0
        var failedCount = 0

        // ---- 1. PUSH: drain the local queue up to Firestore ----
        val pending = queueDao.pending(limit = 200)
        for (entry in pending) {
            val ok = SyncApi.push(entry)
            if (ok) {
                queueDao.markSynced(entry.id)
                pushedCount++
            } else {
                queueDao.markFailed(entry.id, "push failed")
                failedCount++
            }
        }

        // ---- 2. PULL: fetch anything new from Firestore ----
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val since = prefs.getLong(KEY_LAST_SYNC, 0L)

        val changes = try {
            SyncApi.pull(since)
        } catch (e: Exception) {
            return SyncResult(pushedCount, failedCount, pulledOk = false, error = e.message)
        }

        // ---- 3. APPLY: merge pulled changes into Room ----
        SyncApi.applyServerChanges(db, changes)

        prefs.edit().putLong(KEY_LAST_SYNC, changes.serverTime).apply()

        // Housekeeping: drop synced queue rows older than 7 days so the table
        // doesn't grow forever.
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        queueDao.pruneSynced(weekAgo)

        return SyncResult(
            pushedCount = pushedCount,
            failedCount = failedCount,
            pulledOk = true,
            customersReceived = changes.customers.size,
            suppliersReceived = changes.suppliers.size,
            productsReceived = changes.products.size
        )
    }

    data class SyncResult(
        val pushedCount: Int,
        val failedCount: Int,
        val pulledOk: Boolean,
        val customersReceived: Int = 0,
        val suppliersReceived: Int = 0,
        val productsReceived: Int = 0,
        val error: String? = null
    )
}
