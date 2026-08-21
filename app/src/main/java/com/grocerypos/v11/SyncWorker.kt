package com.grocerypos.v11.sync

import android.content.Context
import androidx.work.*
import com.grocerypos.v11.AppSetting
import com.grocerypos.v11.PosDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = PosDatabase.get(applicationContext)
        val dao = db.syncQueueDao()
        val pending = dao.pending(50)

        var anyFailed = false

        if (pending.isNotEmpty()) {
            for (entry in pending) {
                try {
                    val ok = SyncApi.push(entry)
                    if (ok) {
                        dao.markSynced(entry.id)
                    } else {
                        dao.markFailed(entry.id, "server rejected")
                        anyFailed = true
                    }
                } catch (e: Exception) {
                    dao.markFailed(entry.id, e.message ?: "unknown error")
                    anyFailed = true
                }
            }
        }

        try {
            val settingDao = db.appSettingDao()
            val lastSync = settingDao.get("last_sync_ts")?.value?.toLongOrNull() ?: 0L
            val serverChanges = SyncApi.pull(lastSync)
            SyncApi.applyServerChanges(db, serverChanges)
            settingDao.set(AppSetting("last_sync_ts", System.currentTimeMillis().toString()))
        } catch (e: Exception) {
            anyFailed = true
        }

        dao.pruneSynced(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)

        if (anyFailed) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC_NAME = "pos_sync_periodic"
        private const val ONE_SHOT_NAME = "pos_sync_now"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun triggerNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_NAME, ExistingWorkPolicy.KEEP, request
            )
        }
    }
}
