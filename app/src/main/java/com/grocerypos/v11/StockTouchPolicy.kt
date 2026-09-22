package com.grocerypos.v11.domain

import com.grocerypos.v11.PurchaseItem
import com.grocerypos.v11.SaleItem
import com.grocerypos.v11.data.PurchaseLine

/**
 * Single source of truth for the "did this edit actually touch any item's
 * barcode/qty/unit/rate?" check that both [com.grocerypos.v11.RoomSaleRepository]
 * and [com.grocerypos.v11.RoomPurchaseRepository] use to decide whether an
 * edit needs to reverse+reapply stock (and, for purchases, cost) at all.
 *
 * FIX (regression guard): this used to be two near-identical private
 * `itemsUnchanged()` functions, one copy-pasted into each repository. Pure
 * logic living only inside a class that needs a Room [android.content.Context]
 * to construct meant it could never be exercised by a plain JVM unit test —
 * a future edit to either copy (e.g. "fix" the rounding, add a field to
 * compare) could silently break the "unchanged items never touch stock"
 * guarantee and no test would catch it. Pulling it out here, with no Android
 * dependency, lets [StockTouchPolicyTest] cover both call sites directly.
 */
object StockTouchPolicy {

    // Same tolerance both original copies used: values are compared after
    // rounding to 6 decimal places, so float noise well below any real qty/rate
    // never counts as "changed".
    private fun key(barcode: String, qty: Double, unit: String, rate: Double) =
        "$barcode|${"%.6f".format(qty)}|$unit|${"%.6f".format(rate)}"

    /** True when [lines] (the edited Sale screen state) describe exactly the
     * same set of barcode/qty/unit/rate combinations as [originalItems] (what
     * was actually saved before) — order does not matter. */
    fun saleItemsUnchanged(lines: List<SaleLine>, originalItems: List<SaleItem>): Boolean {
        if (lines.size != originalItems.size) return false
        val lineKeys = lines.map { key(it.barcode, it.qty, it.unit, it.unitPrice) }.sorted()
        val itemKeys = originalItems.map { key(it.barcode, it.qty, it.unit, it.unitPrice) }.sorted()
        return lineKeys == itemKeys
    }

    /** Same comparison as [saleItemsUnchanged], for the Purchase screen. A
     * null [PurchaseLine.barcode] is treated as "" — matching how
     * RoomPurchaseRepository.savePurchase already persists it (`line.barcode
     * ?: ""`) when it builds the [PurchaseItem] row. */
    fun purchaseItemsUnchanged(lines: List<PurchaseLine>, originalItems: List<PurchaseItem>): Boolean {
        if (lines.size != originalItems.size) return false
        val lineKeys = lines.map { key(it.barcode ?: "", it.qty, it.unit, it.rate) }.sorted()
        val itemKeys = originalItems.map { key(it.barcode, it.qty, it.unit, it.unitCost) }.sorted()
        return lineKeys == itemKeys
    }

    // FIX (edit-blocked-by-UNRELATED-line bug): purchaseItemsUnchanged() above is
    // whole-bill — editing even ONE line (leaving every other line untouched) makes
    // it report "changed", which used to make RoomPurchaseRepository reverse+reapply
    // EVERY original line, not just the one actually edited. If a later sale had
    // since eaten into some OTHER, untouched line's stock, reversing that untouched
    // line would go negative and reverseStockAndCostForItems() correctly refused the
    // whole edit ("... ka stock is purchase ke baad already kam ho chuka hai...") —
    // even though the line actually being edited had nothing to do with that
    // product. This does a multiset match (same barcode|qty|unit|rate key as
    // itemsUnchanged) between [originalItems] and [lines]: every exact match is a
    // line the user left alone and is paired off (skipped entirely, both sides);
    // whatever's left over on each side is what actually changed — the leftover
    // original items need their stock/cost reversed, the leftover lines need it
    // reapplied. A fully-unchanged edit (e.g. only Paid Amount changed) naturally
    // returns two empty lists here, same as skipStockTouch used to short-circuit.
    fun purchaseChangedLines(
        lines: List<PurchaseLine>,
        originalItems: List<PurchaseItem>
    ): Pair<List<PurchaseItem>, List<PurchaseLine>> {
        val remainingOriginal = originalItems.toMutableList()
        val linesNeedingReapply = mutableListOf<PurchaseLine>()
        for (line in lines) {
            val lineKey = key(line.barcode ?: "", line.qty, line.unit, line.rate)
            val matchIndex = remainingOriginal.indexOfFirst {
                key(it.barcode, it.qty, it.unit, it.unitCost) == lineKey
            }
            if (matchIndex >= 0) {
                remainingOriginal.removeAt(matchIndex)
            } else {
                linesNeedingReapply.add(line)
            }
        }
        return remainingOriginal to linesNeedingReapply
    }
}
