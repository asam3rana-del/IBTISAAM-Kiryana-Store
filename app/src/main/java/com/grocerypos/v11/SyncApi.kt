package com.grocerypos.v11.sync

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Customer
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.Sale
import com.grocerypos.v11.SaleItem
import com.grocerypos.v11.Purchase
import com.grocerypos.v11.PurchaseItem
import com.grocerypos.v11.Expense
import com.grocerypos.v11.CashTransaction

/**
 * Firebase Firestore based sync layer.
 *
 * Firestore collections used (all two-way as of this fix):
 *   customers/{serverId}
 *   suppliers/{serverId}
 *   products/{barcode}
 *   users/{serverId}
 *   sales/{serverId}
 *   purchases/{serverId}
 *   payments/{serverId}           (still push-only, see NOTE below)
 *   expenses/{serverId}
 *   cash_transactions/{serverId}
 */
object SyncApi {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val gson by lazy { com.google.gson.Gson() }

    // ---------- PUSH ----------

    suspend fun push(entry: com.grocerypos.v11.SyncQueueEntry): Boolean {
        return try {
            val collection = when (entry.entityType) {
                "customer" -> "customers"
                "supplier" -> "suppliers"
                "product" -> "products"
                "user" -> "users"
                "sale" -> "sales"
                "purchase" -> "purchases"
                "payment" -> "payments"
                "expense" -> "expenses"
                "cash_transaction" -> "cash_transactions"
                else -> return false
            }

            when (entry.operation) {
                "delete" -> {
                    db.collection(collection).document(entry.entityId).delete().await()
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = gson.fromJson(entry.payloadJson, Map::class.java) as Map<String, Any?>
                    db.collection(collection).document(entry.entityId)
                        .set(map, com.google.firebase.firestore.SetOptions.merge())
                        .await()
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- PULL ----------

    // FIX (multi-device sync): sales/purchases/expenses/cash_transactions used to NOT be
    // pulled back down at all, so a sale made on one device never showed up on another
    // device's History/Reports/Day Book — pushing worked, but nothing ever came back.
    // Now pulled and applied just like customers/suppliers/products/users.
    //
    // Payments are still push-only for now: paymentEntityId() is built from the
    // reference (invoice/billNo) it belongs to, and if a party makes more than one
    // payment against the same invoice, each one currently overwrites the same
    // Firestore doc — pulling that back down isn't reliable until each payment gets
    // its own distinct id. The sale/purchase's own `paid` field (which IS synced) still
    // reflects the correct running total either way.
    data class PullResult(
        val customers: List<Map<String, Any?>> = emptyList(),
        val suppliers: List<Map<String, Any?>> = emptyList(),
        val products: List<Map<String, Any?>> = emptyList(),
        val users: List<Map<String, Any?>> = emptyList(),
        val sales: List<Map<String, Any?>> = emptyList(),
        val purchases: List<Map<String, Any?>> = emptyList(),
        val expenses: List<Map<String, Any?>> = emptyList(),
        val cashTransactions: List<Map<String, Any?>> = emptyList(),
        val serverTime: Long = System.currentTimeMillis()
    )

    // FIX (Phase 3 - Online / multi-branch): each collection query also filters by
    // "branchId" == this build's BuildConfig.BRANCH_ID, so a branch only ever pulls down
    // its own data instead of every branch's data mixed together. NOTE (migration):
    // documents pushed before this fix have no "branchId" field at all, so they won't
    // match this filter — either backfill a branchId onto old documents once, or just
    // let fresh pushes repopulate Firestore correctly from here on.
    //
    // NOTE: combining whereEqualTo("branchId",...) with whereGreaterThan("updatedAt",...)
    // needs a Firestore composite index per collection. The first time each collection
    // is queried, Firestore's exception message includes a direct link to auto-create
    // that collection's missing index — tap it once per collection (8 total now:
    // customers/suppliers/products/users/sales/purchases/expenses/cash_transactions).
    // Until that index exists for a given collection, this function throws for THAT
    // collection and the whole pull is aborted (see SyncRepository's try/catch) — so if
    // sync has been failing silently, this is the most likely reason; check Settings >
    // Sync Now's result message for the actual Firestore error text.
    suspend fun pull(since: Long): PullResult {
        val branchId = com.grocerypos.v11.BuildConfig.BRANCH_ID

        fun query(collection: String) = db.collection(collection)
            .whereEqualTo("branchId", branchId)
            .whereGreaterThan("updatedAt", since)

        val customersSnap = query("customers").get(Source.SERVER).await()
        val suppliersSnap = query("suppliers").get(Source.SERVER).await()
        val productsSnap = query("products").get(Source.SERVER).await()
        val usersSnap = query("users").get(Source.SERVER).await()
        val salesSnap = query("sales").get(Source.SERVER).await()
        val purchasesSnap = query("purchases").get(Source.SERVER).await()
        val expensesSnap = query("expenses").get(Source.SERVER).await()
        val cashTxSnap = query("cash_transactions").get(Source.SERVER).await()

        return PullResult(
            customers = customersSnap.documents.map { it.data ?: emptyMap() },
            suppliers = suppliersSnap.documents.map { it.data ?: emptyMap() },
            products = productsSnap.documents.map { it.data ?: emptyMap() },
            users = usersSnap.documents.map { it.data ?: emptyMap() },
            sales = salesSnap.documents.map { it.data ?: emptyMap() },
            purchases = purchasesSnap.documents.map { it.data ?: emptyMap() },
            expenses = expensesSnap.documents.map { it.data ?: emptyMap() },
            cashTransactions = cashTxSnap.documents.map { it.data ?: emptyMap() },
            serverTime = System.currentTimeMillis()
        )
    }

    // ---------- APPLY ----------

    suspend fun applyServerChanges(db: PosDatabase, changes: PullResult) {
        val custDao = db.customerDao()
        val suppDao = db.supplierDao()
        val prodDao = db.productDao()
        val userDao = db.userDao()
        val saleDao = db.saleDao()
        val purchaseDao = db.purchaseDao()
        val expenseDao = db.expenseDao()
        val cashTxDao = db.cashTransactionDao()

        for (row in changes.customers) {
            val serverId = row["serverId"] as? String ?: continue
            val name = row["name"] as? String ?: continue
            val phone = row["phone"] as? String ?: ""
            val balance = (row["balance"] as? Number)?.toDouble() ?: 0.0
            val creditLimit = (row["creditLimit"] as? Number)?.toDouble() ?: 0.0
            val openingBalance = (row["openingBalance"] as? Number)?.toDouble() ?: 0.0

            val existing = custDao.findByServerId(serverId)
            if (existing != null) {
                custDao.update(
                    existing.copy(
                        name = name, phone = phone, balance = balance,
                        creditLimit = creditLimit, openingBalance = openingBalance,
                        updatedAt = System.currentTimeMillis(), dirty = false
                    )
                )
            } else {
                custDao.insert(
                    Customer(
                        name = name, phone = phone, balance = balance,
                        creditLimit = creditLimit, openingBalance = openingBalance,
                        serverId = serverId, updatedAt = System.currentTimeMillis(), dirty = false
                    )
                )
            }
        }

        for (row in changes.suppliers) {
            val serverId = row["serverId"] as? String ?: continue
            val name = row["name"] as? String ?: continue
            val phone = row["phone"] as? String ?: ""
            val balance = (row["balance"] as? Number)?.toDouble() ?: 0.0
            val openingBalance = (row["openingBalance"] as? Number)?.toDouble() ?: 0.0

            val existing = suppDao.findByServerId(serverId)
            if (existing != null) {
                suppDao.update(
                    existing.copy(
                        name = name, phone = phone, balance = balance,
                        openingBalance = openingBalance,
                        updatedAt = System.currentTimeMillis(), dirty = false
                    )
                )
            } else {
                suppDao.insert(
                    Supplier(
                        name = name, phone = phone, balance = balance,
                        openingBalance = openingBalance, serverId = serverId,
                        updatedAt = System.currentTimeMillis(), dirty = false
                    )
                )
            }
        }

        for (row in changes.products) {
            val barcode = row["barcode"] as? String ?: continue
            val existing = prodDao.find(barcode)
            if (existing != null) {
                // FIX (fraction control): stock is now Double — .toInt() here used to
                // truncate a fractional server-side stock value (e.g. 2.5 Kg) on every
                // pull-sync. Read as Double so a Gram/ml-based product's stock survives sync.
                val stock = (row["stock"] as? Number)?.toDouble() ?: existing.stock
                prodDao.upsert(existing.copy(stock = stock, dirty = false, updatedAt = System.currentTimeMillis()))
            }
        }

        // passwordHash is never synced — preserve local password on pull.
        for (row in changes.users) {
            val username = row["username"] as? String ?: continue
            val displayName = row["displayName"] as? String ?: continue
            val role = row["role"] as? String ?: "cashier"
            val phone = row["phone"] as? String ?: ""
            val active = row["active"] as? Boolean ?: true

            val existing = userDao.findByUsername(username)
            if (existing != null) {
                userDao.upsert(
                    existing.copy(
                        displayName = displayName,
                        role = role,
                        phone = phone,
                        active = active
                    )
                )
            }
        }

        // ADDED — Sales: `invoice` is already a natural, globally-unique key (no local
        // autoincrement id involved), so upsertSale/upsertItems with REPLACE-on-conflict
        // is safe even for a sale this same device already pushed — it just overwrites
        // with an identical copy of itself, a harmless no-op in practice. Only writes to
        // sales/sale_items — deliberately does NOT touch product stock or customer
        // balance here, since those are already kept correct by the separate
        // products/customers sync above; applying them again here would double-count.
        @Suppress("UNCHECKED_CAST")
        for (row in changes.sales) {
            val invoice = row["invoice"] as? String ?: continue
            val sale = Sale(
                invoice = invoice,
                customerId = (row["customerId"] as? Number)?.toLong(),
                subtotal = (row["subtotal"] as? Number)?.toDouble() ?: 0.0,
                discount = (row["discount"] as? Number)?.toDouble() ?: 0.0,
                tax = 0.0,
                total = (row["total"] as? Number)?.toDouble() ?: 0.0,
                paid = (row["paid"] as? Number)?.toDouble() ?: 0.0,
                paymentMethod = row["paymentMethod"] as? String ?: "cash",
                saleType = row["saleType"] as? String ?: "retail",
                createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                status = row["status"] as? String ?: "active",
                updatedAt = System.currentTimeMillis(),
                dirty = false
            )
            saleDao.upsertSale(sale)

            val itemRows = row["items"] as? List<Map<String, Any?>>
            if (!itemRows.isNullOrEmpty()) {
                val items = itemRows.mapNotNull { im ->
                    val barcode = im["barcode"] as? String ?: return@mapNotNull null
                    SaleItem(
                        invoice = invoice,
                        barcode = barcode,
                        product = im["product"] as? String ?: "",
                        qty = (im["qty"] as? Number)?.toDouble() ?: 0.0,
                        unit = im["unit"] as? String ?: "",
                        unitPrice = (im["unitPrice"] as? Number)?.toDouble() ?: 0.0,
                        cost = (im["cost"] as? Number)?.toDouble() ?: 0.0,
                        amount = (im["amount"] as? Number)?.toDouble() ?: 0.0
                    )
                }
                if (items.isNotEmpty()) {
                    saleDao.deleteItems(invoice)
                    saleDao.upsertItems(items)
                }
            }
        }

        // ADDED — Purchases: same reasoning as Sales above, keyed by the natural billNo.
        @Suppress("UNCHECKED_CAST")
        for (row in changes.purchases) {
            val billNo = row["billNo"] as? String ?: continue
            val purchase = Purchase(
                billNo = billNo,
                supplierId = (row["supplierId"] as? Number)?.toLong(),
                total = (row["total"] as? Number)?.toDouble() ?: 0.0,
                paid = (row["paid"] as? Number)?.toDouble() ?: 0.0,
                createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                subtotal = (row["subtotal"] as? Number)?.toDouble() ?: 0.0,
                discount = (row["discount"] as? Number)?.toDouble() ?: 0.0,
                status = row["status"] as? String ?: "active",
                updatedAt = System.currentTimeMillis(),
                dirty = false
            )
            purchaseDao.upsertPurchase(purchase)

            val itemRows = row["items"] as? List<Map<String, Any?>>
            if (!itemRows.isNullOrEmpty()) {
                val items = itemRows.mapNotNull { im ->
                    val barcode = im["barcode"] as? String ?: return@mapNotNull null
                    PurchaseItem(
                        billNo = billNo,
                        barcode = barcode,
                        qty = (im["qty"] as? Number)?.toDouble() ?: 0.0,
                        unitCost = (im["unitCost"] as? Number)?.toDouble() ?: 0.0,
                        amount = (im["amount"] as? Number)?.toDouble() ?: 0.0,
                        unit = im["unit"] as? String ?: ""
                    )
                }
                if (items.isNotEmpty()) {
                    purchaseDao.deleteItems(billNo)
                    purchaseDao.upsertItems(items)
                }
            }
        }

        // ADDED — Expenses/Cash Transactions: unlike Sale/Purchase, these use a local
        // autoincrement id, so matching is by the `serverId` column (which
        // SyncQueueHelper now stamps onto the local row the moment it's created — see
        // enqueueExpense/enqueueCashTransaction) rather than by id, to avoid duplicating
        // this device's own records back into itself.
        for (row in changes.expenses) {
            val serverId = row["serverId"] as? String ?: continue
            val category = row["category"] as? String ?: continue
            val description = row["description"] as? String ?: ""
            val amount = (row["amount"] as? Number)?.toDouble() ?: 0.0
            val createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()

            val existing = expenseDao.findByServerId(serverId)
            if (existing != null) {
                expenseDao.update(
                    existing.copy(
                        category = category, description = description, amount = amount,
                        createdAt = createdAt, updatedAt = System.currentTimeMillis(), dirty = false
                    )
                )
            } else {
                expenseDao.insert(
                    Expense(
                        category = category, description = description, amount = amount,
                        createdAt = createdAt, serverId = serverId,
                        updatedAt = System.currentTimeMillis(), dirty = false
                    )
                )
            }
        }

        for (row in changes.cashTransactions) {
            val serverId = row["serverId"] as? String ?: continue
            val type = row["type"] as? String ?: continue
            val method = row["method"] as? String ?: ""
            val amount = (row["amount"] as? Number)?.toDouble() ?: 0.0
            val reason = row["reason"] as? String ?: ""
            val reference = row["reference"] as? String ?: ""
            val createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()

            val existing = cashTxDao.findByServerId(serverId)
            if (existing != null) {
                cashTxDao.update(
                    existing.copy(
                        type = type, method = method, amount = amount, reason = reason,
                        reference = reference, createdAt = createdAt,
                        updatedAt = System.currentTimeMillis(), dirty = false
                    )
                )
            } else {
                cashTxDao.insert(
                    CashTransaction(
                        type = type, method = method, amount = amount, reason = reason,
                        reference = reference, createdAt = createdAt, serverId = serverId,
                        updatedAt = System.currentTimeMillis(), dirty = false
                    )
                )
            }
        }
    }
}
