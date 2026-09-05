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
import com.grocerypos.v11.User
import com.grocerypos.v11.PasswordHasher
import com.grocerypos.v11.Product
import com.grocerypos.v11.Payment
import com.grocerypos.v11.util.Loc

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
 *   payments/{serverId}
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

    // ADDED (reconstructed — see note below): these were present in the live repo
    // (referenced by SyncRepository.kt's catch blocks) but got lost when an earlier
    // SyncApi.kt handoff in this chat overwrote the file without them. Re-added here
    // with the most standard/sensible behavior for their names; if the original
    // wording of permissionDeniedMessage() differed, this is a reasonable rebuild,
    // not a byte-for-byte restore — happy to adjust the copy if you remember it.

    /** Thrown by pull() when this build has no branch id configured at all — a
     *  genuinely different problem from "no cloud project configured" (that case
     *  returns an empty PullResult from firestoreFor() instead, since it's a normal
     *  "sync not set up yet" state), and from "permission denied" (a project/rules
     *  problem, not a build-config one). */
    class BranchNotConfiguredException(message: String) : Exception(message)

    /** True if this exception is Firestore rejecting the request outright because
     *  the signed-in (anonymous) user isn't allowed by the project's Security Rules
     *  — as opposed to a network error, a missing index, or any other failure. */
    fun isPermissionDenied(e: Exception): Boolean =
        (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code ==
            com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED

    /** Human-readable explanation for isPermissionDenied() — this almost always
     *  means the Firestore project's Security Rules don't match what this app
     *  expects (see firestore.rules in the repo root), not a problem the user can
     *  fix from inside the app itself. */
    fun permissionDeniedMessage(context: Context): String = Loc.t(
        context,
        "Cloud sync was rejected by the server (permission denied) — check that this device's Firebase project's Security Rules allow signed-in access, or re-check your Cloud Sync Setup in Settings.",
        "کلاؤڈ سنک سرور نے مسترد کر دیا (اجازت نہیں) — چیک کریں کہ اس ڈیوائس کے Firebase پراجیکٹ کے Security Rules سائن اِن رسائی کی اجازت دیتے ہیں، یا Settings میں Cloud Sync Setup دوبارہ دیکھیں۔"
    )

    /** Synchronous — returns this device's Firebase Auth (anonymous) UID if it has
     *  ALREADY signed in during some previous sync (via firestoreFor(), which does
     *  the actual signInAnonymously() suspend call), or null if it hasn't signed in
     *  yet. Deliberately never triggers a new sign-in itself, so it's safe to call
     *  from plain (non-coroutine) UI code — Cloud Sync Setup's dialog uses it to show
     *  a "Device ID" the admin hands off to whoever manages the Firebase console, so
     *  they can create the matching branch_members/{uid} document (see
     *  firestore.rules) and approve this device. Returns null until the first real
     *  sync attempt has happened at least once, which is why that dialog says
     *  "Save first — ID will appear after the first sync attempt". */
    fun currentUid(context: Context): String? {
        val app = CloudConfigStore.firebaseApp(context) ?: return null
        return com.google.firebase.auth.FirebaseAuth.getInstance(app).currentUser?.uid
    }

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

    data class PullResult(
        val customers: List<Map<String, Any?>> = emptyList(),
        val suppliers: List<Map<String, Any?>> = emptyList(),
        val products: List<Map<String, Any?>> = emptyList(),
        val users: List<Map<String, Any?>> = emptyList(),
        val sales: List<Map<String, Any?>> = emptyList(),
        val purchases: List<Map<String, Any?>> = emptyList(),
        val payments: List<Map<String, Any?>> = emptyList(),
        val expenses: List<Map<String, Any?>> = emptyList(),
        val cashTransactions: List<Map<String, Any?>> = emptyList(),
        val serverTime: Long = System.currentTimeMillis()
    )

    suspend fun pull(context: Context, since: Long): PullResult {
        val db = firestoreFor(context) ?: return PullResult(serverTime = since)
        val branchId = com.grocerypos.v11.BuildConfig.BRANCH_ID
        if (branchId.isBlank()) {
            throw BranchNotConfiguredException(
                Loc.t(context, "This build has no branch id configured — cannot sync.", "اس بلڈ میں برانچ آئی ڈی سیٹ نہیں ہے — سنک نہیں ہو سکتا۔")
            )
        }

        fun query(collection: String) = db.collection(collection)
            .whereEqualTo("branchId", branchId)
            .whereGreaterThan("updatedAt", since)

        val customersSnap = query("customers").get(Source.SERVER).await()
        val suppliersSnap = query("suppliers").get(Source.SERVER).await()
        val productsSnap = query("products").get(Source.SERVER).await()
        val usersSnap = query("users").get(Source.SERVER).await()
        val salesSnap = query("sales").get(Source.SERVER).await()
        val purchasesSnap = query("purchases").get(Source.SERVER).await()
        val paymentsSnap = query("payments").get(Source.SERVER).await()
        val expensesSnap = query("expenses").get(Source.SERVER).await()
        val cashTxSnap = query("cash_transactions").get(Source.SERVER).await()

        val allSnaps = listOf(
            customersSnap, suppliersSnap, productsSnap, usersSnap,
            salesSnap, purchasesSnap, paymentsSnap, expensesSnap, cashTxSnap
        )
        var maxUpdatedAt = since
        for (snap in allSnaps) {
            for (doc in snap.documents) {
                val updatedAt = (doc.get("updatedAt") as? Number)?.toLong() ?: continue
                if (updatedAt > maxUpdatedAt) maxUpdatedAt = updatedAt
            }
        }

        return PullResult(
            customers = customersSnap.documents.map { it.data ?: emptyMap() },
            suppliers = suppliersSnap.documents.map { it.data ?: emptyMap() },
            products = productsSnap.documents.map { it.data ?: emptyMap() },
            users = usersSnap.documents.map { it.data ?: emptyMap() },
            sales = salesSnap.documents.map { it.data ?: emptyMap() },
            purchases = purchasesSnap.documents.map { it.data ?: emptyMap() },
            payments = paymentsSnap.documents.map { it.data ?: emptyMap() },
            expenses = expensesSnap.documents.map { it.data ?: emptyMap() },
            cashTransactions = cashTxSnap.documents.map { it.data ?: emptyMap() },
            serverTime = maxUpdatedAt
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
        val paymentDao = db.paymentDao()
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
            val name = row["name"] as? String ?: continue
            val category = row["category"] as? String ?: ""
            val cost = (row["cost"] as? Number)?.toDouble() ?: 0.0
            val salePrice = (row["salePrice"] as? Number)?.toDouble() ?: 0.0
            val wholesalePrice = (row["wholesalePrice"] as? Number)?.toDouble() ?: 0.0
            val reorderLevel = (row["reorderLevel"] as? Number)?.toDouble() ?: 0.0
            val expiry = row["expiry"] as? String ?: ""
            val unit = row["unit"] as? String ?: "pcs"
            val unitSize = (row["unitSize"] as? Number)?.toInt() ?: 1
            val unitNote = row["unitNote"] as? String ?: ""
            val secondaryUnit = row["secondaryUnit"] as? String ?: ""
            val secondaryUnitQty = (row["secondaryUnitQty"] as? Number)?.toDouble() ?: 0.0
            val tertiaryUnit = row["tertiaryUnit"] as? String ?: ""
            val tertiaryUnitQty = (row["tertiaryUnitQty"] as? Number)?.toDouble() ?: 0.0
            // FIX (stock sync incomplete): productJson() deliberately leaves "stock" out
            // of the full-snapshot payload (see the big comment above adjustCustomerBalance
            // in SyncQueueHelper.kt) — every stock change instead reaches Firestore as its
            // own "increment_stock" FieldValue.increment() write, so the "stock" field
            // sitting on the pulled product document IS the correctly-merged, additive
            // total across every device/branch-session that has sold or purchased this
            // product. This apply loop was reading every other field off that same pulled
            // document except this one, so a product record merely being re-saved (e.g. a
            // price edit) on the far device — which pulls the whole document, stock field
            // included — never carried that already-merged stock number back down here;
            // the local row silently kept whatever stale stock value it already had.
            // Reading it now, and falling back to the existing/new-row value only when the
            // server document doesn't have it yet, closes that gap without turning this
            // back into a snapshot overwrite (an increment_stock entry queued locally but
            // not yet pushed still layers its own delta on top via decrease()/increase()).
            val stock = (row["stock"] as? Number)?.toDouble()

            val existing = prodDao.find(barcode)
            if (existing != null) {
                if (existing.dirty && (existing.name != name || existing.salePrice != salePrice)) {
                    logAudit(
                        db, "sync_conflict",
                        reference = "product:$barcode",
                        details = "Your unsynced edit (\"${existing.name} / ${existing.salePrice}\") was overwritten by a newer cloud update (\"$name / $salePrice\")."
                    )
                }
                prodDao.upsert(
                    existing.copy(
                        name = name, category = category, cost = cost, salePrice = salePrice,
                        wholesalePrice = wholesalePrice, reorderLevel = reorderLevel, expiry = expiry,
                        unit = unit, unitSize = unitSize, unitNote = unitNote,
                        secondaryUnit = secondaryUnit, secondaryUnitQty = secondaryUnitQty,
                        tertiaryUnit = tertiaryUnit, tertiaryUnitQty = tertiaryUnitQty,
                        stock = stock ?: existing.stock,
                        dirty = false, updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                prodDao.upsert(
                    Product(
                        barcode = barcode, name = name, category = category, cost = cost,
                        salePrice = salePrice, wholesalePrice = wholesalePrice,
                        reorderLevel = reorderLevel, expiry = expiry, unit = unit,
                        unitSize = unitSize, unitNote = unitNote, secondaryUnit = secondaryUnit,
                        secondaryUnitQty = secondaryUnitQty, tertiaryUnit = tertiaryUnit,
                        tertiaryUnitQty = tertiaryUnitQty, stock = stock ?: 0.0, dirty = false,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }

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
            } else {
                userDao.upsert(
                    User(
                        username = username,
                        displayName = displayName,
                        role = role,
                        passwordHash = PasswordHasher.hash(java.util.UUID.randomUUID().toString()),
                        active = active,
                        phone = phone
                    )
                )
            }
        }

        @Suppress("UNCHECKED_CAST")
        for (row in changes.sales) {
            val invoice = row["invoice"] as? String ?: continue
            val customerServerId = row["customerServerId"] as? String
            val localCustomerId = customerServerId?.let { custDao.findByServerId(it)?.id }
            val sale = Sale(
                invoice = invoice,
                customerId = localCustomerId,
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
                dirty = false,
                // NEW (Due Date Reminders): pull the reminder date set on either device.
                dueDate = (row["dueDate"] as? Number)?.toLong() ?: 0L
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
                        amount = (im["amount"] as? Number)?.toDouble() ?: 0.0,
                        conversionFactor = (im["conversionFactor"] as? Number)?.toDouble() ?: 0.0
                    )
                }
                if (items.isNotEmpty()) {
                    saleDao.deleteItems(invoice)
                    saleDao.upsertItems(items)
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        for (row in changes.purchases) {
            val billNo = row["billNo"] as? String ?: continue
            val supplierServerId = row["supplierServerId"] as? String
            val localSupplierId = supplierServerId?.let { suppDao.findByServerId(it)?.id }
            val purchase = Purchase(
                billNo = billNo,
                supplierId = localSupplierId,
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
                        unit = im["unit"] as? String ?: "",
                        conversionFactor = (im["conversionFactor"] as? Number)?.toDouble() ?: 0.0
                    )
                }
                if (items.isNotEmpty()) {
                    purchaseDao.deleteItems(billNo)
                    purchaseDao.upsertItems(items)
                }
            }
        }

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

        for (row in changes.payments) {
            val serverId = row["serverId"] as? String ?: continue
            val reference = row["reference"] as? String ?: continue
            val partyType = row["partyType"] as? String ?: ""
            val partyId = (row["partyId"] as? Number)?.toLong()
            val amount = (row["amount"] as? Number)?.toDouble() ?: 0.0
            val method = row["method"] as? String ?: ""
            val note = row["note"] as? String ?: ""
            val createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()

            val existing = paymentDao.findByServerId(serverId)
            if (existing != null) {
                paymentDao.update(
                    existing.copy(
                        reference = reference, partyType = partyType, partyId = partyId,
                        amount = amount, method = method, note = note, createdAt = createdAt,
                        updatedAt = System.currentTimeMillis(), dirty = false
                    )
                )
            } else {
                paymentDao.insert(
                    Payment(
                        reference = reference, partyType = partyType, partyId = partyId,
                        amount = amount, method = method, note = note, createdAt = createdAt,
                        serverId = serverId, updatedAt = System.currentTimeMillis(), dirty = false
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
