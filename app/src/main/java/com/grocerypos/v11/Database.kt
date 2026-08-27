package com.grocerypos.v11

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

data class DailySales(val day:String,val total:Double)
data class TopProduct(val product:String,val totalQty:Double)
data class PurchaseWithSupplier(val billNo:String,val supplierName:String,val total:Double,val createdAt:Long,val status:String)
data class SupplierPurchaseTotal(val supplierName:String,val total:Double)
data class SaleWithCustomer(val invoice:String,val customerName:String,val total:Double,val paymentMethod:String,val createdAt:Long,val status:String)
data class CustomerSalesTotal(val customerName:String,val total:Double)
data class DailyProfit(val day:String,val profit:Double)
data class PartyItemReport(val product:String,val totalAmount:Double,val totalQty:Double)
data class ItemSaleRecord(val customerName:String,val qty:Double,val unitPrice:Double,val createdAt:Long)
data class ItemPurchaseRecord(val supplierName:String,val qty:Double,val unitCost:Double,val createdAt:Long)

// ---- Day Book (Roznamcha) — merged chronological ledger read models ----
data class DayBookSale(val invoice:String,val customerName:String,val total:Double,val paid:Double,val createdAt:Long,val status:String)
data class DayBookPurchase(val billNo:String,val supplierName:String,val total:Double,val paid:Double,val createdAt:Long,val status:String)

@Entity(tableName="units")
data class UnitType(@PrimaryKey val name:String)

@Entity(tableName="categories")
data class Category(@PrimaryKey val name:String)

@Entity(tableName="products")
data class Product(
    @PrimaryKey val barcode:String,
    val name:String,
    val category:String="",
    val cost:Double=0.0,
    val salePrice:Double=0.0,
    // FIX (fraction control): stock/reorderLevel/openingStock changed Int -> Double so
    // weight/volume-based items (smallest unit = Gram/ml) don't get silently rounded on
    // every purchase/sale. See MIGRATION_24_25. For piece-based items (smallest unit =
    // Piece/Bottle/Dabbi) the UI still enforces whole numbers — see isFractionalUnit()
    // below and the validation added in ProductActivity/PurchaseActivity/SaleActivity.
    val stock:Double=0.0,
    val reorderLevel:Double=0.0,
    val expiry:String="",
    val unit:String="pcs",
    val unitSize:Int=1,
    val unitNote:String="",
    val secondaryUnit:String="",
    val secondaryUnitQty:Double=0.0,
    val wholesalePrice:Double=0.0,
    val openingStock:Double=0.0,
    val tertiaryUnit:String="",
    val tertiaryUnitQty:Double=0.0,
    val updatedAt:Long=0L,
    val dirty:Boolean=true
)

// ================= 3-tier unit conversion helpers =================
// Single source of truth for converting between a product's primary, secondary,
// and tertiary units and its "smallest unit" (the unit `stock` is actually stored
// and compared in, e.g. reorderLevel/stock<=reorderLevel). Every screen that
// touches stock — Purchase, Purchase Return, Sale, Sale Return, Product opening
// stock — must go through these functions instead of re-deriving the math
// locally, so a fix here fixes every screen at once instead of drifting apart.

/** One tier of a product's unit ladder: this unit's name, and how many
 *  "smallest units" ONE of it equals. */
data class ProductUnitTier(val unit: String, val smallestPerUnit: Double)

// FIX: unit names are now compared trimmed + case-insensitive. Previously an
// exact `==` string match meant a unit string that differed only by casing or
// stray whitespace (easy to get from a Spinner/AutoCompleteTextView) silently
// fell through to the wrong branch and quietly corrupted stock.
private fun sameUnit(a: String, b: String): Boolean =
    a.isNotBlank() && b.isNotBlank() && a.trim().equals(b.trim(), ignoreCase = true)

/**
 * Ordered ladder of this product's units, SMALLEST first, each paired with how
 * many "smallest units" one of it equals. Always has at least one tier (the
 * primary unit, factor 1) — a plain 1-unit product. A 2-tier product (primary +
 * secondary, no tertiary) has 2 tiers with the secondary as smallest. A 3-tier
 * product has all 3, with tertiary as smallest.
 *
 * A malformed config (e.g. a tertiary unit typed in without a valid secondary)
 * degrades gracefully to a lower tier instead of producing a wrong/blown-up
 * factor — this is the single place that decides "is this product 1/2/3 tier",
 * so every other function below just reads off this list instead of
 * re-deciding it independently.
 */
fun Product.unitLadder(): List<ProductUnitTier> {
    val hasSecondary = secondaryUnit.isNotBlank() && secondaryUnitQty > 0
    val hasTertiary = hasSecondary && tertiaryUnit.isNotBlank() && tertiaryUnitQty > 0

    if (!hasSecondary) {
        // 1-tier: only the primary unit exists, and it IS the smallest unit.
        return listOf(ProductUnitTier(unit, 1.0))
    }

    if (!hasTertiary) {
        // 2-tier: secondary is the smallest unit; 1 primary = secondaryUnitQty secondary.
        return listOf(
            ProductUnitTier(secondaryUnit, 1.0),
            ProductUnitTier(unit, secondaryUnitQty)
        )
    }

    // 3-tier: tertiary is the smallest unit.
    // 1 secondary = tertiaryUnitQty tertiary; 1 primary = secondaryUnitQty secondary.
    return listOf(
        ProductUnitTier(tertiaryUnit, 1.0),
        ProductUnitTier(secondaryUnit, tertiaryUnitQty),
        ProductUnitTier(unit, secondaryUnitQty * tertiaryUnitQty)
    )
}

/** How many smallest units make up ONE of this product's primary unit. */
fun Product.smallestUnitFactor(): Double = unitLadder().last().smallestPerUnit

/** How many smallest units make up ONE secondary unit (1.0 if there's no secondary tier). */
fun Product.smallestPerSecondary(): Double =
    unitLadder().find { sameUnit(it.unit, secondaryUnit) }?.smallestPerUnit ?: 1.0

