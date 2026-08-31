package com.grocerypos.v11.sync

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import com.grocerypos.v11.CloudConfigStore
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
 *
 * CHANGED (multi-tenant support): which Firestore project this talks to is no longer
 * fixed at compile time — see CloudConfigStore. Every entry point below now takes a
 * Context and resolves the right FirebaseFirestore instance per call via
 * firestoreFor(context), which returns null if this device has no cloud project
 * configured at all (custom or default) — callers must treat that as "nothing to
 * sync to" rather than crashing.
 */
object SyncApi {

    private val gson by lazy { com.google.gson.Gson() }

    /** Returns null if this device has no Firebase project configured (custom or
     *  default), or if it couldn't establish an authenticated session with it.
     *
     *  ADDED (critical security fix): this app has no real login-to-cloud auth (see
     *  CloudConfigStore.kt's doc comment), which meant Firestore had to be left wide
     *  open to completely unauthenticated requests for sync to work at all — anyone
     *  with the project's public API key (visible inside the compiled APK, not a
     *  secret) could read/write/delete the entire database with zero verification.
     *  Firebase Anonymous Authentication closes that off at essentially no cost: it
     *  silently signs this device in with a real (if anonymous) Firebase identity the
     *  first time it's needed, so the paired Firestore Security Rules can require
     *  "allow read, write: if request.auth != null;" instead of "if true;" — a random
     *  script pointed at the API key with no auth token now gets rejected outright.
     *  This is not full user-level security (anyone signed in anonymously can still
     *  read/write within their own project), but combined with each business having
     *  its own separate Firebase project (CloudConfigStore), it means data can no
     *  longer be touched by someone who merely has the API key and nothing else. */
    private suspend fun firestoreFor(context: Context): FirebaseFirestore? {
        val app = CloudConfigStore.firebaseApp(context) ?: return null
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance(app)
        if (auth.currentUser == null) {
            try {
                auth.signInAnonymously().await()
            } catch (e: Exception) {
                return null
            }
        }
        return FirebaseFirestore.getInstance(app)
    }

    // ---------- PUSH ----------

    suspend fun push(context: Context, entry: com.grocerypos.v11.SyncQueueEntry): Boolean {
        val db = firestoreFor(context) ?: return false
        val localDb = PosDatabase.get(context)
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
                // FIX (conflict-safe multi-device sync): stock and balance changes are
                // pushed as a DELTA here instead of the field's absolute value, applied
                // via Firestore's FieldValue.increment() — which the server resolves
                // atomically and additively no matter what order multiple offline
                // devices' pushes arrive in, so two devices selling the same product (or
                // adjusting the same customer's balance) while both offline never lose
                // either device's contribution the way a plain overwritten snapshot
                // would. See SyncQueueHelper.kt's decreaseProductStock() etc. for the
                // push side, and productJson()/customerJson()/supplierJson() for why
                // "stock"/"balance" are deliberately absent from the regular snapshot.
                "increment_stock", "increment_balance" -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = gson.fromJson(entry.payloadJson, Map::class.java) as Map<String, Any?>
                    val delta = (map["delta"] as? Number)?.toDouble() ?: 0.0
                    val fieldName = if (entry.operation == "increment_stock") "stock" else "balance"
                    val updateMap = mapOf(
                        fieldName to com.google.firebase.firestore.FieldValue.increment(delta),
                        "updatedAt" to (map["updatedAt"] ?: System.currentTimeMillis()),
                        "branchId" to (map["branchId"] ?: com.grocerypos.v11.BuildConfig.BRANCH_ID)
                    )
                    db.collection(collection).document(entry.entityId)
                        .set(updateMap, com.google.firebase.firestore.SetOptions.merge())
                        .await()
                }
                // FIX (conflict-safe multi-device sync, part 2): plain field edits
                // (name/phone/role/etc — anything that isn't a counter) used to just
                // overwrite the server doc no matter what, so whichever device's push
                // happened to physically REACH Firestore last would win — which isn't
                // necessarily the device that made the more recent EDIT. E.g. Device A
                // edits a customer's phone at 10am but stays offline until 3pm; Device B
                // edits the same customer's phone at 1pm and pushes immediately. When A
                // finally syncs at 3pm, its older 10am edit would silently overwrite B's
                // newer 1pm edit. Now wrapped in a transaction that reads the server's
                // current "updatedAt" first and only writes if this push's own edit
                // timestamp is the same or newer — so the most recently EDITED version
                // always wins, not the most recently ARRIVED one. When the incoming edit
                // loses this comparison, it's logged to the audit log as a conflict (see
                // Settings > Sync History) instead of silently vanishing.
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = gson.fromJson(entry.payloadJson, Map::class.java) as Map<String, Any?>
                    val incomingUpdatedAt = (map["updatedAt"] as? Number)?.toDouble() ?: 0.0
                    val docRef = db.collection(collection).document(entry.entityId)
                    val applied = db.runTransaction { txn ->
                        val snap = txn.get(docRef)
                        val serverUpdatedAt = (snap.get("updatedAt") as? Number)?.toDouble() ?: 0.0
                        if (!snap.exists() || incomingUpdatedAt >= serverUpdatedAt) {
                            txn.set(docRef, map, com.google.firebase.firestore.SetOptions.merge())
                            true
                        } else {
                            false
                        }
                    }.await()
                    if (!applied) {
                        logAudit(
                            localDb, "sync_conflict",
                            reference = "${entry.entityType}:${entry.entityId}",
                            details = "Local edit was older than the server's — server version kept. Local change was NOT applied to the cloud."
                        )
                    }
                }
            }
            true
        } catch (e: Exception) {
            logAudit(
                localDb, "sync_push_failed",
                reference = "${entry.entityType}:${entry.entityId}",
                details = e.message ?: "unknown error"
            )
            false
        }
    }

    private suspend fun logAudit(localDb: PosDatabase, action: String, reference: String, details: String) {
        try {
            localDb.auditDao().insert(
                com.grocerypos.v11.Audit(username = "sync", action = action, reference = reference, details = details)
            )
        } catch (e: Exception) {
            // logging must never break sync itself
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
    suspend fun pull(context: Context, since: Long): PullResult {
        val db = firestoreFor(context) ?: return PullResult(serverTime = since)
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
                // FIX (conflict recoverability): if this device has its own unpushed
                // edit (dirty=true) to this same customer's name/phone, an incoming
                // pull would otherwise silently discard it here before it ever gets a
                // chance to push. Can't be prevented automatically (the pull already
                // has to win, or nothing would ever converge), but it's now logged so
                // the admin can notice and manually redo whatever was lost.
                if (existing.dirty && (existing.name != name || existing.phone != phone)) {
                    logAudit(
                        db, "sync_conflict",
                        reference = "customer:$serverId",
                        details = "Your unsynced edit (\"${existing.name} / ${existing.phone}\") was overwritten by a newer cloud update (\"$name / $phone\")."
                    )
                }
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
                if (existing.dirty && (existing.name != name || existing.phone != phone)) {
                    logAudit(
                        db, "sync_conflict",
                        reference = "supplier:$serverId",
                        details = "Your unsynced edit (\"${existing.name} / ${existing.phone}\") was overwritten by a newer cloud update (\"$name / $phone\")."
                    )
                }
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
