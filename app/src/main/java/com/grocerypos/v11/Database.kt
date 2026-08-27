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

// ---- Day Book (Roznamcha) â€” merged chronological ledger read models ----
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
    // Piece/Bottle/Dabbi) the UI still enforces whole numbers â€” see isFractionalUnit()
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
// touches stock â€” Purchase, Purchase Return, Sale, Sale Return, Product opening
// stock â€” must go through these functions instead of re-deriving the math
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
 * primary unit, factor 1) â€” a plain 1-unit product. A 2-tier product (primary +
 * secondary, no tertiary) has 2 tiers with the secondary as smallest. A 3-tier
 * product has all 3, with tertiary as smallest.
 *
 * A malformed config (e.g. a tertiary unit typed in without a valid secondary)
 * degrades gracefully to a lower tier instead of producing a wrong/blown-up
 * factor â€” this is the single place that decides "is this product 1/2/3 tier",
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
 * call before adjusting `stock` â€” never re-derive this math locally.
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
 * product â€” sharing this with toSmallestUnits keeps both directions in sync.
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
 * toSmallestUnits()/fromSmallestUnits() â€” previously PricingTierMath.kt kept a
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
// indivisible â€” entries that would produce a fractional smallest-unit qty for
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
 * display can never drift out of sync with what's actually stored/converted â€”
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
            // FIX: smallest tier can be fractional (Gram/ml) â€” show up to 3
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
// the moment a sale was saved â€” the printed bill, sale history, item reports, and any
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
    @Query("SELECT DISTINCT category FROM products WHERE category!=''")
suspend fun distinctCategories(): List<String>
@Query("SELECT DISTINCT unit FROM products WHERE unit!=''")
suspend fun distinctPrimaryUnits(): List<String>
@Query("SELECT DISTINCT secondaryUnit FROM products WHERE secondaryUnit!=''")
suspend fun distinctSecondaryUnits(): List<String>
@Query("SELECT DISTINCT tertiaryUnit FROM products WHERE tertiaryUnit!=''")
suspend fun distinctTertiaryUnits(): List<String>

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
}

@Dao interface CategoryDao {
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insert(c:Category)
    @Query("SELECT * FROM categories ORDER BY name") fun all():Flow<List<Category>>
}

@Dao interface CustomerDao {
    @Insert suspend fun insert(c:Customer):Long
    @Update suspend fun update(c:Customer)
    @Delete suspend fun delete(c:Customer)
    @Query("
