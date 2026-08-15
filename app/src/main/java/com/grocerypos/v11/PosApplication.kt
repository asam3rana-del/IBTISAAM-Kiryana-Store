package com.grocerypos.v11

import android.app.Application

/**
 * Registers AppLock so it can watch every Activity in the app and force re-authentication
 * when the app is fully backgrounded and Settings > Security requires it.
 *
 * IMPORTANT: for this to take effect, AndroidManifest.xml's <application> tag needs
 * android:name=".PosApplication" — without that line the OS never instantiates this class
 * and AppLock.register() never runs.
 */
class PosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLock.register(this)
    }
}
