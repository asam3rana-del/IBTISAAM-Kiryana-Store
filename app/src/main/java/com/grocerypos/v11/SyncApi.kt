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
 * Firestore collections used (Firestore creates them automatically on first
 * write — no manual setup needed):
 *   customers/{serverId}
 *   suppliers/{serverId}
 *   products/{barcode}
 *
 * Prerequisites (one-time project setup, not in this file — see build.gradle.kts):
 *   1. Add google-services.json to the app module folder
 *   2. app-level build.gradle.kts: com.google.gms.google-services plugin +
 *      firebase-bom + firebase-firestore-ktx + kotlinx-coroutines-play-services
 *   3. Enable Firestore in the Firebase console (Build > Firestore Database > Create database)
 */
object SyncApi {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val gson by lazy { com.google.gson.Gson() }

    // ---------- PUSH: send one local change up to Firestore ----------

    suspend fun push(entry: com.grocerypos.v11.SyncQueueEntry): Boolean {
        return try {
            val collection = when (entry.entityType) {
                "customer" -> "customers"
                "supplier" -> "suppliers"
                "product" -> "products"
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

    // ---------- PULL: fetch everything changed since last sync ----------

    data class PullResult(
        val customers: List<Map<String, Any?>> = emptyList(),
        val suppliers: List<Map<String, Any?>> = emptyList(),
        val products: List<Map<String, Any?>> = emptyList(),
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

        return PullResult(
            customers = customersSnap.documents.map { it.data ?: emptyMap() },
            suppliers = suppliersSnap.documents.map { it.data ?: emptyMap() },
            products = productsSnap.documents.map { it.data ?: emptyMap() },
            serverTime = System.currentTimeMillis()
        )
    }

    // ---------- Apply pulled changes into the local Room database ----------
    // Upserts by serverId (customers/suppliers) or barcode (products) instead
    // of blind insert, so repeated pulls update the existing row rather than
    // creating duplicates.

    suspend fun applyServerChanges(db: PosDatabase, changes: PullResult) {
        val custDao = db.customerDao()
        val suppDao = db.supplierDao()
        val prodDao = db.productDao()

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
                        name = name,
                        phone = phone,
                        balance = balance,
                        creditLimit = creditLimit,
                        openingBalance = openingBalance,
                        updatedAt = System.currentTimeMillis(),
                        dirty = false
                    )
                )
            } else {
                custDao.insert(
                    Customer(
                        name = name,
                        phone = phone,
                        balance = balance,
                        creditLimit = creditLimit,
                        openingBalance = openingBalance,
                        serverId = serverId,
                        updatedAt = System.currentTimeMillis(),
                        dirty = false
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
                        name = name,
                        phone = phone,
                        balance = balance,
                        openingBalance = openingBalance,
                        updatedAt = System.currentTimeMillis(),
                        dirty = false
                    )
                )
            } else {
                suppDao.insert(
                    Supplier(
                        name = name,
                        phone = phone,
                        balance = balance,
                        openingBalance = openingBalance,
                        serverId = serverId,
                        updatedAt = System.currentTimeMillis(),
                        dirty = false
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
            // If it doesn't exist locally yet, it's skipped here — products are
            // expected to originate on-device (barcode is the primary key), so a
            // brand-new product from the server with no local row is unusual.
            // If you do want new products created from a pull, add a
            // prodDao.upsert(Product(...)) branch here with all required fields.
        }
    }
}
