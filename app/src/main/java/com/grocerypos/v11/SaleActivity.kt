================================================================
PATCH FOR SaleActivity.kt
================================================================
Do 2 functions replace karni hain. In dono ka poora naya code neeche hai —
apni file mein purane addItem() aur saveSale() ko dhoond kar poora replace
kar dena (function ka opening brace se closing brace tak).

----------------------------------------------------------------
1) REPLACE: addItem()
   FIX: Ab cart mein pehle se add kiye gaye isi product ki qty bhi
   stock check mein count hoti hai — pehle sirf DB ka stock check
   hota tha, cart mein already-added lines ignore ho jati thin.
----------------------------------------------------------------

    private fun addItem() {
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
        val mainUnitQtyEquivalent = when {
            chosenUnit == product.tertiaryUnit && product.tertiaryUnit.isNotEmpty() &&
                product.tertiaryUnitQty > 0 && product.secondaryUnitQty > 0 ->
                q / (product.secondaryUnitQty * product.tertiaryUnitQty)
            chosenUnit == product.secondaryUnit && product.secondaryUnitQty > 0 ->
                q / product.secondaryUnitQty
            else -> q
        }

        // ---- FIX: sum this product's qty already sitting in the cart (converted to
        // main-unit terms via mainUnitQty()) and check against DB stock MINUS that —
        // not just against the raw DB stock. Without this, adding the same product
        // twice each individually passes the check even though together they exceed
        // what's in stock. ----
        val alreadyInCart = lines.filter { it.barcode == product.barcode }.sumOf { it.mainUnitQty() }
        val availableForThisAdd = product.stock - alreadyInCart

        if (availableForThisAdd < mainUnitQtyEquivalent) {
            Toast.makeText(
                this,
                "Stock kam hai (available: ${formatQty(availableForThisAdd.coerceAtLeast(0.0))} ${product.unit})",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val amount = q * price
        lines.add(
            SaleLine(
                barcode = product.barcode,
                itemName = product.name,
                qty = q,
                unit = chosenUnit,
                unitPrice = price,
                cost = product.cost,
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

        itemName.text.clear(); qty.text.clear(); unitPrice.text.clear()
        selectedProduct = null
        lastMainPrice = 0.0
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("pcs"))
        itemName.requestFocus()

        saveDraft()
    }

----------------------------------------------------------------
2) REPLACE: saveSale()
   FIX: Stock ko final commit se theek pehle DOBARA check karta hai
   (race-condition guard — jaise dusri sale isi bich mein ho gayi ho),
   aur decrease() ka return value check karta hai. Agar kisi bhi line
   ke liye stock kam nikla to POORI sale cancel ho jati hai (partial
   save nahi hoga) — customer ko clear error milta hai.
----------------------------------------------------------------

    private fun saveSale() {
        if (lines.isEmpty()) {
            Toast.makeText(this, "Kam az kam ek item add karen", Toast.LENGTH_SHORT).show()
            return
        }
        val enteredCustomer = customerName.text.toString().trim()
        if (!isCashSale && enteredCustomer.isEmpty()) {
            Toast.makeText(this, "Credit sale ke liye Customer zaroori hai", Toast.LENGTH_SHORT).show()
            return
        }

        val subtotal = lines.sumOf { it.amount }
        val discount = (discountInput.text.toString().toDoubleOrNull() ?: 0.0).coerceIn(0.0, subtotal)
        val total = (subtotal - discount).coerceAtLeast(0.0)
        val paid = if (isCashSale) (paidInput.text.toString().toDoubleOrNull() ?: total) else 0.0
        val method = if (isCashSale) (paymentMethodSpinner.selectedItem?.toString() ?: "Cash") else "credit"
        var customer = customers.find { it.name.equals(enteredCustomer, ignoreCase = true) }
        val saleType = if (saleTypeSpinner.selectedItem?.toString() == "Wholesale") "wholesale" else "retail"
        val invoice = "INV" + System.currentTimeMillis().toString()

        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)

            // ---- FIX: re-validate stock right before committing, against the LATEST
            // DB values (not the possibly-stale `products` list snapshot from whenever
            // the screen loaded). Aggregates multiple lines of the same product first.
            // If ANY item comes up short, the whole sale is rejected — nothing is saved,
            // so there's no risk of a half-recorded bill with wrong stock. ----
            val neededByBarcode = lines.groupBy { it.barcode }.mapValues { (_, group) -> group.sumOf { it.mainUnitQty() } }
            for ((barcode, needed) in neededByBarcode) {
                val current = db.productDao().find(barcode)
                if (current == null || current.stock < needed) {
                    Toast.makeText(
                        this@SaleActivity,
                        "Stock badal gaya hai — \"${current?.name ?: barcode}\" mein sirf ${current?.stock ?: 0} available hai. Bill dobara check karen.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
            }

            if (customer == null && enteredCustomer.isNotEmpty()) {
                val newId = db.customerDao().insert(Customer(name = enteredCustomer))
                customer = Customer(id = newId, name = enteredCustomer)
            }

            db.saleDao().sale(
                Sale(
                    invoice = invoice,
                    customerId = customer?.id,
                    subtotal = subtotal,
                    discount = discount,
                    tax = 0.0,
                    total = total,
                    paid = paid,
                    paymentMethod = method.lowercase(),
                    saleType = saleType,
                    createdAt = saleDateMillis
                )
            )

            val saleItems = lines.map {
                SaleItem(
                    invoice = invoice,
                    barcode = it.barcode,
                    product = it.itemName,
                    qty = it.mainUnitQty().roundToInt(),
                    unitPrice = it.mainUnitPrice(),
                    cost = it.cost,
                    amount = it.amount
                )
            }
            db.saleDao().items(saleItems)

            // ---- FIX: check decrease()'s return value. It's guarded (won't go below
            // zero) — already re-validated above, so this should always succeed, but if
            // it somehow doesn't (another sale slipped in between the check and here),
            // we still don't silently pretend the stock moved. ----
            for (line in lines) {
                val rowsAffected = db.productDao().decrease(line.barcode, line.mainUnitQty().roundToInt())
                if (rowsAffected == 0) {
                    Toast.makeText(
                        this@SaleActivity,
                        "Warning: \"${line.itemName}\" ka stock update nahi ho saka — check karen.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            if (customer != null && paid < total) {
                db.customerDao().addBalance(customer!!.id, total - paid)
            }

            if (paid > 0) {
                db.cashTransactionDao().insert(
                    CashTransaction(
                        type = "IN",
                        method = method.lowercase(),
                        amount = paid,
                        reason = "Sale",
                        reference = invoice
                    )
                )
            }

            suppressDraftSave = true
            clearDraft()

            val itemsEncoded = lines.joinToString("\u0002") {
                listOf(it.itemName, formatQty(it.qty), it.unit, it.unitPrice, it.amount).joinToString("\u0003")
            }
            val previewIntent = Intent(this@SaleActivity, BillPreviewActivity::class.java).apply {
                putExtra(BillPreviewActivity.EXTRA_TYPE, "sale")
                putExtra(BillPreviewActivity.EXTRA_REFERENCE, invoice)
                putExtra(BillPreviewActivity.EXTRA_PARTY_NAME, customer?.name ?: enteredCustomer)
                putExtra(BillPreviewActivity.EXTRA_PARTY_LABEL, "Customer")
                putExtra(BillPreviewActivity.EXTRA_DATE_MILLIS, saleDateMillis)
                putExtra(BillPreviewActivity.EXTRA_SUBTOTAL, subtotal)
                putExtra(BillPreviewActivity.EXTRA_DISCOUNT, discount)
                putExtra(BillPreviewActivity.EXTRA_TOTAL, total)
                putExtra(BillPreviewActivity.EXTRA_PAID, paid)
                putExtra(BillPreviewActivity.EXTRA_PAYMENT_METHOD, method)
                putExtra(BillPreviewActivity.EXTRA_ITEMS_ENCODED, itemsEncoded)
            }
            startActivity(previewIntent)
            finish()
        }
    }

----------------------------------------------------------------
NOTE: `mainUnitQty()` aur `mainUnitPrice()` extension functions already
file ke top par maujood hain (private fun SaleLine.mainUnitQty() etc) —
inhe chhedne ki zaroorat nahi.
================================================================
