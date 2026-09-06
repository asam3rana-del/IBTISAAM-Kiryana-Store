package com.grocerypos.v11.domain

import com.grocerypos.v11.Customer
import com.grocerypos.v11.HeldBill
import com.grocerypos.v11.Product
import com.grocerypos.v11.Sale
import com.grocerypos.v11.SaleItem
import com.grocerypos.v11.data.InvalidQuantityException
import com.grocerypos.v11.data.SaleRepository
import com.grocerypos.v11.data.StockUnavailableException
import com.grocerypos.v11.pricing.DiscountCalculator
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UseCases for the Sale screen (SaleActivity / SaleViewModel).
 *
 * Each one is a single, named operation rather than exposing SaleRepository's
 * raw reads/writes to the ViewModel — validation and calculation that belongs
 * to "saving a sale" (line items required, due needs a customer, invoice
 * numbering, applying the discount/paid clamps) lives here, not in the
 * Activity and not in the Repository. This is also the layer a unit test
 * would target: no Android framework classes are involved above
 * SaleRepository.
 */

/** One line item on a bill — the domain shape both the live editing screen
 * (SaleActivity's `lines` list) and an edit-reload (LoadSaleForEditUseCase)
 * use. */
data class SaleLine(
    val barcode: String,
    val itemName: String,
    val qty: Double,
    val unit: String,
    val unitPrice: Double,
    val cost: Double,
    val amount: Double,
    val mainUnit: String,
    val secondaryUnit: String,
    val secondaryUnitQty: Double,
    val tertiaryUnit: String = "",
    val tertiaryUnitQty: Double = 0.0
)

/** Everything needed to repopulate the Sale screen for editing an existing invoice. */
data class SaleForEdit(
    val sale: Sale,
    val items: List<SaleItem>,
    val customerName: String,
    val lines: List<SaleLine>
)

/** Result of validating + saving a sale (new or edit). */
sealed class SaveSaleResult {
    data class Success(
        val invoice: String,
        val customer: Customer?,
        val customerNameEntered: String,
        val subtotal: Double,
        val discount: Double,
        val total: Double,
        val paid: Double,
        val paymentMethod: String,
        val isUpdate: Boolean,
        val stockWarnings: List<String> = emptyList()
    ) : SaveSaleResult()
    object EmptyItems : SaveSaleResult()
    object CustomerRequiredForDue : SaveSaleResult()
    data class StockIssue(val message: String) : SaveSaleResult()
}

/** Result of a Quick Sale (single-item, no draft workflow). */
sealed class QuickSaleResult {
    data class Success(val invoice: String, val isCredit: Boolean) : QuickSaleResult()
    data class StockIssue(val message: String) : QuickSaleResult()
    data class InvalidQty(val message: String) : QuickSaleResult()
}

class ObserveCustomersForSaleUseCase(private val repository: SaleRepository) {
    operator fun invoke(): Flow<List<Customer>> = repository.observeCustomers()
}

class ObserveProductsForSaleUseCase(private val repository: SaleRepository) {
    operator fun invoke(): Flow<List<Product>> = repository.observeProducts()
}

class LoadFirmNameForSaleUseCase(private val repository: SaleRepository) {
    suspend operator fun invoke(): String? = repository.firmName()
}

class LoadSaleForEditUseCase(private val repository: SaleRepository) {
    suspend operator fun invoke(invoice: String): SaleForEdit? {
        val sale = repository.findSale(invoice) ?: return null
        val items = repository.itemsForInvoice(invoice)
        val customers = repository.customersSnapshot()
        val customerName = sale.customerId?.let { id -> customers.find { it.id == id }?.name } ?: ""
        val products = repository.productsSnapshot()
        val lines = items.map { si ->
            val product = products.find { it.barcode == si.barcode }
            SaleLine(
                barcode = si.barcode,
                itemName = si.product,
                qty = si.qty.toDouble(),
                unit = si.unit.ifBlank { product?.unit ?: "" },
                unitPrice = si.unitPrice,
                cost = si.cost,
                amount = si.amount,
                mainUnit = product?.unit ?: "",
                secondaryUnit = product?.secondaryUnit ?: "",
                secondaryUnitQty = product?.secondaryUnitQty ?: 0.0,
                tertiaryUnit = product?.tertiaryUnit ?: "",
                tertiaryUnitQty = product?.tertiaryUnitQty ?: 0.0
            )
        }
        return SaleForEdit(sale = sale, items = items, customerName = customerName, lines = lines)
    }
}

