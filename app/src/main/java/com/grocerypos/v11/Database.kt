package com.grocerypos.v11

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

typealias UnitType = UnitEntity

@Entity(tableName = "products")
data class Product(
    @PrimaryKey val barcode: String,
    val name: String,
    val category: String = "General",
    val cost: Double = 0.0,
    val salePrice: Double = 0.0,
    val wholesalePrice: Double = 0.0,
    val stock: Int = 0,
    val openingStock: Int = 0,
    val unit: String = "ctn",
    val secondaryUnit: String = "",
    val secondaryUnitQty: Double = 0.0,
    val tertiaryUnit: String = "",
    val tertiaryUnitQty: Double = 0.0
)

@Entity(tableName = "categories")
data class Category(@PrimaryKey val name: String)

@Entity(tableName = "units")
data class UnitEntity(@PrimaryKey val name: String)

@Entity(tableName = "users")
data class User(
    @PrimaryKey val username: String,
    val displayName: String = "",
    val role: String = "cashier",
    val passwordHash: String = "",
    val active: Boolean = true
)

@Entity(tableName = "app_settings")
data class AppSetting(@PrimaryKey val key: String, val value: String = "")

@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val phone: String = "",
    val balance: Double = 0.0,
    val openingBalance: Double = 0.0,
    val creditLimit: Double = 0.0
)

@Entity(tableName = "suppliers")
data class Supplier(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val phone: String = "",
    val balance: Double = 0.0,
    val openingBalance: Double = 0.0
)

@Entity(tableName = "sales")
data class Sale(
    @PrimaryKey val invoice: String,
    val customerId: Long? = null,
    val customerName: String = "Walk-in",
    val subtotal: Double = 0.0,
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    val total: Double = 0.0,
    val paid: Double = 0.0,
    val paymentMethod: String = "cash",
    val saleType: String = "retail",
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "completed",
    val date: Long = System.currentTimeMillis()
)

@Entity(tableName = "sale_items")
data class SaleItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val invoice: String,
    val barcode: String,
    val product: String,
    val qty: Int,
    val unit: String = "",
    val unitPrice: Double = 0.0,
    val cost: Double = 0.0,
    val amount: Double = 0.0
)

@Entity(tableName = "purchases")
data class Purchase(
    @PrimaryKey val billNo: String,
    val supplierId: Long? = null,
    val supplierName: String = "Unknown",
    val total: Double = 0.0,
    val paid: Double = 0.0,
    val status: String = "completed",
    val createdAt: Long = System.currentTimeMillis(),
    val date: Long = System.currentTimeMillis()
)

@Entity(tableName = "purchase_items")
data class PurchaseItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val billNo: String,
    val barcode: String,
    val qty: Double = 0.0,
    val unit: String = "",
    val unitCost: Double = 0.0,
    val amount: Double = 0.0
)

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val date: Long = System.currentTimeMillis(),
    val note: String = ""
)

@Entity(tableName = "return_lines")
data class ReturnLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reference: String = "",
    val type: String = "sale",
    val barcode: String = "",
    val qty: Double = 0.0,
    val amount: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "returns")
data class ReturnRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String = "sale",
    val amount: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val date: Long = System.currentTimeMillis()
)

@Entity(tableName = "payments")
data class Payment(@PrimaryKey(autoGenerate = true) val id: Int = 0, val reference: String = "", val amount: Double = 0.0)

@Entity(tableName = "cash_transactions")
data class CashTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String = "IN",
    val method: String = "cash",
    val amount: Double = 0.0,
    val reason: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val date: Long = System.currentTimeMillis(),
    val reference: String = ""
)

