package com.grocerypos.v11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Tests for the pure Daily Rent date maths in [RentLogic] (no database). */
class RentLogicTest {
    private fun day(y: Int, m: Int, d: Int): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, 0, 0, 0) }.timeInMillis

    @Test fun invoiceHasDayAndCustomer() {
        assertEquals("RENT-20261012-5", RentLogic.rentInvoice(5, day(2026, 10, 12) + 5000))
        assertTrue(RentLogic.isRentInvoice("RENT-20261012-5"))
        assertTrue(!RentLogic.isRentInvoice("INV-1"))
    }

    @Test fun missingDaysAreInclusive() {
        val days = RentLogic.missingRentDays(day(2026, 10, 10), day(2026, 10, 12) + 3_600_000, 7, emptySet())
        assertEquals(3, days.size)
    }

    @Test fun existingAndHolidayBillsAreNotRecreated() {
        val existing = setOf(RentLogic.rentInvoice(7, day(2026, 10, 10)), RentLogic.rentInvoice(7, day(2026, 10, 11)))
        val days = RentLogic.missingRentDays(day(2026, 10, 10), day(2026, 10, 12), 7, existing)
        assertEquals(listOf(day(2026, 10, 12)), days)
    }

    @Test fun futureStartGivesNothing() {
        assertTrue(RentLogic.missingRentDays(day(2026, 10, 20), day(2026, 10, 12), 7, emptySet()).isEmpty())
    }
}
