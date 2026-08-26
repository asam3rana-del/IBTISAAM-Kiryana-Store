package com.grocerypos.v11

import android.content.Context
import com.google.gson.Gson
import com.grocerypos.v11.sync.SyncWorker

// FIX (Phase 3 - Online): every ...Json() builder below stamps its payload with
// "branchId" (BuildConfig.BRANCH_ID) before it's pushed to Firestore, so records from
// different branches can be told apart / filtered on pull (see SyncApi.kt).
//
// FIX (sync bug #2): previously NOTHING called db.syncQueueDao().enqueue(...) anywhere
// in the app, so sync_queue stayed empty forever no matter how many sales/customers/etc.
// were created. Below, every ...Json() builder now has a matching enqueueX() function
// that builds the JSON AND inserts the sync_queue row AND triggers a sync — in one call.
//
// Call the relevant enqueueX() right after each successful DAO insert/update, e.g.:
//
//     val newId = db.customerDao().insert(customer)
//     SyncQueueHelper.enqueueCustomer(db, customer.copy(id = newId))
//
//     db.saleDao().sale(sale)
//     db.saleDao().items(saleItems)
//     SyncQueueHelper.enqueueSale(db, sale, saleItems.size)
//
// Do this at every insert/update call site for: customers, suppliers, products, sales,
// purchases, payments, expenses, cash_transactions, users. Deletes should call
// SyncQueueHelper.enqueueDelete(db, entityType, entityId) instead.
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

    // ---------- One-call helpers: build payload + enqueue ----------
    // (context is optional — pass it if you want the sync to fire immediately instead
    // of waiting for the next periodic run; omit it to just queue the row.)

    suspend fun enqueueCustomer(db: PosDatabase, c: Customer, context: Context? = null) {
        enqueue(db, "customer", customerEntityId(c), "upsert", customerJson(c))
        context?.let { trigger(it) }
    }

    suspend fun enqueueSupplier(db: PosDatabase, s: Supplier, context: Context? = null) {
        enqueue(db, "supplier", supplierEntityId(s), "upsert", supplierJson(s))
        context?.let { trigger(it) }
    }

    suspend fun enqueueProduct(db: PosDatabase, p: Product, context: Context? = null) {
        enqueue(db, "product", productEntityId(p), "upsert", productJson(p))
        context?.let { trigger(it) }
    }

    suspend fun enqueueSale(db: PosDatabase, sale: Sale, itemCount: Int, context: Context? = null) {
        enqueue(db, "sale", saleEntityId(sale), "upsert", saleJson(sale, itemCount))
        context?.let { trigger(it) }
    }

    suspend fun enqueuePurchase(db: PosDatabase, purchase: Purchase, itemCount: Int, context: Context? = null) {
        enqueue(db, "purchase", purchaseEntityId(purchase), "upsert", purchaseJson(purchase, itemCount))
        context?.let { trigger(it) }
    }

    suspend fun enqueuePayment(db: PosDatabase, payment: Payment, context: Context? = null) {
        enqueue(db, "payment", paymentEntityId(payment), "upsert", paymentJson(payment))
        context?.let { trigger(it) }
    }

    suspend fun enqueueExpense(db: PosDatabase, expense: Expense, context: Context? = null) {
        enqueue(db, "expense", expenseEntityId(expense), "upsert", expenseJson(expense))
        context?.let { trigger(it) }
    }

    suspend fun enqueueCashTransaction(db: PosDatabase, t: CashTransaction, context: Context? = null) {
        enqueue(db, "cash_transaction", cashTransactionEntityId(t), "upsert", cashTransactionJson(t))
        context?.let { trigger(it) }
    }

    suspend fun enqueueUser(db: PosDatabase, u: User, context: Context? = null) {
        enqueue(db, "user", userEntityId(u), "upsert", userJson(u))
        context?.let { trigger(it) }
    }

    /** Use for any entity delete (e.g. deleting a customer or product). */
    suspend fun enqueueDelete(db: PosDatabase, entityType: String, entityId: String, context: Context? = null) {
        enqueue(db, entityType, entityId, "delete", "{}")
        context?.let { trigger(it) }
    }

    // ---------- Payload builders ----------

    fun customerJson(c: Customer): String {
        val map = mapOf(
            "serverId" to customerEntityId(c),
            "name" to c.name,
            "phone" to c.phone,
            "balance" to c.balance,
            "creditLimit" to c.creditLimit,
            "openingBalance" to c.openingBalance,
            "updatedAt" to System.currentTimeMillis(),
            "branchId" to com.grocerypos.v11.BuildConfig.BRANCH_ID
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
            "updatedAt" to System.currentTimeMillis(),
            "branchId" to com.grocerypos.v11.BuildConfig.BRANCH_ID
        )
        return gson.toJson(map)
    }

    fun productJson(p: Product): String {
        val map = mapOf(
            "barcode" to productEntityId(p),
            "name" to p.name,
            "stock" to p.stock,
            "updatedAt" to System.currentTimeMillis(),
            "branchId" to com.grocerypos.v11.BuildConfig.BRANCH_ID
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
            "updatedAt" to System.currentTimeMillis(),
            "branchId" to com.grocerypos.v11.BuildConfig.BRANCH_ID
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
            "updatedAt" to System.currentTimeMillis(),
            "branchId" to com.grocerypos.v11.BuildConfig.BRANCH_ID
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
            "updatedAt" to System.currentTimeMillis(),
            "branchId" to com.grocerypos.v11.BuildConfig.BRANCH_ID
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
            "updatedAt" to System.currentTimeMillis(),
            "branchId" to com.grocerypos.v11.BuildConfig.BRANCH_ID
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
            "updatedAt" to System.currentTimeMillis(),
            "branchId" to com.grocerypos.v11.BuildConfig.BRANCH_ID
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
            "updatedAt" to System.currentTimeMillis(),
            "branchId" to com.grocerypos.v11.BuildConfig.BRANCH_ID
        )
        return gson.toJson(map)
    }
}