data class SaleWithCustomer(val invoice: String, val customerName: String, val total: Double, val paymentMethod: String, val createdAt: Long, val status: String, val customerId: Long?, val paid: Double)
data class PurchaseWithSupplier(val billNo: String, val supplierName: String, val total: Double, val createdAt: Long, val status: String, val paid: Double, val supplierId: Long?)
data class TopProduct(val product: String, val totalQty: Int, val totalAmount: Double)
data class DailySale(val day: String, val total: Double)
data class AllTimeItemTotal(val product: String, val totalQty: Int, val totalAmount: Double)
data class SaleRecord(val customerName: String, val qty: Int, val unitPrice: Double, val createdAt: Long)
data class PurchaseRecord(val supplierName: String, val qty: Double, val unitCost: Double, val createdAt: Long)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY name ASC")
    fun all(): Flow<List<Product>>
    @Query("SELECT * FROM products WHERE barcode = :code LIMIT 1")
    suspend fun find(code: String): Product?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: Product)
    @Query("DELETE FROM products WHERE barcode = :code")
    suspend fun deleteByCode(code: String)
    @Delete
    suspend fun delete(p: Product)
    @Query("UPDATE products SET stock = stock - :qty WHERE barcode = :code")
    suspend fun decreaseStock(code: String, qty: Int)
    @Query("UPDATE products SET stock = stock + :qty WHERE barcode = :code")
    suspend fun increaseStock(code: String, qty: Int)
    @Query("UPDATE products SET stock = stock - :qty WHERE barcode = :code")
    suspend fun decrease(code: String, qty: Int)
    @Query("UPDATE products SET stock = stock + :qty WHERE barcode = :code")
    suspend fun increase(code: String, qty: Int)
    @Query("UPDATE products SET stock = stock - :qty WHERE barcode = :code")
    suspend fun decreaseForce(code: String, qty: Int)
    @Query("SELECT * FROM products WHERE name LIKE '%' || :q || '%' LIMIT 20")
    suspend fun search(q: String): List<Product>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun all(): Flow<List<Category>>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: Category)
}

@Dao
interface UnitDao {
    @Query("SELECT * FROM units ORDER BY name ASC")
    fun all(): Flow<List<UnitEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(u: UnitEntity)
    @Delete
    suspend fun delete(u: UnitEntity)
    @Query("DELETE FROM units WHERE name = :name")
    suspend fun deleteByName(name: String)
}

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY username ASC")
    fun all(): Flow<List<User>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: User)
    @Query("DELETE FROM users WHERE username = :username")
    suspend fun delete(username: String)
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun find(username: String): User?
}

@Dao
interface AppSettingDao {
    @Query("SELECT * FROM app_settings WHERE key = :key LIMIT 1")
    suspend fun get(key: String): AppSetting?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(setting: AppSetting)
    @Query("SELECT * FROM app_settings")
    fun all(): Flow<List<AppSetting>>
}

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY name ASC")
    fun all(): Flow<List<Customer>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: Customer)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(c: Customer)
    @Update
    suspend fun update(c: Customer)
    @Delete
    suspend fun delete(c: Customer)
    @Query("UPDATE customers SET balance = balance + :amount WHERE id = :id")
    suspend fun addBalance(id: Long, amount: Double)
    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): Customer?
}

@Dao
interface SupplierDao {
    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    fun all(): Flow<List<Supplier>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: Supplier)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: Supplier)
    @Update
    suspend fun update(s: Supplier)
    @Delete
    suspend fun delete(s: Supplier)
    @Query("UPDATE suppliers SET balance = balance + :amount WHERE id = :id")
    suspend fun addBalance(id: Long, amount: Double)
    @Query("SELECT * FROM suppliers WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): Supplier?
}

