================================================================
PATCH FOR PurchaseActivity.kt
================================================================
2 functions replace karni hain: savePurchase() aur deletePurchase().
(confirmDeletePurchase() ko chhedne ki zaroorat nahi, wo deletePurchase()
ko hi call karta hai.)

----------------------------------------------------------------
1) REPLACE: deletePurchase()
   FIX: ab decreaseForce() use hota hai (stock reverse karte waqt guard
   nahi lagta — agar item pehle hi sale ho chuka ho to bhi sahi reverse
   hoga, hamesha silently skip nahi hoga). Cash transaction cleanup bhi
   add hui hai (2nd patch se cash-out record hoga, delete karte waqt use
   bhi saaf karna zaroori hai).
----------------------------------------------------------------

    private fun deletePurchase(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)
            val purchase = originalPurchase ?: db.purchaseDao().findPurchase(billNo) ?: return@launch
            val items = originalItems.ifEmpty { db.purchaseDao().itemsForBill(billNo) }

            // FIX: decreaseForce (unguarded) instead of decrease (guarded) — a purchase's
            // stock may have already been partly/fully sold by now, so this must be able
            // to go negative to correctly reverse it, instead of silently no-op'ing.
            items.forEach { db.productDao().decreaseForce(it.barcode, it.qty) }
            val outstanding = purchase.total - purchase.paid
            if (purchase.supplierId != null && outstanding > 0) {
                db.supplierDao().addBalance(purchase.supplierId, -outstanding)
            }
            db.purchaseDao().deleteItems(billNo)
            db.purchaseDao().deletePurchase(billNo)
            db.paymentDao().deleteByReference(billNo)
            // FIX: also remove the Cash Out record this purchase created (see savePurchase() patch).
            db.cashTransactionDao().deleteByReference(billNo)

            Toast.makeText(this@PurchaseActivity, "Purchase deleted", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

----------------------------------------------------------------
2) REPLACE: savePurchase()
   FIX A: reversal (edit mode) uses decreaseForce instead of decrease.
   FIX B: after each line's stock increase(), the product's weighted-
          average cost is recalculated and saved — so Sale's profit
          calculation actually reflects real purchase rates over time.
   FIX C: when Amount Paid > 0, a CashTransaction("OUT") is now recorded
          — previously a purchase payment never showed up as cash going
          out anywhere in Reports/Cash Register.
----------------------------------------------------------------

    private fun savePurchase() {
        currentFocus?.let { focused ->
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }

        val party = partyName.text.toString().trim()
        if (party.isEmpty()) { partyName.error = "Required"; return }
        if (lines.isEmpty()) {
            Toast.makeText(this, "Add at least one item, or continue without items", Toast.LENGTH_SHORT).show()
        }

        val subtotal = lines.sumOf { it.amount }
        val discount = (discountInput.text.toString().toDoubleOrNull() ?: 0.0).coerceIn(0.0, subtotal)
        val grandTotal = (subtotal - discount).coerceAtLeast(0.0)
        val amountPaid = (paidInput.text.toString().toDoubleOrNull() ?: 0.0).coerceIn(0.0, grandTotal)
        val paymentMethod = "Cash"

        val matchedSupplier = suppliers.find { it.name.equals(party, ignoreCase = true) }
        val supplierId = matchedSupplier?.id

        val billNo = editBillNo ?: genBillNo()

        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)

            val original = originalPurchase
            if (original != null) {
                // FIX: decreaseForce instead of decrease — see deletePurchase() note above.
                originalItems.forEach { db.productDao().decreaseForce(it.barcode, it.qty) }
                val originalOutstanding = original.total - original.paid
                if (original.supplierId != null && originalOutstanding > 0) {
                    db.supplierDao().addBalance(original.supplierId, -originalOutstanding)
                }
                db.purchaseDao().deleteItems(billNo)
                db.purchaseDao().deletePurchase(billNo)
                db.paymentDao().deleteByReference(billNo)
                // FIX: also remove the old Cash Out record before re-inserting the updated one below.
                db.cashTransactionDao().deleteByReference(billNo)
            }

            db.purchaseDao().purchase(
                Purchase(
                    billNo = billNo,
                    supplierId = supplierId,
                    total = grandTotal,
                    paid = amountPaid,
                    createdAt = purchaseDateMillis,
                    subtotal = subtotal,
                    discount = discount
                )
            )

            db.purchaseDao().items(
                lines.map { line ->
                    PurchaseItem(
                        billNo = billNo,
                        barcode = line.barcode ?: "",
                        qty = line.mainUnitQty().roundToInt(),
                        unitCost = line.mainUnitRate(),
                        amount = line.amount,
                        unit = line.unit
                    )
                }
            )

            // FIX: for each line, BEFORE increasing stock, snapshot the product's current
            // stock+cost, then after increasing, write back a weighted-average cost:
            //   newCost = (oldStock * oldCost + purchasedQty * purchaseRate) / (oldStock + purchasedQty)
            // This keeps Product.cost realistic over time instead of it only ever being
            // whatever was typed once in Add/Edit Product.
            lines.forEach { line ->
                val barcode = line.barcode ?: return@forEach
                val before = db.productDao().find(barcode)
                val purchasedQty = line.mainUnitQty().roundToInt()

                db.productDao().increase(barcode, purchasedQty)

                if (before != null && purchasedQty > 0) {
                    val oldStock = before.stock
                    val oldCost = before.cost
                    val purchaseRate = line.mainUnitRate()
                    val newCost = if (oldStock <= 0) {
                        purchaseRate
                    } else {
                        ((oldStock * oldCost) + (purchasedQty * purchaseRate)) / (oldStock + purchasedQty)
                    }
                    db.productDao().updateCost(barcode, newCost)
                }
            }

            val outstanding = grandTotal - amountPaid
            if (supplierId != null && outstanding > 0) {
                db.supplierDao().addBalance(supplierId, outstanding)
            }
            if (supplierId != null && amountPaid > 0) {
                db.paymentDao().insert(
                    Payment(
                        reference = billNo,
                        partyType = "supplier",
                        partyId = supplierId,
                        amount = amountPaid,
                        method = paymentMethod,
                        note = if (original != null) "Purchase payment (edited)" else "Purchase payment"
                    )
                )
            }

            // FIX: record the cash that actually left the register for this purchase —
            // previously nothing logged this, so Cash Register / Reports never reflected
            // money paid out to suppliers.
            if (amountPaid > 0) {
                db.cashTransactionDao().insert(
                    CashTransaction(
                        type = "OUT",
                        method = paymentMethod.lowercase(),
                        amount = amountPaid,
                        reason = "Purchase",
                        reference = billNo
                    )
                )
            }

            suppressDraftSave = true
            clearDraft()

            Toast.makeText(
                this@PurchaseActivity,
                if (original != null) "Purchase updated" else "Purchase saved",
                Toast.LENGTH_SHORT
            ).show()

            editBillNo = billNo
            openBillPreview(billNo, forSaving = true, party = party, grandTotal = grandTotal, discount = discount, amountPaid = amountPaid, paymentMethod = paymentMethod)
        }
    }

================================================================
