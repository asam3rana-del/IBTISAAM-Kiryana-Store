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

    // ---- ADDED: PurchaseActivity.kt calls SyncQueueHelper.purchaseJson(purchaseRecord, itemCount)
    // in proceedSave() — mirrors saleJson's shape for the purchase side of the ledger. ----
    fun purchaseJson(purchase: Purchase, itemCount: Int): String {
        val map = mapOf(
            "serverId" to "purchase:${purchase.billNo}",
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

    // ---- ADDED: PurchaseActivity.kt calls SyncQueueHelper.paymentJson(payment) after
    // inserting a Payment record for the supplier-side payment on a purchase. ----
    fun paymentJson(payment: Payment): String {
        val map = mapOf(
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

    // ---- ADDED: ExpenseActivity.kt calls SyncQueueHelper.expenseJson(expense) right after
    // inserting the expense, using entityId "expense:${expense.id}". ----
    fun expenseJson(expense: Expense): String {
        val map = mapOf(
            "serverId" to "expense:${expense.id}",
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
