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

        // FIX (multi-tenant support): if this device has no Firebase project
        // configured at all (no custom Cloud Sync Setup entered, and this build has
        // no usable default), there is nothing to sync to — say so plainly instead of
        // quietly trying and failing, or worse, silently succeeding with zero effect.
        if (com.grocerypos.v11.CloudConfigStore.firebaseApp(context) == null) {
            return SyncResult(0, 0, pulledOk = false, error = "Cloud sync not set up — add your project in Settings > Cloud Sync Setup")
        }

        var pushedCount = 0
        var failedCount = 0

        // ---- 1. PUSH: drain the local queue up to Firestore ----
        val pending = queueDao.pending(limit = 200)
        for (entry in pending) {
            val ok = SyncApi.push(context, entry)
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
            SyncApi.pull(context, since)
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
            productsReceived = changes.products.size,
            salesReceived = changes.sales.size,
            purchasesReceived = changes.purchases.size,
            expensesReceived = changes.expenses.size,
            cashTxReceived = changes.cashTransactions.size
        )
    }

    data class SyncResult(
        val pushedCount: Int,
        val failedCount: Int,
        val pulledOk: Boolean,
        val customersReceived: Int = 0,
        val suppliersReceived: Int = 0,
        val productsReceived: Int = 0,
        val salesReceived: Int = 0,
        val purchasesReceived: Int = 0,
        val expensesReceived: Int = 0,
        val cashTxReceived: Int = 0,
        val error: String? = null
    ) {
        // ADDED (sync diagnostics): a short, human-readable one-liner so Settings'
        // "Sync Now" button can actually tell the user what happened instead of just
        // showing "Syncing…" and nothing else — that silence was making real failures
        // (e.g. a missing Firestore index, no internet, wrong Firebase project) look
        // exactly the same as success from the user's point of view.
        fun summary(): String {
            if (!pulledOk) return "Sync failed: ${error ?: "unknown error"}"
            val totalReceived = customersReceived + suppliersReceived + productsReceived +
                salesReceived + purchasesReceived + expensesReceived + cashTxReceived
            val parts = mutableListOf<String>()
            if (pushedCount > 0) parts.add("sent $pushedCount")
            if (totalReceived > 0) parts.add("received $totalReceived")
            if (failedCount > 0) parts.add("$failedCount failed")
            return if (parts.isEmpty()) "Already up to date" else parts.joinToString(", ").replaceFirstChar { it.uppercase() }
        }
    }
}
