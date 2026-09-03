package com.grocerypos.v11.data

import com.grocerypos.v11.Customer
import com.grocerypos.v11.HeldBill
import com.grocerypos.v11.Product
import com.grocerypos.v11.Sale
import com.grocerypos.v11.SaleItem
import com.grocerypos.v11.domain.SaleLine
import kotlinx.coroutines.flow.Flow

/** Thrown by [SaleRepository.saveSale] / [SaleRepository.saveQuickSale] when
 * stock changed under us (item disappeared, insufficient stock). The message
 * is already the exact user-facing text — SaveSaleUseCase/SaveQuickSaleUseCase
 * just forward it into their Result. */
class StockUnavailableException(message: String) : Exception(message)

/** Thrown by [SaleRepository.saveQuickSale] when the requested quantity can't
 * convert to a whole smallest-unit quantity for a non-fractional item. */
class InvalidQuantityException(message: String) : Exception(message)

data class SaleSaveResult(val customer: Customer?, val stockWarnings: List<String>)
data class QuickSaleSaveResult(val invoice: String, val isCredit: Boolean)

/**
 * Repository abstraction for creating, editing, and deleting Sales
 * (SaleActivity), plus the held-bill drafts (Hold/Recall) that live
 * alongside them.
 *
 * This is an interface (rather than a concrete class) so the domain layer
 * (SaleUseCases: SaveSaleUseCase, SaveQuickSaleUseCase, LoadSaleForEditUseCase,
 * etc.) can be unit tested against a lightweight in-memory FakeSaleRepository
 * instead of needing a real Room database — see
 * app/src/test/java/com/grocerypos/v11/domain/FakeSaleRepository.kt.
 *
 * The only production implementation is [RoomSaleRepository]
 * (RoomSaleRepository.kt), which is what SaleViewModelFactory wires up — all
 * the real SaleDao/ProductDao/CustomerDao/CashTransactionDao/HeldDao/
 * AppSettingDao/SyncQueueHelper logic lives there, unchanged from before this
 * interface was extracted.
 */
interface SaleRepository {

    fun observeCustomers(): Flow<List<Customer>>

    fun observeProducts(): Flow<List<Product>>

    suspend fun firmName(): String?

    suspend fun findSale(invoice: String): Sale?

    suspend fun itemsForInvoice(invoice: String): List<SaleItem>

    suspend fun customersSnapshot(): List<Customer>

    suspend fun productsSnapshot(): List<Product>

    suspend fun topProductNames(sinceMillis: Long, uptoMillis: Long): List<String>

    suspend fun createCustomer(name: String): Customer

    suspend fun heldBills(): List<HeldBill>

    suspend fun holdBill(holdId: String, payload: String)

    suspend fun deleteHeldBill(bill: HeldBill)

    /**
     * Persists a new or edited sale.
     *
     * @throws StockUnavailableException with an exact user-facing message if
     * an item disappeared or has insufficient stock — callers should show it
     * as-is.
     */
    suspend fun saveSale(
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
    ): SaleSaveResult

    /** Deletes a sale: reverses its stock and customer-balance effect and
     * removes the sale, its line items, and its cash transaction. */
    suspend fun deleteSale(invoice: String, original: Sale?, originalItems: List<SaleItem>)

    /**
     * Persists a Quick Sale line (single-item, no draft/discount workflow).
     *
     * @throws StockUnavailableException if stock changed under us.
     * @throws InvalidQuantityException if [qty] can't convert to a whole
     * smallest-unit quantity for a non-fractional item.
     */
    suspend fun saveQuickSale(
        product: Product,
        qty: Double,
        price: Double,
        unit: String,
        customerName: String
    ): QuickSaleSaveResult
}
