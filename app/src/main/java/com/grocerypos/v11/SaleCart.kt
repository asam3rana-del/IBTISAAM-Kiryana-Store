package com.grocerypos.v11.ui

/*
 * Sale screen — shopping-cart subsystem: picking an item, choosing its unit,
 * auto-filling price, adding/removing lines, and keeping the subtotal /
 * discount / total / due amounts in sync. Split out of SaleActivity.kt as
 * part of the "Oversized Activity files" cleanup (see IMPROVEMENT-PLAN.md) —
 * these functions are declared as extension functions on SaleActivity so
 * they still read/write the screen's views and state directly, exactly as
 * before, just from a separate file. No behavior change.
 */

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.grocerypos.v11.*
import com.grocerypos.v11.domain.SaleLine
import com.grocerypos.v11.pricing.DiscountCalculator
import com.grocerypos.v11.ui.components.*

internal fun SaleActivity.defaultUnitIndexFor(product: Product): Int {
    val hasSecondary = product.secondaryUnit.isNotEmpty()
    val hasTertiary = hasSecondary && product.tertiaryUnit.isNotEmpty() && product.tertiaryUnitQty > 0

    if (!hasSecondary) return 0          // 1-tier: only one unit exists
    if (hasTertiary) return 1            // 3-tier: always default to the 2nd unit

    // 2-tier: Beverages keep the 1st (primary) unit; everything else defaults
    // to the 2nd (secondary) unit.
    val isBeverage = product.category.equals(SaleActivity.BEVERAGE_CATEGORY, ignoreCase = true)
    return if (isBeverage) 0 else 1
}

internal fun SaleActivity.onItemPicked(name: String) {
    val product = products.find { it.name.equals(name, ignoreCase = true) } ?: return
    selectedProduct = product
    val unitChoices = mutableListOf(product.unit)
    if (product.secondaryUnit.isNotEmpty()) {
        unitChoices.add(product.secondaryUnit)
        if (product.tertiaryUnit.isNotEmpty() && product.tertiaryUnitQty > 0) {
            unitChoices.add(product.tertiaryUnit)
        }
    }
    val defaultIndex = defaultUnitIndexFor(product)
    unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitChoices)
    unitSpinner.setSelection(defaultIndex)
    buildUnitChips(unitChoices, unitChoices[defaultIndex])
    conversionInfo.text = buildString {
        if (unitChoices.size > 1 && product.secondaryUnitQty > 0) append("1 ${product.unit} = ${product.secondaryUnitQty} ${product.secondaryUnit}")
        if (unitChoices.size > 2 && product.tertiaryUnitQty > 0) { if (isNotEmpty()) append("   •   "); append("1 ${product.secondaryUnit} = ${product.tertiaryUnitQty} ${product.tertiaryUnit}") }
    }
    conversionInfo.visibility = if (conversionInfo.text.isNotEmpty()) View.VISIBLE else View.GONE
    lastMainPrice = 0.0
    refillAutoPrice()
    qty.requestFocus()
    qty.selectAll()
    qty.post {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(qty, InputMethodManager.SHOW_IMPLICIT)
    }
}

internal fun SaleActivity.buildUnitChips(options: List<String>, selected: String) {
    unitToggleRow.removeAllViews()
    if (options.size < 2) { unitToggleRow.visibility = View.GONE; return }
    unitToggleRow.visibility = View.VISIBLE
    options.forEachIndexed { index, unitLabel ->
        val isSelected = unitLabel == selected
        val chip = TextView(this).apply {
            text = unitLabel; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(28, 13, 28, 13)
            setTextColor(if (isSelected) Color.WHITE else Color.parseColor(teal))
            background = if (isSelected) roundedBg(teal, 30) else strokedBg(teal, cardBg, 30)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(if (index == 0) 0 else 8, 0, 0, 0) }
            setOnClickListener { unitSpinner.setSelection(options.indexOf(unitLabel)); buildUnitChips(options, unitLabel) }
        }
        unitToggleRow.addView(chip)
    }
}

