package com.grocerypos.v11.parity

// NEW (Flutter parity): Repair tools math (batch 3).
// Pure-logic checks run on the JVM (no Android / org.json calls). Each FAIL line names the case.
import com.grocerypos.v11.*

import org.junit.Assert.fail
import org.junit.Test

class ParityBatch3RepairTest {
    private var f3 = 0
    fun c3(n: String, ok: Boolean) { if (!ok) { f3++; println("FAIL: $n") } else println("ok:   $n") }

    @Test
    fun allCases() {
        c3("drift no", !RepairMath.isCostDrifted(9370.0, 9370.0) && !RepairMath.isCostDrifted(9500.0, 9370.0) && !RepairMath.isCostDrifted(9000.0, 9370.0))
        c3("drift yes", RepairMath.isCostDrifted(18432.0, 9370.0) && RepairMath.isCostDrifted(4000.0, 9370.0) && RepairMath.isCostDrifted(0.0, 9370.0))
        c3("drift no last", !RepairMath.isCostDrifted(18432.0, 0.0) && !RepairMath.isCostDrifted(18432.0, Double.NaN))
        c3("suspect", RepairMath.isSuspiciousLineCost(194000.0, 45786.0) && !RepairMath.isSuspiciousLineCost(900.0, 1000.0) && !RepairMath.isSuspiciousLineCost(1000.0, 1000.0) &&
            !RepairMath.isSuspiciousLineCost(1400.0, 1000.0) && !RepairMath.isSuspiciousLineCost(30.0, 10.0) && !RepairMath.isSuspiciousLineCost(0.0, 100.0) && !RepairMath.isSuspiciousLineCost(Double.NaN, 100.0))
        val p = Product(barcode = "c", name = "Capstan", cost = 2360.0, salePrice = 2500.0, unit = "outer", secondaryUnit = "dabbi", secondaryUnitQty = 10.0)
        c3("repair 1 outer", RepairMath.repairedLineCost(p, 1.0, "outer", 10.0) == 2360.0)
        c3("repair 3 dabbi", RepairMath.repairedLineCost(p, 3.0, "dabbi", 1.0) == 708.0)
        c3("repair ladder", RepairMath.repairedLineCost(p, 1.0, "outer", 0.0) == 2360.0)
            if (f3 != 0) fail("$f3 check(s) failed — see the FAIL lines in the test output")
    }
}
