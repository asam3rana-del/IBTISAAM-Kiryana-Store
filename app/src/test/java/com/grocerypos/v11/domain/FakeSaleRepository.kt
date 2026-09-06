package com.grocerypos.v11.domain

import com.grocerypos.v11.Customer
import com.grocerypos.v11.HeldBill
import com.grocerypos.v11.Product
import com.grocerypos.v11.Sale
import com.grocerypos.v11.SaleItem
import com.grocerypos.v11.data.InvalidQuantityException
import com.grocerypos.v11.data.QuickSaleSaveResult
import com.grocerypos.v11.data.SaleRepository
import com.grocerypos.v11.data.SaleSaveResult
import com.grocerypos.v11.data.StockUnavailableException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory test double for [SaleRepository]. Lets SaveSaleUseCase /
 * SaveQuickSaleUseCase / LoadSaleForEditUseCase (etc.) be unit tested without
 * a real Room database or Android Context — configure the `*Result` /
 * `*Throws` fields before calling a use case, then inspect the `last*Call`
 * fields afterward to assert what the use case actually asked the repository
 * to do.
 *
 * This does NOT reimplement RoomSaleRepository's stock/transaction logic —
 * it's a controllable stand-in, not a second copy of production logic. Tests
 * that need real stock-decrement/transaction behavior belong in an
 * instrumented (Robolectric or on-device) test against RoomSaleRepository
 * itself.
 */
class FakeSaleRepository(
    private val customers: MutableList<Customer> = mutableListOf(),
    private val products: MutableList<Product> = mutableListOf()
) : SaleRepository {

    // ---- Configure these before invoking a use case under test ----
    var saveSaleResult: SaleSaveResult = SaleSaveResult(customer = null, stockWarnings = emptyList())
    var saveSaleThrows: StockUnavailableException? = null

    var saveQuickSaleResult: QuickSaleSaveResult = QuickSaleSaveResult(invoice = "TESTINV", isCredit = false)
    var saveQuickSaleThrowsStock: StockUnavailableException? = null
    var saveQuickSaleThrowsInvalidQty: InvalidQuantityException? = null

    var findSaleResult: Sale? = null
    var itemsForInvoiceResult: List<SaleItem> = emptyList()

    // ---- Recorded calls, for assertions ----
    var lastSaveSaleCall: SaveSaleCallArgs? = null
        private set
    var lastSaveQuickSaleCall: SaveQuickSaleCallArgs? = null
        private set
    var deleteSaleCallCount: Int = 0
        private set

    data class SaveSaleCallArgs(
        val invoice: String,
        val enteredCustomerName: String,
        val method: String,
        val saleType: String,
        val subtotal: Double,
        val discount: Double,
        val total: Double,
        val paid: Double,
        val isUpdate: Boolean
    )

    data class SaveQuickSaleCallArgs(
        val product: Product,
        val qty: Double,
        val price: Double,
        val unit: String,
        val customerName: String
    )

    override fun observeCustomers(): Flow<List<Customer>> = flowOf(customers.toList())

    override fun observeProducts(): Flow<List<Product>> = flowOf(products.toList())

    override suspend fun firmName(): String? = "Test Shop"

    override suspend fun findSale(invoice: String): Sale? = findSaleResult

    override suspend fun itemsForInvoice(invoice: String): List<SaleItem> = itemsForInvoiceResult

    override suspend fun customersSnapshot(): List<Customer> = customers.toList()

    override suspend fun productsSnapshot(): List<Product> = products.toList()

    override suspend fun topProductNames(sinceMillis: Long, uptoMillis: Long): List<String> = emptyList()

    override suspend fun createCustomer(name: String): Customer {
        val newCustomer = Customer(id = (customers.size + 1).toLong(), name = name)
        customers.add(newCustomer)
        return newCustomer
    }

    override suspend fun heldBills(): List<HeldBill> = emptyList()

    override suspend fun holdBill(holdId: String, payload: String) = Unit

    override suspend fun deleteHeldBill(bill: HeldBill) = Unit

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
        lines: List<com.grocerypos.v11.domain.SaleLine>,
        original: Sale?,
        originalItems: List<SaleItem>
    ): SaleSaveResult {
        lastSaveSaleCall = SaveSaleCallArgs(
            invoice = invoice,
            enteredCustomerName = enteredCustomerName,
            method = method,
            saleType = saleType,
            subtotal = subtotal,
            discount = discount,
            total = total,
            paid = paid,
            isUpdate = original != null
        )
        saveSaleThrows?.let { throw it }
        return saveSaleResult
    }

    override suspend fun deleteSale(invoice: String, original: Sale?, originalItems: List<SaleItem>) {
        deleteSaleCallCount++
    }

    override suspend fun saveQuickSale(
        product: Product,
        qty: Double,
        price: Double,
        unit: String,
        customerName: String
    ): QuickSaleSaveResult {
        lastSaveQuickSaleCall = SaveQuickSaleCallArgs(product, qty, price, unit, customerName)
        saveQuickSaleThrowsStock?.let { throw it }
        saveQuickSaleThrowsInvalidQty?.let { throw it }
        return saveQuickSaleResult
    }
}
