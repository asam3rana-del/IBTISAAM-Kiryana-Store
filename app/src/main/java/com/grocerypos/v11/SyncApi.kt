package com.grocerypos.v11.sync

import android.content.Context
import androidx.room.withTransaction
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import com.grocerypos.v11.CloudConfigStore
import com.grocerypos.v11.BranchConfigStore
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
import com.grocerypos.v11.UnitType
import com.grocerypos.v11.Category
import com.grocerypos.v11.ZakatYear
import com.grocerypos.v11.ZakatPayment
import com.grocerypos.v11.ReturnLine
import com.grocerypos.v11.StockMovement
import com.grocerypos.v11.AppSetting
import com.grocerypos.v11.CashRegister
import com.grocerypos.v11.ShellCustomer
import com.grocerypos.v11.ShellTransaction
import com.grocerypos.v11.ShopEmptyShellLog
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
 *   units/{name}
 *   categories/{name}
 *   zakat_years/{serverId}
 *   zakat_payments/{serverId}
 *   returns/{serverId}
 *   stock_movements/{serverId} (Stock History + Cost History — one shared ledger, see Database.kt's StockMovement doc comment)
 *   app_settings/{key} (whitelisted shop-identity keys only — see SyncQueueHelper.SYNCED_APP_SETTING_KEYS)
 *   cash_register/{date} (NEW — one shared till record per branch per day, see SyncQueueHelper.cashRegisterEntityId's comment)
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
        // Never perform a cloud request without an explicitly configured branch.
        // This prevents an accidental empty/wrong tenant write during first setup.
        if (!BranchConfigStore.isConfigured()) return null
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
                "unit" -> "units"
                "category" -> "categories"
                "zakat_year" -> "zakat_years"
                "zakat_payment" -> "zakat_payments"
                "return" -> "returns"
                "stock_movement" -> "stock_movements"
                "app_setting" -> "app_settings"
                "cash_register" -> "cash_register"
                "shell_customer" -> "shell_customers"
                "shell_transaction" -> "shell_transactions"
                "shop_empty_shell_log" -> "shop_empty_shell_log"
                else -> return false
            }

            when (entry.operation) {
                "delete" -> {
                    // Use a timestamped tombstone instead of a hard delete. A hard
                    // delete cannot be observed by other devices because pull() only
                    // sees documents changed since its last checkpoint.
                    //
                    // FIX (clock skew — see the "local data is authoritative" comment
                    // on the upsert branch below): this used to run inside a
                    // transaction and only apply the tombstone if this device's local
                    // clock (System.currentTimeMillis()) looked >= the server's stored
                    // updatedAt. Two devices with different clocks made that comparison
                    // unreliable — a device running slow could have its real, later
                    // delete silently dropped because its timestamp looked "older" than
                    // the server's. Deletes now follow the same rule as every other
                    // push in this function: the action a device actually took is
                    // always applied, unconditionally, no clock-based comparison.
                    val deleteAt = System.currentTimeMillis()
                    val docRef = db.collection(collection).document(entry.entityId)
                    db.runTransaction { tx ->
                        val snap = tx.get(docRef)
                        val serverUpdatedAt = (snap.get("updatedAt") as? Number)?.toLong() ?: Long.MIN_VALUE
                        // Deterministic last-write-wins using the queue entry timestamp.
                        // A stale delete must never erase a newer cloud record.
                        if (deleteAt >= serverUpdatedAt) {
                            tx.set(
                                docRef,
                                mapOf(
                                    "serverId" to entry.entityId,
                                    "_deleted" to true,
                                    "updatedAt" to deleteAt,
                                    "branchId" to BranchConfigStore.current
                                ),
                                com.google.firebase.firestore.SetOptions.merge()
                            )
                        }
                        null
                    }.await()
                }
                // FIX (audit — cross-device OPEN REGISTER race): see
                // SyncQueueHelper.enqueueCashRegisterCreate's comment. This is the
                // create-half of the fix — genuinely atomic on the server because it
                // runs inside a transaction and only writes if the doc is absent (or
                // tombstoned), instead of the generic branch below's last-write-wins
                // merge. Whichever device's transaction commits first "wins" the
                // day's opening balance; the loser's write is simply skipped — no
                // exception, no retry — and that device's own local row self-heals on
                // its next pull() once the guard in applyServerChanges() no longer
                // sees a pending push for this date.
                "create_if_absent" -> {
                    @Suppress("UNCHECKED_CAST")
                    val rawMap = gson.fromJson(entry.payloadJson, Map::class.java) as Map<String, Any?>
                    val incomingUpdatedAt = (rawMap["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                    val map = rawMap + mapOf("branchId" to BranchConfigStore.current, "updatedAt" to incomingUpdatedAt)
                    val docRef = db.collection(collection).document(entry.entityId)
                    db.runTransaction { tx ->
                        val snap = tx.get(docRef)
                        val alreadyExists = snap.exists() && snap.get("_deleted") != true
                        if (!alreadyExists) {
                            tx.set(docRef, map, com.google.firebase.firestore.SetOptions.merge())
                        }
                        null
                    }.await()
                }
                "increment_stock", "increment_balance" -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = gson.fromJson(entry.payloadJson, Map::class.java) as Map<String, Any?>
                    val delta = (map["delta"] as? Number)?.toDouble() ?: 0.0
                    val fieldName = if (entry.operation == "increment_stock") "stock" else "balance"
                    // FIX (audit — retried increment applied TWICE): a plain set(FieldValue.increment)
                    // is not idempotent. Firestore's own offline write queue keeps the write and
                    // delivers it later, while THIS app's queue also marks the entry failed
                    // (timeout / worker cancelled / app killed before markSynced) and retries it
                    // => the balance or stock moved twice. Now the increment runs inside a
                    // TRANSACTION (which fails cleanly when offline instead of being queued) and
                    // records this entry's unique op id in the same document, so a retry of an
                    // entry that already landed is recognised and skipped. Stored inside the
                    // entity doc itself (no new collection => Firestore rules unchanged); ids
                    // older than 30 days / beyond 1000 entries are pruned to keep the doc small.
                    val opId = "${com.grocerypos.v11.DeviceTag.current}-${entry.id}-${entry.createdAt}"
                    val docRef = db.collection(collection).document(entry.entityId)
                    val nowTs = System.currentTimeMillis()
                    val updatedAtValue: Any = map["updatedAt"] ?: nowTs
                    val branchNow = BranchConfigStore.current
                    db.runTransaction { tx ->
                        val snap = tx.get(docRef)
                        @Suppress("UNCHECKED_CAST")
                        val applied = (snap.get("appliedOps") as? Map<String, Any?>) ?: emptyMap()
                        if (applied.containsKey(opId)) return@runTransaction null
                        val cutoff = nowTs - 30L * 24 * 60 * 60 * 1000
                        val kept = LinkedHashMap<String, Any?>()
                        applied.entries
                            .filter { (it.value as? Number)?.toLong()?.let { t -> t >= cutoff } == true }
                            .sortedBy { (it.value as Number).toLong() }
                            .takeLast(999)
                            .forEach { kept[it.key] = it.value }
                        kept[opId] = nowTs
                        tx.set(
                            docRef,
                            mapOf(
                                fieldName to com.google.firebase.firestore.FieldValue.increment(delta),
                                "updatedAt" to updatedAtValue,
                                "branchId" to branchNow,
                                "appliedOps" to kept
                            ),
                            com.google.firebase.firestore.SetOptions.mergeFields(fieldName, "updatedAt", "branchId", "appliedOps")
                        )
                        null
                    }.await()
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val rawMap = gson.fromJson(entry.payloadJson, Map::class.java) as Map<String, Any?>
                    // FIX (bulk stuck-item repair): see the increment_stock/increment_balance
                    // branch above for why this is always re-stamped with the CURRENT branch
                    // code rather than trusting whatever was in the payload when this entry
                    // was originally queued (which may predate correct Branch Code / cloud
                    // project setup on this device, e.g. items stuck permanently on
                    // PERMISSION_DENIED after 10 retries — see stuck()/resetAllStuck() in
                    // SyncQueueDao). This heals old bad entries automatically on their next
                    // successful push, no manual per-item edit needed.
                    val incomingUpdatedAt = (rawMap["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                    val map = rawMap + mapOf("branchId" to BranchConfigStore.current, "updatedAt" to incomingUpdatedAt)
                    val docRef = db.collection(collection).document(entry.entityId)
                    db.runTransaction { tx ->
                        val snap = tx.get(docRef)
                        val serverUpdatedAt = (snap.get("updatedAt") as? Number)?.toLong() ?: Long.MIN_VALUE
                        // Last-write-wins: a delayed/offline device cannot overwrite a
                        // newer cloud edit merely because its queue item arrived later.
                        if (incomingUpdatedAt >= serverUpdatedAt) {
                            tx.set(docRef, map, com.google.firebase.firestore.SetOptions.merge())
                        }
                        null
                    }.await()
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
        val units: List<Map<String, Any?>> = emptyList(),
        val categories: List<Map<String, Any?>> = emptyList(),
        val zakatYears: List<Map<String, Any?>> = emptyList(),
        val zakatPayments: List<Map<String, Any?>> = emptyList(),
        val returns: List<Map<String, Any?>> = emptyList(),
        val stockMovements: List<Map<String, Any?>> = emptyList(),
        val appSettings: List<Map<String, Any?>> = emptyList(),
        val cashRegisters: List<Map<String, Any?>> = emptyList(),
        // NEW (Shell Ledger sync)
        val shellCustomers: List<Map<String, Any?>> = emptyList(),
        val shellTransactions: List<Map<String, Any?>> = emptyList(),
        val shopEmptyShellLogs: List<Map<String, Any?>> = emptyList(),
        val serverTime: Long = System.currentTimeMillis()
    )

    suspend fun pull(context: Context, since: Long): PullResult {
        val db = firestoreFor(context) ?: return PullResult(serverTime = since)
        val branchId = BranchConfigStore.current
        if (branchId.isBlank()) {
            throw BranchNotConfiguredException(
                Loc.t(context, "No branch code is configured on this device — open Settings > Cloud Sync Setup and save a branch code before syncing.", "اس ڈیوائس پر برانچ کوڈ سیٹ نہیں ہے — Sync سے پہلے Settings > Cloud Sync Setup میں برانچ کوڈ محفوظ کریں۔")
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
        val unitsSnap = query("units").get(Source.SERVER).await()
        val categoriesSnap = query("categories").get(Source.SERVER).await()
        val zakatYearsSnap = query("zakat_years").get(Source.SERVER).await()
        val zakatPaymentsSnap = query("zakat_payments").get(Source.SERVER).await()
        val returnsSnap = query("returns").get(Source.SERVER).await()
        val stockMovementsSnap = query("stock_movements").get(Source.SERVER).await()
        val appSettingsSnap = query("app_settings").get(Source.SERVER).await()
        val cashRegisterSnap = query("cash_register").get(Source.SERVER).await()
        val shellCustomersSnap = query("shell_customers").get(Source.SERVER).await()
        val shellTransactionsSnap = query("shell_transactions").get(Source.SERVER).await()
        val shopEmptyShellLogSnap = query("shop_empty_shell_log").get(Source.SERVER).await()

        val allSnaps = listOf(
            customersSnap, suppliersSnap, productsSnap, usersSnap,
            salesSnap, purchasesSnap, paymentsSnap, expensesSnap, cashTxSnap,
            unitsSnap, categoriesSnap, zakatYearsSnap, zakatPaymentsSnap, returnsSnap,
            stockMovementsSnap, appSettingsSnap, cashRegisterSnap,
            shellCustomersSnap, shellTransactionsSnap, shopEmptyShellLogSnap
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
            units = unitsSnap.documents.map { it.data ?: emptyMap() },
            categories = categoriesSnap.documents.map { it.data ?: emptyMap() },
            zakatYears = zakatYearsSnap.documents.map { it.data ?: emptyMap() },
            zakatPayments = zakatPaymentsSnap.documents.map { it.data ?: emptyMap() },
            returns = returnsSnap.documents.map { it.data ?: emptyMap() },
            stockMovements = stockMovementsSnap.documents.map { it.data ?: emptyMap() },
            appSettings = appSettingsSnap.documents.map { it.data ?: emptyMap() },
            cashRegisters = cashRegisterSnap.documents.map { it.data ?: emptyMap() },
            shellCustomers = shellCustomersSnap.documents.map { it.data ?: emptyMap() },
            shellTransactions = shellTransactionsSnap.documents.map { it.data ?: emptyMap() },
            shopEmptyShellLogs = shopEmptyShellLogSnap.documents.map { it.data ?: emptyMap() },
            serverTime = maxUpdatedAt
        )
    }

    // ---------- APPLY ----------

    // FIX (audit #6 — "Sync ka biggest remaining real-world test" / sync apply
    // transaction strategy): a pull can touch a dozen+ collections (customers,
    // products, sales, purchases, expenses, cash transactions, zakat, stock
    // movements, cash register, ...) in one go. The loops below used to run as
    // one un-grouped sequence of individual DAO writes — if the app crashed or
    // got killed partway through applying a pull (e.g. after products were
    // updated but before sales were), the local database was left part-old,
    // part-new: an inconsistent snapshot that never fully matches any point in
    // time on the server. Wrapped in db.withTransaction {} so an entire pull's
    // worth of changes lands atomically — either the whole batch applies, or
    // (on a crash) none of it does and the next pull retries cleanly from
    // wherever the local `since` cursor last was.
    suspend fun applyServerChanges(db: PosDatabase, changes: PullResult) {
        db.withTransaction {
            applyServerChangesLocked(db, changes)
        }
    }

    private suspend fun applyServerChangesLocked(db: PosDatabase, changes: PullResult) {
        val custDao = db.customerDao()
        val suppDao = db.supplierDao()
        val prodDao = db.productDao()
        val userDao = db.userDao()
        val saleDao = db.saleDao()
        val purchaseDao = db.purchaseDao()
        val paymentDao = db.paymentDao()
        val expenseDao = db.expenseDao()
        val cashTxDao = db.cashTransactionDao()
        val unitDao = db.unitDao()
        val categoryDao = db.categoryDao()
        val zakatDao = db.zakatDao()
        val returnDao = db.returnDao()
        val stockMovementDao = db.stockMovementDao()
        val appSettingDao = db.appSettingDao()
        val cashRegisterDao = db.cashRegisterDao()
        val shellDao = db.shellDao()

        // Local deltas that are still queued must be layered on top of the latest
        // server snapshot. Without this, a pull could temporarily erase an offline
        // sale/purchase/adjustment until the queued increment reached Firestore.
        suspend fun pendingDelta(entityType: String, entityId: String, operation: String): Double {
            return db.syncQueueDao().pendingForEntityAnyRetry(entityType, entityId, operation).asSequence()
                .sumOf { row ->
                    runCatching {
                        (gson.fromJson(row.payloadJson, Map::class.java)["delta"] as? Number)?.toDouble() ?: 0.0
                    }.getOrDefault(0.0)
                }
        }

        for (row in changes.customers) {
            if (row["_deleted"] == true) {
                (row["serverId"] as? String)?.let { custDao.findByServerId(it)?.let { c -> custDao.delete(c) } }
                continue
            }
            val serverId = row["serverId"] as? String ?: continue
            val name = row["name"] as? String ?: continue
            val phone = row["phone"] as? String ?: ""
            val balance = (row["balance"] as? Number)?.toDouble() ?: 0.0
            val creditLimit = (row["creditLimit"] as? Number)?.toDouble() ?: 0.0
            val openingBalance = (row["openingBalance"] as? Number)?.toDouble() ?: 0.0
            // NEW (Stuck Balance): null when the row came from a device/build that predates
            // this field — in that case keep OUR stuck amount instead of zeroing it.
            val stuckBalanceRemote = (row["stuckBalance"] as? Number)?.toDouble()
            val serverUpdatedAt = (row["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val localPendingBalance = pendingDelta("customer", serverId, "increment_balance")
            // FIX (2-device sync — same class of bug as the purchase/sale fix above):
            // a name/phone edit on THIS device is pushed as a separate "upsert" queue
            // entry. If a pull ran before that specific push confirmed, this loop used
            // to overwrite the local row from the server's still-old copy anyway — only
            // logging it as a "sync_conflict" audit entry instead of preventing it, even
            // though it's this device's own unconfirmed edit, not a real conflict from
            // another device. Skipping while that upsert is still pending leaves the
            // local edit intact; the very next successful push+pull resumes normally.
            if (db.syncQueueDao().pendingForEntityAnyRetry("customer", serverId, "upsert").isNotEmpty()) continue

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
                        name = name, phone = phone, balance = balance + localPendingBalance,
                        creditLimit = creditLimit, openingBalance = openingBalance,
                        stuckBalance = stuckBalanceRemote ?: existing.stuckBalance,
                        updatedAt = serverUpdatedAt, dirty = localPendingBalance != 0.0
                    )
                )
            } else {
                custDao.insert(
                    Customer(
                        name = name, phone = phone, balance = balance + localPendingBalance,
                        creditLimit = creditLimit, openingBalance = openingBalance,
                        stuckBalance = stuckBalanceRemote ?: 0.0,
                        serverId = serverId, updatedAt = serverUpdatedAt, dirty = localPendingBalance != 0.0
                    )
                )
            }
        }

        for (row in changes.suppliers) {
            if (row["_deleted"] == true) {
                (row["serverId"] as? String)?.let { suppDao.findByServerId(it)?.let { x -> suppDao.delete(x) } }
                continue
            }
            val serverId = row["serverId"] as? String ?: continue
            val name = row["name"] as? String ?: continue
            val phone = row["phone"] as? String ?: ""
            val balance = (row["balance"] as? Number)?.toDouble() ?: 0.0
            val openingBalance = (row["openingBalance"] as? Number)?.toDouble() ?: 0.0
            val serverUpdatedAt = (row["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val localPendingBalance = pendingDelta("supplier", serverId, "increment_balance")
            // FIX (2-device sync): see the matching customer-loop comment above — same
            // guard so a supplier rename/edit still waiting to push (e.g. exactly the
            // "Cash Purchase" -> "M Deen & brother's" edit reported) can't be reverted
            // by a pull that lands first.
            if (db.syncQueueDao().pendingForEntityAnyRetry("supplier", serverId, "upsert").isNotEmpty()) continue

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
                        name = name, phone = phone, balance = balance + localPendingBalance,
                        openingBalance = openingBalance,
                        updatedAt = serverUpdatedAt, dirty = localPendingBalance != 0.0
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
            if (row["_deleted"] == true) {
                (row["barcode"] as? String)?.let { prodDao.find(it)?.let { p -> prodDao.delete(p) } }
                continue
            }
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
            val defaultUnitIndex = (row["defaultUnitIndex"] as? Number)?.toInt() ?: -1
            val quickSaleDefaultUnitIndex = (row["quickSaleDefaultUnitIndex"] as? Number)?.toInt() ?: -1
            val searchTag = row["searchTag"] as? String ?: ""
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
            val serverUpdatedAt = (row["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val localPendingStock = pendingDelta("product", barcode, "increment_stock")
            // FIX (2-device sync): see the matching customer-loop comment above. Only
            // guards against a pending "upsert" (name/price/etc.) — deliberately NOT a
            // blanket pendingCountForEntity check, since a product almost always has
            // some pending "increment_stock" entry in flight from ordinary sales/
            // purchases; blocking on those too would stall product syncing constantly.
            if (db.syncQueueDao().pendingForEntityAnyRetry("product", barcode, "upsert").isNotEmpty()) continue

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
                        defaultUnitIndex = defaultUnitIndex,
                        quickSaleDefaultUnitIndex = quickSaleDefaultUnitIndex,
                        searchTag = searchTag,
                        stock = (stock ?: existing.stock) + localPendingStock,
                        dirty = localPendingStock != 0.0, updatedAt = serverUpdatedAt
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
                        tertiaryUnitQty = tertiaryUnitQty, defaultUnitIndex = defaultUnitIndex,
                        quickSaleDefaultUnitIndex = quickSaleDefaultUnitIndex,
                        searchTag = searchTag,
                        stock = (stock ?: 0.0) + localPendingStock,
                        dirty = localPendingStock != 0.0, updatedAt = serverUpdatedAt
                    )
                )
            }
        }

        for (row in changes.users) {
            if (row["_deleted"] == true) {
                (row["username"] as? String)?.let { userDao.delete(it) }
                continue
            }
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
            if (row["_deleted"] == true) {
                saleDao.deleteItems(invoice)
                saleDao.deleteSale(invoice)
                continue
            }
            // FIX (purchase/sale name reverts after sync): don't clobber a local edit
            // to this sale that's still waiting to push — see
            // SyncQueueDao.pendingCountForEntity's comment. Matches
            // SyncQueueHelper.saleEntityId()'s "sale:$invoice" format directly rather
            // than building a throwaway Sale just to call that function.
            if (db.syncQueueDao().pendingCountForEntity("sale", "sale:$invoice") > 0) continue
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
                updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                dirty = false,
                // NEW (Due Date Reminders): pull the reminder date set on either device.
                dueDate = (row["dueDate"] as? Number)?.toLong() ?: 0L,
                // NEW (Improvement Pack P2): keep whatever saleUid the creating device
                // assigned, instead of generating a fresh one here — a fresh fallback is
                // only used for a sale synced from before this field existed.
                saleUid = (row["saleUid"] as? String)?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
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
                        conversionFactor = (im["conversionFactor"] as? Number)?.toDouble() ?: 0.0,
                        // NEW (Improvement Pack P2): see the Sale.saleUid pull above.
                        lineUid = (im["lineUid"] as? String)?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
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
            if (row["_deleted"] == true) {
                purchaseDao.deleteItems(billNo)
                purchaseDao.deletePurchase(billNo)
                continue
            }
            // FIX (purchase name reverts after sync — "M Deen & brother's" back to
            // "Cash Purchase"): don't clobber a local edit to this purchase (e.g.
            // attaching/changing its supplier) that's still waiting to push. Without
            // this, a pull landing before that specific push confirms can silently
            // restore the old server copy — wiping supplierId back to null while the
            // supplier's own balance (synced separately as a delta, see pendingDelta()
            // above) stays correct, which is exactly the mismatch this fixes. See
            // SyncQueueDao.pendingCountForEntity's comment.
            if (db.syncQueueDao().pendingCountForEntity("purchase", "purchase:$billNo") > 0) continue
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
                updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                dirty = false,
                // NEW (Improvement Pack P2): see the Sale.saleUid pull above.
                purchaseUid = (row["purchaseUid"] as? String)?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
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
                        conversionFactor = (im["conversionFactor"] as? Number)?.toDouble() ?: 0.0,
                        // NEW (Improvement Pack P2): see the Sale.saleUid pull above.
                        lineUid = (im["lineUid"] as? String)?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                        // FIX (item name / retail-wholesale rate "gayab" after sync): pull
                        // this row's own name/rate snapshot down too — see purchaseJson()'s
                        // matching push in SyncQueueHelper.kt and PurchaseItem.itemName's
                        // comment in Database.kt.
                        itemName = im["itemName"] as? String ?: "",
                        retailRate = (im["retailRate"] as? Number)?.toDouble() ?: 0.0,
                        wholesaleRate = (im["wholesaleRate"] as? Number)?.toDouble() ?: 0.0
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
            if (row["_deleted"] == true) {
                expenseDao.findByServerId(serverId)?.let { expenseDao.delete(it) }
                continue
            }
            val category = row["category"] as? String ?: continue
            val description = row["description"] as? String ?: ""
            val amount = (row["amount"] as? Number)?.toDouble() ?: 0.0
            val createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            // FIX (Bug 2 — Cash in Hand): older pushes (pre-migration) won't carry
            // "method" at all, so fall back to "cash" — same default the column itself
            // uses, and what those expenses actually were before this fix existed.
            val method = row["method"] as? String ?: "cash"

            val existing = expenseDao.findByServerId(serverId)
            if (existing != null) {
                expenseDao.update(
                    existing.copy(
                        category = category, description = description, amount = amount, method = method,
                        createdAt = createdAt, updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: createdAt, dirty = false
                    )
                )
            } else {
                expenseDao.insert(
                    Expense(
                        category = category, description = description, amount = amount, method = method,
                        createdAt = createdAt, serverId = serverId,
                        updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: createdAt, dirty = false
                    )
                )
            }
        }

        for (row in changes.payments) {
            val serverId = row["serverId"] as? String ?: continue
            if (row["_deleted"] == true) {
                paymentDao.deleteByServerId(serverId)
                continue
            }
            val reference = row["reference"] as? String ?: continue
            val partyType = row["partyType"] as? String ?: ""
            val partyId = (row["partyId"] as? Number)?.toLong()
            val amount = (row["amount"] as? Number)?.toDouble() ?: 0.0
            val method = row["method"] as? String ?: ""
            val note = row["note"] as? String ?: ""
            val billReference = row["billReference"] as? String ?: ""
            val createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()

            val existing = paymentDao.findByServerId(serverId)
            if (existing != null) {
                paymentDao.update(
                    existing.copy(
                        reference = reference, partyType = partyType, partyId = partyId,
                        amount = amount, method = method, note = note, billReference = billReference, createdAt = createdAt,
                        updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: createdAt, dirty = false
                    )
                )
            } else {
                paymentDao.insert(
                    Payment(
                        reference = reference, partyType = partyType, partyId = partyId,
                        amount = amount, method = method, note = note, billReference = billReference, createdAt = createdAt,
                        serverId = serverId, updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: createdAt, dirty = false
                    )
                )
            }
        }

        for (row in changes.cashTransactions) {
            val serverId = row["serverId"] as? String ?: continue
            if (row["_deleted"] == true) {
                cashTxDao.deleteByServerId(serverId)
                continue
            }
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
                        updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: createdAt, dirty = false
                    )
                )
            } else {
                cashTxDao.insert(
                    CashTransaction(
                        type = type, method = method, amount = amount, reason = reason,
                        reference = reference, createdAt = createdAt, serverId = serverId,
                        updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: createdAt, dirty = false
                    )
                )
            }
        }

        // NEW (Units/Categories master-list sync): name IS the key (same shape as
        // the products loop above), so this is just an insert-if-missing / delete —
        // no other field to merge or compare, unlike every other entity above.
        for (row in changes.units) {
            val name = row["name"] as? String ?: continue
            if (row["_deleted"] == true) {
                unitDao.deleteByName(name)
                continue
            }
            unitDao.insert(UnitType(name))
        }

        for (row in changes.categories) {
            val name = row["name"] as? String ?: continue
            if (row["_deleted"] == true) {
                categoryDao.deleteByName(name)
                continue
            }
            categoryDao.insert(Category(name))
        }

        // NEW (Zakat sync): years MUST be applied before payments in this same call,
        // since a freshly-pulled payment's parent year (zakatYearServerId) needs to
        // already exist locally for the lookup below to succeed.
        for (row in changes.zakatYears) {
            val serverId = row["serverId"] as? String ?: continue
            if (row["_deleted"] == true) {
                zakatDao.findYearByServerId(serverId)?.let { /* no deleteYear() exists — Zakat years are never deleted from the UI */ }
                continue
            }
            val startDate = (row["startDate"] as? Number)?.toLong() ?: continue
            val endDate = (row["endDate"] as? Number)?.toLong() ?: continue
            val assetsSnapshot = (row["assetsSnapshot"] as? Number)?.toDouble() ?: 0.0
            val totalPayable = (row["totalPayable"] as? Number)?.toDouble() ?: 0.0
            // NEW (Zakat currency/calendar): default to "Rs"/"islamic" for rows pulled
            // from a server document written before this field existed.
            val currency = row["currency"] as? String ?: "Rs"
            val calendarType = row["calendarType"] as? String ?: "islamic"
            val createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: createdAt

            val existing = zakatDao.findYearByServerId(serverId)
            if (existing != null) {
                zakatDao.updateYear(
                    existing.copy(
                        startDate = startDate, endDate = endDate, assetsSnapshot = assetsSnapshot,
                        totalPayable = totalPayable, currency = currency, calendarType = calendarType,
                        updatedAt = updatedAt, dirty = false
                    )
                )
            } else {
                zakatDao.insertYear(
                    ZakatYear(
                        startDate = startDate, endDate = endDate, assetsSnapshot = assetsSnapshot,
                        totalPayable = totalPayable, currency = currency, calendarType = calendarType,
                        createdAt = createdAt, serverId = serverId, updatedAt = updatedAt, dirty = false
                    )
                )
            }
        }

        for (row in changes.zakatPayments) {
            val serverId = row["serverId"] as? String ?: continue
            if (row["_deleted"] == true) {
                zakatDao.findPaymentByServerId(serverId)?.let {
                    // No deletePayment() exists — Zakat payments are never deleted from
                    // the UI, so a tombstone here has nothing to do locally yet.
                }
                continue
            }
            val yearServerId = row["zakatYearServerId"] as? String ?: continue
            // The parent year may not have reached this device yet (pull order isn't
            // guaranteed across collections) — skip for now, it'll resolve on a later
            // pull once the year row itself has synced down.
            val localYear = zakatDao.findYearByServerId(yearServerId) ?: continue
            val amount = (row["amount"] as? Number)?.toDouble() ?: 0.0
            val method = row["method"] as? String ?: ""
            val note = row["note"] as? String ?: ""
            // NEW (Zakat payment date + category): fall back to createdAt/blank for rows
            // pulled from a server document written before these fields existed.
            val category = row["category"] as? String ?: ""
            val createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val paymentDate = (row["paymentDate"] as? Number)?.toLong() ?: createdAt
            val updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: createdAt

            val existing = zakatDao.findPaymentByServerId(serverId)
            if (existing != null) {
                zakatDao.updatePayment(
                    existing.copy(
                        zakatYearId = localYear.id, amount = amount, method = method, note = note,
                        category = category, paymentDate = paymentDate, updatedAt = updatedAt, dirty = false
                    )
                )
            } else {
                zakatDao.insertPayment(
                    ZakatPayment(
                        zakatYearId = localYear.id, amount = amount, method = method, note = note,
                        category = category, paymentDate = paymentDate,
                        createdAt = createdAt, serverId = serverId, updatedAt = updatedAt, dirty = false
                    )
                )
            }
        }

        // NEW (Returns sync): append-only ledger, no delete/edit UI exists for a
        // return — so this is just an insert-if-not-already-pulled (idempotent
        // across repeated pulls via findByServerId), same shape as the products/
        // customers loops but with nothing to conflict-check against.
        for (row in changes.returns) {
            val serverId = row["serverId"] as? String ?: continue
            if (returnDao.findByServerId(serverId) != null) continue
            val reference = row["reference"] as? String ?: continue
            val type = row["type"] as? String ?: continue
            val barcode = row["barcode"] as? String ?: continue
            val qty = (row["qty"] as? Number)?.toDouble() ?: 0.0
            val amount = (row["amount"] as? Number)?.toDouble() ?: 0.0
            val createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: createdAt
            returnDao.insert(
                ReturnLine(
                    reference = reference, type = type, barcode = barcode, qty = qty, amount = amount,
                    createdAt = createdAt, serverId = serverId, updatedAt = updatedAt, dirty = false
                )
            )
        }

        // NEW (Stock/Cost History sync): append-only ledger, same insert-if-not-
        // already-pulled idempotency as the returns loop just above — Stock History
        // and Cost History both read straight off this one table (see
        // StockMovementDao.forProduct()/costHistoryForProduct()), so applying it here
        // once is enough for both screens to pick up a movement made on another device.
        for (row in changes.stockMovements) {
            val serverId = row["serverId"] as? String ?: continue
            if (stockMovementDao.findByServerId(serverId) != null) continue
            val barcode = row["barcode"] as? String ?: continue
            val type = row["type"] as? String ?: continue
            val qty = (row["qty"] as? Number)?.toDouble() ?: continue
            val unit = row["unit"] as? String ?: ""
            val cost = (row["cost"] as? Number)?.toDouble() ?: 0.0
            val reference = row["reference"] as? String ?: ""
            val note = row["note"] as? String ?: ""
            val createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: createdAt
            // FIX (duplicate PURCHASE/SALE row in Stock History): this row this device
            // itself just wrote locally (moments before its own push completed) hasn't
            // been stamped with serverId yet, so the findByServerId() check above can't
            // recognize it as "already have this" — it used to fall through to a second
            // INSERT here, showing e.g. one purchase as two identical +2160 Pcs rows.
            // Claim the existing unclaimed local row instead of inserting a duplicate.
            val unclaimed = stockMovementDao.findUnclaimedMatch(barcode, type, reference, qty, createdAt)
            if (unclaimed != null) {
                stockMovementDao.update(unclaimed.copy(serverId = serverId, updatedAt = updatedAt))
                continue
            }
            stockMovementDao.insert(
                StockMovement(
                    barcode = barcode, type = type, qty = qty, unit = unit, cost = cost,
                    reference = reference, note = note, createdAt = createdAt,
                    serverId = serverId, updatedAt = updatedAt, dirty = false
                )
            )
        }

        // NEW (Shell Ledger sync): create-or-update by serverId, last-write-wins on
        // pull — same shape as the zakatYears loop above (a plain whole-row snapshot,
        // no increment_* delta semantics like Customer.balance needs).
        for (row in changes.shellCustomers) {
            val serverId = row["serverId"] as? String ?: continue
            val name = row["name"] as? String ?: continue
            val phone = row["phone"] as? String ?: ""
            val shellsOwed = (row["shellsOwed"] as? Number)?.toInt() ?: 0
            val createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: createdAt

            val existing = shellDao.findCustomerByServerId(serverId)
            if (existing != null) {
                shellDao.updateCustomer(
                    existing.copy(
                        name = name, phone = phone, shellsOwed = shellsOwed,
                        updatedAt = updatedAt, dirty = false
                    )
                )
            } else {
                shellDao.insertCustomer(
                    ShellCustomer(
                        name = name, phone = phone, shellsOwed = shellsOwed,
                        createdAt = createdAt, serverId = serverId, updatedAt = updatedAt, dirty = false
                    )
                )
            }
        }

        // NEW (Shell Ledger sync): append-only ledger, same insert-if-not-already-
        // pulled idempotency as the returns/stockMovements loops above. Links back to
        // the local customerId by looking up the customer's own serverId (customerId
        // itself is a per-device local autoincrement — see shellTransactionJson).
        for (row in changes.shellTransactions) {
            val serverId = row["serverId"] as? String ?: continue
            if (shellDao.findTransactionByServerId(serverId) != null) continue
            val customerServerId = row["customerServerId"] as? String ?: continue
            val localCustomer = shellDao.findCustomerByServerId(customerServerId) ?: continue
            val type = row["type"] as? String ?: continue
            val qty = (row["qty"] as? Number)?.toInt() ?: continue
            val note = row["note"] as? String ?: ""
            val createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: createdAt
            shellDao.insertTransaction(
                ShellTransaction(
                    customerId = localCustomer.id, type = type, qty = qty, note = note,
                    createdAt = createdAt, serverId = serverId, updatedAt = updatedAt, dirty = false
                )
            )
        }

        // NEW (Shell Ledger sync): same insert-if-not-already-pulled idempotency —
        // the shop's own empty-shell count log has no cross-table link to resolve.
        for (row in changes.shopEmptyShellLogs) {
            val serverId = row["serverId"] as? String ?: continue
            if (shellDao.findShopLogByServerId(serverId) != null) continue
            val delta = (row["delta"] as? Number)?.toInt() ?: continue
            val reason = row["reason"] as? String ?: continue
            val note = row["note"] as? String ?: ""
            val createdAt = (row["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val updatedAt = (row["updatedAt"] as? Number)?.toLong() ?: createdAt
            shellDao.insertShopLog(
                ShopEmptyShellLog(
                    delta = delta, reason = reason, note = note, createdAt = createdAt,
                    serverId = serverId, updatedAt = updatedAt, dirty = false
                )
            )
        }

        // NEW (App Settings sync): only whitelisted keys are ever pushed (see
        // SyncQueueHelper.SYNCED_APP_SETTING_KEYS), so nothing extra to filter here —
        // whatever arrives in this collection is safe to apply. Skips a key that has
        // a pending local push still in flight, same guard as the products loop above,
        // so a pull can't stomp on an edit this device made seconds ago but hasn't
        // pushed yet.
        for (row in changes.appSettings) {
            val key = row["key"] as? String ?: continue
            if (db.syncQueueDao().pendingForEntityAnyRetry("app_setting", key, "upsert").isNotEmpty()) continue
            val value = row["value"] as? String ?: continue
            appSettingDao.set(AppSetting(key, value))
        }

        // NEW (Cash Register sync): date is the doc id AND the local PK, so this is a
        // plain upsert-by-date — no findByServerId lookup needed (same shape as
        // units/categories above). No delete branch: CashRegisterActivity never
        // deletes a register (only opens/edits/closes/reopens), so no "_deleted"
        // tombstone is ever produced for this collection — mirrors zakat_years'
        // "no deleteYear() exists" note above. Skipped while this device's own
        // open/edit/close/reopen for that date is still queued to push, so a pull
        // landing mid-edit can't revert what was just typed in on this device.
        // FIX (cross-device OPEN REGISTER race): was pendingForEntityAnyRetry(...,
        // "upsert") specifically, which stopped covering the OPEN action once it
        // started queuing as "create_if_absent" instead (see
        // SyncQueueHelper.enqueueCashRegisterCreate). pendingCountForEntity checks
        // for ANY pending push regardless of operation, so this guard now holds for
        // OPEN too, and still self-heals correctly once that create's push settles
        // (won or lost against another device).
        for (row in changes.cashRegisters) {
            val date = row["date"] as? String ?: continue
            if (db.syncQueueDao().pendingCountForEntity("cash_register", date) > 0) continue
            val openingCash = (row["openingCash"] as? Number)?.toDouble() ?: 0.0
            val closingCash = (row["closingCash"] as? Number)?.toDouble() ?: 0.0
            val openingBank = (row["openingBank"] as? Number)?.toDouble() ?: 0.0
            val closingBank = (row["closingBank"] as? Number)?.toDouble() ?: 0.0
            val closed = row["closed"] as? Boolean ?: false
            cashRegisterDao.upsert(
                CashRegister(
                    date = date, openingCash = openingCash, closingCash = closingCash,
                    openingBank = openingBank, closingBank = closingBank, closed = closed
                )
            )
        }
    }
}
