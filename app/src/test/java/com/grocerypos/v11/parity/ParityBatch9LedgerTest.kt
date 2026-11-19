package com.grocerypos.v11.parity

// NEW (Flutter parity): Amanat / loan ledger rows (batch 9).
// Pure-logic checks run on the JVM (no Android / org.json calls). Each FAIL line names the case.
import com.grocerypos.v11.*

import org.junit.Assert.fail
import org.junit.Test

class ParityBatch9LedgerTest {
    private var f9 = 0
    fun c9(n: String, ok: Boolean) { if (!ok) { f9++; println("FAIL: $n") } else println("ok:   $n") }

    @Test
    fun allCases() {
        val e = listOf(ReturnEntry(1, 1000.0), ReturnEntry(2, 500.0))
        val full = ReturnLog.backfill("loan", e, 5L)
        c9("complete log => no extra row", ReturnLog.ledgerRows("loan", e, full).size == 2)
        val partial = full.take(1)
        val rows = ReturnLog.ledgerRows("loan", e, partial)
        c9("gap row", rows.size == 2 && rows.last().id == -1 && Math.abs(rows.last().balanceEffect - 500.0) < 1e-9)
        val am = ReturnLog.ledgerRows("amanat", e, emptyList())
        c9("amanat gap sign", am.single().id == -1 && Math.abs(am.single().balanceEffect - (-1500.0)) < 1e-9)
            if (f9 != 0) fail("$f9 check(s) failed — see the FAIL lines in the test output")
    }
}