// ---- delegated to Database.kt's toPrimaryUnitRate()/fromPrimaryUnitRate() —
// the same central unitLadder() that backs toSmallestUnits()/fromSmallestUnits() —
// instead of a duplicated inline formula (was previously copy-pasted separately in
// PurchaseActivity too — any future fix now only needs to happen in one place). ----
internal fun SaleActivity.toMainUnitPrice(entered: Double): Double {
    val product = selectedProduct ?: return entered
    val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
    return product.toPrimaryUnitRate(entered, chosenUnit)
}

internal fun SaleActivity.fromMainUnitPrice(mainPrice: Double, chosenUnit: String): Double {
    val product = selectedProduct ?: return mainPrice
    return product.fromPrimaryUnitRate(mainPrice, chosenUnit)
}

internal fun SaleActivity.refillAutoPrice() {
    val product = selectedProduct ?: return
    val isWholesale = saleTypeSpinner.selectedItem?.toString() == "Wholesale"
    val basePrice = if (isWholesale) product.wholesalePrice else product.salePrice
    val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit
    val base = if (lastMainPrice > 0) lastMainPrice else basePrice
    val price = fromMainUnitPrice(base, chosenUnit)
    suppressPriceWatcher = true
    unitPrice.setText(if (price > 0) "%.2f".format(price) else "")
    suppressPriceWatcher = false
    updateItemLineTotal()
}

internal fun SaleActivity.updateItemLineTotal() {
    val q = qty.text.toString().toDoubleOrNull() ?: 0.0
    val p = unitPrice.text.toString().toDoubleOrNull() ?: 0.0
    itemLineTotalText.text = "Total Amount: Rs %.0f".format(Math.round(q * p).toDouble())
}

// ================= Add item to bill =================
internal fun SaleActivity.addItem() {
    val n = itemName.text.toString().trim()
    val q = qty.text.toString().toDoubleOrNull() ?: 0.0
    val price = unitPrice.text.toString().toDoubleOrNull() ?: 0.0
    val product = products.find { it.name.equals(n, ignoreCase = true) }

    if (product == null) {
        Toast.makeText(this, "Ye item product list mein nahi hai", Toast.LENGTH_SHORT).show()
        return
    }
    if (q <= 0) {
        Toast.makeText(this, "Quantity theek se likhen", Toast.LENGTH_SHORT).show()
        return
    }

    val chosenUnit = unitSpinner.selectedItem?.toString() ?: product.unit

    val alreadyInCartSmallest = lines.filter { it.barcode == product.barcode }
        .sumOf { product.toSmallestUnits(it.qty, it.unit) }
    val neededSmallest = product.toSmallestUnits(q, chosenUnit)

    // FIX (fraction control): reject a cart-add whose qty doesn't resolve to a
    // whole smallest-unit for non-fractional items (Piece/Dabbi/Bottle etc.) —
    // catches the bad entry here instead of at save time.
    if (!product.isValidSmallestQty(neededSmallest)) {
        Toast.makeText(this, "Qty ($q $chosenUnit) whole ${product.smallestUnitName()} mein convert nahi hoti", Toast.LENGTH_SHORT).show()
        return
    }

    val availableForThisAdd = product.stock - alreadyInCartSmallest

    if (availableForThisAdd < neededSmallest) {
        Toast.makeText(
            this,
            "Stock kam hai (available: ${formatQty(availableForThisAdd.coerceAtLeast(0.0))} ${product.smallestUnitName()})",
            Toast.LENGTH_SHORT
        ).show()
        return
    }

    val amount = q * price
    val smallestQtyForCost = product.toSmallestUnits(q, chosenUnit)
    val factor = product.smallestUnitFactor()
    val costPerSmallest = if (factor > 0) product.cost / factor else product.cost
    val lineCost = smallestQtyForCost * costPerSmallest

    lines.add(
        SaleLine(
            barcode = product.barcode,
            itemName = product.name,
            qty = q,
            unit = chosenUnit,
            unitPrice = price,
            cost = lineCost,
            amount = amount,
            mainUnit = product.unit,
            secondaryUnit = product.secondaryUnit,
            secondaryUnitQty = product.secondaryUnitQty,
            tertiaryUnit = product.tertiaryUnit,
            tertiaryUnitQty = product.tertiaryUnitQty
        )
    )
    renderItemsList()
    updateTotals()

    // FIX (UX): after adding an item, jump the ScrollView back to the very top so
    // the rest of the form (Billed Items / Subtotal / Total / Payment / Save) is
    // visible right away instead of staying wherever the keyboard had scrolled to.
    scrollView.post { scrollView.smoothScrollTo(0, 0) }

    itemName.text.clear(); qty.text.clear(); unitPrice.text.clear()
    selectedProduct = null
    lastMainPrice = 0.0
    unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
    conversionInfo.visibility = View.GONE
    unitToggleRow.visibility = View.GONE
    itemLineTotalText.text = "Total Amount: Rs 0"
    itemName.requestFocus()

    if (editInvoice == null) saveDraft()
    if (lines.isNotEmpty() && paidInput.text.toString().isBlank()) {
<<<<<<< HEAD
        paymentSection.background = strokedBg(amber, amberBg, 18)
=======
        paymentSection.background = strokedBg("#FF9800", "#FFF8E1", 18)
>>>>>>> cc8b3ed1c3be113f6b2a67aab0b9727c246553fa
        paymentSection.postDelayed({ paymentSection.background = strokedBg(border, cardBg, 18) }, 2000)
    }
}

