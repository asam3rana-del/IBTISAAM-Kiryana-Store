package com.grocerypos.v11.parity

// NEW (Flutter parity): Reminder notification texts + timing (batch 5).
// Pure-logic checks run on the JVM (no Android / org.json calls). Each FAIL line names the case.
import com.grocerypos.v11.*
import java.util.Calendar
import org.junit.Assert.fail
import org.junit.Test

class ParityBatch5RemindersTest {
    fun cal5(y: Int, m: Int, d: Int, h: Int = 0, mi: Int = 0): Long = Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, mi) }.timeInMillis
    private var fails5 = 0
    fun check5(n: String, ok: Boolean) { if (!ok) { fails5++; println("FAIL: $n") } else println("ok:   $n") }

    @Test
    fun allCases() {
        val R = ReminderText
        check5("before 10 none", R.dueSlot(cal5(2026, 3, 15, 9, 59), null, null) == ReminderText.Slot.NONE)
        check5("10 morning", R.dueSlot(cal5(2026, 3, 15, 10), null, null) == ReminderText.Slot.MORNING)
        check5("morning done", R.dueSlot(cal5(2026, 3, 15, 13), "2026-03-15", null) == ReminderText.Slot.NONE)
        check5("yesterday stamp => morning", R.dueSlot(cal5(2026, 3, 15, 13), "2026-03-14", null) == ReminderText.Slot.MORNING)
        check5("6pm evening", R.dueSlot(cal5(2026, 3, 15, 18), "2026-03-15", null) == ReminderText.Slot.EVENING)
        check5("evening done", R.dueSlot(cal5(2026, 3, 15, 21), "2026-03-15", "2026-03-15") == ReminderText.Slot.NONE)
        check5("dateKey", R.dateKey(cal5(2026, 1, 5, 23)) == "2026-01-05")

        val now = cal5(2026, 3, 15, 14)
        data class X(val n: String, val d: Long)
        val list = listOf(X("a", cal5(2026, 3, 16)), X("b", cal5(2026, 3, 15, 9)), X("c", cal5(2026, 3, 1)), X("d", 0L))
        val due = R.returnDueNow(list, now) { it.d }
        check5("return due today+past, oldest first", due.map { it.n } == listOf("c", "b"))
        check5("return body", R.returnBody(listOf("c", "b"), 1) == "2 Deposit/Loan return dates are due: c, b (1 overdue). Tap to view.")
        check5("return body more", R.returnBody(listOf("a", "b", "c", "d", "e"), 0) == "5 Deposit/Loan return dates are due: a, b, c +2. Tap to view.")

        fun st(days: Int, paid: Boolean = false) = BillStatus("2026-03", cal5(2026, 3, 15 + days), paid, days)
        check5("stage pre", R.billStage(st(1)) == "pre")
        check5("stage due", R.billStage(st(0)) == "due")
        check5("stage over", R.billStage(st(-3)) == "over")
        check5("stage none", R.billStage(st(5)) == null && R.billStage(st(0, true)) == null)
        check5("stamp", R.billStamp(st(0), "due", now) == "2026-03|due" && R.billStamp(st(-2), "over", now) == "2026-03|over2026-03-15")
        check5("title", R.billTitle("Bijli", "pre") == "Bijli ka bill kal due hai")

        val q = AutopilotQueue.EMPTY
        check5("empty body", R.autopilotBody(q) == "Aaj ke sab reminder ho chuke.")
        check5("collected body", R.autopilotBody(q, 1500.4, 2) == "Aaj ke sab reminder ho chuke • aaj wasool: Rs 1500 (2 payment).")
            if (fails5 != 0) fail("$fails5 check(s) failed — see the FAIL lines in the test output")
    }
}
