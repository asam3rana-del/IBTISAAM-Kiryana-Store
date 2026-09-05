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

    // FIX (multi-device sync bug): customer/supplier/expense/cash_transaction/payment
    // IDs are built from a LOCAL autoincrement id, which two different devices can and
    // will generate identically (e.g. both devices' first customer is local id=1) —
    // without DeviceTag mixed in, one device's Firestore doc would silently overwrite
    // the other's. Sale/Purchase/Product/User don't need this: their keys (invoice,
    // billNo, barcode, username) are already naturally unique, not local-id-based.
    fun customerEntityId(c: Customer) = "customer:${DeviceTag.current}-${c.id}"
    fun supplierEntityId(s: Supplier) = "supplier:${DeviceTag.current}-${s.id}"
    fun productEntityId(p: Product) = p.barcode
    fun saleEntityId(sale: Sale) = "sale:${sale.invoice}"
    fun purchaseEntityId(purchase: Purchase) = "purchase:${purchase.billNo}"
    // NOTE: payment.reference is the invoice / billNo it belongs to — NOT unique on its
    // own, since a single invoice can receive multiple separate payments (e.g. a
    // customer pays 5,000 then later 3,000 against the same invoice). payment.id (a
    // local autoincrement, like customer/supplier/expense/cash_transaction) plus
    // DeviceTag is what's actually unique — see FIX (payment sync incomplete) at
    // paymentJson() below.
    fun paymentEntityId(payment: Payment) = "payment:${DeviceTag.current}-${payment.id}"
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

    // ---------- One-call helpers: build payload + enqueue ----------
    // (context is optional — pass it if you want the sync to fire immediately instead
    // of waiting for the next periodic run; omit it to just queue the row.)

    // FIX (self-duplication on pull): entityId is deterministic (built purely from the
    // local row's own id), but the local row's own `serverId` column was never being
    // set to that same value — so on the very next pull, this device wouldn't recognize
    // its own just-pushed record (findByServerId would find nothing) and would insert
    // it again as a brand-new "pulled" row, duplicating it locally. Stamping serverId
    // onto the local row immediately (no need to wait for the actual network push,
    // since the id is computed locally and doesn't depend on the server) fixes this.
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

    // ---------- Balance / stock adjustment wrappers ----------
    // FIX (sync bug #3 — the big one): db.customerDao().addBalance()/supplierDao()
    // .addBalance()/productDao().decrease()/increase()/decreaseForce() are raw SQL
    // UPDATE queries used everywhere a sale/purchase/return/edit/delete changes a
    // balance or stock level. NONE of these ever enqueued a sync row, so a customer's
    // running balance and a product's running stock — the two numbers that matter most
    // for two devices sharing one register — never actually synced, even though the
    // customer/supplier/product record itself looked like it was "syncing" whenever it
    // was first created. Use these wrappers instead of calling the DAO methods
    // directly, wherever a balance or stock change should be visible on other devices.
    //
    // FIX (conflict-safe sync): these used to push the ABSOLUTE resulting balance/stock
    // as a plain snapshot (via enqueueCustomer/enqueueSupplier/enqueueProduct). That's
    // fine when only one device ever touches a given customer/product between syncs —
    // but if TWO devices are offline at the same time and both sell from the same
    // product (or both adjust the same customer's balance), whichever device's snapshot
    // happens to push LAST silently overwrites the other's — the earlier device's sale
    // effect on stock/balance is lost even though the sale record itself is safe.
    // Now these send the DELTA as a Firestore FieldValue.increment() operation instead
    // (see SyncApi.push()'s "increment_stock"/"increment_balance" handling) — Firestore
    // applies increments from multiple offline devices atomically and additively on its
    // own servers, regardless of what order they arrive in, so no device's contribution
    // is ever lost. `stock` and `balance` are deliberately excluded from
    // productJson()/customerJson()/supplierJson() below for the same reason: those
    // full-snapshot payloads must never carry these two fields again, or a routine name/
    // price edit could clobber a correctly-merged server value back to a stale local one.
    suspend fun adjustCustomerBalance(db: PosDatabase, customerId: Long, amount: Double) {
        db.customerDao().addBalance(customerId, amount)
        val c = db.customerDao().find(customerId) ?: return
        enqueue(db, "customer", customerEntityId(c), "increment_balance", balanceDeltaJson(amount))
    }

    suspend fun adjustSupplierBalance(db: PosDatabase, supplierId: Long, amount: Double) {
        db.supplierDao().addBalance(supplierId, amount)
        val s = db.supplierDao().find(supplierId) ?: return
        enqueue(db, "supplier", supplierEntityId(s), "increment_balance", balanceDeltaJson(amount))
    }

    // ADDED (Inventory Accounting upgrade — stock_movements table): every stock
    // change funnels through these three wrappers (plus enqueueProductOpeningStock
    // below), so this is the one place that needs to write the stock_movements
    // ledger row for the new Stock History / Cost History screens — no call site
    // elsewhere needs to touch StockMovementDao directly.
    //
    // `type` is a short caps tag identifying WHY stock moved (e.g. "SALE",
    // "PURCHASE", "SALE_REVERSAL", "PURCHASE_EDIT", "OPENING_STOCK" — see each call
    // site's comment for the full list in use). `reference` is the invoice/billNo
    // this movement belongs to, so a movement can be traced back to the actual
    // sale/purchase document. `unitCost` is optional — pass it explicitly at any
    // call site that has ALREADY computed the product's new cost-per-unit right
    // before calling this (every purchase-related call site does, since
    // SyncQueueHelper.updateProductCost() is always called right alongside); when
    // omitted (sales, which never change cost), the product's current `cost`
    // field is used as-is.
    suspend fun decreaseProductStock(db: PosDatabase, barcode: String, qty: Double, type: String, reference: String = "", unitCost: Double? = null, note: String = ""): Int {
        val rows = db.productDao().decrease(barcode, qty)
        if (rows > 0) {
            enqueue(db, "product", barcode, "increment_stock", stockDeltaJson(-qty))
            logMovement(db, barcode, type, -qty, reference, unitCost, note)
        }
        return rows
    }

    suspend fun decreaseProductStockForce(db: PosDatabase, barcode: String, qty: Double, type: String, reference: String = "", unitCost: Double? = null, note: String = "") {
        db.productDao().decreaseForce(barcode, qty)
        enqueue(db, "product", barcode, "increment_stock", stockDeltaJson(-qty))
        logMovement(db, barcode, type, -qty, reference, unitCost, note)
    }

    suspend fun increaseProductStock(db: PosDatabase, barcode: String, qty: Double, type: String, reference: String = "", unitCost: Double? = null, note: String = "") {
        db.productDao().increase(barcode, qty)
        enqueue(db, "product", barcode, "increment_stock", stockDeltaJson(qty))
        logMovement(db, barcode, type, qty, reference, unitCost, note)
    }

    private suspend fun logMovement(db: PosDatabase, barcode: String, type: String, signedQty: Double, reference: String, unitCost: Double?, note: String) {
        val p = db.productDao().find(barcode)
        db.stockMovementDao().insert(
            StockMovement(
                barcode = barcode,
                type = type,
                qty = signedQty,
                unit = p?.smallestUnitName() ?: "",
                cost = unitCost ?: (p?.cost ?: 0.0),
                reference = reference,
                note = note,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    // ADDED: db.productDao().updateCost() was being called directly in HistoryActivity
    // (return reversal) and PurchaseRepository (delete/edit reversal + new-purchase
    // weighted-avg update) — none of these enqueued a sync row, so a product's cost
    // silently diverged between devices after any purchase edit/delete/return. Unlike
    // stock/balance, cost is NOT excluded from productJson()'s full snapshot, so a plain
    // "upsert" (not a delta) is correct here — no increment_cost handling needed in SyncApi.
    suspend fun updateProductCost(db: PosDatabase, barcode: String, newCost: Double) {
        db.productDao().updateCost(barcode, newCost)
        val p = db.productDao().find(barcode) ?: return
        enqueue(db, "product", productEntityId(p), "upsert", productJson(p))
    }

    // ADDED for the increment fix above: a new product's opening stock has nowhere
    // else to go now that productJson() never carries "stock" — this treats the
    // opening stock the same as any other delta, incrementing up from an implicit 0
    // (Firestore's FieldValue.increment() on a field that doesn't exist yet on the
    // document starts from 0, so this correctly sets the very first value too).
    suspend fun enqueueProductOpeningStock(db: PosDatabase, barcode: String, qty: Double, unitCost: Double? = null) {
        if (qty == 0.0) return
        enqueue(db, "product", barcode, "increment_stock", stockDeltaJson(qty))
        logMovement(db, barcode, "OPENING_STOCK", qty, "", unitCost, "")
    }

    private fun stockDeltaJson(delta: Double): String {
        val map = mapOf(
            "delta" to delta,
            "updatedAt" to System.currentTimeMillis(),
            "branchId" to com.grocerypos.v11.BuildConfig.BRANCH_ID
        )
        return gson.toJson(map)
    }

    private fun balanceDeltaJson(delta: Double): String {
        val map = mapOf(
            "delta" to delta,
            "updatedAt" to System.currentTimeMillis(),
            "branchId" to com.grocerypos.v11.BuildConfig.BRANCH_ID
        )
        return gson.toJson(map)
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
        val id = paymentEntityId(payment)
        val stamped = if (payment.serverId != id) payment.copy(serverId = id) else payment
        if (stamped !== payment) db.paymentDao().update(stamped)
        enqueue(db, "payment", id, "upsert", paymentJson(stamped))
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
            // FIX (conflict-safe sync): "balance" deliberately excluded — see the big
            // comment above adjustCustomerBalance(). balance is now ONLY ever touched
            // via the increment_balance operation, never overwritten by a full snapshot.
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
            // FIX (conflict-safe sync): "balance" excluded — same reasoning as customerJson.
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
            // FIX (product sync incomplete): the rest of Product's editable fields were
            // never actually added here — only barcode/name/updatedAt/branchId went out,
            // so a product created/edited on one device showed up on another with a
            // name and barcode but no price, category, or unit info. "stock" and
            // "openingStock" remain deliberately excluded — see the big comment above
            // decreaseProductStock()/increaseProductStock(). stock is ONLY ever touched
            // via the increment_stock operation (including a brand-new product's opening
            // stock — see enqueueProductOpeningStock()), never overwritten by a full
            // snapshot like this one.
            "category" to p.category,
            "cost" to p.cost,
            "salePrice" to p.salePrice,
            "wholesalePrice" to p.wholesalePrice,
            "reorderLevel" to p.reorderLevel,
            "expiry" to p.expiry,
            "unit" to p.unit,
            "unitSize" to p.unitSize,
            "unitNote" to p.unitNote,
            "secondaryUnit" to p.secondaryUnit,
            "secondaryUnitQty" to p.secondaryUnitQty,
            "tertiaryUnit" to p.tertiaryUnit,
            "tertiaryUnitQty" to p.tertiaryUnitQty,
            "updatedAt" to System.currentTimeMillis(),
            "branchId" to com.grocerypos.v11.BuildConfig.BRANCH_ID
        )
        return gson.toJson(map)
    }

    // FIX (multi-device: sales/purchases weren't two-way syncable): previously only
    // itemCount was sent, so even if a sale/purchase document was ever pulled back
    // down to another device, there weren't enough details to reconstruct the actual
    // sale_items/purchase_items rows — the other device would see a total but not
    // what was actually sold/bought. Now sends the full item list. Suspend + takes db
    // because it needs to look the items up itself (every call site already has both
    // in scope, so nothing else needs to change).
    // FIX (cross-device party linkage): customerId/supplierId here used to be the raw
    // local Room autoincrement Long (sale.customerId / purchase.supplierId). That id is
    // only meaningful on the device that created it — customer id=5 on this device and
    // customer id=5 on another device are unrelated rows. Pushing the raw number meant
    // every sale/purchase pulled onto a different device silently linked to whatever
    // (wrong, or nonexistent) local customer/supplier happened to have that same id —
    // corrupting party ledgers/balances on the receiving device without any error.
    // Now resolves and sends the customer's/supplier's stable serverId string instead
    // (same one customerEntityId()/supplierEntityId() computes — deterministic, doesn't
    // require the customer to have synced yet); the pull side below resolves it back to
    // whatever local id it maps to on that device via findByServerId().
    suspend fun saleJson(db: PosDatabase, sale: Sale): String {
        val items = db.saleDao().itemsForInvoice(sale.invoice)
        val itemMaps = items.map {
            mapOf(
                "barcode" to it.barcode, "product" to it.product, "qty" to it.qty,
                "unit" to it.unit, "unitPrice" to it.unitPrice, "cost" to it.cost, "amount" to it.amount,
                // FIX (historical unit conversion bug): carry the transaction-time
                // conversion factor across devices too — otherwise a sale pulled onto
                // another device would have no conversionFactor recorded, and that
                // device's later delete/edit/return would silently fall back to ITS
                // current (possibly different) product config. See Database.kt's
                // SaleItem.smallestQty() comment.
                "conversionFactor" to it.conversionFactor
            )
        }
        val customerServerId = sale.customerId?.let { db.customerDao().find(it)?.let { c -> customerEntityId(c) } }
        val map = mapOf(
            "serverId" to saleEntityId(sale),
            "invoice" to sale.invoice,
            "customerServerId" to customerServerId,
            "subtotal" to sale.subtotal,
            "discount" to sale.discount,
            "total" to sale.total,
            "paid" to sale.paid,
            "paymentMethod" to sale.paymentMethod,
            "saleType" to sale.saleType,
            "createdAt" to sale.createdAt,
            "status" to sale.status,
            // NEW (Due Date Reminders): synced like every other Sale field so a due
            // date set on one device (from DueRemindersActivity) shows up on the other.
            "dueDate" to sale.dueDate,
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
                "unitCost" to it.unitCost, "amount" to it.amount,
                // FIX (historical unit conversion bug): see saleJson()'s itemMaps above.
                "conversionFactor" to it.conversionFactor
            )
        }
        val supplierServerId = purchase.supplierId?.let { db.supplierDao().find(it)?.let { s -> supplierEntityId(s) } }
        val map = mapOf(
            "serverId" to purchaseEntityId(purchase),
            "billNo" to purchase.billNo,
            "supplierServerId" to supplierServerId,
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
            "createdAt" to payment.createdAt,
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
