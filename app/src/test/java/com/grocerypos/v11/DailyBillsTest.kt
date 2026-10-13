package com.grocerypos.v11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the pure Daily Bills maths (buildDailyParty / dailyBillText). */
class DailyBillsTest {
    private val d0 = 1_000_000L          // day start
    private val d1 = d0 + 86_400_000L    // day end

    @Test fun previousAndClosingBalance() {
        val bills = listOf(
            DailyBillRow("A", 500.0, 0.0, d0 - 10_000),       // yesterday: +500
            DailyBillRow("B", 300.0, 100.0, d0 + 1_000)       // today: +200
        )
        val pays = listOf(DailyPayRow("P1", "", 50.0, d0 + 2_000)) // today general payment: -50
        val p = buildDailyParty(1, "Ali", 100.0, bills, pays, d0, d1)!!
        assertEquals(600.0, p.previousBalance, 0.001)
        assertEquals(750.0, p.closingBalance, 0.001)
        assertEquals(300.0, p.salesTotal, 0.001)
        assertEquals(150.0, p.receivedTotal, 0.001)
    }

    @Test fun paymentLinkedToOwnBillIsNotCountedTwice() {
        val bills = listOf(DailyBillRow("B", 300.0, 100.0, d0 + 1_000))
        val pays = listOf(DailyPayRow("B", "", 100.0, d0 + 1_000), DailyPayRow("X", "B", 20.0, d0 + 1_500))
        val p = buildDailyParty(1, "Ali", 0.0, bills, pays, d0, d1)!!
        assertEquals(200.0, p.closingBalance, 0.001)
        assertTrue(p.payments.isEmpty())
    }

    @Test fun returnedBillsAreIgnoredAndEmptyDayIsNull() {
        val bills = listOf(DailyBillRow("R", 400.0, 0.0, d0 + 1_000, "returned"))
        assertNull(buildDailyParty(1, "Ali", 0.0, bills, emptyList(), d0, d1))
    }

    @Test fun rentBillGetsADailyRentLine() {
        val bills = listOf(DailyBillRow("RENT-20261012-1", 150.0, 0.0, d0 + 1_000))
        val p = buildDailyParty(1, "Shop", 0.0, bills, emptyList(), d0, d1)!!
        assertEquals("Daily Rent", p.bills[0].items[0].product)
    }

    @Test fun textContainsClosingBalance() {
        val bills = listOf(DailyBillRow("B", 300.0, 0.0, d0 + 1_000))
        val p = buildDailyParty(1, "Ali", 0.0, bills, emptyList(), d0, d1)!!
        assertNotNull(p)
        assertTrue(dailyBillText(p, d0, "My Shop").contains("Closing balance: Rs 300"))
    }
}
