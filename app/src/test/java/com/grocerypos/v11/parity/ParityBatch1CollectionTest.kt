package com.grocerypos.v11.parity

// NEW (Flutter parity): Wasooli autopilot / dues / qist / amanat logic (batch 1).
// Pure-logic checks run on the JVM (no Android / org.json calls). Each FAIL line names the case.
import com.grocerypos.v11.*
import java.util.Calendar
import org.junit.Assert.fail
import org.junit.Test

class ParityBatch1CollectionTest {
    fun cal(y: Int, m: Int, d: Int, h: Int = 0, mi: Int = 0): Long = Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, mi) }.timeInMillis
    private var fails = 0
    fun check(name: String, ok: Boolean) { if (!ok) { fails++; println("FAIL: $name") } else println("ok:   $name") }

    @Test
    fun allCases() {
        val now = cal(2026, 3, 15, 14, 30)
        fun day(d: Int) = cal(2026, 3, d)
        fun at(d: Int, h: Int) = cal(2026, 3, d, h)
        fun fu(createdAt: Long, partyId: Long = 1, method: String = FollowUpMethod.WHATSAPP, outcome: String = FollowUpOutcome.SENT, promise: Long = 0, next: Long = 0) =
            DueFollowUp(id = createdAt, partyId = partyId, partyName = "Ali", method = method, outcome = outcome, promiseDate = promise, nextFollowUp = next, createdAt = createdAt)
        fun party(id: Long = 1, name: String = "Ali", total: Double = 1000.0, dueDate: Long = 0, createdAt: Long = 1) =
            Dues.groupByParty(listOf(DueBill("b$id", id, name, "0300", total, 0.0, dueDate, createdAt))).first()

        var q = Autopilot.build(listOf(party(dueDate = day(1))), emptyMap(), now)
        check("late never touched => TODO", q.todo.size == 1 && q.todo.first().neverTouched && q.todo.first().reason == TouchReason.LATE && q.done.isEmpty())
        q = Autopilot.build(listOf(party(dueDate = day(1))), mapOf(1L to listOf(fu(at(15, 9)))), now)
        check("whatsapp today => DONE", q.todo.isEmpty() && q.done.size == 1 && q.done.first().touchesToday == 1 && q.isClear)
        val p = party(dueDate = day(10))
        val w = Autopilot.build(listOf(p), mapOf(1L to listOf(fu(at(14, 10)))), now)
        check("1 day ago => WAITING 2", w.todo.isEmpty() && w.waiting.size == 1 && w.waiting.first().daysUntilTouch == 2)
        check("3 days ago => TODO", Autopilot.build(listOf(p), mapOf(1L to listOf(fu(at(12, 10)))), now).todo.size == 1)
        check("gap days", Autopilot.gapDays(40, false) == 2 && Autopilot.gapDays(5, false) == 3 && Autopilot.gapDays(40, true) == 1)
        q = Autopilot.build(listOf(party(dueDate = day(1))), mapOf(1L to listOf(fu(at(5, 10), outcome = FollowUpOutcome.DISPUTE))), now)
        check("dispute => waiting", q.todo.isEmpty() && q.waiting.size == 1)
        q = Autopilot.build(listOf(party(1, "Ali", 100.0, day(1)), party(2, "Bilal", 5000.0, day(1))),
            mapOf(2L to listOf(fu(at(8, 10), partyId = 2, outcome = FollowUpOutcome.PROMISED, promise = day(10)))), now)
        check("broken promise first", q.todo.first().party.partyName == "Bilal" && q.todo.first().reason == TouchReason.BROKEN_PROMISE && q.brokenCount == 1)
        check("note not touch", !FollowUps.isTouch(fu(at(15, 9), method = FollowUpMethod.NOTE, outcome = FollowUpOutcome.NOTE)) && FollowUps.isTouch(fu(at(15, 9))))

        // Qist
        fun d(m: Int, dd: Int) = cal(2026, m, dd)
        val p3 = QistPlan(1000.0, 3, d(4, 1), 30, d(3, 20))
        check("qist amounts", p3.amountOf(0) == 333.0 && p3.amountOf(1) == 333.0 && p3.amountOf(2) == 334.0)
        val plan = QistPlan(10000.0, 4, d(4, 1), 30, d(3, 20))
        check("qist dates", plan.dateOf(0) == d(4, 1) && plan.dateOf(1) == d(5, 1) && plan.dateOf(2) == d(5, 31))
        val s0 = Qist.status(plan, 0.0, cal(2026, 3, 25, 12))
        check("before first", s0.dueByToday == 0.0 && s0.overdue == 0.0 && s0.nextIndex == 0 && s0.nextDate == d(4, 1) && !Qist.needsTouch(s0, cal(2026, 3, 25, 12)))
        val s1 = Qist.status(plan, 0.0, cal(2026, 4, 1, 12))
        check("on date", s1.overdue == 0.0 && Qist.needsTouch(s1, cal(2026, 4, 1, 12)))
        val s2 = Qist.status(plan, 3500.0, cal(2026, 5, 10, 12))
        check("partial applied in order", s2.paidCount == 1 && s2.nextIndex == 1 && s2.nextRemaining == 1500.0 && s2.overdue == 1500.0 && s2.remaining == 6500.0 && Qist.needsTouch(s2, cal(2026, 5, 10, 12)))

        // Pay schedule: 13 Mar 2026 is a Friday
        check("parse", PaySchedule.parse("w:5") == Pair(true, 5) && PaySchedule.parse("m:31") == Pair(false, 31) && PaySchedule.parse("w:8") == null && PaySchedule.parse("x") == null)
        check("juma", PaySchedule.isPayDay("w:5", cal(2026, 3, 13, 10)) && !PaySchedule.isPayDay("w:5", cal(2026, 3, 14, 10)))
        check("sunday", PaySchedule.isPayDay("w:7", cal(2026, 3, 15, 10)))
        check("month end short", PaySchedule.isPayDay("m:31", cal(2026, 2, 28, 10)) && !PaySchedule.isPayDay("m:31", cal(2026, 3, 30, 10)))

        // Amanat FIFO
        val ents = listOf(ReturnEntry(1, 500.0, 0, d(5, 1)), ReturnEntry(2, 300.0, 0, d(4, 1)), ReturnEntry(3, 200.0, 0, 0))
        val (rem, settled) = ReturnEntries.settleFifo(ents, 400.0)
        check("fifo earliest return first", settled == 400.0 && rem.size == 2 && rem.first { it.id == 1 }.amount == 400.0 && rem.none { it.id == 2 })
        val (rem2, set2) = ReturnEntries.settleFifo(ents, 5000.0)
        check("fifo cap at total", set2 == 1000.0 && rem2.isEmpty())
        check("signed", ReturnEntries.signed("amanat", 100.0) == -100.0 && ReturnEntries.signed("loan", 100.0) == 100.0)
        val log = ReturnLog.addGiven(emptyList(), "amanat", ents[0], 1L)
        check("log effect", log.first().balanceEffect == -500.0 && ReturnLog.recovered(log, "amanat", 200.0, 2L, "").last().balanceEffect == 200.0)

        // Due renewal
        val renewed = DueRenewal.renewedDueDate(now, day(10), day(3), 0) // term 7 days
        check("renew 7 day term", renewed == DayUtil.plusDays(now, 7))
        check("renew no date", DueRenewal.renewedDueDate(now, 0, day(3)) == 0L)
        check("renew never backwards", DueRenewal.renewedDueDate(now, cal(2026, 4, 30), day(3), 2) == cal(2026, 4, 30))

        // Follow-up state
        val st = FollowUps.state(listOf(fu(at(8, 10), outcome = FollowUpOutcome.PROMISED, promise = day(20))), now)
        check("on promise", st.onPromise && !st.promiseBroken)
        val st2 = FollowUps.state(listOf(fu(at(8, 10), outcome = FollowUpOutcome.PROMISED, promise = day(10)), fu(at(11, 10), outcome = FollowUpOutcome.PAID)), now)
        check("paid resolves promise", st2.promise == null)
        check("whatsapp digits", Dues.whatsAppDigits("0300-1234567") == "923001234567" && Dues.whatsAppDigits("3001234567") == "923001234567")

        // cap by balance
        val bills = listOf(DueBill("a", 1, "A", "", 1000.0, 0.0, 0, 1), DueBill("b", 1, "A", "", 1000.0, 0.0, 0, 2))
        val capped = Dues.capByBalance(bills, mapOf(1L to 1500.0))
        check("cap oldest first", capped.size == 2 && capped.first { it.id == "a" }.due == 500.0 && capped.first { it.id == "b" }.due == 1000.0)
        val withPrev = Dues.addUnbilledBalance(capped, mapOf(1L to Triple("A", "", 2000.0)))
        check("previous balance row", withPrev.any { it.isBalance && it.due == 500.0 })
            if (fails != 0) fail("$fails check(s) failed — see the FAIL lines in the test output")
    }
}
