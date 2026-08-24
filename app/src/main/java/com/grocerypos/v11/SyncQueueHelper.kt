package com.grocerypos.v11

import android.content.Context
import com.google.gson.Gson
import com.grocerypos.v11.sync.SyncWorker

object SyncQueueHelper {

    private val gson = Gson()

    fun customerEntityId(c: Customer) = "customer:${c.id}"
    fun supplierEntityId(s: Supplier) = "supplier:${s.id}"
    fun productEntityId(p: Product) = p.barcode
    fun saleEntityId(sale: Sale) = "sale:${sale.invoice}"
    fun purchaseEntityId(purchase: Purchase) = "purchase:${purchase.billNo}"
    fun paymentEntityId(payment: Payment) = "payment:${payment.reference}"
    fun expenseEntityId(expense: Expense) = "expense:${expense.id}"
    fun cashTransactionEntityId(t: CashTransaction) = "cash_transaction:${t.id}"
    fun userEntityId(u: User) = "user:${u.username}"

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
            "serverId" to customerEntityId(c),
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
            "serverId" to supplierEntityId(s),
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
            "barcode" to productEntityId(p),
            "name" to p.name,
            "stock" to p.stock,
            "updatedAt" to System.currentTimeMillis()
        )
        return gson.toJson(map)
    }

    fun saleJson(sale: Sale, itemCount: Int): String {
        val map = mapOf(
            "serverId" to saleEntityId(sale),
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

    fun purchaseJson(purchase: Purchase, itemCount: Int): String {
        val map = mapOf(
            "serverId" to purchaseEntityId(purchase),
            "billNo" to purchase.billNo,
            "supplierId" to purchase.supplierId,
            "subtotal" to purchase.subtotal,
            "discount" to purchase.discount,
            "total" to purchase.total,
            "paid" to purchase.paid,
            "createdAt" to purchase.createdAt,
            "itemCount" to itemCount,
            "updatedAt" to System.currentTimeMillis()
        )
        return gson.toJson(map)
    }

    fun paymentJson(payment: Payment): String {
        val map = mapOf(
            "serverId" to paymentEntityId(payment),
            "reference" to payment.reference,
            "partyType" to payment.partyType,
            "partyId" to payment.partyId,
            "amount" to payment.amount,
            "method" to payment.method,
            "note" to payment.note,
            "updatedAt" to System.currentTimeMillis()
        )
        return gson.toJson(map)
    }

    fun expenseJson(expense: Expense): String {
        val map = mapOf(
            "serverId" to expenseEntityId(expense),
            "category" to expense.category,
            "description" to expense.description,
            "amount" to expense.amount,
            "createdAt" to expense.createdAt,
            "updatedAt" to System.currentTimeMillis()
        )
        return gson.toJson(map)
    }

    fun cashTransactionJson(t: CashTransaction): String {
        val map = mapOf(
            "serverId" to cashTransactionEntityId(t),
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

    // NOTE: passwordHash is deliberately excluded so it never sits in Firestore.
    fun userJson(u: User): String {
        val map = mapOf(
            "serverId" to userEntityId(u),
            "username" to u.username,
            "displayName" to u.displayName,
            "role" to u.role,
            "phone" to u.phone,
            "active" to u.active,
            "updatedAt" to System.currentTimeMillis()
        )
        return gson.toJson(map)
    }
}
