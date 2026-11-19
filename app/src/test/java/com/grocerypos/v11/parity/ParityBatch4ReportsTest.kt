package com.grocerypos.v11.parity

// NEW (Flutter parity): Receivables aging + expiry report (batch 4).
// Pure-logic checks run on the JVM (no Android / org.json calls). Each FAIL line names the case.
import com.grocerypos.v11.*
import java.util.Calendar
import org.junit.Assert.fail
import org.junit.Test

class ParityBatch4ReportsTest {
    fun cal4(y: Int, m: Int, d: Int, h: Int = 0): Long = Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis
    private var fails4 = 0
    fun check4(n: String, ok: Boolean) { if (!ok) { fails4++; println("FAIL: $n") } else println("ok:   $n") }

    @Test
    fun allCases() {
        val now = cal4(2026, 3, 15, 14)
        fun bill(id: String, pid: Long, name: String, due: Double, dueDate: Long) = DueBill(id, pid, name, "", due, 0.0, dueDate, 1)
        val parties = Dues.groupByParty(listOf(
            bill("a1", 1, "Ali", 100.0, cal4(2026, 3, 20)),     // not due
            bill("a2", 1, "Ali", 200.0, cal4(2026, 3, 10)),     // 5 late
            bill("b1", 2, "Bilal", 500.0, cal4(2026, 1, 1)),    // 73 late
            bill("c1", 3, "Chand", 50.0, 0L),                   // no date
            bill("d1", 4, "Dawood", 300.0, cal4(2026, 2, 13)),  // 30 late
            bill("e1", 5, "Ehsan", 80.0, cal4(2026, 2, 12))     // 31 late
        ))
        val s = Aging.build(parties, now)
        check4("total", Math.abs(s.totalDue - 1230.0) < 1e-6)
        check4("not due", s.amountIn(AgingBucket.NOT_DUE) == 100.0)
        check4("1-7", s.amountIn(AgingBucket.LATE_1_7) == 200.0)
        check4("8-30 boundary 30", s.amountIn(AgingBucket.LATE_8_30) == 300.0)
        check4("31-60 boundary 31", s.amountIn(AgingBucket.LATE_31_60) == 80.0)
        check4("60+", s.amountIn(AgingBucket.LATE_60_PLUS) == 500.0)
        check4("no date", s.amountIn(AgingBucket.NO_DATE) == 50.0)
        check4("late amount", s.lateAmount == 1080.0)
        check4("rows sorted by total", s.rows.first().party.partyName == "Bilal")
        val ali = s.rows.first { it.party.partyName == "Ali" }
        check4("ali worst 1-7, maxLate 5", ali.worst == AgingBucket.LATE_1_7 && ali.maxDaysLate == 5)
        check4("parties count", s.partiesIn(AgingBucket.NOT_DUE) == 1 && s.partiesIn(AgingBucket.LATE_60_PLUS) == 1)
        check4("bucket 0 -> not due", Aging.bucketForDays(0) == AgingBucket.NOT_DUE && Aging.bucketForDays(61) == AgingBucket.LATE_60_PLUS)

        check4("parse iso", Expiry.parse("2026-03-20") == cal4(2026, 3, 20))
        check4("parse dmy", Expiry.parse("20/03/2026") == cal4(2026, 3, 20))
        check4("parse my -> last day", Expiry.parse("02/2028") == cal4(2028, 2, 29))
        check4("bad", Expiry.parse("31/02/2026") == null && Expiry.parse("hello") == null && Expiry.parse("") == null)
        val prods = listOf(
            Product(barcode = "1", name = "Milk", stock = 5.0, expiry = "2026-03-10"),
            Product(barcode = "2", name = "Bread", stock = 2.0, expiry = "2026-03-15"),
            Product(barcode = "3", name = "Jam", stock = 1.0, expiry = "2026-04-14"),
            Product(barcode = "4", name = "Rice", stock = 1.0, expiry = "2026-04-15"),
            Product(barcode = "5", name = "Oil", stock = 0.0, expiry = "2026-03-01"),
            Product(barcode = "6", name = "Salt", stock = 9.0, expiry = "")
        )
        val ex = Expiry.build(prods, now)
        check4("expiry list", ex.map { it.product.name } == listOf("Milk", "Bread", "Jam"))
        check4("statuses", ex[0].status == ExpiryStatus.EXPIRED && ex[0].daysLeft == -5 && ex[1].daysLeft == 0 && ex[1].status == ExpiryStatus.SOON && ex[2].daysLeft == 30)
            if (fails4 != 0) fail("$fails4 check(s) failed — see the FAIL lines in the test output")
    }
}