/** Name of the smallest unit this product's stock is actually stored/compared in. */
fun Product.smallestUnitName(): String = unitLadder().first().unit

/**
 * Converts [qty] entered in [enteredUnit] into the product's smallest-unit basis
 * (the same basis `stock` is stored in). This is THE function every screen must
 * call before adjusting `stock` — never re-derive this math locally.
 *
 * Falls back to the primary unit's factor if [enteredUnit] doesn't match any
 * tier (e.g. a stale/blank unit string) rather than silently returning the raw
 * qty unconverted, which would previously understate stock changes for
 * multi-tier products.
 */
fun Product.toSmallestUnits(qty: Double, enteredUnit: String): Double {
    val tier = unitLadder().find { sameUnit(it.unit, enteredUnit) } ?: unitLadder().last()
    return qty * tier.smallestPerUnit
}

/**
 * Reverse of [toSmallestUnits]: converts a quantity already expressed in
 * smallest units back into [targetUnit]. Used for return flows, display, and
 * for round-tripping a stored rate/qty into a different unit on the same
 * product — sharing this with toSmallestUnits keeps both directions in sync.
 */
fun Product.fromSmallestUnits(smallestQty: Double, targetUnit: String): Double {
    val tier = unitLadder().find { sameUnit(it.unit, targetUnit) } ?: unitLadder().last()
    return if (tier.smallestPerUnit > 0) smallestQty / tier.smallestPerUnit else smallestQty
}

/** How many smallest units make up ONE of [unitName] (any tier), reading off the same unitLadder(). */
fun Product.smallestPerUnitOf(unitName: String): Double =
    unitLadder().find { sameUnit(it.unit, unitName) }?.smallestPerUnit ?: 1.0

/**
 * Converts a rate/price entered per [fromUnit] into the equivalent rate per the
 * product's PRIMARY unit (e.g. Rs per pcs -> Rs per carton). This is the single
 * source of truth for rate conversion, built on the same unitLadder() that backs
 * toSmallestUnits()/fromSmallestUnits() — previously PricingTierMath.kt kept a
 * second, independent unit-tier system for this, risking drift from this one.
 */
fun Product.toPrimaryUnitRate(entered: Double, fromUnit: String): Double {
    val perFromUnit = smallestPerUnitOf(fromUnit)
    if (perFromUnit <= 0) return entered
    return entered * (smallestUnitFactor() / perFromUnit)
}

/** Reverse of [toPrimaryUnitRate]: converts a primary-unit rate down to [chosenUnit]. */
fun Product.fromPrimaryUnitRate(mainRate: Double, chosenUnit: String): Double {
    val perChosenUnit = smallestPerUnitOf(chosenUnit)
    val factor = if (perChosenUnit > 0) smallestUnitFactor() / perChosenUnit else 1.0
    return if (factor > 0) mainRate / factor else mainRate
}

// FIX (fraction control): names of smallest units that are allowed to carry a
// fractional stock value (continuous/weight/volume units). Any smallest unit
// NOT in this list (Piece, Dabbi, Bottle, Dozen, etc.) is treated as
// indivisible — entries that would produce a fractional smallest-unit qty for
// such a product must be rejected by the UI instead of silently rounded, which
// is what used to happen when `stock` was an Int.
private val FRACTIONAL_UNIT_NAMES = setOf(
    "gram", "grams", "gm", "g", "kg", "kilogram", "kilograms",
    "ml", "milliliter", "millilitre", "litre", "liter", "l",
    "tola", "maund"
)

/** True if this product's smallest unit is a continuous/weight/volume unit
 *  (Gram, ml, etc.) and may therefore legally hold a fractional stock qty. */
fun Product.isFractionalUnit(): Boolean =
    smallestUnitName().trim().lowercase() in FRACTIONAL_UNIT_NAMES

/**
 * Whether [smallestQty] (already converted via toSmallestUnits) is a legal
 * quantity for this product: whole numbers only unless the smallest unit is a
 * fractional/continuous one. Every screen that converts an entered qty down to
 * smallest units (Purchase, Sale, Purchase Return, Sale Return, Opening Stock)
 * must call this before writing to `stock` and reject/re-prompt on failure
 * instead of letting a value like "0.5 Dabbi" silently corrupt stock.
 */
fun Product.isValidSmallestQty(smallestQty: Double): Boolean {
    if (isFractionalUnit()) return true
    return kotlin.math.abs(smallestQty - kotlin.math.round(smallestQty)) < 0.0001
}

/**
 * Human-readable stock breakdown, e.g. "1 carton 2 box 1 pcs". Built directly
 * off the same unitLadder() used by toSmallestUnits/fromSmallestUnits, so the
 * display can never drift out of sync with what's actually stored/converted —
 * previously this had its own separate divide/modulo math.
 */
private fun Double.trimZero(): String =
    if (this == kotlin.math.round(this)) this.toLong().toString()
    else String.format("%.3f", this).trimEnd('0').trimEnd('.')

fun Product.formatStockBreakdown(): String {
    val ladder = unitLadder()
    if (ladder.size == 1) return "${stock.trimZero()} ${ladder[0].unit}"

    val largestToSmallest = ladder.asReversed()
    var remaining = stock
    val parts = mutableListOf<String>()

    largestToSmallest.forEachIndexed { index, tier ->
        val isSmallestTier = index == largestToSmallest.lastIndex
        if (isSmallestTier) {
            // FIX: smallest tier can be fractional (Gram/ml) — show up to 3
            // decimals instead of truncating to Int, which used to drop
            // fractional leftovers like "0.5 Gram" from the display entirely.
            if (remaining > 0) parts.add("${remaining.trimZero()} ${tier.unit}")
        } else {
            val perUnit = tier.smallestPerUnit
            val count = kotlin.math.floor(remaining / perUnit)
            remaining -= count * perUnit
            if (count > 0) parts.add("${count.toLong()} ${tier.unit}")
        }
    }

    if (parts.isEmpty()) return "0 ${smallestUnitName()}"
    return parts.joinToString(" ")
}

