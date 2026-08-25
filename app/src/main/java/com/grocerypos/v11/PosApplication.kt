package com.grocerypos.v11

import android.app.Application
import com.grocerypos.v11.sync.NetworkMonitor
import com.grocerypos.v11.sync.SyncRepository
import com.grocerypos.v11.sync.SyncWorker

/**
 * Single Application class for the app.
 *
 * Registers:
 * - SyncRepository.appContextRef (needed by sync push/pull)
 * - SyncWorker periodic background sync (every 15 minutes)
 * - NetworkMonitor (triggers an immediate sync when connectivity returns)
 * - AppLock (watches every Activity, forces re-auth per Settings > Security)
 *
 * IMPORTANT: AndroidManifest.xml's <application> tag must have
 * android:name=".PosApplication" — without that line the OS never instantiates
 * this class and NONE of the above run (this was previously the case; a
 * duplicate App.kt with its own onCreate() was silently dead code and has
 * been removed).
 */
class PosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncRepository.appContextRef = applicationContext
        SyncWorker.schedulePeriodic(this)
        NetworkMonitor.register(this)
        AppLock.register(this)
    }
}