@Dao
interface SaleDao {
    @Query("UPDATE sales SET status = 'returned' WHERE invoice = :invoice")
    suspend fun markReturned(invoice: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun sale(s: Sale)
    @Insert
    suspend fun items(items: List<SaleItem>)
    @Query("SELECT invoice, customerName, total, paymentMethod, createdAt, status, customerId, paid FROM sales ORDER BY createdAt DESC")
    suspend fun allSales(): List<SaleWithCustomer>
    @Query("SELECT * FROM sales WHERE customerId = :customerId ORDER BY createdAt DESC")
    suspend fun salesByCustomer(customerId: Long): List<Sale>
    @Query("SELECT * FROM sale_items WHERE invoice = :invoice")
    suspend fun itemsForInvoice(invoice: String): List<SaleItem>
    @Query("SELECT * FROM sales WHERE invoice = :invoice LIMIT 1")
    suspend fun findSale(invoice: String): Sale?
    @Query("DELETE FROM sales WHERE invoice = :invoice")
    suspend fun deleteSale(invoice: String)
    @Query("DELETE FROM sale_items WHERE invoice = :invoice")
    suspend fun deleteItems(invoice: String)
    @Query("SELECT IFNULL(SUM(total),0) FROM sales WHERE createdAt BETWEEN :start AND :end")
    suspend fun totalSalesBetween(start: Long, end: Long): Double
    @Query("SELECT COUNT(*) FROM sales WHERE createdAt BETWEEN :start AND :end")
    suspend fun countBetween(start: Long, end: Long): Int
    @Query("SELECT IFNULL(SUM((si.unitPrice - si.cost) * si.qty),0) FROM sale_items si JOIN sales s ON s.invoice = si.invoice WHERE s.createdAt BETWEEN :start AND :end")
    suspend fun profitBetween(start: Long, end: Long): Double
    @Query("SELECT IFNULL(SUM(si.cost * si.qty),0) FROM sale_items si JOIN sales s ON s.invoice = si.invoice WHERE s.createdAt BETWEEN :start AND :end")
    suspend fun cogsBetween(start: Long, end: Long): Double
    @Query("SELECT product as product, SUM(qty) as totalQty, SUM(amount) as totalAmount FROM sale_items si JOIN sales s ON s.invoice = si.invoice WHERE s.createdAt BETWEEN :start AND :end GROUP BY product ORDER BY totalQty DESC LIMIT 10")
    suspend fun topProducts(start: Long, end: Long): List<TopProduct>
    @Query("SELECT date(createdAt/1000, 'unixepoch','localtime') as day, SUM(total) as total FROM sales WHERE createdAt BETWEEN :start AND :end GROUP BY day ORDER BY day DESC")
    suspend fun dailySales(start: Long, end: Long): List<DailySale>
    @Query("SELECT product as product, SUM(qty) as totalQty, SUM(amount) as totalAmount FROM sale_items GROUP BY product")
    suspend fun allTimeItemTotals(): List<AllTimeItemTotal>
    @Query("SELECT s.customerName as customerName, si.qty as qty, si.unitPrice as unitPrice, s.createdAt as createdAt FROM sale_items si JOIN sales s ON s.invoice = si.invoice WHERE si.barcode = :barcode ORDER BY s.createdAt DESC")
    suspend fun saleRecordsForItem(barcode: String): List<SaleRecord>
}

@Dao
interface PurchaseDao {
    @Query("SELECT billNo, supplierName, total, createdAt, status, paid, supplierId FROM purchases ORDER BY createdAt DESC")
    suspend fun allPurchases(): List<PurchaseWithSupplier>
    @Query("SELECT * FROM purchases WHERE supplierId = :supplierId ORDER BY createdAt DESC")
    suspend fun purchasesBySupplier(supplierId: Long): List<Purchase>
    @Query("SELECT * FROM purchase_items WHERE billNo = :billNo")
    suspend fun itemsForBill(billNo: String): List<PurchaseItem>
    @Query("SELECT * FROM purchases WHERE billNo = :billNo LIMIT 1")
    suspend fun findPurchase(billNo: String): Purchase?
    @Query("DELETE FROM purchases WHERE billNo = :billNo")
    suspend fun deletePurchase(billNo: String)
    @Query("DELETE FROM purchase_items WHERE billNo = :billNo")
    suspend fun deleteItems(billNo: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPurchase(p: Purchase)
    @Insert
    suspend fun insertItems(items: List<PurchaseItem>)
    @Query("UPDATE purchases SET status = 'returned' WHERE billNo = :billNo")
    suspend fun markReturned(billNo: String)
    @Query("SELECT IFNULL(SUM(total),0) FROM purchases WHERE createdAt BETWEEN :start AND :end")
    suspend fun totalBetween(start: Long, end: Long): Double
    @Query("SELECT pi.barcode as product, SUM(pi.qty) as totalQty, SUM(pi.amount) as totalAmount FROM purchase_items pi GROUP BY pi.barcode")
    suspend fun allTimeItemTotals(): List<AllTimeItemTotal>
    @Query("SELECT pu.supplierName as supplierName, pi.qty as qty, pi.unitCost as unitCost, pu.createdAt as createdAt FROM purchase_items pi JOIN purchases pu ON pu.billNo = pi.billNo WHERE pi.barcode = :barcode ORDER BY pu.createdAt DESC")
    suspend fun purchaseRecordsForItem(barcode: String): List<PurchaseRecord>
}

@Dao
interface ExpenseDao {
    @Query("SELECT IFNULL(SUM(amount),0) FROM expenses WHERE createdAt BETWEEN :start AND :end")
    suspend fun totalBetween(start: Long, end: Long): Double
}

@Dao
interface ReturnDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(line: ReturnLine)