@Entity(tableName="customers")
data class Customer(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val name:String,
    val phone:String="",
    val creditLimit:Double=0.0,
    val openingBalance:Double=0.0,
    val balance:Double=0.0,
    val serverId:String?=null,
    val updatedAt:Long=0L,
    val dirty:Boolean=true
)

@Entity(tableName="suppliers")
data class Supplier(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val name:String,
    val phone:String="",
    val openingBalance:Double=0.0,
    val balance:Double=0.0,
    val serverId:String?=null,
    val updatedAt:Long=0L,
    val dirty:Boolean=true
)

@Entity(tableName="sales")
data class Sale(
    @PrimaryKey val invoice:String,
    val customerId:Long?=null,
    val subtotal:Double,
    val discount:Double,
    val tax:Double,
    val total:Double,
    val paid:Double,
    val paymentMethod:String,
    val saleType:String="retail",
    val createdAt:Long=System.currentTimeMillis(),
    val status:String="active",
    val updatedAt:Long=0L,
    val dirty:Boolean=true
)

// FIX (fractional qty consistency): qty is REAL/Double, matching PurchaseItem.qty,
// ReturnLine.qty, and every in-memory line (SaleLine/PurchaseLine). Previously this was
// an Int, so any fractional quantity (e.g. 1.5 kg, 2.5 dozen) got silently rounded away
// the moment a sale was saved — the printed bill, sale history, item reports, and any
// later edit/return/delete of that sale all worked off the rounded number instead of
// what was actually sold. See MIGRATION_23_24 for the matching DB-side change.
@Entity(tableName="sale_items")
data class SaleItem(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val invoice:String,
    val barcode:String,
    val product:String,
    val qty:Double,
    val unit:String="",
    val unitPrice:Double,
    val cost:Double,
    val amount:Double
)

@Entity(tableName="payments")
data class Payment(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val reference:String,
    val partyType:String,
    val partyId:Long?,
    val amount:Double,
    val method:String,
    val note:String="",
    val createdAt:Long=System.currentTimeMillis(),
    val serverId:String?=null,
    val updatedAt:Long=0L,
    val dirty:Boolean=true
)

@Entity(tableName="purchases")
data class Purchase(
    @PrimaryKey val billNo:String,
    val supplierId:Long?,
    val total:Double,
    val paid:Double,
    val createdAt:Long=System.currentTimeMillis(),
    val subtotal:Double=0.0,
    val discount:Double=0.0,
    val status:String="active",
    val updatedAt:Long=0L,
    val dirty:Boolean=true
)

@Entity(tableName="purchase_items")
data class PurchaseItem(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val billNo:String,
    val barcode:String,
    val qty:Double,
    val unitCost:Double,
    val amount:Double,
    val unit:String=""
)

@Entity(tableName="returns")
data class ReturnLine(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val reference:String,
    val type:String,
    val barcode:String,
    val qty:Double,
    val amount:Double,
    val createdAt:Long=System.currentTimeMillis()
)

@Entity(tableName="users")
data class User(
    @PrimaryKey val username:String,
    val displayName:String,
    val role:String,
    val passwordHash:String,
    val active:Boolean=true,
    val phone:String=""
)

@Entity(tableName="audit")
data class Audit(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val username:String,
    val action:String,
    val reference:String="",
    val details:String="",
    val createdAt:Long=System.currentTimeMillis()
)

@Entity(tableName="expenses")
data class Expense(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val category:String,
    val description:String,
    val amount:Double,
    val createdAt:Long=System.currentTimeMillis(),
    val serverId:String?=null,
    val updatedAt:Long=0L,
    val dirty:Boolean=true
)

@Entity(tableName="held_bills")
data class HeldBill(
    @PrimaryKey val holdId:String,
    val payload:String,
    val createdAt:Long=System.currentTimeMillis()
)

@Entity(tableName="cash_transactions")
data class CashTransaction(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val type:String,
    val method:String,
    val amount:Double,
    val reason:String="",
    val reference:String="",
    val createdAt:Long=System.currentTimeMillis(),
    val serverId:String?=null,
    val updatedAt:Long=0L,
    val dirty:Boolean=true
)

@Entity(tableName="cash_register")
data class CashRegister(
    @PrimaryKey val date:String,
    val openingCash:Double=0.0,
    val closingCash:Double=0.0,
    val openingBank:Double=0.0,
    val closingBank:Double=0.0,
    val closed:Boolean=false
)

@Entity(tableName="app_settings")
data class AppSetting(@PrimaryKey val key:String, val value:String)

