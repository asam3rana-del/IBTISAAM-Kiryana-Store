package com.grocerypos.v11.sync

import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Customer
import com.grocerypos.v11.Supplier
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

data class PushRequest(val entityType: String, val entityId: String, val operation: String, val payload: String)
data class PushResponse(val accepted: Boolean, val serverId: String? = null)
data class PullResponse(
    val customers: List<Map<String, Any?>> = emptyList(),
    val suppliers: List<Map<String, Any?>> = emptyList(),
    val products: List<Map<String, Any?>> = emptyList(),
    val serverTime: Long = System.currentTimeMillis()
)

interface SyncEndpoints {
    @POST("sync/push")
    suspend fun push(@Body body: PushRequest): PushResponse

    @GET("sync/pull")
    suspend fun pull(@Query("since") since: Long): PullResponse
}

object SyncApi {

    private const val BASE_URL = "https://YOUR_SERVER_DOMAIN/api/" // TODO: set real base URL

    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val endpoints by lazy { retrofit.create(SyncEndpoints::class.java) }

    suspend fun push(entry: com.grocerypos.v11.SyncQueueEntry): Boolean {
        val resp = endpoints.push(
            PushRequest(
                entityType = entry.entityType,
                entityId = entry.entityId,
                operation = entry.operation,
                payload = entry.payloadJson
            )
        )
        return resp.accepted
    }

    suspend fun pull(since: Long): PullResponse = endpoints.pull(since)

    suspend fun applyServerChanges(db: PosDatabase, changes: PullResponse) {
        val custDao = db.customerDao()
        val suppDao = db.supplierDao()
        val prodDao = db.productDao()

        for (row in changes.customers) {
            val serverId = row["serverId"] as? String ?: continue
            val name = row["name"] as? String ?: continue
            val phone = row["phone"] as? String ?: ""
            val balance = (row["balance"] as? Number)?.toDouble() ?: 0.0
            // NOTE: add a findByServerId query to CustomerDao for real
            // upsert-by-serverId merge logic before wiring this to production.
            custDao.insert(
                Customer(
                    name = name,
                    phone = phone,
                    balance = balance,
                    serverId = serverId,
                    updatedAt = System.currentTimeMillis(),
                    dirty = false
                )
            )
        }

        for (row in changes.suppliers) {
            val serverId = row["serverId"] as? String ?: continue
            val name = row["name"] as? String ?: continue
            val phone = row["phone"] as? String ?: ""
            val balance = (row["balance"] as? Number)?.toDouble() ?: 0.0
            suppDao.insert(
                Supplier(
                    name = name,
                    phone = phone,
                    balance = balance,
                    serverId = serverId,
                    updatedAt = System.currentTimeMillis(),
                    dirty = false
                )
            )
        }

        for (row in changes.products) {
            val barcode = row["barcode"] as? String ?: continue
            val existing = prodDao.find(barcode)
            if (existing != null) {
                val stock = (row["stock"] as? Number)?.toInt() ?: existing.stock
                prodDao.upsert(existing.copy(stock = stock, dirty = false, updatedAt = System.currentTimeMillis()))
            }
        }
    }
}