/** Validates + saves a new or edited sale — the discount/total/paid/due math
 * comes from the same [DiscountCalculator] used by the live on-screen preview
 * (recomputeAmounts/refreshDue in SaleActivity), so the preview and the saved
 * bill can never disagree. */
class SaveSaleUseCase(private val repository: SaleRepository) {
    suspend operator fun invoke(
        editInvoice: String?,
        enteredCustomerName: String,
        knownCustomers: List<Customer>,
        saleTypeLabel: String,
        lines: List<SaleLine>,
        discountInput: Double,
        paidInput: Double,
        paymentMethodLabel: String,
        saleDateMillis: Long,
        original: Sale?,
        originalItems: List<SaleItem>
    ): SaveSaleResult {
        if (lines.isEmpty()) return SaveSaleResult.EmptyItems

        val subtotal = lines.sumOf { it.amount }
        val totals = DiscountCalculator.compute(subtotal, discountInput, paidInput)
        val enteredCustomer = enteredCustomerName.trim()

        if (totals.due > 0.009 && enteredCustomer.isEmpty()) return SaveSaleResult.CustomerRequiredForDue

        val method = if (totals.paid <= 0.009) "credit" else paymentMethodLabel
        val existingCustomer = knownCustomers.find { it.name.equals(enteredCustomer, ignoreCase = true) }
        val saleType = if (saleTypeLabel == "Wholesale") "wholesale" else "retail"
        val invoice = editInvoice ?: run {
            val mmYY = SimpleDateFormat("MMyy", Locale.getDefault()).format(Date(saleDateMillis))
            mmYY + System.currentTimeMillis().toString().takeLast(8)
        }

        return try {
            val result = repository.saveSale(
                invoice = invoice,
                enteredCustomerName = enteredCustomer,
                existingCustomer = existingCustomer,
                saleType = saleType,
                method = method,
                saleDateMillis = saleDateMillis,
                subtotal = totals.subtotal,
                discount = totals.discount,
                total = totals.total,
                paid = totals.paid,
                lines = lines,
                original = original,
                originalItems = originalItems
            )
            SaveSaleResult.Success(
                invoice = invoice,
                customer = result.customer,
                customerNameEntered = enteredCustomer,
                subtotal = totals.subtotal,
                discount = totals.discount,
                total = totals.total,
                paid = totals.paid,
                paymentMethod = method,
                isUpdate = original != null,
                stockWarnings = result.stockWarnings
            )
        } catch (e: StockUnavailableException) {
            SaveSaleResult.StockIssue(e.message ?: "")
        }
    }
}

class SaveQuickSaleUseCase(private val repository: SaleRepository) {
    suspend operator fun invoke(
        product: Product,
        qty: Double,
        price: Double,
        unit: String,
        customerName: String
    ): QuickSaleResult {
        return try {
            val result = repository.saveQuickSale(product, qty, price, unit, customerName.trim())
            QuickSaleResult.Success(result.invoice, result.isCredit)
        } catch (e: StockUnavailableException) {
            QuickSaleResult.StockIssue(e.message ?: "")
        } catch (e: InvalidQuantityException) {
            QuickSaleResult.InvalidQty(e.message ?: "")
        }
    }
}

class DeleteSaleUseCase(private val repository: SaleRepository) {
    suspend operator fun invoke(invoice: String, original: Sale?, originalItems: List<SaleItem>) {
        repository.deleteSale(invoice, original, originalItems)
    }
}

class CreateCustomerUseCase(private val repository: SaleRepository) {
    suspend operator fun invoke(name: String): Customer? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return repository.createCustomer(trimmed)
    }
}

/** Names of the best-selling products over the trailing 30 days, used to
 * pre-populate the Quick Sale dialog's shortcut row. */
class TopSellingProductNamesUseCase(private val repository: SaleRepository) {
    suspend operator fun invoke(): List<String> {
        val now = System.currentTimeMillis()
        val since = now - 30L * 24 * 60 * 60 * 1000
        return repository.topProductNames(since, now)
    }
}

class HoldBillUseCase(private val repository: SaleRepository) {
    suspend operator fun invoke(payload: String) {
        val holdId = "HOLD" + System.currentTimeMillis().toString()
        repository.holdBill(holdId, payload)
    }
}

class HeldBillsUseCase(private val repository: SaleRepository) {
    suspend operator fun invoke(): List<HeldBill> = repository.heldBills()
}

class DeleteHeldBillUseCase(private val repository: SaleRepository) {
    suspend operator fun invoke(bill: HeldBill) = repository.deleteHeldBill(bill)
}