@Entity(tableName="sync_queue")
data class SyncQueueEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payloadJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val retryCount: Int = 0,
    val lastError: String? = null
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE barcode=:code LIMIT 1")
    suspend fun find(code:String):Product?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(p:Product)
    @Delete suspend fun delete(p:Product)
    @Query("UPDATE products SET stock=stock-:qty WHERE barcode=:code AND stock>=:qty")
    suspend fun decrease(code:String,qty:Double):Int
    @Query("UPDATE products SET stock=stock-:qty WHERE barcode=:code")
    suspend fun decreaseForce(code:String,qty:Double)
    @Query("UPDATE products SET stock=stock+:qty WHERE barcode=:code")
    suspend fun increase(code:String,qty:Double)
    @Query("UPDATE products SET cost=:newCost WHERE barcode=:code")
    suspend fun updateCost(code:String,newCost:Double)
    @Query("UPDATE products SET unit=:unit WHERE barcode=:code")
    suspend fun updateUnit(code:String,unit:String)
    @Query("SELECT * FROM products WHERE stock<=reorderLevel ORDER BY name")
    fun lowStock():Flow<List<Product>>
    @Query("SELECT * FROM products WHERE expiry!='' ORDER BY expiry")
    fun expiring():Flow<List<Product>>
    @Query("SELECT * FROM products ORDER BY name")
    fun all():Flow<List<Product>>
    // ADDED (Balance Sheet): current stock value at cost, for the "Stock in Hand" asset line.
    @Query("SELECT COALESCE(SUM(stock*cost),0) FROM products")
    suspend fun stockValueTotal():Double

    // ================= Bulk Translate support =================
    // Distinct Urdu/English values currently saved directly on product rows —
    // used by BulkTranslateActivity to find every value that needs renaming,
    // since category/unit are plain text columns on Product, not foreign keys.
    @Query("SELECT DISTINCT category FROM products WHERE category!=''")
    suspend fun distinctCategories(): List<String>
    @Query("SELECT DISTINCT unit FROM products WHERE unit!=''")
    suspend fun distinctPrimaryUnits(): List<String>
    @Query("SELECT DISTINCT secondaryUnit FROM products WHERE secondaryUnit!=''")
    suspend fun distinctSecondaryUnits(): List<String>
    @Query("SELECT DISTINCT tertiaryUnit FROM products WHERE tertiaryUnit!=''")
    suspend fun distinctTertiaryUnits(): List<String>

    // Cascades a rename from BulkTranslateActivity into every product row that
    // used the old value, one column at a time (a unit name can appear in any
    // of the three unit slots, so all three are updated independently).
    @Query("UPDATE products SET category=:newVal WHERE category=:oldVal")
    suspend fun renameCategoryInProducts(oldVal: String, newVal: String)
    @Query("UPDATE products SET unit=:newVal WHERE unit=:oldVal")
    suspend fun renamePrimaryUnitInProducts(oldVal: String, newVal: String)
    @Query("UPDATE products SET secondaryUnit=:newVal WHERE secondaryUnit=:oldVal")
    suspend fun renameSecondaryUnitInProducts(oldVal: String, newVal: String)
    @Query("UPDATE products SET tertiaryUnit=:newVal WHERE tertiaryUnit=:oldVal")
    suspend fun renameTertiaryUnitInProducts(oldVal: String, newVal: String)
}

@Dao interface UnitDao {
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insert(u:UnitType)
    @Delete suspend fun delete(u:UnitType)
    @Query("SELECT * FROM units ORDER BY name") fun all():Flow<List<UnitType>>
    // One-shot version of all() for screens (like BulkTranslateActivity) that just need
    // a snapshot once, without collecting a Flow.
    @Query("SELECT * FROM units ORDER BY name") suspend fun allOnce():List<UnitType>
    // Used by BulkTranslateActivity to remove the old Urdu master row after the
    // English name has been inserted in its place.
    @Query("DELETE FROM units WHERE name=:name") suspend fun deleteByName(name: String)
}

@Dao interface CategoryDao {
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insert(c:Category)
    @Query("SELECT * FROM categories ORDER BY name") fun all():Flow<List<Category>>
    // One-shot version of all() for screens (like BulkTranslateActivity) that just need
    // a snapshot once, without collecting a Flow.
    @Query("SELECT * FROM categories ORDER BY name") suspend fun allOnce():List<Category>
    // Used by BulkTranslateActivity to remove the old Urdu master row after the
    // English name has been inserted in its place.
    @Query("DELETE FROM categories WHERE name=:name") suspend fun deleteByName(name: String)
}

@Dao interface CustomerDao {
    @Insert suspend fun insert(c:Customer):Long
    @Update suspend fun update(c:Customer)
    @Delete suspend fun delete(c:Customer)
    @Query("SELECT * FROM customers WHERE id=:id LIMIT 1") suspend fun find(id:Long):Customer?
    @Query("SELECT * FROM customers WHERE serverId=:serverId LIMIT 1") suspend fun findByServerId(serverId:String):Customer?
    @Query("SELECT * FROM customers ORDER BY name") fun all():Flow<List<Customer>>
    @Query("UPDATE customers SET balance=balance+:amt WHERE id=:id")
    suspend fun addBalance(id:Long,amt:Double)
    @Query("SELECT COALESCE(name,'Walk-in') as customerName, SUM(total) as total FROM sales LEFT JOIN customers ON sales.customerId=customers.id GROUP BY customerId ORDER BY total DESC")
    suspend fun salesTotalsByCustomer():List<CustomerSalesTotal>
    // ADDED (Balance Sheet): balance>0 = customer owes us (Accounts Receivable, an Asset).
    // balance<0 = we've received an advance from them (a Liability), kept separate so the
    // two don't silently net against each other on the statement.
    @Query("SELECT COALESCE(SUM(balance),0) FROM customers WHERE balance>0")
    suspend fun receivablesTotal():Double
    @Query("SELECT COALESCE(SUM(-balance),0) FROM customers WHERE balance<0")
    suspend fun advancesReceivedTotal():Double
}

@Dao interface SupplierDao {
    @Insert suspend fun insert(s:Supplier):Long
    @Update suspend fun update(s:Supplier)
    @Delete suspend fun delete(s:Supplier)
    @Query("SELECT * FROM suppliers WHERE id=:id LIMIT 1") suspend fun find(id:Long):Supplier?
    @Query("SELECT * FROM suppliers WHERE serverId=:serverId LIMIT 1") suspend fun findByServerId(serverId:String):Supplier?
    @Query("UPDATE suppliers SET balance=balance+:amt WHERE id=:id") suspend fun addBalance(id:Long,amt:Double)
    @Query("SELECT * FROM suppliers ORDER BY name") fun all():Flow<List<Supplier>>
    @Query("SELECT COALESCE(name,'Cash Purchase') as supplierName, SUM(total) as total FROM purchases LEFT JOIN suppliers ON purchases.supplierId=suppliers.id GROUP BY supplierId ORDER BY total DESC")
    suspend fun purchaseTotalsBySupplier():List<SupplierPurchaseTotal>
    // ADDED (Balance Sheet): balance>0 = we owe the supplier (Accounts Payable, a Liability).
    // balance<0 = we've overpaid/advanced them (an Asset), kept separate for the same reason
    // as CustomerDao.advancesReceivedTotal().
    @Query("SELECT COALESCE(SUM(balance),0) FROM suppliers WHERE balance>0")
    suspend fun payablesTotal():Double
    @Query("SELECT COALESCE(SUM(-balance),0) FROM suppliers WHERE balance<0")
    suspend fun advancesPaidTotal():Double
}

