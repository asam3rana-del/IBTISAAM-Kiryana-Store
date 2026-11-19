package com.grocerypos.v11.parity

// NEW (Flutter parity): Pending Cost backfill (batch 8, ported from Flutter cost_backfill_test).
// Pure-logic checks run on the JVM (no Android / org.json calls). Each FAIL line names the case.
import com.grocerypos.v11.*

import org.junit.Assert.fail
import org.junit.Test

class ParityBatch8PendingCostTest {
    private var f8 = 0
    fun c8(n: String, ok: Boolean) { if (!ok) { f8++; println("FAIL: $n") } else println("ok:   $n") }
    fun nr(a: Double, b: Double) = Math.abs(a - b) < 1e-9
    fun line(id: Long, cost: Double = 100.0, pq: Double = 10.0, pe: Double = 100.0, log: String = "") = PendingLine(id, "INV$id", cost, pq, pe, log)
    fun BackfillResult.asLine() = PendingLine(id, invoice, cost, pendingQty, pendingEst, backfillLog)

    @Test
    fun allCases() {
        val M = CostBackfillMath
        var p = M.pendingForSale(10.0, 50.0, 10.0, 100.0); c8("full stock => none", p.first == 0.0 && p.second == 0.0)
        p = M.pendingForSale(10.0, 4.0, 10.0, 100.0); c8("short stock part", nr(p.first, 6.0) && nr(p.second, 60.0))
        p = M.pendingForSale(10.0, -3.0, 10.0, 100.0); c8("minus stock all", p.first == 10.0 && p.second == 100.0)
        p = M.pendingForSale(10.0, 50.0, 0.0, 0.0); c8("cost 0 all", p.first == 10.0 && p.second == 0.0)

        var r = M.planBackfill(listOf(line(1, cost = 118.0, pe = 118.0)), "B1", 10.0, 10.0)
        c8("full fill", r.size == 1 && nr(r[0].cost, 100.0) && r[0].pendingQty == 0.0 && r[0].pendingEst == 0.0)
        r = M.planBackfill(listOf(line(1)), "B1", 4.0, 12.0)
        c8("half fill", nr(r[0].cost, 108.0) && nr(r[0].pendingQty, 6.0) && nr(r[0].pendingEst, 60.0))
        r = M.planBackfill(listOf(line(1, pq = 3.0, pe = 30.0, cost = 30.0), line(2)), "B1", 5.0, 10.0)
        c8("oldest first", r.map { it.id } == listOf(1L, 2L) && r[0].pendingQty == 0.0 && nr(r[1].pendingQty, 8.0))
        r = M.planBackfill(listOf(line(1, pq = 2.0, pe = 20.0, cost = 20.0)), "B1", 50.0, 10.0)
        c8("more than pending", r.single().pendingQty == 0.0 && nr(r.single().cost, 20.0))
        val a = M.planBackfill(listOf(line(1)), "B1", 4.0, 12.0).single()
        val back = M.planReverse(listOf(a.asLine()), "B1").single()
        c8("reverse exact", nr(back.cost, 100.0) && nr(back.pendingQty, 10.0) && nr(back.pendingEst, 100.0) && back.backfillLog == "")
        val b = M.planBackfill(listOf(a.asLine()), "B2", 3.0, 20.0).single()
        val back2 = M.planReverse(listOf(b.asLine()), "B1").single()
        c8("reverse only that bill", nr(back2.pendingQty, 7.0) && M.decodeLog(back2.backfillLog).single().b == "B2")
        c8("nothing pending", M.planBackfill(listOf(line(1, pq = 0.0, pe = 0.0)), "B1", 5.0, 10.0).isEmpty())
        val s = M.scaled(3.0, 30.0, "", 2.0); c8("scaled", s.first == 6.0 && s.second == 60.0)
        // Flutter-written log is readable; needle matches Flutter's LIKE
        val flutterLog = "[{\"b\":\"P-0001\",\"q\":4,\"r\":12.5,\"e\":40.0}]"
        val d = M.decodeLog(flutterLog).single(); c8("decode flutter log", d.b == "P-0001" && d.q == 4.0 && d.r == 12.5 && d.e == 40.0)
        c8("needle", M.likeNeedle("P-0001") == "%\"P-0001\"%" && flutterLog.contains("\"P-0001\""))
        c8("encode no html escape", M.encodeLog(listOf(BackfillEntry("A=1", 1.0, 2.0, 3.0))).contains("\"A=1\""))
            if (f8 != 0) fail("$f8 check(s) failed — see the FAIL lines in the test output")
    }
}
