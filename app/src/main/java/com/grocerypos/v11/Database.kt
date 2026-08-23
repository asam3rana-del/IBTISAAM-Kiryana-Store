package com.grocerypos.v11

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

data class DailySales(val day:String,val total:Double)
data class TopProduct(val product:String,val totalQty:Int)
data class PurchaseWithSupplier(val billNo:String,val supplierName:String,val total:Double,val createdAt:Long,val status:String)
data class SupplierPurchaseTotal(val supplierName:String,val total:Double)
data class SaleWithCustomer(val invoice:String,val customerName:String,val total:Double,val paymentMethod:String,val createdAt:Long,val status:String)
data class CustomerSalesTotal(val customerName:String,val total:Double)
data class DailyProfit(val day:String,val profit:Double)
data class PartyItemReport(val product:String,val totalAmount:Double,val totalQty:Int)
data class ItemSaleRecord(val customerName:String,val qty:Int,val unitPrice:Double,val createdAt:Long)
data class ItemPurchaseRecord(val supplierName:String,val qty:Double,val unitCost:Double,val createdAt:Long)

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
    val stock:Int=0,
    val reorderLevel:Int=0,
    val expiry:String="",
    val unit:String="pcs",
    val unitSize:Int=1,
    val unitNote:String="",
    val secondaryUnit:String="",
    val secondaryUnitQty:Double=0.0,
    val wholesalePrice:Double=0.0,
    val openingStock:Int=0,
    val tertiaryUnit:String="",
    val tertiaryUnitQty:Double=0.0,
    val updatedAt:Long=0L,
    val dirty:Boolean=true
)

// ================= 3-tier unit conversion helpers =================
fun Product.smallestUnitFactor(): Double {
    if (secondaryUnit.isBlank() || secondaryUnitQty <= 0) return 1.0
    return if (tertiaryUnit.isNotBlank() && tertiaryUnitQty > 0) secondaryUnitQty * tertiaryUnitQty else secondaryUnitQty
}

fun Product.smallestPerSecondary(): Double =
    if (tertiaryUnit.isNotBlank() && tertiaryUnitQty > 0) tertiaryUnitQty else 1.0

fun Product.smallestUnitName(): String = when {
    tertiaryUnit.isNotBlank() && tertiaryUnitQty > 0 && secondaryUnitQty > 0 -> tertiaryUnit
    secondaryUnit.isNotBlank() && secondaryUnitQty > 0 -> secondaryUnit
    else -> unit
}

fun Product.toSmallestUnits(qty: Double, enteredUnit: String): Double = when {
    enteredUnit == tertiaryUnit && tertiaryUnit.isNotBlank() && tertiaryUnitQty > 0 && secondaryUnitQty > 0 -> qty
    enteredUnit == secondaryUnit && secondaryUnitQty > 0 -> qty * smallestPerSecondary()
    else -> qty * smallestUnitFactor()
}

