package com.grocerypos.v11

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.grocerypos.v11.ui.LoginActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tracks whether the app as a whole (not just one Activity) has left the foreground, and — if
 * Settings > Security is set to "Fingerprint Only" or "Both" — forces the user back through
 * LoginActivity the next time ANY Activity comes to the front.
 *
 * Why a counter instead of relying on onPause/onResume of a single Activity: navigating between
 * two of the app's own screens (e.g. MainActivity -> SaleActivity) also fires onPause/onResume,
 * but that is NOT the user leaving the app. Every Activity's onStart/onStop increments/decrements
 * `startedCount`; it only reaches 0 when literally nothing from this app is visible anymore
 * (Home pressed, app switched away, screen locked, etc.) — that's the only case that should
 * trigger a re-lock.
 *
 * Must be registered once from the Application class (see PosApplication).
 */
object AppLock {

    private var startedCount = 0

    @Volatile
    private var pendingReauth = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityStarted(activity: Activity) {
                startedCount++

                // The app just came back to the foreground after being fully backgrounded,
                // and the saved login method requires re-authentication. Redirect whichever
                // Activity is coming up straight to LoginActivity, clearing the task so Back
                // can't skip past it. LoginActivity itself is excluded so this doesn't loop.
                if (pendingReauth && activity !is LoginActivity) {
                    pendingReauth = false
                    val intent = Intent(activity, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    activity.startActivity(intent)
                    activity.finish()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount--
                if (startedCount <= 0) {
                    startedCount = 0
                    // Whole app just left the foreground. Check (off the main thread) whether
                    // the configured login method requires re-auth on next open.
                    scope.launch {
                        val method = PosDatabase.get(activity.applicationContext)
                            .appSettingDao().get("login_method")?.value ?: "password"
                        if (method == "fingerprint" || method == "both") {
                            pendingReauth = true
                        }
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