@Dao interface SaleDao {
    @Insert suspend fun sale(s:Sale)
    @Insert suspend fun items(items:List<SaleItem>)
    @Query("SELECT COUNT(*) FROM sales") suspend fun count():Int
    @Query("SELECT COALESCE(SUM(total),0) FROM sales") suspend fun totalSales():Double
    @Query("SELECT COALESCE(SUM(total),0) FROM sales WHERE createdAt BETWEEN :start AND :end AND status!='returned'") suspend fun totalSalesBetween(start:Long,end:Long):Double
    @Query("SELECT COUNT(*) FROM sales WHERE createdAt BETWEEN :start AND :end AND status!='returned'") suspend fun countBetween(start:Long,end:Long):Int
    @Query("SELECT strftime('%Y-%m-%d', createdAt/1000, 'unixepoch', 'localtime') as day, COALESCE(SUM(total),0) as total FROM sales WHERE createdAt BETWEEN :start AND :end AND status!='returned' GROUP BY day ORDER BY day") suspend fun dailySales(start:Long,end:Long):List<DailySales>
    @Query("SELECT product, SUM(qty) as totalQty FROM sale_items WHERE invoice IN (SELECT invoice FROM sales WHERE createdAt BETWEEN :start AND :end AND status!='returned') GROUP BY product ORDER BY totalQty DESC LIMIT 5") suspend fun topProducts(start:Long,end:Long):List<TopProduct>
    @Query("SELECT invoice, COALESCE((SELECT name FROM customers WHERE customers.id=sales.customerId),'Walk-in') as customerName, total, paymentMethod, createdAt, status FROM sales ORDER BY createdAt DESC LIMIT 100") suspend fun allSales():List<SaleWithCustomer>
    @Query("SELECT * FROM sales WHERE customerId=:customerId ORDER BY createdAt DESC") suspend fun salesByCustomer(customerId:Long):List<Sale>
    @Query("SELECT * FROM sales WHERE invoice=:invoice LIMIT 1") suspend fun findSale(invoice:String):Sale?
    @Query("SELECT * FROM sale_items WHERE invoice=:invoice") suspend fun itemsForInvoice(invoice:String):List<SaleItem>
    @Query("DELETE FROM sale_items WHERE invoice=:invoice") suspend fun deleteItems(invoice:String)
    @Query("DELETE FROM sales WHERE invoice=:invoice") suspend fun deleteSale(invoice:String)
    @Query("UPDATE sales SET status='returned' WHERE invoice=:invoice") suspend fun markReturned(invoice:String)
    @Query("SELECT COALESCE(SUM(si.amount-si.cost),0) FROM sale_items si JOIN sales s ON si.invoice=s.invoice WHERE s.createdAt BETWEEN :start AND :end AND s.status!='returned'") suspend fun profitBetween(start:Long,end:Long):Double
    @Query("SELECT strftime('%Y-%m-%d', s.createdAt/1000,'unixepoch','localtime') as day, COALESCE(SUM(si.amount-si.cost),0) as profit FROM sale_items si JOIN sales s ON si.invoice=s.invoice WHERE s.createdAt BETWEEN :start AND :end AND s.status!='returned' GROUP BY day ORDER BY day") suspend fun dailyProfit(start:Long,end:Long):List<DailyProfit>
    @Query("SELECT COALESCE(SUM(si.cost),0) FROM sale_items si JOIN sales s ON si.invoice=s.invoice WHERE s.createdAt BETWEEN :start AND :end AND s.status!='returned'") suspend fun cogsBetween(start:Long,end:Long):Double
    @Query("SELECT si.product as product, COALESCE(SUM(si.amount),0) as totalAmount, COALESCE(SUM(si.qty),0) as totalQty FROM sale_items si JOIN sales s ON si.invoice=s.invoice WHERE s.customerId=:customerId AND s.status!='returned' GROUP BY si.product ORDER BY totalAmount DESC") suspend fun itemReportByCustomer(customerId:Long):List<PartyItemReport>
    @Query("SELECT COALESCE((SELECT name FROM customers WHERE customers.id=s.customerId),'Walk-in') as customerName, si.qty as qty, si.unitPrice as unitPrice, s.createdAt as createdAt FROM sale_items si JOIN sales s ON si.invoice=s.invoice WHERE si.barcode=:barcode ORDER BY s.createdAt DESC") suspend fun saleRecordsForItem(barcode:String):List<ItemSaleRecord>
    @Query("SELECT COALESCE(SUM(qty),0) FROM sale_items WHERE barcode=:barcode AND invoice IN (SELECT invoice FROM sales WHERE status='active')") suspend fun totalActiveQtySold(barcode:String):Int
    @Query("SELECT si.product as product, COALESCE(SUM(si.amount),0) as totalAmount, COALESCE(SUM(si.qty),0) as totalQty FROM sale_items si JOIN sales s ON si.invoice=s.invoice WHERE s.status!='returned' GROUP BY si.product ORDER BY totalAmount DESC") suspend fun allTimeItemTotals():List<PartyItemReport>
    @Query("SELECT invoice, COALESCE((SELECT name FROM customers WHERE customers.id=sales.customerId),'Walk-in') as customerName, total, paid, createdAt, status FROM sales WHERE createdAt BETWEEN :start AND :end ORDER BY createdAt ASC") suspend fun salesBetween(start:Long,end:Long):List<DayBookSale>
}

