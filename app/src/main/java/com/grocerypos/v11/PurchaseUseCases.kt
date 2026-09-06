package com.grocerypos.v11.domain

import com.grocerypos.v11.Product
import com.grocerypos.v11.Purchase
import com.grocerypos.v11.PurchaseItem
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.UnitType
import com.grocerypos.v11.data.PurchaseEditData
import com.grocerypos.v11.data.PurchaseLine
import com.grocerypos.v11.data.PurchaseRepository
import com.grocerypos.v11.data.SavePurchaseResult
import kotlinx.coroutines.flow.Flow

/**
 * UseCases for the Purchase screen (PurchaseActivity / PurchaseViewModel).
 *
 * Each one is a single, named operation rather than exposing
 * PurchaseRepository's raw calls to the ViewModel — this is also the layer a
 * unit test would target: no Android framework classes are involved above
 * PurchaseRepository.
 */

class ObserveSuppliersForPurchaseUseCase(private val repository: PurchaseRepository) {
    operator fun invoke(): Flow<List<Supplier>> = repository.observeSuppliers()
}

class ObserveProductsUseCase(private val repository: PurchaseRepository) {
    operator fun invoke(): Flow<List<Product>> = repository.observeProducts()
}

class ObserveUnitsUseCase(private val repository: PurchaseRepository) {
    operator fun invoke(): Flow<List<UnitType>> = repository.observeUnits()
}

class LoadFirmNameUseCase(private val repository: PurchaseRepository) {
    suspend operator fun invoke(): String? = repository.getFirmName()
}

class LoadCategoriesUseCase(private val repository: PurchaseRepository) {
    suspend operator fun invoke(): List<String> = repository.categories()
}

class LoadPurchaseForEditUseCase(private val repository: PurchaseRepository) {
    suspend operator fun invoke(billNo: String): PurchaseEditData? = repository.loadForEdit(billNo)
}

class FindLastPurchaseRateUseCase(private val repository: PurchaseRepository) {
    suspend operator fun invoke(barcode: String, excludeBillNo: String?): Pair<Double, String>? =
        repository.findLastPurchaseRate(barcode, excludeBillNo)
}

class AddSupplierUseCase(private val repository: PurchaseRepository) {
    suspend operator fun invoke(name: String): Supplier = repository.addSupplier(name)
}

class AddProductUseCase(private val repository: PurchaseRepository) {
    suspend operator fun invoke(product: Product) = repository.addProduct(product)
}

class AddUnitUseCase(private val repository: PurchaseRepository) {
    suspend operator fun invoke(name: String) = repository.addUnit(name)
}

/** A single scanned bill row, before it's matched against known products. */
data class ScannedLine(val name: String, val qty: Double, val rate: Double)

/**
 * Matches each scanned row against the currently-loaded [Product] list (exact
 * name match, then a loose contains-match, exactly as PurchaseActivity's
 * handleScannedItems used to inline), auto-creating a product via the
 * repository for anything unmatched.
 */
class ProcessScannedItemsUseCase(private val repository: PurchaseRepository) {
    suspend operator fun invoke(scanned: List<ScannedLine>, currentProducts: List<Product>): List<PurchaseLine> {
        val lines = mutableListOf<PurchaseLine>()
        scanned.forEachIndexed { index, item ->
            if (item.name.isEmpty() || item.qty <= 0) return@forEachIndexed
            var product = currentProducts.find { it.name.equals(item.name, ignoreCase = true) }
                ?: currentProducts.find { it.name.contains(item.name, ignoreCase = true) || item.name.contains(it.name, ignoreCase = true) }
            if (product == null) {
                product = repository.createProductForScan(item.name, item.rate, index)
            }
            lines.add(
                PurchaseLine(
                    itemName = product.name, barcode = product.barcode, qty = item.qty, unit = product.unit,
                    rate = item.rate, amount = Math.round(item.qty * item.rate).toDouble(),
                    mainUnit = product.unit, secondaryUnit = product.secondaryUnit, secondaryUnitQty = product.secondaryUnitQty,
                    tertiaryUnit = product.tertiaryUnit, tertiaryUnitQty = product.tertiaryUnitQty
                )
            )
        }
        return lines
    }
}

class SavePurchaseUseCase(private val repository: PurchaseRepository) {
    suspend operator fun invoke(
        editBillNo: String?,
        party: String,
        grandTotal: Double,
        amountPaid: Double,
        discount: Double,
        paymentMethod: String,
        purchaseDateMillis: Long,
        lines: List<PurchaseLine>,
        original: Purchase?,
        originalItems: List<PurchaseItem>,
        suppliers: List<Supplier>
    ): SavePurchaseResult = repository.savePurchase(
        editBillNo, party, grandTotal, amountPaid, discount, paymentMethod,
        purchaseDateMillis, lines, original, originalItems, suppliers
    )
}

class DeletePurchaseUseCase(private val repository: PurchaseRepository) {
    suspend operator fun invoke(billNo: String, original: Purchase?, originalItems: List<PurchaseItem>) =
        repository.deletePurchase(billNo, original, originalItems)
}
