package com.grocerypos.v11.sync

import androidx.room.withTransaction
import com.grocerypos.v11.*
import org.json.JSONObject

/**
 * Wraps each write in db.withTransaction { } so the actual insert
 * and the sync_queue enqueue happen atomically — kill the app mid-save
 * and either both happened or neither did.
 */
class SyncRepository(private val db: PosDatabase) {

    suspend fun saveSale(sale: Sale, items: List<SaleItem>) {
        db.withTransaction {
            db.saleDao().sale(sale)
            db.saleDao().items(items)
            db.syncQueueDao().enqueue(
                SyncQueueEntry(
                    entityType = "sale",
                    entityId = sale.invoice,
                    operation = "create",
                    payloadJson = saleJson(sale, items)
                )
            )
        }
        appContextRef?.let { SyncWorker.triggerNow(it) }
    }

    suspend fun savePurchase(purchase: Purchase, items: List<PurchaseItem>) {
        db.withTransaction {
            db.purchaseDao().purchase(purchase)
            db.purchaseDao().items(items)
            db.syncQueueDao().enqueue(
                SyncQueueEntry(
                    entityType = "purchase",
                    entityId = purchase.billNo,
                    operation = "create",
                    payloadJson = purchaseJson(purchase, items)
                )
            )
        }
        appContextRef?.let { SyncWorker.triggerNow(it) }
    }

    suspend fun saveCustomer(customer: Customer, isUpdate: Boolean): Long {
        var newId = customer.id
        db.withTransaction {
            newId = if (isUpdate) {
                db.customerDao().update(customer.copy(dirty = true, updatedAt = System.currentTimeMillis()))
                customer.id
            } else {
                db.customerDao().insert(customer.copy(dirty = true, updatedAt = System.currentTimeMillis()))
            }
            db.syncQueueDao().enqueue(
                SyncQueueEntry(
                    entityType = "customer",
                    entityId = "customer:$newId",
                    operation = if (isUpdate) "update" else "create",
                    payloadJson = JSONObject().apply {
                        put("id", newId); put("name", customer.name); put("phone", customer.phone)
                        put("creditLimit", customer.creditLimit); put("balance", customer.balance)
                    }.toString()
                )
            )
        }
        appContextRef?.let { SyncWorker.triggerNow(it) }
        return newId
    }

    suspend fun saveSupplier(supplier: Supplier, isUpdate: Boolean): Long {
        var newId = supplier.id
        db.withTransaction {
            newId = if (isUpdate) {
                db.supplierDao().update(supplier.copy(dirty = true, updatedAt = System.currentTimeMillis()))
                supplier.id
            } else {
                db.supplierDao().insert(supplier.copy(dirty = true, updatedAt = System.currentTimeMillis()))
            }
            db.syncQueueDao().enqueue(
                SyncQueueEntry(
                    entityType = "supplier",
                    entityId = "supplier:$newId",
                    operation = if (isUpdate) "update" else "create",
                    payloadJson = JSONObject().apply {
                        put("id", newId); put("name", supplier.name); put("phone", supplier.phone)
                        put("balance", supplier.balance)
                    }.toString()
                )
            )
        }
        appContextRef?.let { SyncWorker.triggerNow(it) }
        return newId
    }

    suspend fun savePayment(payment: Payment) {
        db.withTransaction {
            db.paymentDao().insert(payment.copy(dirty = true, updatedAt = System.currentTimeMillis()))
            db.syncQueueDao().enqueue(
                SyncQueueEntry(
                    entityType = "payment",
                    entityId = payment.reference,
                    operation = "create",
                    payloadJson = JSONObject().apply {
                        put("reference", payment.reference); put("partyType", payment.partyType)
                        put("partyId", payment.partyId); put("amount", payment.amount)
                        put("method", payment.method); put("note", payment.note)
                    }.toString()
                )
            )
        }
        appContextRef?.let { SyncWorker.triggerNow(it) }
    }

    suspend fun saveExpense(expense: Expense) {
        db.withTransaction {
            db.expenseDao().insert(expense.copy(dirty = true, updatedAt = System.currentTimeMillis()))
            db.syncQueueDao().enqueue(
                SyncQueueEntry(
                    entityType = "expense",
                    entityId = "expense:${expense.id}",
                    operation = "create",
                    payloadJson = JSONObject().apply {
                        put("category", expense.category); put("description", expense.description)
                        put("amount", expense.amount)
                    }.toString()
                )
            )
        }
        appContextRef?.let { SyncWorker.triggerNow(it) }
    }

    private fun saleJson(sale: Sale, items: List<SaleItem>) = JSONObject().apply {
        put("invoice", sale.invoice)
        put("customerId", sale.customerId)
        put("subtotal", sale.subtotal)
        put("discount", sale.discount)
        put("tax", sale.tax)
        put("total", sale.total)
        put("paid", sale.paid)
        put("paymentMethod", sale.paymentMethod)
        put("saleType", sale.saleType)
        put("createdAt", sale.createdAt)
        put("status", sale.status)
        put("itemCount", items.size)
    }.toString()

    private fun purchaseJson(purchase: Purchase, items: List<PurchaseItem>) = JSONObject().apply {
        put("billNo", purchase.billNo)
        put("supplierId", purchase.supplierId)
        put("total", purchase.total)
        put("paid", purchase.paid)
        put("subtotal", purchase.subtotal)
        put("discount", purchase.discount)
        put("createdAt", purchase.createdAt)
        put("status", purchase.status)
        put("itemCount", items.size)
    }.toString()

    companion object {
        var appContextRef: android.content.Context? = null
    }
}
