package com.grocerypos.v11.data

import android.content.Context
import androidx.room.withTransaction
import com.grocerypos.v11.CashTransaction
import com.grocerypos.v11.Customer
import com.grocerypos.v11.HeldBill
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.Sale
import com.grocerypos.v11.SaleItem
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.domain.SaleLine
import com.grocerypos.v11.isValidSmallestQty
import com.grocerypos.v11.smallestPerUnitOf
import com.grocerypos.v11.smallestQty
import com.grocerypos.v11.smallestUnitFactor
import com.grocerypos.v11.smallestUnitName
import com.grocerypos.v11.toSmallestUnits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Room-backed implementation of [SaleRepository] — the only place in the app
 * that should call SaleDao/ProductDao/CustomerDao/CashTransactionDao/HeldDao/
 * AppSettingDao for sale-related work. UseCases and SaleViewModel go through
 * the [SaleRepository] interface instead of touching PosDatabase directly,
 * and every write stays paired with its SyncQueueHelper call.
 *
 * (Split out from SaleRepository.kt, which now holds just the interface, so
 * SaveSaleUseCase/SaveQuickSaleUseCase/etc. can be unit tested against a
 * fake instead of this real Room-backed class — behavior here is unchanged.)
 *
 * [appContext] should be an application Context (not an Activity one) since
 * this repository is expected to outlive any single screen.
 */