@Dao interface ExpenseDao {
    @Insert suspend fun insert(e:Expense): Long
    @Delete suspend fun delete(e:Expense)
    @Query("SELECT COALESCE(SUM(amount),0) FROM expenses") suspend fun total():Double
    @Query("SELECT COALESCE(SUM(amount),0) FROM expenses WHERE createdAt BETWEEN :start AND :end") suspend fun totalBetween(start:Long,end:Long):Double
    @Query("SELECT * FROM expenses ORDER BY createdAt DESC") fun all():Flow<List<Expense>>
    @Query("SELECT * FROM expenses WHERE createdAt BETWEEN :start AND :end ORDER BY createdAt ASC") suspend fun between(start:Long,end:Long):List<Expense>
}

@Dao interface HeldDao {
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun hold(h:HeldBill)
    @Query("SELECT * FROM held_bills ORDER BY createdAt DESC") fun all():Flow<List<HeldBill>>
    @Delete suspend fun delete(h:HeldBill)
}

@Dao interface PaymentDao {
    @Insert suspend fun insert(p:Payment)
    @Query("SELECT COALESCE(SUM(amount),0) FROM payments") suspend fun total():Double
    @Query("SELECT COALESCE(SUM(amount),0) FROM payments WHERE method=:method AND createdAt BETWEEN :start AND :end") suspend fun totalByMethodBetween(method:String,start:Long,end:Long):Double
    @Query("DELETE FROM payments WHERE reference=:ref") suspend fun deleteByReference(ref:String)
}

@Dao interface PurchaseDao {
    @Insert suspend fun purchase(p:Purchase)
    @Insert suspend fun items(items:List<PurchaseItem>)
    @Query("SELECT COALESCE(SUM(total),0) FROM purchases") suspend fun total():Double
    @Query("SELECT COALESCE(SUM(total),0) FROM purchases WHERE createdAt BETWEEN :start AND :end AND status!='returned'") suspend fun totalBetween(start:Long,end:Long):Double
    @Query("SELECT billNo, COALESCE((SELECT name FROM suppliers WHERE suppliers.id=purchases.supplierId),'Cash Purchase') as supplierName, total, createdAt, status FROM purchases ORDER BY createdAt DESC LIMIT 100") suspend fun allPurchases():List<PurchaseWithSupplier>
    @Query("SELECT * FROM purchases WHERE supplierId=:supplierId ORDER BY createdAt DESC") suspend fun purchasesBySupplier(supplierId:Long):List<Purchase>
    @Query("SELECT * FROM purchases WHERE billNo=:bill LIMIT 1") suspend fun findPurchase(bill:String):Purchase?
    @Query("SELECT * FROM purchase_items WHERE billNo=:bill") suspend fun itemsForBill(bill:String):List<PurchaseItem>
    @Query("DELETE FROM purchase_items WHERE billNo=:bill") suspend fun deleteItems(bill:String)
    @Query("DELETE FROM purchases WHERE billNo=:bill") suspend fun deletePurchase(bill:String)
    @Query("UPDATE purchases SET status='returned' WHERE billNo=:bill") suspend fun markReturned(bill:String)
    @Query("SELECT p.name as product, COALESCE(SUM(pi.amount),0) as totalAmount, COALESCE(SUM(pi.qty),0) as totalQty FROM purchase_items pi JOIN purchases pu ON pi.billNo=pu.billNo JOIN products p ON pi.barcode=p.barcode WHERE pu.supplierId=:supplierId AND pu.status!='returned' GROUP BY p.name ORDER BY totalAmount DESC") suspend fun itemReportBySupplier(supplierId:Long):List<PartyItemReport>
    @Query("SELECT COALESCE((SELECT name FROM suppliers WHERE suppliers.id=p.supplierId),'Cash Purchase') as supplierName, pi.qty as qty, pi.unitCost as unitCost, p.createdAt as createdAt FROM purchase_items pi JOIN purchases p ON pi.billNo=p.billNo WHERE pi.barcode=:barcode ORDER BY p.createdAt DESC") suspend fun purchaseRecordsForItem(barcode:String):List<ItemPurchaseRecord>
    @Query("SELECT p.name as product, COALESCE(SUM(pi.amount),0) as totalAmount, COALESCE(SUM(pi.qty),0) as totalQty FROM purchase_items pi JOIN purchases pu ON pi.billNo=pu.billNo JOIN products p ON pi.barcode=p.barcode WHERE pu.status!='returned' GROUP BY p.name ORDER BY totalAmount DESC") suspend fun allTimeItemTotals():List<PartyItemReport>
    @Query("SELECT billNo, COALESCE((SELECT name FROM suppliers WHERE suppliers.id=purchases.supplierId),'Cash Purchase') as supplierName, total, paid, createdAt, status FROM purchases WHERE createdAt BETWEEN :start AND :end ORDER BY createdAt ASC") suspend fun purchasesBetween(start:Long,end:Long):List<DayBookPurchase>
}

@Dao interface ReturnDao {
    @Insert suspend fun insert(r:ReturnLine)
    @Query("SELECT COALESCE(SUM(amount),0) FROM returns WHERE type=:type") suspend fun totalByType(type:String):Double
    @Query("SELECT COALESCE(SUM(amount),0) FROM returns WHERE type=:type AND createdAt BETWEEN :start AND :end") suspend fun totalByTypeBetween(type:String,start:Long,end:Long):Double
    @Query("SELECT * FROM returns WHERE reference=:reference") suspend fun forReference(reference:String):List<ReturnLine>
}

@Dao interface UserDao {
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(u:User)
    @Query("SELECT * FROM users WHERE username=:u AND active=1 LIMIT 1") suspend fun find(u:String):User?
    @Query("SELECT * FROM users WHERE username=:u LIMIT 1") suspend fun findByUsername(u:String):User?
    @Query("SELECT * FROM users WHERE phone=:phone AND active=1 LIMIT 1") suspend fun findByPhone(phone:String):User?
    @Query("SELECT * FROM users ORDER BY username") fun all():Flow<List<User>>
    @Query("DELETE FROM users WHERE username=:u") suspend fun delete(u:String)
}

