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

    data class PullResult(
        val customers: List<Map<String, Any?>> = emptyList(),
        val suppliers: List<Map<String, Any?>> = emptyList(),
        val products: List<Map<String, Any?>> = emptyList(),
        val users: List<Map<String, Any?>> = emptyList(),
        val serverTime: Long = System.currentTimeMillis()
    )

    suspend fun pull(since: Long): PullResult {
        val customersSnap = db.collection("customers")
            .whereGreaterThan("updatedAt", since)
            .get(Source.SERVER)
            .await()

        val suppliersSnap = db.collection("suppliers")
            .whereGreaterThan("updatedAt", since)
            .get(Source.SERVER)
            .await()

        val productsSnap = db.collection("products")
            .whereGreaterThan("updatedAt", since)
            .get(Source.SERVER)
            .await()

        val usersSnap = db.collection("users")
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
                val stock = (row["stock"] as? Number)?.toInt() ?: existing.stock
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
