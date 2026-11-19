package com.grocerypos.v11.parity

// NEW (Flutter parity): Purchase restore cost math (batch 7).
// Pure-logic checks run on the JVM (no Android / org.json calls). Each FAIL line names the case.
import com.grocerypos.v11.*

import org.junit.Assert.fail
import org.junit.Test

class ParityBatch7BillRecycleTest {
    private var f7 = 0
    fun c7(n: String, ok: Boolean) { if (!ok) { f7++; println("FAIL: $n") } else println("ok:   $n") }
    fun near(a: Double, b: Double) = Math.abs(a - b) < 1e-6
    // Same formula as RoomPurchaseRepository.reverseStockAndCostForItems
    fun reverse(stock: Double, cost: Double, factor: Double, qty: Double, amount: Double): Pair<Double, Double> {
        val per = cost / factor; val newStock = stock - qty
        val valueAfter = (stock * per - amount).coerceAtLeast(0.0)
        val newPer = if (newStock > 0) valueAfter / newStock else 0.0
        return newStock to newPer * factor
    }

    @Test
    fun allCases() {
        // 10 kg @ 100 in stock, a purchase of 5 kg for 600 is deleted then restored
        val (s1, c1) = reverse(15.0, (10 * 100.0 + 600) / 15, 1.0, 5.0, 600.0)
        c7("reverse gives back 10 @ 100", near(s1, 10.0) && near(c1, 100.0))
        val back = PurchaseRestoreMath.costAfterAddBack(s1, c1, 1.0, 5.0, 600.0)
        c7("restore gives original avg", near(back, 1600.0 / 15))
        // carton factor 12: cost per carton
        val (s2, c2) = reverse(24.0, 1200.0, 12.0, 12.0, 1200.0)
        c7("factor reverse", near(s2, 12.0) && near(c2, 1200.0))
        c7("factor restore", near(PurchaseRestoreMath.costAfterAddBack(s2, c2, 12.0, 12.0, 1200.0), 1200.0))
        c7("empty stock restore", near(PurchaseRestoreMath.costAfterAddBack(0.0, 0.0, 1.0, 4.0, 400.0), 100.0))
        c7("zero qty keeps cost", near(PurchaseRestoreMath.costAfterAddBack(0.0, 55.0, 1.0, 0.0, 0.0), 55.0))
            if (f7 != 0) fail("$f7 check(s) failed — see the FAIL lines in the test output")
    }
}