@Dao interface AuditDao { @Insert suspend fun insert(a:Audit) }

@Dao interface CashTransactionDao {
    @Insert suspend fun insert(t:CashTransaction): Long
    @Query("SELECT * FROM cash_transactions ORDER BY createdAt DESC") fun all():Flow<List<CashTransaction>>
    @Query("SELECT COALESCE(SUM(amount),0) FROM cash_transactions WHERE type=:type AND method=:method AND createdAt BETWEEN :start AND :end") suspend fun totalBetween(type:String,method:String,start:Long,end:Long):Double
    // ADDED (Balance Sheet): all-time IN/OUT total per method, for the Cash/Bank asset lines.
    @Query("SELECT COALESCE(SUM(amount),0) FROM cash_transactions WHERE type=:type AND method=:method") suspend fun totalAll(type:String,method:String):Double
    @Query("DELETE FROM cash_transactions WHERE reference=:ref") suspend fun deleteByReference(ref:String)
    @Query("SELECT * FROM cash_transactions WHERE createdAt BETWEEN :start AND :end ORDER BY createdAt ASC") suspend fun between(start:Long,end:Long):List<CashTransaction>
}

@Dao interface CashRegisterDao {
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(r:CashRegister)
    @Query("SELECT * FROM cash_register WHERE date=:date LIMIT 1") suspend fun find(date:String):CashRegister?
    @Query("SELECT * FROM cash_register ORDER BY date DESC") fun all():Flow<List<CashRegister>>
}

@Dao interface AppSettingDao {
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun set(s:AppSetting)
    @Query("SELECT * FROM app_settings WHERE key=:key LIMIT 1") suspend fun get(key:String):AppSetting?
    @Query("SELECT * FROM app_settings") fun all():Flow<List<AppSetting>>
}

