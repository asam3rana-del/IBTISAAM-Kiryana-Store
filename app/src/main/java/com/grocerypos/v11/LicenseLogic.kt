package com.grocerypos.v11

import java.util.Calendar
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// =====================================================================================
// NEW: App license (Hanna Solutions) — pure rules, no Android (unit-tested).
//
// A device may run the app when ANY of these is true:
//   * ONLINE  — Hanna's Firebase project has licenses/{deviceCode} with active=true and an
//               expiry date not passed. The last answer is cached, so the app keeps working
//               offline until that cached expiry.
//   * OFFLINE — a key "IBT-YYYYMMDD-XXXXXXXXXX" for this device's code, made by Hanna's key
//               generator (HMAC-SHA256 with a secret that only lives in GitHub Secrets / the
//               generator, never in the public repo). Valid until its date.
//   * TRIAL   — no license known yet: TRIAL_DAYS from the first start (so existing shops keep
//               working after the update until Hanna adds their license).
// Hard stops: licenses/{deviceCode} says active=false (switched off by Hanna — wins over a
// key), or the phone clock was moved back more than a day (until an online check succeeds).
// An expiry date is valid THROUGH that whole day.
// =====================================================================================

enum class LicenseStatus { ACTIVE, TRIAL, EXPIRED, DISABLED, CLOCK }

data class LicenseState(
    val status: LicenseStatus,
    /** End of the last valid day (ms), 0 when none. */
    val validUntil: Long,
    /** Whole days left including today (ACTIVE / TRIAL), else 0. */
    val daysLeft: Int,
    /** "online" / "key" / "trial" / "" — what is keeping the app open. */
    val source: String
) {
    val canUse: Boolean get() = status == LicenseStatus.ACTIVE || status == LicenseStatus.TRIAL
}

/** Everything the device remembers (SharedPreferences in LicenseManager). */
data class LicenseCache(
    val onlineKnown: Boolean = false,      // an online answer was received at least once
    val onlineActive: Boolean = false,
    val onlineExpiry: Long = 0L,           // end of day, ms
    val keyExpiry: Long = 0L,              // end of day of a verified offline key, ms
    val trialStart: Long = 0L,
    val maxSeenTime: Long = 0L,
    val clockSuspect: Boolean = false
)

object LicenseLogic {
    const val TRIAL_DAYS = 30
    const val WARN_DAYS = 7
    private const val DAY = 24L * 60 * 60 * 1000
    /** Clock moved back by more than this => suspicious. */
    const val CLOCK_SLACK = DAY

    /** End (23:59:59.999) of the local day [y]-[m]-[d]. */
    fun endOfDay(y: Int, m: Int, d: Int): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, 0, 0, 0); add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis - 1

    /** "2027-01-31", "31-01-2027", "31/01/2027", "20270131" → end of that day; null if not a real date. */
    fun parseExpiry(raw: String?): Long? {
        val s = raw?.trim() ?: return null
        val (y, m, d) = when {
            Regex("""\d{4}-\d{1,2}-\d{1,2}""").matches(s) -> s.split("-").map { it.toInt() }.let { Triple(it[0], it[1], it[2]) }
            Regex("""\d{1,2}[-/]\d{1,2}[-/]\d{4}""").matches(s) -> s.split('-', '/').map { it.toInt() }.let { Triple(it[2], it[1], it[0]) }
            Regex("""\d{8}""").matches(s) -> Triple(s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt())
            else -> return null
        }
        if (y < 2000 || y > 2200 || m !in 1..12 || d !in 1..31) return null
        val c = Calendar.getInstance().apply { isLenient = false; clear(); set(y, m - 1, d) }
        return try { c.timeInMillis; endOfDay(y, m, d) } catch (e: Exception) { null }
    }

    fun ymd(endOfDayMillis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = endOfDayMillis }
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Device code shown to the shop: 10 upper-case hex chars, grouped "ABCDE-12345". */
    fun deviceCodeFrom(androidId: String?): String {
        val clean = (androidId ?: "").lowercase(Locale.US).filter { it in '0'..'9' || it in 'a'..'f' }
        val base = if (clean.length >= 10) clean else sha256Hex("ibt-device:" + (androidId ?: "unknown"))
        val h = sha256Hex("ibt-device:$base").substring(0, 10).uppercase(Locale.US)
        return h.substring(0, 5) + "-" + h.substring(5)
    }

    fun normalizeCode(code: String) = code.uppercase(Locale.US).filter { it in '0'..'9' || it in 'A'..'Z' }

    private fun sha256Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun hmac(secret: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Offline key for [deviceCode] valid through [yyyymmdd]. Same algorithm as the HTML generator. */
    fun keyFor(secret: String, deviceCode: String, yyyymmdd: String): String =
        "IBT-$yyyymmdd-" + hmac(secret, normalizeCode(deviceCode) + "|" + yyyymmdd).substring(0, 10).uppercase(Locale.US)

    /** Returns the key's expiry (end of day) when it is genuine for this device, else null. */
    fun verifyKey(secret: String, deviceCode: String, key: String): Long? {
        if (secret.isBlank()) return null
        val k = key.trim().uppercase(Locale.US).replace(" ", "")
        val m = Regex("""IBT-?(\d{8})-?([0-9A-F]{10})""").matchEntire(k) ?: return null
        val date = m.groupValues[1]
        val expiry = parseExpiry(date) ?: return null
        val expected = keyFor(secret, deviceCode, date)
        val given = "IBT-$date-" + m.groupValues[2]
        // constant-time compare
        if (expected.length != given.length) return null
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor given[i].code)
        return if (diff == 0) expiry else null
    }

    private fun daysLeft(until: Long, now: Long): Int {
        if (until < now) return 0
        val startToday = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        return ((until - startToday) / DAY).toInt() + 1
    }

    fun evaluate(c: LicenseCache, now: Long): LicenseState {
        if (c.clockSuspect || (c.maxSeenTime > 0 && now + CLOCK_SLACK < c.maxSeenTime))
            return LicenseState(LicenseStatus.CLOCK, 0, 0, "")
        if (c.onlineKnown && !c.onlineActive) return LicenseState(LicenseStatus.DISABLED, 0, 0, "online")
        val online = if (c.onlineKnown && c.onlineActive) c.onlineExpiry else 0L
        val best = maxOf(online, c.keyExpiry)
        if (best > 0) {
            val src = if (online >= c.keyExpiry) "online" else "key"
            return if (now <= best) LicenseState(LicenseStatus.ACTIVE, best, daysLeft(best, now), src)
            else LicenseState(LicenseStatus.EXPIRED, best, 0, src)
        }
        // No license known at all → trial.
        val start = if (c.trialStart > 0) c.trialStart else now
        val s = Calendar.getInstance().apply { timeInMillis = start }
        val trialEnd = endOfDay(s.get(Calendar.YEAR), s.get(Calendar.MONTH) + 1, s.get(Calendar.DAY_OF_MONTH)) + (TRIAL_DAYS - 1) * DAY
        return if (now <= trialEnd) LicenseState(LicenseStatus.TRIAL, trialEnd, daysLeft(trialEnd, now), "trial")
        else LicenseState(LicenseStatus.EXPIRED, trialEnd, 0, "trial")
    }

    /** Should the app warn ("license khatam hone wala hai")? */
    fun shouldWarn(s: LicenseState) = s.canUse && s.daysLeft in 1..WARN_DAYS
}
