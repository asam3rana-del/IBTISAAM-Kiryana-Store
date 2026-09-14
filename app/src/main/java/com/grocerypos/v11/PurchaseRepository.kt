package com.grocerypos.v11.data

import com.grocerypos.v11.Product
import com.grocerypos.v11.Purchase
import com.grocerypos.v11.PurchaseItem
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.UnitType
import kotlinx.coroutines.flow.Flow

/**
 * One line item on a purchase bill being built/edited — carries enough unit-
 * conversion context (mainUnit/secondaryUnit/tertiaryUnit + their quantities)
 * for PurchaseActivity to redo its live unit-toggle math on an edit/scan/draft
 * reload without re-querying the product.
 */
data class PurchaseLine(
    val itemName: String,
    val barcode: String?,
    val qty: Double,
    val unit: String,
    val rate: Double,
    val amount: Double,
    val mainUnit: String,
    val secondaryUnit: String,
    val secondaryUnitQty: Double,
    val tertiaryUnit: String = "",
    val tertiaryUnitQty: Double = 0.0,
    // NEW ("10/10 Purchase screen" item #7): retail (salePrice) / wholesale rate
    // entered on the Purchase screen for this line, in the product's PRIMARY-unit
    // basis (same basis as Product.salePrice/wholesalePrice) — see
    // PurchaseActivity's toMainUnitRate()/fromMainUnitRate(). 0.0 means "leave the
    // product's current rate unchanged"; RoomPurchaseRepository.savePurchase only
    // writes a new salePrice/wholesalePrice when the corresponding value is > 0.
    val retailRate: Double = 0.0,
    val wholesaleRate: Double = 0.0
)

/** Snapshot loaded for editing an existing purchase bill. */
data class PurchaseEditData(
    val purchase: Purchase,
    val items: List<PurchaseItem>,
    val supplierName: String,
    val lines: List<PurchaseLine>
)

/** Result of [PurchaseRepository.savePurchase]. */
sealed class SavePurchaseResult {
    data class Success(val billNo: String, val isUpdate: Boolean) : SavePurchaseResult()
    data class Error(val message: String) : SavePurchaseResult()
}

/**
 * Repository abstraction for the Purchase screen (PurchaseActivity /
 * PurchaseViewModel).
 *
 * This is an interface (rather than a concrete class) so the domain layer
 * (PurchaseUseCases: SavePurchaseUseCase, ProcessScannedItemsUseCase, etc.)
 * can be unit tested against a lightweight in-memory FakePurchaseRepository
 * instead of needing a real Room database — see
 * app/src/test/java/com/grocerypos/v11/domain/FakePurchaseRepository.kt.
 * (Extracted in Improvement Pack P9, mirroring the same split already done
 * for SaleRepository / RoomSaleRepository.)
 *
 * The only production implementation is [RoomPurchaseRepository]
 * (RoomPurchaseRepository.kt), which is what PurchaseViewModelFactory,
 * HistoryActivity, and PurchaseHistoryActivity wire up — all the real
 * PurchaseDao/SupplierDao/ProductDao/UnitDao/CategoryDao/PaymentDao/
 * CashTransactionDao/AppSettingDao/SyncQueueHelper logic lives there,
 * unchanged from before this interface was extracted.
 */
interface PurchaseRepository {

    fun observeSuppliers(): Flow<List<Supplier>>

    fun observeProducts(): Flow<List<Product>>

    fun observeUnits(): Flow<List<UnitType>>

    suspend fun getFirmName(): String?

    suspend fun categories(): List<String>

    suspend fun addUnit(name: String)

    /** Quick-add from the purchase screen's "+" button. Enqueued for sync just like
     * Party's own supplier add (see RoomPurchaseRepository.addSupplier) — this used
     * to skip sync entirely, which meant a supplier created here was invisible on
     * every other device along with its whole payable balance. See the FIX comment
     * on the implementation for the full story. */
    suspend fun addSupplier(name: String): Supplier

    /** Quick-add-product paths (the manual "+ Add New Product" dialog and
     * bill-scan auto-create) — enqueued for sync just like [addSupplier], for the
     * same reason: this used to skip sync entirely, leaving the product (and
     * later its stock/cost) invisible on every other device. */
    suspend fun addProduct(product: Product)

    /** Auto-creates a Product for a scanned bill item that doesn't match any
     * existing product, exactly as PurchaseActivity's handleScannedItems used
     * to inline. [seed] disambiguates the generated barcode across a batch.
     * Enqueued for sync — see [addProduct]'s comment. */
    suspend fun createProductForScan(name: String, cost: Double, seed: Int): Product

    suspend fun loadForEdit(billNo: String): PurchaseEditData?

    /** Finds the most recent PAST purchase of [barcode] (excluding
     * [excludeBillNo], the bill currently being edited if any) and returns its
     * rate + the unit it was recorded in. */
    suspend fun findLastPurchaseRate(barcode: String, excludeBillNo: String?): Pair<Double, String>?

    /** Deletes a purchase: reverses its stock/cost and supplier-balance effect
     * and removes the purchase, its line items, and its payment/cash rows. */
    suspend fun deletePurchase(billNo: String, original: Purchase?, originalItems: List<PurchaseItem>)

    /**
     * Persists a new or edited purchase bill.
     *
     * Callers (see [com.grocerypos.v11.domain.SavePurchaseUseCase]) are
     * expected to have already rejected an empty bill / qty<=0 / negative
     * rate line before calling this — this method itself may still throw
     * an internal data-integrity refusal it detects during the save (e.g.
     * reversing an edited purchase would need to remove more stock than
     * currently exists), which is caught and surfaced as
     * [SavePurchaseResult.Error].
     */
    suspend fun savePurchase(
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
        suppliers: List<Supplier>,
        // NEW ("10/10 Purchase screen" item #2): the SUPPLIER's own invoice/bill
        // number, stored on the Purchase record for the exact-match duplicate check
        // (see PurchaseDao.findDuplicateBySupplierInvoice). Blank = not entered.
        supplierInvoiceNo: String = ""
    ): SavePurchaseResult
}
