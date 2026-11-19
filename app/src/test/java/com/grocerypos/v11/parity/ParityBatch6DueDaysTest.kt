package com.grocerypos.v11.parity

// NEW (Flutter parity): Default credit period / due date (batch 6).
// Pure-logic checks run on the JVM (no Android / org.json calls). Each FAIL line names the case.
import com.grocerypos.v11.*
import java.util.Calendar
import org.junit.Assert.fail
import org.junit.Test

class ParityBatch6DueDaysTest {
    fun cal6(y: Int, m: Int, d: Int, h: Int = 0): Long = Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis
    private var f6 = 0
    fun c6(n: String, ok: Boolean) { if (!ok) { f6++; println("FAIL: $n") } else println("ok:   $n") }

    @Test
    fun allCases() {
        c6("blank => 7", DueDays.parseShop(null) == 7 && DueDays.parseShop("  ") == 7 && DueDays.parseShop("abc") == 7)
        c6("0 off, neg off", DueDays.parseShop("0") == 0 && DueDays.parseShop("-3") == 0)
        c6("cap", DueDays.parseShop("999") == 365 && DueDays.parseShop(" 15 ") == 15)
        c6("effective", DueDays.effective(10, 7) == 10 && DueDays.effective(0, 7) == 7 && DueDays.effective(0, 0) == 0)
        c6("due date", DueDays.dueDateFor(cal6(2026, 3, 28, 15), 500.0, 0, 7) == cal6(2026, 4, 4))
        c6("customer days", DueDays.dueDateFor(cal6(2026, 3, 1, 9), 1.0, 30, 7) == cal6(2026, 3, 31))
        c6("paid => 0", DueDays.dueDateFor(cal6(2026, 3, 1), 0.0, 30, 7) == 0L)
        c6("off => 0", DueDays.dueDateFor(cal6(2026, 3, 1), 100.0, 0, 0) == 0L)
            if (f6 != 0) fail("$f6 check(s) failed — see the FAIL lines in the test output")
    }
}