internal fun SaleActivity.renderItemsList() {
    itemsContainer.removeAllViews()
    lines.forEachIndexed { index, line ->
        itemsContainer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            background = strokedBg(border, cardBg, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
            applyElevation(this, 2f)

            val top = LinearLayout(this@renderItemsList).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this@renderItemsList).apply {
                text = line.itemName; textSize = 15f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this@renderItemsList).apply {
                text = "Rs %.2f".format(line.amount)
                setTextColor(Color.parseColor(teal))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(top)
            addView(TextView(this@renderItemsList).apply {
                text = "Qty: ${line.qty} ${line.unit}   •   Rate: ${line.unitPrice}"
                textSize = 12.5f
                setTextColor(Color.parseColor(textGray))
                setPadding(0, 6, 0, 0)
            })
            addView(TextView(this@renderItemsList).apply {
                text = "\u2715 " + com.grocerypos.v11.util.Loc.t(this@renderItemsList, "Remove", "ہٹائیں")
                textSize = 12f
                setTextColor(Color.parseColor(red))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 10, 0, 0)
                setOnClickListener {
                    lines.removeAt(index)
                    renderItemsList()
                    updateTotals()
                    if (editInvoice == null) saveDraft()
                    if (lines.isEmpty()) billedItemsDialog?.dismiss()
                }
            })
        })
    }
    updateBilledItemsTrigger()
}

internal fun SaleActivity.updateBilledItemsTrigger() {
    if (!isBilledItemsTriggerReady()) return
    val count = lines.size
    val total = lines.sumOf { it.amount }
    billedItemsTrigger.text = com.grocerypos.v11.util.Loc.t(
        this,
        "Billed Items",
        "بل کردہ آئٹمز"
    ) + "  ($count)  ·  Rs %.0f".format(total)
}

