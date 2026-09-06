package com.grocerypos.v11

import android.content.Context

/**
 * Runtime-configurable branch ID, replacing the old compile-time BuildConfig.BRANCH_ID.
 *
 * WHY THIS EXISTS: BuildConfig.BRANCH_ID meant every branch of a multi-branch shop
 * needed its own separately-built APK (see app/build.gradle.kts's old
 * buildConfigField("String", "BRANCH_ID", ...)). That doesn't scale — adding a new
 * branch meant a new build, and every device at a branch had to be flashed with the
 * matching APK. This store lets the branch code be entered once on-device, in
 * Settings > Cloud Sync Setup, the same way CloudConfigStore lets the Firebase
 * project be entered on-device instead of baked in at compile time.
 *
 * SECURITY NOTE: this value is NOT what grants a device access to a branch's data —
 * it's just what gets stamped onto documents this device pushes, and what pull()
 * filters by locally. The actual access control lives entirely server-side, in
 * Firestore's branch_members/{uid} mapping and the security rules that check against
 * it (see firestore.rules) — a device that enters the wrong branch code here simply
 * gets permission-denied by Firestore, it doesn't gain access to anything. Whoever
 * manages the Firebase console/admin script is the only place that can actually
 * grant a device access to a given branch's data.
 *
 * Generated/loaded once on app start and cached in memory (like DeviceTag), so it's
 * available synchronously wherever an ID needs to be built, without needing a
 * suspend function or a database call.
 */
object BranchConfigStore {
    private const val PREFS = "branch_config_prefs"
    private const val KEY_BRANCH_ID = "branch_id"

    // Fallback only used if init() somehow hasn't run yet when current is first read
    // (shouldn't happen — PosApplication.onCreate() calls init() before anything else
    // that could need it, same as DeviceTag).
    @Volatile private var cached: String = ""

    /** Call once, e.g. first line of PosApplication.onCreate() (after DeviceTag.init).
     *  Safe to call again. Falls back to the old BuildConfig.BRANCH_ID for existing
     *  installs that already had a compile-time branch baked in and haven't entered
     *  one on-device yet, so upgrading the app doesn't silently break their sync. */
    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_BRANCH_ID, null)
        cached = if (!stored.isNullOrBlank()) {
            stored
        } else {
            @Suppress("SENSELESS_COMPARISON")
            (BuildConfig.BRANCH_ID ?: "").also {
                if (it.isNotBlank()) {
                    // Persist the compile-time fallback on first run so it survives
                    // even if a future build removes the BuildConfig field entirely.
                    prefs.edit().putString(KEY_BRANCH_ID, it).apply()
                }
            }
        }
    }

    /** The branch code currently in effect on this device. Empty string means "not
     *  configured yet" — callers pushing/pulling should treat that the same way
     *  CloudConfigStore.isConfigured()==false is treated: nothing to sync. */
    val current: String get() = cached

    fun isConfigured(): Boolean = cached.isNotBlank()

    fun set(context: Context, branchId: String) {
        val trimmed = branchId.trim()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BRANCH_ID, trimmed).apply()
        cached = trimmed
    }
}
