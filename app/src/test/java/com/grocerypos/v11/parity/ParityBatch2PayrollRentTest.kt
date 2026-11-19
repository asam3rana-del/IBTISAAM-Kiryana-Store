package com.grocerypos.v11.parity

// NEW (Flutter parity): Workers payroll, shop rent, utility bill logic (batch 2).
// Pure-logic checks run on the JVM (no Android / org.json calls). Each FAIL line names the case.
import com.grocerypos.v11.*
import java.util.Calendar
import org.junit.Assert.fail
import org.junit.Test

class ParityBatch2PayrollRentTest {
    fun cal2(y: Int, m: Int, d: Int, h: Int = 0): Long = Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis
    private var f2 = 0
    fun ck(name: String, ok: Boolean) { if (!ok) { f2++; println("FAIL: $name") } else println("ok:   $name") }

    @Test
    fun allCases() {
        // Ym
        ck("ym of", Ym.of(cal2(2026, 3, 9)) == "2026-03")
        ck("add", Ym.add("2026-11", 2) == "2027-01" && Ym.add("2026-01", -1) == "2025-12")
        ck("between", Ym.between("2025-10", "2026-10") == 12)
        ck("range", Ym.range("2026-11", "2027-02") == listOf("2026-11", "2026-12", "2027-01", "2027-02") && Ym.range("2026-11", "2026-10").isEmpty())
        ck("dateOf clamps", Ym.dateOf("2026-02", 31) == cal2(2026, 2, 28) && Ym.dateOf("2028-02", 31) == cal2(2028, 2, 29) && Ym.dateOf("2026-10", 0) == cal2(2026, 10, 1))
        ck("dayKey", Ym.dayKey(cal2(2026, 10, 5)) == "2026-10-05" && Ym.daysIn(2026, 2) == 28 && Ym.daysIn(2028, 2) == 29)
        // Rent
        val terms = mapOf("2025-10" to 15000.0, "2026-10" to 16500.0)
        ck("rentForMonth", RentMath.rentForMonth(terms, "2025-10") == 15000.0 && RentMath.rentForMonth(terms, "2026-09") == 15000.0 &&
            RentMath.rentForMonth(terms, "2026-10") == 16500.0 && RentMath.rentForMonth(terms, "2020-01") == 15000.0 && RentMath.rentForMonth(emptyMap(), "2026-10") == 0.0)
        ck("review", !RentMath.needsReview("2025-10", "2026-09") && RentMath.needsReview("2025-10", "2026-10"))
        ck("percent", RentMath.increaseByPercent(15000.0, 10.0) == 16500.0 && RentMath.increaseByPercent(12345.0, 5.0) == 12962.0)
        val dues = listOf(MonthDue("2026-09", 5000.0), MonthDue("2026-10", 15000.0))
        val (a, ea) = RentMath.allocateFifo(dues, 7000.0)
        ck("fifo", a == listOf(Allocation("2026-09", 5000.0), Allocation("2026-10", 2000.0)) && ea == 0.0)
        val (b, eb) = RentMath.allocateFifo(dues, 25000.0)
        ck("fifo extra", b.size == 2 && eb == 5000.0)
        val (cc, ec) = RentMath.allocateFifo(emptyList(), 300.0)
        ck("fifo empty", cc.isEmpty() && ec == 300.0)
        val shop = RentedShop(id = 1, shopName = "A", dueDay = 5, startYm = "2026-10", createdAt = 0)
        val led = listOf(RentLedgerEntry(shopId = 1, kind = "charge", ym = "2026-10", amount = 15000.0, createdAt = 0),
            RentLedgerEntry(shopId = 1, kind = "advance", amount = 20000.0, createdAt = 0),
            RentLedgerEntry(shopId = 1, kind = "applied", ym = "2026-10", amount = 5000.0, createdAt = 0))
        val sum = RentMath.summarize(shop, mapOf("2026-10" to 15000.0), led, cal2(2026, 10, 10, 12))
        ck("summary", sum.outstanding == 10000.0 && sum.overdue && sum.advanceBalance == 15000.0 && sum.currentRent == 15000.0)
        // Workers
        val c1 = Payroll.counts(mapOf(1 to "P", 2 to "A", 3 to "H", 4 to "L", 5 to "P"), 2026, 10)
        ck("counts", c1.present == 2.0 && c1.absent == 1.0 && c1.half == 1.0 && c1.paidLeave == 1.0 && c1.unmarked == 26)
        val r1 = Payroll.earned(Payroll.MONTHLY, 31000.0, 2026, 10, Payroll.counts(mapOf(1 to "A", 2 to "A", 3 to "H", 4 to "L"), 2026, 10))
        ck("monthly cut", r1.deduction == 2500.0 && r1.earned == 28500.0 && r1.days == 2.5)
        ck("monthly full", Payroll.earned(Payroll.MONTHLY, 20000.0, 2026, 10, Payroll.counts(emptyMap(), 2026, 10)).earned == 20000.0)
        val allA = Payroll.earned(Payroll.MONTHLY, 20000.0, 2026, 10, Payroll.counts((1..31).associateWith { "A" }, 2026, 10))
        ck("monthly cap", allA.earned == 0.0 && allA.deduction == 20000.0)
        val r2 = Payroll.earned(Payroll.DAILY, 800.0, 2026, 10, Payroll.counts(mapOf(1 to "P", 2 to "P", 3 to "H", 4 to "L", 5 to "A"), 2026, 10))
        ck("daily", r2.days == 3.5 && r2.earned == 2800.0 && r2.deduction == 0.0)
        // Bills
        val s1 = Bills.status(25, cal2(2026, 1, 1), setOf("2026-09"), cal2(2026, 10, 22, 10))
        ck("bill this month", s1.month == "2026-10" && !s1.paid && s1.daysLeft == 3)
        fun at(d: Int) = Bills.status(25, cal2(2026, 1, 1), setOf("2026-09"), cal2(2026, 10, d, 9))
        ck("bill stages", at(24).dueTomorrow && at(25).dueToday && at(27).overdue && at(27).daysLeft == -2)
        ck("bill paid", Bills.status(25, cal2(2026, 1, 1), setOf("2026-09", "2026-10"), cal2(2026, 10, 24, 9)).paid)
        val s2 = Bills.status(5, cal2(2026, 1, 1), setOf("2026-08"), cal2(2026, 10, 2, 9))
        ck("bill prev overdue", s2.month == "2026-09" && s2.overdue && s2.daysLeft == -27)
        val s3 = Bills.status(5, cal2(2026, 10, 20), emptySet(), cal2(2026, 10, 22, 9))
        ck("bill next month", s3.month == "2026-11" && !s3.paid && s3.daysLeft == 14)
        val s4 = Bills.status(28, cal2(2025, 1, 1), setOf("2025-11"), cal2(2026, 1, 3, 9))
        ck("bill year wrap", s4.month == "2025-12" && s4.overdue)
            if (f2 != 0) fail("$f2 check(s) failed — see the FAIL lines in the test output")
    }
}
