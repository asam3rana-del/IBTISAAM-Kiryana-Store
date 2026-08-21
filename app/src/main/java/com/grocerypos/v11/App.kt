package com.grocerypos.v11

import android.app.Application
import com.grocerypos.v11.sync.NetworkMonitor
import com.grocerypos.v11.sync.SyncRepository
import com.grocerypos.v11.sync.SyncWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncRepository.appContextRef = applicationContext
        SyncWorker.schedulePeriodic(this)
        NetworkMonitor.register(this)
    }
}