    @Query("SELECT IFNULL(SUM(amount),0) FROM returns WHERE type = :type AND createdAt BETWEEN :start AND :end")
    suspend fun totalByTypeBetween(type: String, start: Long, end: Long): Double
}

@Dao
interface PaymentDao {
    @Query("DELETE FROM payments WHERE reference = :ref")
    suspend fun deleteByReference(ref: String)
}

@Dao
interface CashTransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(t: CashTransaction)
    @Query("SELECT * FROM cash_transactions ORDER BY createdAt DESC")
    fun all(): Flow<List<CashTransaction>>
    @Query("SELECT IFNULL(SUM(amount),0) FROM cash_transactions WHERE type = :type AND method = :method AND createdAt BETWEEN :start AND :end")
    suspend fun totalBetween(type: String, method: String, start: Long, end: Long): Double
    @Query("DELETE FROM cash_transactions WHERE reference = :ref")
    suspend fun deleteByReference(ref: String)
}

@Database(entities = [Product::class, Category::class, UnitEntity::class, User::class, AppSetting::class, Customer::class, Supplier::class, Sale::class, SaleItem::class, Purchase::class, PurchaseItem::class, Expense::class, ReturnRecord::class, ReturnLine::class, Payment::class, CashTransaction::class], version = 27, exportSchema = false)
abstract class PosDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun categoryDao(): CategoryDao
    abstract fun unitDao(): UnitDao
    abstract fun userDao(): UserDao
    abstract fun appSettingDao(): AppSettingDao
    abstract fun customerDao(): CustomerDao
    abstract fun supplierDao(): SupplierDao
    abstract fun saleDao(): SaleDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun returnDao(): ReturnDao
    abstract fun paymentDao(): PaymentDao
    abstract fun cashTransactionDao(): CashTransactionDao

    companion object {
        @Volatile private var INSTANCE: PosDatabase? = null
        val MIGRATION = object : Migration(19, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE TABLE IF NOT EXISTS users (username TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL DEFAULT '', role TEXT NOT NULL DEFAULT 'cashier', passwordHash TEXT NOT NULL DEFAULT '', active INTEGER NOT NULL DEFAULT 1)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS app_settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL DEFAULT '')")
                    db.execSQL("CREATE TABLE IF NOT EXISTS customers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL DEFAULT '', phone TEXT NOT NULL DEFAULT '', balance REAL NOT NULL DEFAULT 0, openingBalance REAL NOT NULL DEFAULT 0, creditLimit REAL NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS suppliers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL DEFAULT '', phone TEXT NOT NULL DEFAULT '', balance REAL NOT NULL DEFAULT 0, openingBalance REAL NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS expenses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amount REAL NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL DEFAULT 0, date INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '')")
                    db.execSQL("CREATE TABLE IF NOT EXISTS returns (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL DEFAULT 'sale', amount REAL NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL DEFAULT 0, date INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS return_lines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, reference TEXT NOT NULL DEFAULT '', type TEXT NOT NULL DEFAULT 'sale', barcode TEXT NOT NULL DEFAULT '', qty REAL NOT NULL DEFAULT 0, amount REAL NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS payments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, reference TEXT NOT NULL DEFAULT '', amount REAL NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS cash_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL DEFAULT 'IN', method TEXT NOT NULL DEFAULT 'cash', amount REAL NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '', createdAt INTEGER NOT NULL DEFAULT 0, date INTEGER NOT NULL DEFAULT 0, reference TEXT NOT NULL DEFAULT '')")
                    db.execSQL("CREATE TABLE IF NOT EXISTS purchases (billNo TEXT NOT NULL PRIMARY KEY, supplierId INTEGER, supplierName TEXT NOT NULL DEFAULT 'Unknown', total REAL NOT NULL DEFAULT 0, paid REAL NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'completed', createdAt INTEGER NOT NULL DEFAULT 0, date INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS purchase_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, billNo TEXT NOT NULL, barcode TEXT NOT NULL, qty REAL NOT NULL DEFAULT 0, unit TEXT NOT NULL DEFAULT '', unitCost REAL NOT NULL DEFAULT 0, amount REAL NOT NULL DEFAULT 0)")
                } catch (e: Exception) {}
            }
        }
        fun closeInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
        fun get(context: Context): PosDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, PosDatabase::class.java, "pos.db")
                    .addMigrations(MIGRATION)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
