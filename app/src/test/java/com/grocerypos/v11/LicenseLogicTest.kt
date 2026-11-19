package com.grocerypos.v11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** NEW: App license rules (LicenseLogic). Uses a throw-away test secret, never the real one. */
class LicenseLogicTest {
    private val secret = "test-secret-not-the-real-one"
    private fun at(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis
    private val day = 24L * 60 * 60 * 1000

    @Test fun parseExpiryFormats() {
        val e = LicenseLogic.endOfDay(2027, 1, 31)
        assertEquals(e, LicenseLogic.parseExpiry("2027-01-31"))
        assertEquals(e, LicenseLogic.parseExpiry("31-01-2027"))
        assertEquals(e, LicenseLogic.parseExpiry("31/1/2027"))
        assertEquals(e, LicenseLogic.parseExpiry("20270131"))
        assertNull(LicenseLogic.parseExpiry("2027-02-30"))
        assertNull(LicenseLogic.parseExpiry("kal"))
        assertNull(LicenseLogic.parseExpiry(""))
    }

    @Test fun deviceCodeIsStableAndShaped() {
        val a = LicenseLogic.deviceCodeFrom("9774d56d682e549c")
        assertEquals(a, LicenseLogic.deviceCodeFrom("9774d56d682e549c"))
        assertTrue(a.matches(Regex("[0-9A-F]{5}-[0-9A-F]{5}")))
        assertFalse(a == LicenseLogic.deviceCodeFrom("1234567890abcdef"))
    }

    @Test fun keyRoundTripAndTamper() {
        val code = "ABCDE-12345"
        val key = LicenseLogic.keyFor(secret, code, "20271231")
        assertTrue(key.matches(Regex("IBT-20271231-[0-9A-F]{10}")))
        assertEquals(LicenseLogic.endOfDay(2027, 12, 31), LicenseLogic.verifyKey(secret, code, key))
        assertNotNull(LicenseLogic.verifyKey(secret, "abcde12345", " " + key.lowercase() + " "))
        assertNull(LicenseLogic.verifyKey(secret, "ABCDE-12346", key))            // other device
        assertNull(LicenseLogic.verifyKey(secret, code, key.replace("20271231", "20281231"))) // date changed
        assertNull(LicenseLogic.verifyKey("other-secret", code, key))
        assertNull(LicenseLogic.verifyKey("", code, key))                          // keys off without secret
    }

    @Test fun trialThenExpired() {
        val start = at(2026, 11, 1)
        val c = LicenseCache(trialStart = start)
        val s = LicenseLogic.evaluate(c, at(2026, 11, 10))
        assertEquals(LicenseStatus.TRIAL, s.status); assertTrue(s.canUse)
        assertEquals(LicenseLogic.TRIAL_DAYS - 9, s.daysLeft)
        assertEquals(LicenseStatus.EXPIRED, LicenseLogic.evaluate(c, start + (LicenseLogic.TRIAL_DAYS + 1) * day).status)
    }

    @Test fun onlineActiveWorksTillEndOfExpiryDay() {
        val c = LicenseCache(onlineKnown = true, onlineActive = true, onlineExpiry = LicenseLogic.endOfDay(2026, 12, 31), trialStart = at(2020, 1, 1))
        assertEquals(LicenseStatus.ACTIVE, LicenseLogic.evaluate(c, at(2026, 12, 31, 23)).status)
        assertEquals(1, LicenseLogic.evaluate(c, at(2026, 12, 31, 9)).daysLeft)
        assertEquals(LicenseStatus.EXPIRED, LicenseLogic.evaluate(c, at(2027, 1, 1, 0) + 1000).status)
    }

    @Test fun disabledBeatsKeyAndKeyBeatsExpiredOnline() {
        val key = LicenseLogic.endOfDay(2027, 6, 30)
        assertEquals(LicenseStatus.DISABLED, LicenseLogic.evaluate(LicenseCache(onlineKnown = true, onlineActive = false, keyExpiry = key), at(2026, 12, 1)).status)
        val s = LicenseLogic.evaluate(LicenseCache(onlineKnown = true, onlineActive = true, onlineExpiry = LicenseLogic.endOfDay(2026, 1, 1), keyExpiry = key), at(2026, 12, 1))
        assertEquals(LicenseStatus.ACTIVE, s.status); assertEquals("key", s.source)
    }

    @Test fun clockMovedBackIsCaught() {
        val c = LicenseCache(onlineKnown = true, onlineActive = true, onlineExpiry = LicenseLogic.endOfDay(2027, 1, 1), maxSeenTime = at(2026, 12, 20))
        assertEquals(LicenseStatus.CLOCK, LicenseLogic.evaluate(c, at(2026, 12, 10)).status)
        assertEquals(LicenseStatus.ACTIVE, LicenseLogic.evaluate(c, at(2026, 12, 19, 13)).status) // within 1 day slack
    }

    @Test fun warnInLastWeek() {
        val c = LicenseCache(onlineKnown = true, onlineActive = true, onlineExpiry = LicenseLogic.endOfDay(2026, 12, 31))
        assertTrue(LicenseLogic.shouldWarn(LicenseLogic.evaluate(c, at(2026, 12, 26))))
        assertFalse(LicenseLogic.shouldWarn(LicenseLogic.evaluate(c, at(2026, 12, 1))))
    }
}