fun Product.formatStockBreakdown(): String {
    val total = stock
    if (secondaryUnit.isBlank() || secondaryUnitQty <= 0) return "$total $unit"

    val perSecondary = smallestPerSecondary().toInt().coerceAtLeast(1)
    val secondaryPerPrimary = secondaryUnitQty.toInt().coerceAtLeast(1)

    val secondaryTotal = total / perSecondary
    val remSmallest = total % perSecondary
    val primaryCount = secondaryTotal / secondaryPerPrimary
    val remSecondary = secondaryTotal % secondaryPerPrimary

    val parts = mutableListOf<String>()
    if (primaryCount > 0) parts.add("$primaryCount $unit")
    if (remSecondary > 0) parts.add("$remSecondary $secondaryUnit")
    if (tertiaryUnit.isNotBlank() && tertiaryUnitQty > 0 && remSmallest > 0) parts.add("$remSmallest $tertiaryUnit")
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

@Entity(tableName="sale_items")
data class SaleItem(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val invoice:String,
    val barcode:String,
    val product:String,
    val qty:Int,
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
    val active:Boolean=true
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
    suspend fun decrease(code:String,qty:Int):Int
    @Query("UPDATE products SET stock=stock-:qty WHERE barcode=:code")
    suspend fun decreaseForce(code:String,qty:Int)
    @Query("UPDATE products SET stock=stock+:qty WHERE barcode=:code")
    suspend fun increase(code:String,qty:Int)
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
    @Query("SELECT * FROM customers WHERE id=:id LIMIT 1") suspend fun find(id:Long):Customer?
    // NEW: needed so Firestore pulls can upsert by serverId instead of always
    // inserting a fresh row (which would duplicate the customer on every pull).
    @Query("SELECT * FROM customers WHERE serverId=:serverId LIMIT 1") suspend fun findByServerId(serverId:String):Customer?
    @Query("SELECT * FROM customers ORDER BY name") fun all():Flow<List<Customer>>
    @Query("UPDATE customers SET balance=balance+:amt WHERE id=:id")
    suspend fun addBalance(id:Long,amt:Double)
    @Query("SELECT COALESCE(name,'Walk-in') as customerName, SUM(total) as total FROM sales LEFT JOIN customers ON sales.customerId=customers.id GROUP BY customerId ORDER BY total DESC")
    suspend fun salesTotalsByCustomer():List<CustomerSalesTotal>
}

@Dao interface SupplierDao {
    @Insert suspend fun insert(s:Supplier):Long
    @Update suspend fun update(s:Supplier)
    @Delete suspend fun delete(s:Supplier)
    @Query("SELECT * FROM suppliers WHERE id=:id LIMIT 1") suspend fun find(id:Long):Supplier?
    // NEW: same reasoning as CustomerDao.findByServerId above.
    @Query("SELECT * FROM suppliers WHERE serverId=:serverId LIMIT 1") suspend fun findByServerId(serverId:String):Supplier?
    @Query("UPDATE suppliers SET balance=balance+:amt WHERE id=:id") suspend fun addBalance(id:Long,amt:Double)
    @Query("SELECT * FROM suppliers ORDER BY name") fun all():Flow<List<Supplier>>
    @Query("SELECT COALESCE(name,'Cash Purchase') as supplierName, SUM(total) as total FROM purchases LEFT JOIN suppliers ON purchases.supplierId=suppliers.id GROUP BY supplierId ORDER BY total DESC")
    suspend fun purchaseTotalsBySupplier():List<SupplierPurchaseTotal>
}

@Dao interface SaleDao {
    @Insert suspend fun sale(s:Sale)
    @Insert suspend fun items(items:List<SaleItem>)
    @Query("SELECT COUNT(*) FROM sales") suspend fun count():Int
    @Query("SELECT COALESCE(SUM(total),0) FROM sales") suspend fun totalSales():Double
    @Query("SELECT COALESCE(SUM(total),0) FROM sales WHERE createdAt BETWEEN :start AND :end") suspend fun totalSalesBetween(start:Long,end:Long):Double
    @Query("SELECT COUNT(*) FROM sales WHERE createdAt BETWEEN :start AND :end") suspend fun countBetween(start:Long,end:Long):Int
    @Query("SELECT strftime('%Y-%m-%d', createdAt/1000, 'unixepoch', 'localtime') as day, COALESCE(SUM(total),0) as total FROM sales WHERE createdAt BETWEEN :start AND :end GROUP BY day ORDER BY day") suspend fun dailySales(start:Long,end:Long):List<DailySales>
    @Query("SELECT product, SUM(qty) as totalQty FROM sale_items WHERE invoice IN (SELECT invoice FROM sales WHERE createdAt BETWEEN :start AND :end) GROUP BY product ORDER BY totalQty DESC LIMIT 5") suspend fun topProducts(start:Long,end:Long):List<TopProduct>
    @Query("SELECT invoice, COALESCE((SELECT name FROM customers WHERE customers.id=sales.customerId),'Walk-in') as customerName, total, paymentMethod, createdAt, status FROM sales ORDER BY createdAt DESC LIMIT 100") suspend fun allSales():List<SaleWithCustomer>
    @Query("SELECT * FROM sales WHERE customerId=:customerId ORDER BY createdAt DESC") suspend fun salesByCustomer(customerId:Long):List<Sale>
    @Query("SELECT * FROM sales WHERE invoice=:invoice LIMIT 1") suspend fun findSale(invoice:String):Sale?
    @Query("SELECT * FROM sale_items WHERE invoice=:invoice") suspend fun itemsForInvoice(invoice:String):List<SaleItem>
    @Query("DELETE FROM sale_items WHERE invoice=:invoice") suspend fun deleteItems(invoice:String)
    @Query("DELETE FROM sales WHERE invoice=:invoice") suspend fun deleteSale(invoice:String)
    @Query("UPDATE sales SET status='returned' WHERE invoice=:invoice") suspend fun markReturned(invoice:String)
    @Query("SELECT COALESCE(SUM(si.amount-si.cost),0) FROM sale_items si JOIN sales s ON si.invoice=s.invoice WHERE s.createdAt BETWEEN :start AND :end") suspend fun profitBetween(start:Long,end:Long):Double
    @Query("SELECT strftime('%Y-%m-%d', s.createdAt/1000,'unixepoch','localtime') as day, COALESCE(SUM(si.amount-si.cost),0) as profit FROM sale_items si JOIN sales s ON si.invoice=s.invoice WHERE s.createdAt BETWEEN :start AND :end GROUP BY day ORDER BY day") suspend fun dailyProfit(start:Long,end:Long):List<DailyProfit>
    @Query("SELECT COALESCE(SUM(si.cost),0) FROM sale_items si JOIN sales s ON si.invoice=s.invoice WHERE s.createdAt BETWEEN :start AND :end") suspend fun cogsBetween(start:Long,end:Long):Double
    @Query("SELECT si.product as product, COALESCE(SUM(si.amount),0) as totalAmount, COALESCE(SUM(si.qty),0) as totalQty FROM sale_items si JOIN sales s ON si.invoice=s.invoice WHERE s.customerId=:customerId GROUP BY si.product ORDER BY totalAmount DESC") suspend fun itemReportByCustomer(customerId:Long):List<PartyItemReport>
    @Query("SELECT COALESCE((SELECT name FROM customers WHERE customers.id=s.customerId),'Walk-in') as customerName, si.qty as qty, si.unitPrice as unitPrice, s.createdAt as createdAt FROM sale_items si JOIN sales s ON si.invoice=s.invoice WHERE si.barcode=:barcode ORDER BY s.createdAt DESC") suspend fun saleRecordsForItem(barcode:String):List<ItemSaleRecord>
    @Query("SELECT COALESCE(SUM(qty),0) FROM sale_items WHERE barcode=:barcode AND invoice IN (SELECT invoice FROM sales WHERE status='active')") suspend fun totalActiveQtySold(barcode:String):Int
    @Query("SELECT si.product as product, COALESCE(SUM(si.amount),0) as totalAmount, COALESCE(SUM(si.qty),0) as totalQty FROM sale_items si GROUP BY si.product ORDER BY totalAmount DESC") suspend fun allTimeItemTotals():List<PartyItemReport>
}

@Dao interface ExpenseDao {
    @Insert suspend fun insert(e:Expense)
    @Delete suspend fun delete(e:Expense)
    @Query("SELECT COALESCE(SUM(amount),0) FROM expenses") suspend fun total():Double
    @Query("SELECT COALESCE(SUM(amount),0) FROM expenses WHERE createdAt BETWEEN :start AND :end") suspend fun totalBetween(start:Long,end:Long):Double
    @Query("SELECT * FROM expenses ORDER BY createdAt DESC") fun all():Flow<List<Expense>>
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
    @Query("SELECT COALESCE(SUM(total),0) FROM purchases WHERE createdAt BETWEEN :start AND :end") suspend fun totalBetween(start:Long,end:Long):Double
    @Query("SELECT billNo, COALESCE((SELECT name FROM suppliers WHERE suppliers.id=purchases.supplierId),'Cash Purchase') as supplierName, total, createdAt, status FROM purchases ORDER BY createdAt DESC LIMIT 100") suspend fun allPurchases():List<PurchaseWithSupplier>
    @Query("SELECT * FROM purchases WHERE supplierId=:supplierId ORDER BY createdAt DESC") suspend fun purchasesBySupplier(supplierId:Long):List<Purchase>
    @Query("SELECT * FROM purchases WHERE billNo=:bill LIMIT 1") suspend fun findPurchase(bill:String):Purchase?
    @Query("SELECT * FROM purchase_items WHERE billNo=:bill") suspend fun itemsForBill(bill:String):List<PurchaseItem>
    @Query("DELETE FROM purchase_items WHERE billNo=:bill") suspend fun deleteItems(bill:String)
    @Query("DELETE FROM purchases WHERE billNo=:bill") suspend fun deletePurchase(bill:String)
    @Query("UPDATE purchases SET status='returned' WHERE billNo=:bill") suspend fun markReturned(bill:String)
    @Query("SELECT p.name as product, COALESCE(SUM(pi.amount),0) as totalAmount, COALESCE(SUM(pi.qty),0) as totalQty FROM purchase_items pi JOIN purchases pu ON pi.billNo=pu.billNo JOIN products p ON pi.barcode=p.barcode WHERE pu.supplierId=:supplierId GROUP BY p.name ORDER BY totalAmount DESC") suspend fun itemReportBySupplier(supplierId:Long):List<PartyItemReport>
    @Query("SELECT COALESCE((SELECT name FROM suppliers WHERE suppliers.id=p.supplierId),'Cash Purchase') as supplierName, pi.qty as qty, pi.unitCost as unitCost, p.createdAt as createdAt FROM purchase_items pi JOIN purchases p ON pi.billNo=p.billNo WHERE pi.barcode=:barcode ORDER BY p.createdAt DESC") suspend fun purchaseRecordsForItem(barcode:String):List<ItemPurchaseRecord>
    @Query("SELECT p.name as product, COALESCE(SUM(pi.amount),0) as totalAmount, COALESCE(SUM(pi.qty),0) as totalQty FROM purchase_items pi JOIN products p ON pi.barcode=p.barcode GROUP BY p.name ORDER BY totalAmount DESC") suspend fun allTimeItemTotals():List<PartyItemReport>
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
    @Query("SELECT * FROM users ORDER BY username") fun all():Flow<List<User>>
    @Query("DELETE FROM users WHERE username=:u") suspend fun delete(u:String)
}

@Dao interface AuditDao { @Insert suspend fun insert(a:Audit) }

@Dao interface CashTransactionDao {
    @Insert suspend fun insert(t:CashTransaction)
    @Query("SELECT * FROM cash_transactions ORDER BY createdAt DESC") fun all():Flow<List<CashTransaction>>
    @Query("SELECT COALESCE(SUM(amount),0) FROM cash_transactions WHERE type=:type AND method=:method AND createdAt BETWEEN :start AND :end") suspend fun totalBetween(type:String,method:String,start:Long,end:Long):Double
    @Query("DELETE FROM cash_transactions WHERE reference=:ref") suspend fun deleteByReference(ref:String)
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

// ---- FIX: MIGRATION_18_19 rescaled `stock` and `openingStock` from "primary unit" counts to
// "smallest unit" counts (e.g. dozens -> pcs) but left `reorderLevel` untouched. Now that
// ProductActivity exposes a reorder-level field (entered in smallest units, to match how
// `stock<=reorderLevel` is compared), any reorderLevel set on an install that predates this
// migration and went through MIGRATION_18_19 needs the same rescale applied, or it will be
// off by the same factor as stock/openingStock were before that migration. Safe to run even
// on installs where reorderLevel is still 0 for every product (0 * factor = 0).
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(database: SupportSQLiteDatabase) {
        val factorExpr = "(CASE WHEN secondaryUnit != '' AND secondaryUnitQty > 0 THEN " +
            "secondaryUnitQty * (CASE WHEN tertiaryUnit != '' AND tertiaryUnitQty > 0 THEN tertiaryUnitQty ELSE 1 END) " +
            "ELSE 1 END)"
        database.execSQL("UPDATE products SET reorderLevel = CAST(ROUND(reorderLevel * $factorExpr) AS INTEGER)")
    }
}

@Database(
    entities=[Product::class,Customer::class,Supplier::class,Sale::class,SaleItem::class,
        Payment::class,Purchase::class,PurchaseItem::class,ReturnLine::class,User::class,Audit::class,
        Expense::class,HeldBill::class,UnitType::class,Category::class,CashTransaction::class,
        CashRegister::class,AppSetting::class,SyncQueueEntry::class],
    version=22, exportSchema=false
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
                .addMigrations(MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
                .build().also{INSTANCE=it}
        }
        fun closeInstance() { INSTANCE?.close(); INSTANCE = null }
    }
}
