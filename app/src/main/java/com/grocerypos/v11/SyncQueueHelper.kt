package com.grocerypos.v11

import android.content.Context
import com.google.gson.Gson
import com.grocerypos.v11.sync.SyncWorker

object SyncQueueHelper {

    private val gson = Gson()

    suspend fun enqueue(db: PosDatabase, entityType: String, entityId: String, operation: String, payloadJson: String) {
        db.syncQueueDao().enqueue(
            SyncQueueEntry(
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payloadJson = payloadJson
            )
        )
    }

    fun trigger(context: Context) {
        SyncWorker.syncNowOnce(context)
    }

    fun customerJson(c: Customer): String {
        val map = mapOf(
            "serverId" to "customer:${c.id}",
            "name" to c.name,
            "phone" to c.phone,
            "balance" to c.balance,
            "creditLimit" to c.creditLimit,
            "openingBalance" to c.openingBalance,
            "updatedAt" to System.currentTimeMillis()
        )
        return gson.toJson(map)
    }

    fun supplierJson(s: Supplier): String {
        val map = mapOf(
            "serverId" to "supplier:${s.id}",
            "name" to s.name,
            "phone" to s.phone,
            "balance" to s.balance,
            "openingBalance" to s.openingBalance,
            "updatedAt" to System.currentTimeMillis()
        )
        return gson.toJson(map)
    }

    fun productJson(p: Product): String {
        val map = mapOf(
            "barcode" to p.barcode,
            "name" to p.name,
            "stock" to p.stock,
            "updatedAt" to System.currentTimeMillis()
        )
        return gson.toJson(map)
    }

    fun saleJson(sale: Sale, itemCount: Int): String {
        val map = mapOf(
            "serverId" to "sale:${sale.invoice}",
            "invoice" to sale.invoice,
            "customerId" to sale.customerId,
            "subtotal" to sale.subtotal,
            "discount" to sale.discount,
            "total" to sale.total,
            "paid" to sale.paid,
            "paymentMethod" to sale.paymentMethod,
            "saleType" to sale.saleType,
            "createdAt" to sale.createdAt,
            "status" to sale.status,
            "itemCount" to itemCount,
            "updatedAt" to System.currentTimeMillis()
        )
        return gson.toJson(map)
    }

    fun cashTransactionJson(t: CashTransaction): String {
        val map = mapOf(
            "serverId" to "cash_transaction:${t.id}",
            "type" to t.type,
            "method" to t.method,
            "amount" to t.amount,
            "reason" to t.reason,
            "reference" to t.reference,
            "createdAt" to t.createdAt,
            "updatedAt" to System.currentTimeMillis()
        )
        return gson.toJson(map)
    }
}
