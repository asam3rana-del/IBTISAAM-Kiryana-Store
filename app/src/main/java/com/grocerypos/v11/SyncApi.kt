package com.grocerypos.v11.sync

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Customer
import com.grocerypos.v11.Supplier

/**
 * Firebase Firestore based sync layer.
 *
 * Firestore collections used:
 *   customers/{serverId}
 *   suppliers/{serverId}
 *   products/{barcode}
 *   users/{serverId}
 *   sales/{serverId}              (push-only, see FIX note below)
 *   purchases/{serverId}          (push-only)
 *   payments/{serverId}           (push-only)
 *   expenses/{serverId}           (push-only)
 *   cash_transactions/{serverId}  (push-only)
 */
object SyncApi {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val gson by lazy { com.google.gson.Gson() }

    // ---------- PUSH ----------

    // FIX (sync bug #1): "sale", "purchase", "payment", "expense", "cash_transaction"
    // were missing from this map entirely. SyncQueueHelper already builds JSON for all
    // of them (saleJson, purchaseJson, paymentJson, expenseJson, cashTransactionJson),
    // but push() fell through to `else -> return false` for every one of them, so any
    // queued row of these types could never succeed and just kept retrying forever.
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

    // NOTE: sales/purchases/payments/expenses/cash_transactions are intentionally NOT
    // pulled back down here. Each branch creates these records locally first (that's
    // why they end up dirty/queued to push) — pulling them back would just be re-reading
    // your own data. It would also be lossy for sales/purchases, since saleJson()/
    // purchaseJson() only send an itemCount, not the actual sale_items/purchase_items
    // rows, so a pulled-back sale could never be reconstructed correctly in Room.
    // If you later need cross-branch reporting (branch A seeing branch B's sales),
    // that's a separate read-only reporting feature, not a two-way sync of this table.
    data class PullResult(
        val customers: List<Map<String, Any?>> = emptyList(),
        val suppliers: List<Map<String, Any?>> = emptyList(),
        val products: List<Map<String, Any?>> = emptyList(),
        val users: List<Map<String, Any?>> = emptyList(),
        val serverTime: Long = System.currentTimeMillis()
    )

    // FIX (Phase 3 - Online / multi-branch): each collection query also filters by
    // "branchId" == this build's BuildConfig.BRANCH_ID, so a branch only ever pulls down
    // its own customers/suppliers/products/users instead of every branch's data mixed
    // together. NOTE (migration): documents pushed before this fix have no "branchId"
    // field at all, so they won't match this filter and won't be pulled by ANY branch
    // going forward — if you have pre-fix data in Firestore you want to keep, either
    // backfill a branchId onto those documents once, or just let each branch's local
    // Room data (which was already there before this change) keep serving as the source
    // of truth and let fresh pushes repopulate Firestore correctly from here on.
    suspend fun pull(since: Long): PullResult {
        val branchId = com.grocerypos.v11.BuildConfig.BRANCH_ID
        // NOTE: combining whereEqualTo("branchId",...) with whereGreaterThan("updatedAt",...)
        // needs a Firestore composite index per collection. The first time this runs,
        // Firestore's error message includes a direct link to auto-create the missing
        // index — just tap it once per collection (customers/suppliers/products/users).

        val customersSnap = db.collection("customers")
            .whereEqualTo("branchId", branchId)
            .whereGreaterThan("updatedAt", since)
            .get(Source.SERVER)
            .await()

        val suppliersSnap = db.collection("suppliers")
            .whereEqualTo("branchId", branchId)
            .whereGreaterThan("updatedAt", since)
            .get(Source.SERVER)
            .await()

        val productsSnap = db.collection("products")
            .whereEqualTo("branchId", branchId)
            .whereGreaterThan("updatedAt", since)
            .get(Source.SERVER)
            .await()

        val usersSnap = db.collection("users")
            .whereEqualTo("branchId", branchId)
            .whereGreaterThan("updatedAt", since)
            .get(Source.SERVER)
            .await()

        return PullResult(
            customers = customersSnap.documents.map { it.data ?: emptyMap() },
            suppliers = suppliersSnap.documents.map { it.data ?: emptyMap() },
            products = productsSnap.documents.map { it.data ?: emptyMap() },
            users = usersSnap.documents.map { it.data ?: emptyMap() },
            serverTime = System.currentTimeMillis()
        )
    }

    // ---------- APPLY ----------

    suspend fun applyServerChanges(db: PosDatabase, changes: PullResult) {
        val custDao = db.customerDao()
        val suppDao = db.supplierDao()
        val prodDao = db.productDao()
        val userDao = db.userDao()

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
    }
}
