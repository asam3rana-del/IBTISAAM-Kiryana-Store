package com.grocerypos.v11.data

import android.content.Context
import androidx.room.withTransaction
import com.grocerypos.v11.CashTransaction
import com.grocerypos.v11.DeviceTag
import com.grocerypos.v11.Payment
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.Purchase
import com.grocerypos.v11.PurchaseItem
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.UnitType
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
    val tertiaryUnitQty: Double = 0.0
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
 * Repository for the Purchase screen (PurchaseActivity / PurchaseViewModel).
 *
 * This is the only place in the app that should call PurchaseDao/SupplierDao/
 * ProductDao/UnitDao/CategoryDao/PaymentDao/CashTransactionDao/AppSettingDao
 * for purchase-related work — UseCases and the ViewModel go through here
 * instead of touching PosDatabase directly, and every write stays paired
 * with the matching SyncQueueHelper call (exactly as PurchaseActivity used to
 * do inline). The save/delete paths keep their single-transaction guarantee:
 * stock, cost, and supplier balance can never go out of sync with the
 * purchase record, even on a crash mid-save.
 *
 * [appContext] should be an application Context (not an Activity one) since
 * this repository is expected to outlive any single screen.
 */
class PurchaseRepository(
    private val db: PosDatabase,
    private val appContext: Context
) {

    fun observeSuppliers(): Flow<List<Supplier>> = db.supplierDao().all()

    fun observeProducts(): Flow<List<Product>> = db.productDao().all()

    fun observeUnits(): Flow<List<UnitType>> = db.unitDao().all()

    suspend fun getFirmName(): String? = db.appSettingDao().get("shop_name")?.value

    suspend fun categories(): List<String> =
        (listOf("General") + db.categoryDao().all().first().map { it.name }).distinct()

    suspend fun addUnit(name: String) {
        db.unitDao().insert(UnitType(name))
    }

    /** Quick-add from the purchase screen's "+" button — matches the original
     * inline behaviour, which (unlike Party's supplier add) does not enqueue
     * this insert for sync. */
    suspend fun addSupplier(name: String): Supplier {
        val supplier = Supplier(name = name)
        val id = db.supplierDao().insert(supplier)
        return supplier.copy(id = id)
    }

    /** Quick-add-product paths (the manual "+ Add New Product" dialog and
     * bill-scan auto-create) — same no-sync-enqueue behaviour as [addSupplier]. */
    suspend fun addProduct(product: Product) {
        db.productDao().upsert(product)
    }

    /** Auto-creates a Product for a scanned bill item that doesn't match any
     * existing product, exactly as PurchaseActivity's handleScannedItems used
     * to inline. [seed] disambiguates the generated barcode across a batch. */
    suspend fun createProductForScan(name: String, cost: Double, seed: Int): Product {
        val newProduct = Product(
            barcode = "P" + System.currentTimeMillis() + seed,
            name = name, category = "General", cost = cost, salePrice = 0.0, wholesalePrice = 0.0,
            stock = 0.0, openingStock = 0.0, unit = "pcs", secondaryUnit = "", secondaryUnitQty = 0.0,
            tertiaryUnit = "", tertiaryUnitQty = 0.0
        )
        db.productDao().upsert(newProduct)
        return newProduct
    }

    suspend fun loadForEdit(billNo: String): PurchaseEditData? {
        val purchase = db.purchaseDao().findPurchase(billNo) ?: return null
        val items = db.purchaseDao().itemsForBill(billNo)
        val supplierName = purchase.supplierId?.let { id -> db.supplierDao().find(id)?.name } ?: ""
        val lines = items.map { pi ->
            val product = db.productDao().find(pi.barcode)
            PurchaseLine(
                itemName = product?.name ?: pi.barcode,
                barcode = pi.barcode,
                qty = pi.qty,
                unit = pi.unit.ifBlank { product?.unit ?: "" },
                rate = pi.unitCost,
                amount = pi.amount,
                mainUnit = product?.unit ?: "",
                secondaryUnit = product?.secondaryUnit ?: "",
                secondaryUnitQty = product?.secondaryUnitQty ?: 0.0,
                tertiaryUnit = product?.tertiaryUnit ?: "",
                tertiaryUnitQty = product?.tertiaryUnitQty ?: 0.0
            )
        }
        return PurchaseEditData(purchase, items, supplierName, lines)
    }

    /** Finds the most recent PAST purchase of [barcode] (excluding
     * [excludeBillNo], the bill currently being edited if any) and returns its
     * rate + the unit it was recorded in. */
    suspend fun findLastPurchaseRate(barcode: String, excludeBillNo: String?): Pair<Double, String>? {
        if (barcode.isBlank()) return null
        val candidatePurchases = db.purchaseDao().allPurchases()
            .filter { it.billNo != excludeBillNo }
            .sortedByDescending { it.createdAt }
        for (purchase in candidatePurchases) {
            val items = db.purchaseDao().itemsForBill(purchase.billNo)
            val match = items.find { it.barcode == barcode }
            if (match != null) return match.unitCost to match.unit
        }
        return null
    }

    // FIX (multi-device collision bug): billNo used to be a pure local sequence number
    // ("PUR-Aug26-0001", "0002", ...) with nothing device-specific in it. Two different
    // devices at the same branch would independently generate the exact same billNo
    // (e.g. both devices' very first purchase this month becomes "PUR-Aug26-0001"), and
    // since billNo is also the Firestore document ID this purchase syncs under, one
    // device's purchase would silently overwrite the other's instead of both existing.
    // Appending each device's DeviceTag makes billNo unique across devices while keeping
    // the same readable per-device sequence.
    private suspend fun genBillNo(): String {
        val prefix = "PUR-" + SimpleDateFormat("MMMyy", Locale.getDefault()).format(Date()) + "-"
        val existing = db.purchaseDao().allPurchases().map { it.billNo }.toHashSet()
        var seqNum = existing.count { it.startsWith(prefix) } + 1
        var candidate = prefix + seqNum.toString().padStart(4, '0') + "-" + DeviceTag.current
        while (existing.contains(candidate)) {
            seqNum++
            candidate = prefix + seqNum.toString().padStart(4, '0') + "-" + DeviceTag.current
        }
        return candidate
    }

    // FIX (Purchase costing safety, item #7): reversing a purchase (on edit or
    // delete) used to reconstruct "cost before this purchase" by subtracting
    // this purchase's amount from (currentStock * currentAverageCost) — a
    // simple average-cost algebra trick that is only valid if NOTHING else
    // (no sale, no other purchase) has touched this product's stock/cost
    // since this purchase was recorded. In a real sequence like
    // Purchase -> Sale -> Purchase -> Sale -> [delete the first Purchase],
    // that assumption is already broken: sales in between consumed stock at
    // an average cost that had this purchase's value mixed in, so naively
    // subtracting this purchase's amount no longer recovers a correct number.
    // A fully correct fix needs a per-transaction inventory ledger (see
    // README/plan) — a larger change than this pass. Until that lands, this
    // validates FIRST (before any writes) that reversing every line would
    // not need to remove more units than are currently in stock; if any
    // line's already been drawn down below its own purchased quantity by
    // later sales, we refuse the whole edit/delete rather than silently
    // producing a corrupted stock/cost number. The user should record a
    // stock adjustment instead in that case.
    private suspend fun reverseStockAndCostForItems(items: List<PurchaseItem>) {
        items.forEach { pi ->
            val product = db.productDao().find(pi.barcode) ?: return@forEach
            val smallestQty = pi.smallestQty(product)
            if (smallestQty > 0 && smallestQty > product.stock) {
                throw IllegalStateException(
                    "\"${product.name}\" ka stock is purchase ke baad already kam ho chuka hai " +
                    "(sale ya doosri entry se) — is purchase ko edit/delete karna cost ko galat kar dega. " +
                    "Iski jagah stock adjustment karen."
                )
            }
        }
        items.forEach { pi ->
            val product = db.productDao().find(pi.barcode) ?: return@forEach
            val factor = product.smallestUnitFactor()
            // FIX (historical unit conversion bug): use the factor frozen on this
            // line AT PURCHASE TIME (pi.conversionFactor) instead of re-deriving it
            // from the product's CURRENT unit configuration — see Database.kt's
            // PurchaseItem.smallestQty() comment. Otherwise editing a product's unit
            // ladder after the fact (e.g. "1 Carton = 10 Box" -> "= 12 Box") would
            // silently reverse the wrong quantity for every old purchase on delete/edit.
            val smallestQty = pi.smallestQty(product)
            if (smallestQty <= 0) return@forEach

            val currentCostPerSmallest = if (factor > 0) product.cost / factor else product.cost
            val currentStock = product.stock
            val newStock = currentStock - smallestQty

            val totalValueBefore = currentStock * currentCostPerSmallest
            val totalValueAfterRemoval = (totalValueBefore - pi.amount).coerceAtLeast(0.0)

            val newCostPerSmallest = if (newStock > 0) totalValueAfterRemoval / newStock else 0.0
            val newCost = newCostPerSmallest * factor

            SyncQueueHelper.decreaseProductStockForce(db, pi.barcode, smallestQty, "PURCHASE_REVERSAL", pi.billNo, newCost)
            SyncQueueHelper.updateProductCost(db, pi.barcode, newCost)
        }
    }

    // FIX (Phase 1 - Data Safety): stock/cost reversal + supplier balance reversal + all
    // row deletes now run as one atomic Room transaction (previously separate sequential
    // writes — same class of bug as savePurchase()/HistoryActivity.deletePurchase(), which
    // were already fixed; this was the one remaining unguarded purchase-delete path).
    suspend fun deletePurchase(billNo: String, original: Purchase?, originalItems: List<PurchaseItem>) {
        val purchase = original ?: db.purchaseDao().findPurchase(billNo) ?: return
        val items = originalItems.ifEmpty { db.purchaseDao().itemsForBill(billNo) }
        db.withTransaction {
            reverseStockAndCostForItems(items)
            val outstanding = purchase.total - purchase.paid
            if (purchase.supplierId != null && outstanding > 0) {
                SyncQueueHelper.adjustSupplierBalance(db, purchase.supplierId, -outstanding)
            }
            db.purchaseDao().deleteItems(billNo)
            db.purchaseDao().deletePurchase(billNo)
            db.paymentDao().deleteByReference(billNo)
            db.cashTransactionDao().deleteByReference(billNo)
        }
        SyncQueueHelper.enqueue(
            db, "purchase", "purchase:$billNo", "delete",
            org.json.JSONObject().apply { put("billNo", billNo) }.toString()
        )
        SyncQueueHelper.trigger(appContext)
    }

    // FIX (Phase 1 - Data Safety): everything below (supplier insert, reversal
    // of the original purchase on edit, purchase+items insert, stock/cost
    // update per line, supplier balance update, payment + cash transaction
    // insert) runs inside one Room transaction instead of as separate
    // sequential writes — a crash/kill partway through previously could leave
    // stock, cost, and supplier balance out of sync with the purchase record.
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
        suppliers: List<Supplier>
    ): SavePurchaseResult {
        return try {
            val matchedSupplier = suppliers.find { it.name.equals(party, ignoreCase = true) }
            var supplierId = matchedSupplier?.id
            val billNo = editBillNo ?: genBillNo()
            db.withTransaction {
                if (supplierId == null && party.isNotEmpty()) {
                    supplierId = db.supplierDao().insert(Supplier(name = party))
                }
                if (original != null) {
                    reverseStockAndCostForItems(originalItems)
                    val originalOutstanding = original.total - original.paid
                    if (original.supplierId != null && originalOutstanding > 0) {
                        SyncQueueHelper.adjustSupplierBalance(db, original.supplierId, -originalOutstanding)
                    }
                    db.purchaseDao().deleteItems(billNo)
                    db.purchaseDao().deletePurchase(billNo)
                    db.paymentDao().deleteByReference(billNo)
                    db.cashTransactionDao().deleteByReference(billNo)
                }
                val purchaseRecord = Purchase(
                    billNo = billNo, supplierId = supplierId, total = grandTotal, paid = amountPaid,
                    createdAt = purchaseDateMillis, subtotal = lines.sumOf { it.amount }, discount = discount
                )
                db.purchaseDao().purchase(purchaseRecord)
                val purchaseItems = lines.map { line ->
                    val lineProduct = line.barcode?.let { db.productDao().find(it) }
                    PurchaseItem(
                        billNo = billNo, barcode = line.barcode ?: "", qty = line.qty,
                        unitCost = line.rate, amount = line.amount, unit = line.unit,
                        // FIX (historical unit conversion bug): freeze this line's
                        // smallest-units-per-`unit` factor at purchase time — see
                        // Database.kt's PurchaseItem.smallestQty()/conversionFactor comment.
                        conversionFactor = lineProduct?.smallestPerUnitOf(line.unit) ?: 0.0
                    )
                }
                db.purchaseDao().items(purchaseItems)
                SyncQueueHelper.enqueue(
                    db, "purchase", SyncQueueHelper.purchaseEntityId(purchaseRecord), if (original != null) "update" else "create",
                    SyncQueueHelper.purchaseJson(db, purchaseRecord)
                )
                lines.forEach { line ->
                    val barcode = line.barcode ?: return@forEach
                    val before = db.productDao().find(barcode) ?: return@forEach
                    val purchasedSmallest = before.toSmallestUnits(line.qty, line.unit)
                    // FIX (fraction control): reject a purchase line that would leave a
                    // fractional smallest-unit qty for a non-fractional item (Piece/Dabbi/
                    // Bottle etc.) instead of silently rounding it away — previously stock
                    // was an Int so e.g. "2.5 Dabbi" quietly became "2 Dabbi" or "3 Dabbi".
                    if (!before.isValidSmallestQty(purchasedSmallest)) {
                        throw IllegalStateException("\"${before.name}\" ke liye qty (${line.qty} ${line.unit}) whole ${before.smallestUnitName()} mein convert nahi hoti — qty check karen.")
                    }
                    // Compute the new weighted-average cost BEFORE the stock increase below
                    // (uses `before.stock`, i.e. pre-increase) so the stock_movements row
                    // logged by increaseProductStock() carries the correct just-computed
                    // cost instead of the stale pre-purchase one.
                    var newCostForMovement = before.cost
                    if (purchasedSmallest > 0) {
                        val oldStockSmallest = before.stock
                        val factor = before.smallestUnitFactor()
                        val oldCostPerSmallest = if (factor > 0) before.cost / factor else before.cost
                        val purchaseRatePerSmallest = line.amount / purchasedSmallest
                        val newCostPerSmallest = if (oldStockSmallest <= 0) purchaseRatePerSmallest
                            else ((oldStockSmallest * oldCostPerSmallest) + (purchasedSmallest * purchaseRatePerSmallest)) / (oldStockSmallest + purchasedSmallest)
                        newCostForMovement = newCostPerSmallest * factor
                    }
                    SyncQueueHelper.increaseProductStock(db, barcode, purchasedSmallest, "PURCHASE", billNo, newCostForMovement)
                    if (purchasedSmallest > 0) {
                        SyncQueueHelper.updateProductCost(db, barcode, newCostForMovement)
                    }
                }
                val outstanding = grandTotal - amountPaid
                if (supplierId != null && outstanding > 0) {
                    SyncQueueHelper.adjustSupplierBalance(db, supplierId!!, outstanding)
                }
                if (supplierId != null && amountPaid > 0) {
                    val payment = Payment(reference = billNo, partyType = "supplier", partyId = supplierId, amount = amountPaid, method = paymentMethod, note = if (original != null) "Purchase payment (edited)" else "Purchase payment")
                    val paymentId = db.paymentDao().insert(payment)
                    SyncQueueHelper.enqueuePayment(db, payment.copy(id = paymentId))
                }
                if (amountPaid > 0) {
                    val cashTx = CashTransaction(type = "OUT", method = paymentMethod.lowercase(), amount = amountPaid, reason = "Purchase", reference = billNo)
                    val cashTxId = db.cashTransactionDao().insert(cashTx)
                    val savedCashTx = cashTx.copy(id = cashTxId)
                    SyncQueueHelper.enqueueCashTransaction(db, savedCashTx)
                }
            } // end db.withTransaction
            SyncQueueHelper.trigger(appContext)
            SavePurchaseResult.Success(billNo, original != null)
        } catch (e: IllegalStateException) {
            SavePurchaseResult.Error(e.message ?: "Save failed")
        }
    }
}
