package com.grocerypos.v11

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * NEW: App license (Hanna Solutions) — device side. Rules live in LicenseLogic.kt.
 *
 * ONLINE CHECK always uses Hanna's OWN Firebase project (the [DEFAULT] app from
 * google-services.json), never a shop's custom Cloud Sync project — a shop owns its custom
 * project and could otherwise edit its own license. Document: licenses/{deviceCode} with
 *   active : boolean   (false = switched off)
 *   expiry : string "2027-01-31"  (or a Firestore Timestamp)
 *   shop   : string    (optional, just for Hanna's own reference)
 * Firestore rule needed (Hanna's live rules):
 *   match /licenses/{code} { allow get: if request.auth != null; allow list, write: if false; }
 * Until that rule / document exists the online check simply "can't tell" and the cached
 * answer, an offline key or the trial keeps the app running.
 */
object LicenseManager {
    private const val PREFS = "license_prefs"

    /** Offline-key secret: injected at build time from the LICENSE_KEY_SECRET GitHub secret. */
    private val secret: String get() = BuildConfig.LICENSE_KEY_SECRET

    fun offlineKeysEnabled() = secret.isNotBlank()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @SuppressLint("HardwareIds")
    fun deviceCode(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("device_code", null)?.let { return it }
        val id = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) } catch (e: Exception) { null }
        val code = LicenseLogic.deviceCodeFrom(id ?: java.util.UUID.randomUUID().toString())
        p.edit().putString("device_code", code).apply()
        return code
    }

    fun cache(ctx: Context): LicenseCache {
        val p = prefs(ctx)
        return LicenseCache(
            onlineKnown = p.getBoolean("online_known", false),
            onlineActive = p.getBoolean("online_active", false),
            onlineExpiry = p.getLong("online_expiry", 0L),
            keyExpiry = p.getLong("key_expiry", 0L),
            trialStart = p.getLong("trial_start", 0L),
            maxSeenTime = p.getLong("max_seen", 0L),
            clockSuspect = p.getBoolean("clock_suspect", false)
        )
    }

    /** Current state; also starts the trial clock and tracks the newest time seen. */
    fun state(ctx: Context, now: Long = System.currentTimeMillis()): LicenseState {
        val p = prefs(ctx)
        val e = p.edit()
        if (p.getLong("trial_start", 0L) == 0L) e.putLong("trial_start", now)
        val maxSeen = p.getLong("max_seen", 0L)
        if (maxSeen > 0 && now + LicenseLogic.CLOCK_SLACK < maxSeen) e.putBoolean("clock_suspect", true)
        if (now > maxSeen) e.putLong("max_seen", now)
        e.apply()
        return LicenseLogic.evaluate(cache(ctx), now)
    }

    fun lastOnlineCheck(ctx: Context): Long = prefs(ctx).getLong("last_online_ok", 0L)
    fun shopName(ctx: Context): String = prefs(ctx).getString("shop", "") ?: ""

    /** Offline key entry. Returns the new state, or null when the key is wrong for this device. */
    fun applyKey(ctx: Context, key: String): LicenseState? {
        val exp = LicenseLogic.verifyKey(secret, deviceCode(ctx), key) ?: return null
        val p = prefs(ctx)
        // Keep the later of an earlier key and this one.
        if (exp > p.getLong("key_expiry", 0L)) p.edit().putLong("key_expiry", exp).putString("key", key.trim().uppercase()).apply()
        return state(ctx)
    }

    enum class Online { OK, NOT_FOUND, NO_ACCESS, OFFLINE }

    private fun hannaApp(ctx: Context): FirebaseApp? = try {
        FirebaseApp.getApps(ctx).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME } ?: FirebaseApp.initializeApp(ctx)
    } catch (e: Exception) { null }

    /**
     * Ask Hanna's Firebase for licenses/{deviceCode}. Only a real answer changes the cache:
     * OK (active / expiry stored), NOT_FOUND (nothing changes — trial / key / old cache stay).
     */
    suspend fun refreshOnline(ctx: Context): Online {
        val app = hannaApp(ctx.applicationContext) ?: return Online.OFFLINE
        return try {
            withTimeout(15_000) {
                val auth = FirebaseAuth.getInstance(app)
                if (auth.currentUser == null) auth.signInAnonymously().await()
                val doc = FirebaseFirestore.getInstance(app).collection("licenses").document(deviceCode(ctx)).get().await()
                if (!doc.exists()) return@withTimeout Online.NOT_FOUND
                val active = doc.getBoolean("active") ?: false
                val expiry = when (val raw = doc.get("expiry")) {
                    is com.google.firebase.Timestamp -> {
                        val c = java.util.Calendar.getInstance().apply { time = raw.toDate() }
                        LicenseLogic.endOfDay(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
                    }
                    is String -> LicenseLogic.parseExpiry(raw) ?: 0L
                    is Number -> raw.toLong()
                    else -> 0L
                }
                val now = System.currentTimeMillis()
                prefs(ctx).edit()
                    .putBoolean("online_known", true)
                    .putBoolean("online_active", active)
                    .putLong("online_expiry", expiry)
                    .putString("shop", doc.getString("shop") ?: "")
                    .putLong("last_online_ok", now)
                    // A successful server answer clears a clock alarm and re-bases the clock.
                    .putBoolean("clock_suspect", false)
                    .putLong("max_seen", now)
                    .apply()
                Online.OK
            }
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) Online.NO_ACCESS else Online.OFFLINE
        } catch (e: Exception) {
            Online.OFFLINE
        }
    }

    /** Background refresh at most every 6 hours (called on app start). */
    suspend fun refreshIfDue(ctx: Context) {
        if (System.currentTimeMillis() - lastOnlineCheck(ctx) < 6 * 60 * 60 * 1000L) return
        try { refreshOnline(ctx) } catch (e: Exception) { }
    }
}