// ---- Purchase-style billed items dialog: a plain AlertDialog with a Close
// button, restoring focus to the Paid field on dismiss.
// ---- CHANGED (billed items -> paid amount flow): the ScrollView now also scrolls
// down to the Payment section on dismiss (in addition to focusing paidInput), so
// the field the keyboard is about to fill is actually visible on screen. ----
internal fun SaleActivity.openBilledItemsDialog() {
    if (lines.isEmpty()) {
        Toast.makeText(
            this,
            com.grocerypos.v11.util.Loc.t(this, "No items added yet", "ابھی تک کوئی آئٹم شامل نہیں"),
            Toast.LENGTH_SHORT
        ).show()
        return
    }

    (itemsContainer.parent as? ViewGroup)?.removeView(itemsContainer)
    renderItemsList()
    val wrapper = ScrollView(this).apply {
        setPadding(20, 10, 20, 4)
        addView(itemsContainer)
    }

    val dialog = AlertDialog.Builder(this)
        .setTitle(com.grocerypos.v11.util.Loc.t(this, "Billed Items", "بل کردہ آئٹمز"))
        .setView(wrapper)
        .setPositiveButton(com.grocerypos.v11.util.Loc.t(this, "Close", "بند کریں"), null)
        .setOnDismissListener {
            (itemsContainer.parent as? ViewGroup)?.removeView(itemsContainer)
            billedItemsDialog = null
            scrollView.post { scrollView.smoothScrollTo(0, paymentSection.top) }
            paidInput.requestFocus()
            paidInput.post {
                paidInput.selectAll()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(paidInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        .create()
    billedItemsDialog = dialog
    dialog.show()
}

// ---- Purchase-style live totals: subtotal/discount/total mirror what the save
// path will actually persist, computed through the same DiscountCalculator. ----
internal fun SaleActivity.recomputeAmounts(): Double {
    val subtotal = lines.sumOf { it.amount }
    val enteredDiscount = discountInput.text.toString().toDoubleOrNull() ?: 0.0
    val totals = DiscountCalculator.compute(subtotal, enteredDiscount, 0.0)
    subtotalText.text = "Rs %.2f".format(subtotal)
    totalText.text = "Rs %.2f".format(totals.total)
    return totals.total
}

// FIX (Paid amount bug): this used to auto-fill Paid = Total whenever Paid was
// blank. That meant Paid silently got stamped with whatever the *first* item's
// total happened to be (since Paid was blank right after the first add), and then
// stayed stuck at that stale number as more items were added — while also fighting
// with the "Paid khali hai - Udhaar jayega" warning below, which assumes Paid
// starts blank. Paid is now left exactly as the user typed it; nothing auto-fills it.
internal fun SaleActivity.updateTotals() {
    recomputeAmounts()
    refreshDue()
    updateBilledItemsTrigger()
}

internal fun SaleActivity.refreshDue() {
    val total = recomputeAmounts()
    val enteredPaid = paidInput.text.toString().toDoubleOrNull() ?: 0.0
    val paidClamped = enteredPaid.coerceIn(0.0, total)
    val due = (total - paidClamped).coerceAtLeast(0.0)
    dueAmountText.text = "Rs %.2f".format(due)
    dueAmountText.setTextColor(Color.parseColor(if (due > 0.009) red else green))
    isCashSale = due <= 0.009
    if (isPaidWarningTextReady()) {
        if (enteredPaid <= 0.009 && total > 0) {
            paidWarningText.visibility = View.VISIBLE
            paidWarningText.text = "Paid khali hai - Rs %.2f Udhaar jayega".format(due)
        } else {
            paidWarningText.visibility = View.GONE
        }
    }
}

internal fun SaleActivity.clearAll() {
    lines.clear()
    renderItemsList()
    customerName.text.clear()
    discountInput.text.clear()
    itemName.text.clear(); qty.text.clear(); unitPrice.text.clear()
    selectedProduct = null
    lastMainPrice = 0.0
    conversionInfo.visibility = View.GONE
    unitToggleRow.visibility = View.GONE
    itemLineTotalText.text = "Total Amount: Rs 0"
    paidInput.text.clear()
    updateTotals()
    saleDateMillis = System.currentTimeMillis()
    dateValueText.text = formatDate(saleDateMillis)
    clearDraft()
}
