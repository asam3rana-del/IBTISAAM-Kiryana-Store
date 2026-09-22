package com.grocerypos.v11.domain

import com.grocerypos.v11.PurchaseItem
import com.grocerypos.v11.SaleItem
import com.grocerypos.v11.data.PurchaseLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for [StockTouchPolicy] — the "did this edit actually
 * touch any item?" check both RoomSaleRepository and RoomPurchaseRepository
 * use to decide whether an edit needs to reverse+reapply stock/cost at all.
 *
 * This is exactly the itemsUnchanged() bug class described in
 * RoomSaleRepository/RoomPurchaseRepository's comments: an edit that only
 * changes something unrelated (customer name, Paid Amount, date) must NOT
 * touch stock, while ANY change to a line's barcode/qty/unit/rate MUST.
 * These tests exist so a future change to the comparison (rounding,
 * ordering, a forgotten field) gets caught here instead of surfacing as a
 * false "Stock badal gaya hai" or a silent double-decrement in production.
 */
class StockTouchPolicyTest {

    // ---------------------------------------------------------------------
    // Sale side
    // ---------------------------------------------------------------------

    private fun saleLine(
        barcode: String = "B1",
        qty: Double = 2.0,
        unit: String = "Piece",
        unitPrice: Double = 50.0
    ) = SaleLine(
        barcode = barcode,
        itemName = "Item $barcode",
        qty = qty,
        unit = unit,
        unitPrice = unitPrice,
        cost = 0.0,
        amount = qty * unitPrice,
        mainUnit = "Piece",
        secondaryUnit = "",
        secondaryUnitQty = 0.0
    )

    private fun saleItem(
        barcode: String = "B1",
        qty: Double = 2.0,
        unit: String = "Piece",
        unitPrice: Double = 50.0
    ) = SaleItem(
        invoice = "INV1",
        barcode = barcode,
        product = "Item $barcode",
        qty = qty,
        unit = unit,
        unitPrice = unitPrice,
        cost = 0.0,
        amount = qty * unitPrice
    )

    @Test
    fun `identical single sale line is unchanged`() {
        assertTrue(StockTouchPolicy.saleItemsUnchanged(listOf(saleLine()), listOf(saleItem())))
    }

    @Test
    fun `sale lines in a different order are still unchanged`() {
        val lines = listOf(saleLine(barcode = "B2"), saleLine(barcode = "B1"))
        val items = listOf(saleItem(barcode = "B1"), saleItem(barcode = "B2"))
        assertTrue(StockTouchPolicy.saleItemsUnchanged(lines, items))
    }

    @Test
    fun `different qty on a sale line counts as changed`() {
        val lines = listOf(saleLine(qty = 3.0))
        val items = listOf(saleItem(qty = 2.0))
        assertFalse(StockTouchPolicy.saleItemsUnchanged(lines, items))
    }

    @Test
    fun `different unit on a sale line counts as changed`() {
        val lines = listOf(saleLine(unit = "Dozen"))
        val items = listOf(saleItem(unit = "Piece"))
        assertFalse(StockTouchPolicy.saleItemsUnchanged(lines, items))
    }

    @Test
    fun `different rate on a sale line counts as changed`() {
        val lines = listOf(saleLine(unitPrice = 55.0))
        val items = listOf(saleItem(unitPrice = 50.0))
        assertFalse(StockTouchPolicy.saleItemsUnchanged(lines, items))
    }

    @Test
    fun `different barcode counts as changed even with same qty-unit-rate`() {
        val lines = listOf(saleLine(barcode = "B9"))
        val items = listOf(saleItem(barcode = "B1"))
        assertFalse(StockTouchPolicy.saleItemsUnchanged(lines, items))
    }

    @Test
    fun `adding or removing a sale line counts as changed`() {
        val lines = listOf(saleLine(barcode = "B1"), saleLine(barcode = "B2"))
        val items = listOf(saleItem(barcode = "B1"))
        assertFalse(StockTouchPolicy.saleItemsUnchanged(lines, items))
    }

    @Test
    fun `sub-tolerance float noise on qty and rate still counts as unchanged`() {
        // Both round to the same value at 6 decimal places — must not trip a
        // stock reverse+reapply over floating point noise.
        val lines = listOf(saleLine(qty = 2.0000001, unitPrice = 50.0000004))
        val items = listOf(saleItem(qty = 2.0, unitPrice = 50.0))
        assertTrue(StockTouchPolicy.saleItemsUnchanged(lines, items))
    }

    // ---------------------------------------------------------------------
    // Purchase side
    // ---------------------------------------------------------------------

    private fun purchaseLine(
        barcode: String? = "B1",
        qty: Double = 2.0,
        unit: String = "Piece",
        rate: Double = 40.0
    ) = PurchaseLine(
        itemName = "Item ${barcode ?: ""}",
        barcode = barcode,
        qty = qty,
        unit = unit,
        rate = rate,
        amount = qty * rate,
        mainUnit = "Piece",
        secondaryUnit = "",
        secondaryUnitQty = 0.0
    )

    private fun purchaseItem(
        barcode: String = "B1",
        qty: Double = 2.0,
        unit: String = "Piece",
        unitCost: Double = 40.0
    ) = PurchaseItem(
        billNo = "BILL1",
        barcode = barcode,
        qty = qty,
        unitCost = unitCost,
        amount = qty * unitCost,
        unit = unit
    )

    @Test
    fun `identical single purchase line is unchanged`() {
        assertTrue(StockTouchPolicy.purchaseItemsUnchanged(listOf(purchaseLine()), listOf(purchaseItem())))
    }

    @Test
    fun `different qty on a purchase line counts as changed`() {
        val lines = listOf(purchaseLine(qty = 5.0))
        val items = listOf(purchaseItem(qty = 2.0))
        assertFalse(StockTouchPolicy.purchaseItemsUnchanged(lines, items))
    }

    @Test
    fun `different rate on a purchase line counts as changed`() {
        val lines = listOf(purchaseLine(rate = 45.0))
        val items = listOf(purchaseItem(unitCost = 40.0))
        assertFalse(StockTouchPolicy.purchaseItemsUnchanged(lines, items))
    }

    @Test
    fun `different unit on a purchase line counts as changed`() {
        val lines = listOf(purchaseLine(unit = "Dozen"))
        val items = listOf(purchaseItem(unit = "Piece"))
        assertFalse(StockTouchPolicy.purchaseItemsUnchanged(lines, items))
    }

    @Test
    fun `adding or removing a purchase line counts as changed`() {
        val lines = listOf(purchaseLine(barcode = "B1"), purchaseLine(barcode = "B2"))
        val items = listOf(purchaseItem(barcode = "B1"))
        assertFalse(StockTouchPolicy.purchaseItemsUnchanged(lines, items))
    }

    @Test
    fun `null barcode purchase line is treated as empty string, matching how it is persisted`() {
        val lines = listOf(purchaseLine(barcode = null))
        val items = listOf(purchaseItem(barcode = ""))
        assertTrue(StockTouchPolicy.purchaseItemsUnchanged(lines, items))
    }

    @Test
    fun `purchase lines in a different order are still unchanged`() {
        val lines = listOf(purchaseLine(barcode = "B2"), purchaseLine(barcode = "B1"))
        val items = listOf(purchaseItem(barcode = "B1"), purchaseItem(barcode = "B2"))
        assertTrue(StockTouchPolicy.purchaseItemsUnchanged(lines, items))
    }
}