class RoomSaleRepository(
    private val db: PosDatabase,
    private val appContext: Context
) : SaleRepository {

    override fun observeCustomers(): Flow<List<Customer>> = db.customerDao().all()

    override fun observeProducts(): Flow<List<Product>> = db.productDao().all()

    override suspend fun firmName(): String? = db.appSettingDao().get("shop_name")?.value

    override suspend fun findSale(invoice: String): Sale? = db.saleDao().findSale(invoice)

    override suspend fun itemsForInvoice(invoice: String): List<SaleItem> = db.saleDao().itemsForInvoice(invoice)

    override suspend fun customersSnapshot(): List<Customer> = db.customerDao().all().first()

    override suspend fun productsSnapshot(): List<Product> = db.productDao().all().first()

    override suspend fun topProductNames(sinceMillis: Long, uptoMillis: Long): List<String> = try {
        db.saleDao().topProducts(sinceMillis, uptoMillis).map { it.product }
    } catch (e: Exception) {
        emptyList()
    }

    override suspend fun createCustomer(name: String): Customer {
        val newCustomer = Customer(name = name)
        val newId = db.customerDao().insert(newCustomer)
        val saved = newCustomer.copy(id = newId)
        SyncQueueHelper.enqueueCustomer(db, saved)
        SyncQueueHelper.trigger(appContext)
        return saved
    }

    override suspend fun heldBills(): List<HeldBill> = db.heldDao().all().first()

    override suspend fun holdBill(holdId: String, payload: String) {
        db.heldDao().hold(HeldBill(holdId = holdId, payload = payload))
    }

    override suspend fun deleteHeldBill(bill: HeldBill) {
        db.heldDao().delete(bill)
    }

    /**
     * Persists a new or edited sale: reverses the original's stock/balance
     * effect (edit only), re-validates stock against current levels, writes
     * the sale + items, decrements stock, adjusts the customer's balance, and
     * logs a cash-in transaction — all inside one Room transaction (Phase 1
     * data-safety fix: a crash/kill partway through can no longer leave
     * stock, the sale row, and the customer balance out of sync with each
     * other).
     */
    override suspend fun saveSale(
        invoice: String,
        enteredCustomerName: String,
        existingCustomer: Customer?,
        saleType: String,
        method: String,
        saleDateMillis: Long,
        subtotal: Double,
        discount: Double,
        total: Double,
        paid: Double,
        lines: List<SaleLine>,
        original: Sale?,
        originalItems: List<SaleItem>
    ): SaleSaveResult {
        var customer = existingCustomer
        val stockWarnings = mutableListOf<String>()

        db.withTransaction {
            if (original != null) {
                originalItems.forEach { si ->
                    val p = db.productDao().find(si.barcode)
                    val smallestQty = si.smallestQty(p)
                    SyncQueueHelper.increaseProductStock(db, si.barcode, smallestQty, "SALE_EDIT_REVERSAL", invoice)
                }
                val originalOutstanding = original.total - original.paid
                if (original.customerId != null && originalOutstanding > 0) {
                    SyncQueueHelper.adjustCustomerBalance(db, original.customerId, -originalOutstanding)
                }
                db.saleDao().deleteItems(invoice)
                db.saleDao().deleteSale(invoice)
                db.cashTransactionDao().deleteByReference(invoice)
            }

            val productsByBarcode = mutableMapOf<String, Product>()
            for ((barcode, group) in lines.groupBy { it.barcode }) {
                val current = db.productDao().find(barcode)
                    ?: throw StockUnavailableException("Stock badal gaya hai — item nahi mila. Bill dobara check karen.")
                val neededSmallest = group.sumOf { current.toSmallestUnits(it.qty, it.unit) }
                if (current.stock < neededSmallest) {
                    throw StockUnavailableException(
                        "Stock badal gaya hai — \"${current.name}\" mein sirf ${formatQty(current.stock.toDouble())} ${current.smallestUnitName()} available hai. Bill dobara check karen."
                    )
                }
                productsByBarcode[barcode] = current
            }

            if (customer == null && enteredCustomerName.isNotEmpty()) {
                val newCustomer = Customer(name = enteredCustomerName)
                val newId = db.customerDao().insert(newCustomer)
                customer = newCustomer.copy(id = newId)
                // FIX (sync): new customer created inline during Save Sale was never
                // enqueued — the SyncQueueHelper.trigger() call at the end of this
                // function now also pushes this along with the sale.
                SyncQueueHelper.enqueueCustomer(db, customer!!)
            }

            db.saleDao().sale(
                Sale(
                    invoice = invoice,
                    customerId = customer?.id,
                    subtotal = subtotal,
                    discount = discount,
                    tax = 0.0,
                    total = total,
                    paid = paid,
                    paymentMethod = method.lowercase(),
                    saleType = saleType,
                    createdAt = saleDateMillis
                )
            )

            val saleItems = lines.map {
                val lineProduct = productsByBarcode[it.barcode]
                SaleItem(
                    invoice = invoice,
                    barcode = it.barcode,
                    product = it.itemName,
                    qty = it.qty,
                    unit = it.unit,
                    unitPrice = it.unitPrice,
                    cost = it.cost,
                    amount = it.amount,
                    // FIX (historical unit conversion bug): freeze this line's
                    // smallest-units-per-`unit` factor at sale time — see
                    // Database.kt's SaleItem.smallestQty()/conversionFactor comment.
                    conversionFactor = lineProduct?.smallestPerUnitOf(it.unit) ?: 0.0
                )
            }
            db.saleDao().items(saleItems)

            val savedSale = db.saleDao().findSale(invoice)!!
            SyncQueueHelper.enqueue(
                db, "sale", SyncQueueHelper.saleEntityId(savedSale), if (original != null) "update" else "create",
                SyncQueueHelper.saleJson(db, savedSale)
            )

            for (line in lines) {
                val product = productsByBarcode[line.barcode] ?: db.productDao().find(line.barcode)
                if (product == null) continue
                val smallestQty = product.toSmallestUnits(line.qty, line.unit)
                val rowsAffected = SyncQueueHelper.decreaseProductStock(db, line.barcode, smallestQty, "SALE", invoice)
                if (rowsAffected == 0) {
                    stockWarnings.add("Warning: \"${line.itemName}\" ka stock update nahi ho saka — check karen.")
                }
            }

            if (customer != null && paid < total) {
                SyncQueueHelper.adjustCustomerBalance(db, customer!!.id, total - paid)
            }

            if (paid > 0) {
                val cashTx = CashTransaction(
                    type = "IN",
                    method = method.lowercase(),
                    amount = paid,
                    reason = "Sale",
                    reference = invoice
                )
                val cashTxId = db.cashTransactionDao().insert(cashTx)
                val savedCashTx = cashTx.copy(id = cashTxId)
                SyncQueueHelper.enqueueCashTransaction(db, savedCashTx)
            }
        }

        SyncQueueHelper.trigger(appContext)
        return SaleSaveResult(customer = customer, stockWarnings = stockWarnings)
    }

    /** Deletes a sale: reverses its stock and customer-balance effect and
     * removes the sale, its line items, and its cash transaction — all in one
     * transaction (Phase 1 data-safety fix, same as [saveSale]). */
    override suspend fun deleteSale(invoice: String, original: Sale?, originalItems: List<SaleItem>) {
        val sale = original ?: db.saleDao().findSale(invoice) ?: return
        val items = originalItems.ifEmpty { db.saleDao().itemsForInvoice(invoice) }

        db.withTransaction {
            items.forEach { si ->
                val p = db.productDao().find(si.barcode)
                val smallestQty = si.smallestQty(p)
                SyncQueueHelper.increaseProductStock(db, si.barcode, smallestQty, "SALE_REVERSAL", invoice)
            }
            val outstanding = sale.total - sale.paid
            if (sale.customerId != null && outstanding > 0) {
                SyncQueueHelper.adjustCustomerBalance(db, sale.customerId, -outstanding)
            }
            db.saleDao().deleteItems(invoice)
            db.saleDao().deleteSale(invoice)
            db.cashTransactionDao().deleteByReference(invoice)
        }
        SyncQueueHelper.enqueue(
            db, "sale", "sale:$invoice", "delete",
            org.json.JSONObject().apply { put("invoice", invoice) }.toString()
        )
        SyncQueueHelper.trigger(appContext)
    }

    /** Persists a Quick Sale line (single-item, no draft/discount workflow).
     * FIX (atomicity): the whole write path (sale insert, item insert, stock
     * decrease, customer balance adjust, cash transaction) now runs inside a
     * single db.withTransaction {} — same pattern as [saveSale]/[deleteSale] —
     * so a mid-way crash can no longer leave the sale row saved without its
     * matching stock/balance/cash effects (or vice versa).
     */
    override suspend fun saveQuickSale(
        product: Product,
        qty: Double,
        price: Double,
        unit: String,
        customerName: String
    ): QuickSaleSaveResult {
        var resultInvoice = ""
        var resultIsCredit = false

        db.withTransaction {
            val current = db.productDao().find(product.barcode)
            if (current == null || current.stock < current.toSmallestUnits(qty, unit)) {
                throw StockUnavailableException("Stock badal gaya hai, dobara try karen")
            }
            // FIX (fraction control): reject a sale qty that would leave a fractional
            // smallest-unit qty for a non-fractional item (Piece/Dabbi/Bottle etc.).
            if (!current.isValidSmallestQty(current.toSmallestUnits(qty, unit))) {
                throw InvalidQuantityException("Qty ($qty $unit) whole ${current.smallestUnitName()} mein convert nahi hoti")
            }

            var customer: Customer? = null
            val isCredit = customerName.isNotEmpty()
            if (isCredit) {
                customer = db.customerDao().all().first().find { it.name.equals(customerName, ignoreCase = true) }
                if (customer == null) {
                    val newCustomer = Customer(name = customerName)
                    val newId = db.customerDao().insert(newCustomer)
                    customer = newCustomer.copy(id = newId)
                    // FIX (sync): new customer created inline during Quick Sale was
                    // never enqueued — the SyncQueueHelper.trigger() call later in this
                    // function will now also push this along with the sale.
                    SyncQueueHelper.enqueueCustomer(db, customer)
                }
            }

            val amount = qty * price
            val smallestQtyForCost = current.toSmallestUnits(qty, unit)
            val factor = current.smallestUnitFactor()
            val costPerSmallest = if (factor > 0) current.cost / factor else current.cost
            val lineCost = smallestQtyForCost * costPerSmallest

            val now = System.currentTimeMillis()
            val mmYY = SimpleDateFormat("MMyy", Locale.getDefault()).format(Date(now))
            val invoice = mmYY + now.toString().takeLast(8)

            val paid = if (isCredit) 0.0 else amount
            val method = if (isCredit) "credit" else "cash"

            db.saleDao().sale(
                Sale(
                    invoice = invoice,
                    customerId = customer?.id,
                    subtotal = amount,
                    discount = 0.0,
                    tax = 0.0,
                    total = amount,
                    paid = paid,
                    paymentMethod = method,
                    saleType = "retail",
                    createdAt = now
                )
            )

            val saleItem = SaleItem(
                invoice = invoice,
                barcode = current.barcode,
                product = current.name,
                qty = qty,
                unit = unit,
                unitPrice = price,
                cost = lineCost,
                amount = amount,
                // FIX (historical unit conversion bug): see saveSale() above.
                conversionFactor = current.smallestPerUnitOf(unit)
            )
            db.saleDao().items(listOf(saleItem))

            SyncQueueHelper.decreaseProductStock(db, current.barcode, smallestQtyForCost, "SALE", invoice)

            if (isCredit && customer != null) {
                SyncQueueHelper.adjustCustomerBalance(db, customer.id, amount)
            }

            val savedSale = db.saleDao().findSale(invoice)!!
            SyncQueueHelper.enqueue(
                db, "sale", SyncQueueHelper.saleEntityId(savedSale), "create",
                SyncQueueHelper.saleJson(db, savedSale)
            )

            if (paid > 0) {
                val cashTx = CashTransaction(
                    type = "IN",
                    method = method,
                    amount = paid,
                    reason = "Quick Sale",
                    reference = invoice
                )
                val cashTxId = db.cashTransactionDao().insert(cashTx)
                val savedCashTx = cashTx.copy(id = cashTxId)
                SyncQueueHelper.enqueueCashTransaction(db, savedCashTx)
            }

            resultInvoice = invoice
            resultIsCredit = isCredit
        }

        SyncQueueHelper.trigger(appContext)

        return QuickSaleSaveResult(invoice = resultInvoice, isCredit = resultIsCredit)
    }

    private fun formatQty(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