@Dao interface SyncQueueDao {
    @Insert suspend fun enqueue(e: SyncQueueEntry): Long
    @Query("SELECT * FROM sync_queue WHERE syncedAt IS NULL ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pending(limit: Int = 50): List<SyncQueueEntry>
    @Query("UPDATE sync_queue SET syncedAt=:ts WHERE id=:id")
    suspend fun markSynced(id: Long, ts: Long = System.currentTimeMillis())
    @Query("UPDATE sync_queue SET retryCount=retryCount+1, lastError=:err WHERE id=:id")
    suspend fun markFailed(id: Long, err: String)
    @Query("DELETE FROM sync_queue WHERE syncedAt IS NOT NULL AND syncedAt < :before")
    suspend fun pruneSynced(before: Long)
    @Query("SELECT COUNT(*) FROM sync_queue WHERE syncedAt IS NULL")
    fun pendingCountFlow(): Flow<Int>
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE purchase_items ADD COLUMN unit TEXT NOT NULL DEFAULT ''")
    }
}
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE customers ADD COLUMN openingBalance REAL NOT NULL DEFAULT 0.0")
        database.execSQL("ALTER TABLE suppliers ADD COLUMN openingBalance REAL NOT NULL DEFAULT 0.0")
    }
}
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sales ADD COLUMN status TEXT NOT NULL DEFAULT 'active'")
        database.execSQL("ALTER TABLE purchases ADD COLUMN status TEXT NOT NULL DEFAULT 'active'")
    }
}
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE products ADD COLUMN tertiaryUnit TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE products ADD COLUMN tertiaryUnitQty REAL NOT NULL DEFAULT 0.0")
    }
}
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE TABLE purchase_items_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, billNo TEXT NOT NULL, barcode TEXT NOT NULL, qty REAL NOT NULL, unitCost REAL NOT NULL, amount REAL NOT NULL, unit TEXT NOT NULL DEFAULT '')")
        database.execSQL("INSERT INTO purchase_items_new (id, billNo, barcode, qty, unitCost, amount, unit) SELECT id, billNo, barcode, qty, unitCost, amount, unit FROM purchase_items")
        database.execSQL("DROP TABLE purchase_items")
        database.execSQL("ALTER TABLE purchase_items_new RENAME TO purchase_items")
        database.execSQL("CREATE TABLE returns_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, reference TEXT NOT NULL, type TEXT NOT NULL, barcode TEXT NOT NULL, qty REAL NOT NULL, amount REAL NOT NULL, createdAt INTEGER NOT NULL)")
        database.execSQL("INSERT INTO returns_new (id, reference, type, barcode, qty, amount, createdAt) SELECT id, reference, type, barcode, qty, amount, createdAt FROM returns")
        database.execSQL("DROP TABLE returns")
        database.execSQL("ALTER TABLE returns_new RENAME TO returns")
    }
}
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(database: SupportSQLiteDatabase) {
        val factorExpr = "(CASE WHEN secondaryUnit != '' AND secondaryUnitQty > 0 THEN " +
            "secondaryUnitQty * (CASE WHEN tertiaryUnit != '' AND tertiaryUnitQty > 0 THEN tertiaryUnitQty ELSE 1 END) " +
            "ELSE 1 END)"
        database.execSQL("UPDATE products SET stock = CAST(ROUND(stock * $factorExpr) AS INTEGER)")
        database.execSQL("UPDATE products SET openingStock = CAST(ROUND(openingStock * $factorExpr) AS INTEGER)")
    }
}
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sale_items ADD COLUMN unit TEXT NOT NULL DEFAULT ''")
    }
}
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE sync_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                entityType TEXT NOT NULL,
                entityId TEXT NOT NULL,
                operation TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                syncedAt INTEGER,
                retryCount INTEGER NOT NULL DEFAULT 0,
                lastError TEXT
            )
        """.trimIndent())

        for (table in listOf("customers", "suppliers", "payments", "expenses", "cash_transactions")) {
            database.execSQL("ALTER TABLE $table ADD COLUMN serverId TEXT")
            database.execSQL("ALTER TABLE $table ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE $table ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
        }
        for (table in listOf("sales", "purchases", "products")) {
            database.execSQL("ALTER TABLE $table ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE $table ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
        }
    }
}

val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(database: SupportSQLiteDatabase) {
        val factorExpr = "(CASE WHEN secondaryUnit != '' AND secondaryUnitQty > 0 THEN " +
            "secondaryUnitQty * (CASE WHEN tertiaryUnit != '' AND tertiaryUnitQty > 0 THEN tertiaryUnitQty ELSE 1 END) " +
            "ELSE 1 END)"
        database.execSQL("UPDATE products SET reorderLevel = CAST(ROUND(reorderLevel * $factorExpr) AS INTEGER)")
    }
}

val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE users ADD COLUMN phone TEXT NOT NULL DEFAULT ''")
    }
}

// FIX (fractional qty consistency): sale_items.qty was INTEGER, unlike purchase_items.qty
// and returns.qty which are already REAL (see MIGRATION_17_18). SQLite can't ALTER COLUMN
// a type directly, so this recreates the table with qty REAL, same table-recreate pattern
// as MIGRATION_17_18 used for purchase_items/returns. Existing rows keep their (already
// rounded) values — this only stops NEW sales from losing their fractional qty.
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE TABLE sale_items_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, invoice TEXT NOT NULL, barcode TEXT NOT NULL, product TEXT NOT NULL, qty REAL NOT NULL, unit TEXT NOT NULL DEFAULT '', unitPrice REAL NOT NULL, cost REAL NOT NULL, amount REAL NOT NULL)")
        database.execSQL("INSERT INTO sale_items_new (id, invoice, barcode, product, qty, unit, unitPrice, cost, amount) SELECT id, invoice, barcode, product, qty, unit, unitPrice, cost, amount FROM sale_items")
        database.execSQL("DROP TABLE sale_items")
        database.execSQL("ALTER TABLE sale_items_new RENAME TO sale_items")
    }
}

// FIX (fraction control): products.stock/reorderLevel/openingStock were INTEGER,
// so any product whose smallest unit is Gram/ml (Sugar, Daal, etc.) lost its
// fractional part on every purchase/sale/return — e.g. 2.5 Kg silently became
// 2 or 3 Kg. SQLite can't ALTER COLUMN type, so this recreates the table with
// those three columns as REAL, same table-recreate pattern as MIGRATION_17_18/
// MIGRATION_23_24. Existing rows keep their (already rounded) values — this
// only stops NEW purchases/sales from losing precision going forward.
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE products_new (
                barcode TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                cost REAL NOT NULL DEFAULT 0.0,
                salePrice REAL NOT NULL DEFAULT 0.0,
                stock REAL NOT NULL DEFAULT 0.0,
                reorderLevel REAL NOT NULL DEFAULT 0.0,
                expiry TEXT NOT NULL DEFAULT '',
                unit TEXT NOT NULL DEFAULT 'pcs',
                unitSize INTEGER NOT NULL DEFAULT 1,
                unitNote TEXT NOT NULL DEFAULT '',
                secondaryUnit TEXT NOT NULL DEFAULT '',
                secondaryUnitQty REAL NOT NULL DEFAULT 0.0,
                wholesalePrice REAL NOT NULL DEFAULT 0.0,
                openingStock REAL NOT NULL DEFAULT 0.0,
                tertiaryUnit TEXT NOT NULL DEFAULT '',
                tertiaryUnitQty REAL NOT NULL DEFAULT 0.0,
                updatedAt INTEGER NOT NULL DEFAULT 0,
                dirty INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
        database.execSQL("""
            INSERT INTO products_new SELECT
                barcode, name, category, cost, salePrice, stock, reorderLevel, expiry,
                unit, unitSize, unitNote, secondaryUnit, secondaryUnitQty, wholesalePrice,
                openingStock, tertiaryUnit, tertiaryUnitQty, updatedAt, dirty
            FROM products
        """.trimIndent())
        database.execSQL("DROP TABLE products")
        database.execSQL("ALTER TABLE products_new RENAME TO products")
    }
}

@Database(
    entities=[Product::class,Customer::class,Supplier::class,Sale::class,SaleItem::class,
        Payment::class,Purchase::class,PurchaseItem::class,ReturnLine::class,User::class,Audit::class,
        Expense::class,HeldBill::class,UnitType::class,Category::class,CashTransaction::class,
        CashRegister::class,AppSetting::class,SyncQueueEntry::class],
    version=25, exportSchema=false
)
abstract class PosDatabase:RoomDatabase(){
    abstract fun productDao():ProductDao
    abstract fun customerDao():CustomerDao
    abstract fun supplierDao():SupplierDao
    abstract fun saleDao():SaleDao
    abstract fun expenseDao():ExpenseDao
    abstract fun paymentDao():PaymentDao
    abstract fun purchaseDao():PurchaseDao
    abstract fun returnDao():ReturnDao
    abstract fun userDao():UserDao
    abstract fun auditDao():AuditDao
    abstract fun heldDao():HeldDao
    abstract fun unitDao():UnitDao
    abstract fun categoryDao():CategoryDao
    abstract fun cashTransactionDao():CashTransactionDao
    abstract fun cashRegisterDao():CashRegisterDao
    abstract fun appSettingDao():AppSettingDao
    abstract fun syncQueueDao():SyncQueueDao
    companion object{
        @Volatile private var INSTANCE:PosDatabase?=null
        fun get(c:Context)=INSTANCE?: synchronized(this){
            INSTANCE?:Room.databaseBuilder(c.applicationContext,PosDatabase::class.java,"grocery_pos_v11.db")
                .addMigrations(MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25)
                .build().also{INSTANCE=it}
        }
        fun closeInstance() { INSTANCE?.close(); INSTANCE = null }
    }
}
