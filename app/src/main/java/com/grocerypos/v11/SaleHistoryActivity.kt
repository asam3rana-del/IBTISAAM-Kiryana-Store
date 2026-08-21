private fun deleteSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleHistoryActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)

            // ---- FIX: stored si.unit se, Product.toSmallestUnits() (multiply-only) —
            // pehle `db.productDao().increase(it.barcode, it.qty)` primary-unit qty ko
            // seedha smallest-unit stock mein add kar raha tha, jo unit-tier products ke
            // liye galat tha. Ab SaleActivity/PurchaseActivity jaisa hi. ----
            items.forEach { si ->
                val p = db.productDao().find(si.barcode)
                if (p != null) {
                    val smallestQty = p.toSmallestUnits(si.qty.toDouble(), si.unit.ifBlank { p.unit }).roundToInt()
                    db.productDao().increase(si.barcode, smallestQty)
                }
            }

            val outstanding = sale.total - sale.paid
            if (sale.customerId != null && outstanding > 0) {
                db.customerDao().addBalance(sale.customerId, -outstanding)
            }

            db.saleDao().deleteItems(invoice)
            db.saleDao().deleteSale(invoice)
            db.paymentDao().deleteByReference(invoice)
            db.cashTransactionDao().deleteByReference(invoice)

            expandedSales.remove(invoice)
            saleBodyViews.remove(invoice)
            Toast.makeText(this@SaleHistoryActivity, "Sale deleted", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }
