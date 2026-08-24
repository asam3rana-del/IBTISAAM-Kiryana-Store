package com.grocerypos.v11.sync

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val result = SyncRepository.syncNow(applicationContext)
            if (result.pulledOk) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "grocery_pos_periodic_sync"
        private const val ONE_TIME_WORK_NAME = "grocery_pos_manual_sync"

        /**
         * Call this once, e.g. in your Application.onCreate() or MainActivity.onCreate(),
         * to start automatic background sync every 15 minutes (WorkManager's minimum
         * interval for periodic work). Safe to call every app start — ExistingPeriodicWorkPolicy.KEEP
         * means it won't duplicate or restart an already-scheduled sync.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Call this for an immediate one-off sync, e.g. from a "Sync Now" button's
         * onClick, or right after login, or after saving a sale/customer/product.
         */
        fun syncNowOnce(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        // ---- ADDED: NetworkMonitor.kt calls SyncWorker.triggerNow(context) from its
        // onAvailable() callback the moment connectivity comes back, so any sale/purchase/
        // expense that queued up while offline gets pushed right away instead of waiting
        // for the next 15-minute periodic run. Same one-off enqueue as syncNowOnce — kept
        // as a distinct name so call sites read as "connectivity just returned" vs.
        // "user tapped Sync Now", but they do the same thing under the hood. ----
        fun triggerNow(context: Context) {
            syncNowOnce(context)
        }
    }
}

/*
===================================================================================
SETUP — do these two things to actually turn sync on. Neither is inside this file.
===================================================================================

1) Start periodic background sync once, e.g. in your Application class:

    class GroceryPosApp : Application() {
        override fun onCreate() {
            super.onCreate()
            SyncWorker.schedulePeriodic(this)
        }
    }

   (Make sure GroceryPosApp is registered as android:name in AndroidManifest.xml's
   <application> tag — if you don't have a custom Application class yet, add one.)

2) Add a "Sync Now" button anywhere useful (e.g. Settings screen, or the main
   dashboard) so the user can force an immediate sync instead of waiting up to
   15 minutes:

    syncNowButton.setOnClickListener {
        SyncWorker.syncNowOnce(this)
        Toast.makeText(this, "Syncing…", Toast.LENGTH_SHORT).show()
    }

===================================================================================
THE OTHER MISSING PIECE — nothing enqueues rows into sync_queue yet.
===================================================================================
SyncRepository only PUSHES what's already sitting in the sync_queue table. Right
now nothing writes to that table when you add/edit a customer, supplier, or
product — so the queue stays empty and push() has nothing to send.

Wherever you currently call e.g. customerDao().insert(customer), also enqueue a
sync_queue row right after, using the same Gson used elsewhere in the app:

    val entry = SyncQueueEntry(
        entityType = "customer",
        entityId = newId.toString(),      // or customer.serverId if you generate one
        operation = "upsert",
        payloadJson = Gson().toJson(mapOf(
            "serverId" to newId.toString(),
            "name" to customer.name,
            "phone" to customer.phone,
            "balance" to customer.balance,
            "creditLimit" to customer.creditLimit,
            "openingBalance" to customer.openingBalance,
            "updatedAt" to System.currentTimeMillis()
        ))
    )
    db.syncQueueDao().enqueue(entry)

Do the same after supplier and product inserts/updates (entityType = "supplier" /
"product"). This is the one piece that touches your existing Activity/ViewModel
code, so it wasn't safe for me to guess at without seeing those files — send them
if you want this wired in directly.
*/
