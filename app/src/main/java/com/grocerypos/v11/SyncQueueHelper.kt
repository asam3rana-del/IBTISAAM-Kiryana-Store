package com.grocerypos.v11

import android.content.Context
import com.google.gson.Gson
import com.grocerypos.v11.sync.SyncWorker

object SyncQueueHelper {

    private val gson = Gson()

    fun customerEntityId(c: Customer) = "customer:${DeviceTag.current}-${c.id}"
    fun supplierEntityId(s: Supplier) = "supplier:${DeviceTag.current}-${s.id}"
    fun productEntityId(p: Product) = p.barcode
    fun saleEntityId(sale: Sale) = "sale:${sale.invoice}"
    fun purchaseEntityId(purchase: Purchase) = "purchase:${purchase.billNo}"
    fun paymentEntityId(payment: Payment) = "payment:${payment.reference}"
    fun expenseEntityId(expense: Expense) = "expense:${DeviceTag.current}-${expense.id}"
    fun cashTransactionEntityId(t: CashTransaction) = "cash_transaction:${DeviceTag.current}-${t.id}"
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

    suspend fun enqueueCustomer(db: PosDatabase, c: Customer, context: Context? = null) {
        val id = customerEntityId(c)
        if (c.serverId != id) db.customerDao().update(c.copy(serverId = id))
        enqueue(db, "customer", id, "upsert", customerJson(c))
        context?.let { trigger(it) }
    }

    suspend fun enqueueSupplier(db: PosDatabase, s: Supplier, context: Context? = null) {
        val id = supplierEntityId(s)
        if (s.serverId != id) db.supplierDao().update(s.copy(serverId = id))
        enqueue(db, "supplier", id, "upsert", supplierJson(s))
        context?.let { trigger(it) }
    }

    suspend fun enqueueProduct(db: PosDatabase, p: Product, context: Context? = null) {
        enqueue(db, "product", productEntityId(p), "upsert", productJson(p))
        context?.let { trigger(it) }
    }

    suspend fun adjustCustomerBalance(db: PosDatabase, customerId: Long, amount: Double) {
        db.customerDao().addBalance(customerId, amount)
        db.customerDao().find(customerId)?.let { enqueueCustomer(db, it) }
    }

    suspend fun adjustSupplierBalance(db: PosDatabase, supplierId: Long, amount: Double) {
        db.supplierDao().addBalance(supplierId, amount)
        db.supplierDao().find(supplierId)?.let { enqueueSupplier(db, it) }
    }

    suspend fun decreaseProductStock(db: PosDatabase, barcode: String, qty: Double): Int {
        val rows = db.productDao().decrease(barcode, qty)
        db.productDao().find(barcode)?.let { enqueueProduct(db, it) }
        return rows
    }

    suspend fun decreaseProductStockForce(db: PosDatabase, barcode: String, qty: Double) {
        db.productDao().decreaseForce(barcode, qty)
        db.productDao().find(barcode)?.let { enqueueProduct(db, it) }
    }

    suspend fun increaseProductStock(db: PosDatabase, barcode: String, qty: Double) {
        db.productDao().increase(barcode, qty)
        db.productDao().find(barcode)?.let { enqueueProduct(db, it) }
    }

    suspend fun enqueueSale(db: PosDatabase, sale: Sale, context: Context? = null) {
        enqueue(db, "sale", saleEntityId(sale), "upsert", saleJson(db, sale))
        context?.let { trigger(it) }
    }

    suspend fun enqueuePurchase(db: PosDatabase, purchase: Purchase, context: Context? = null) {
        enqueue(db, "purchase", purchaseEntityId(purchase), "upsert", purchaseJson(db, purchase))
        context?.let { trigger(it) }
    }

    suspend fun enqueuePayment(db: PosDatabase, payment: Payment, context: Context? = null) {
        enqueue(db, "payment", paymentEntityId(payment), "upsert", paymentJson(payment))
        context?.let { trigger(it) }
    }

    suspend fun enqueueExpense(db: PosDatabase, expense: Expense, context: Context? = null) {
        val id = expenseEntityId(expense)
        val stamped = if (expense.serverId != id) expense.copy(serverId = id) else expense
        if (stamped !== expense) db.expenseDao().update(stamped)
        enqueue(db, "expense", id, "upsert", expenseJson(stamped))
        context?.let { trigger(it) }
    }

    suspend fun enqueueCashTransaction(db: PosDatabase, t: CashTransaction, context: Context? = null) {
        val id = cashTransactionEntityId(t)
        val stamped = if (t.serverId != id) t.copy(serverId = id) else t
        if (stamped !== t) db.cashTransactionDao().update(stamped)
        enqueue(db, "cash_transaction", id, "upsert", cashTransactionJson(stamped))
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

    suspend fun saleJson(db: PosDatabase, sale: Sale): String {
        val items = db.saleDao().itemsForInvoice(sale.invoice)
        val itemMaps = items.map {
            mapOf(
                "barcode" to it.barcode, "product" to it.product, "qty" to it.qty,
                "unit" to it.unit, "unitPrice" to it.unitPrice, "cost" to it.cost, "amount" to it.amount
            )
        }
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
            "itemCount" to items.size,
            "items" to itemMaps,
            "updatedAt" to System.currentTimeMillis(),
            "branchId" to com.grocerypos.v11.BuildConfig.BRANCH_ID
        )
        return gson.toJson(map)
    }

    suspend fun purchaseJson(db: PosDatabase, purchase: Purchase): String {
        val items = db.purchaseDao().itemsForBill(purchase.billNo)
        val itemMaps = items.map {
            mapOf(
                "barcode" to it.barcode, "qty" to it.qty, "unit" to it.unit,
                "unitCost" to it.unitCost, "amount" to it.amount
            )
        }
        val map = mapOf(
            "serverId" to purchaseEntityId(purchase),
            "billNo" to purchase.billNo,
            "supplierId" to purchase.supplierId,
            "subtotal" to purchase.subtotal,
            "discount" to purchase.discount,
            "total" to purchase.total,
            "paid" to purchase.paid,
            "createdAt" to purchase.createdAt,
            "status" to purchase.status,
            "itemCount" to items.size,
            "items" to itemMaps,
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
