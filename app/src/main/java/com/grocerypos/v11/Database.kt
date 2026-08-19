package com.grocerypos.v11

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// ================= PRODUCTS - 3-Tier Urdu =================
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
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String = ""
)

@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey val id: String,
    val name: String = "",
    val phone: String = "",
    val balance: Double = 0.0
)

@Entity(tableName = "sales")
data class Sale(
    @PrimaryKey val invoice: String,
    val customerId: String? = null,
    val customerName: String = "Walk-in",
    val subtotal: Double = 0.0,
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    val total: Double = 0.0,
    val paid: Double = 0.0,
    val paymentMethod: String = "cash",
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

@Entity(tableName = "payments")
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val reference: String = "",
    val amount: Double = 0.0
)

@Entity(tableName = "cash_transactions")
data class CashTransaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val reference: String = "",
    val amount: Double = 0.0
)

// Data class for SaleHistory grouping
data class SaleWithCustomer(
    val invoice: String,
    val customerName: String,
    val total: Double,
    val paymentMethod: String,
    val createdAt: Long,
    val status: String,
    val customerId: String?,
    val paid: Double
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY name ASC")
    fun all(): kotlinx.coroutines.flow.Flow<List<Product>>
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
    @Query("SELECT * FROM products WHERE name LIKE '%' || :q || '%' LIMIT 20")
    suspend fun search(q: String): List<Product>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun all(): kotlinx.coroutines.flow.Flow<List<Category>>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: Category)
}

@Dao
interface UnitDao {
    @Query("SELECT * FROM units ORDER BY name ASC")
    fun all(): kotlinx.coroutines.flow.Flow<List<UnitEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(u: UnitEntity)
}

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY username ASC")
    fun all(): kotlinx.coroutines.flow.Flow<List<User>>
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
    fun all(): kotlinx.coroutines.flow.Flow<List<AppSetting>>
}

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers")
    fun all(): kotlinx.coroutines.flow.Flow<List<Customer>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: Customer)
    @Query("UPDATE customers SET balance = balance + :amount WHERE id = :id")
    suspend fun addBalance(id: String, amount: Double)
    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    suspend fun find(id: String): Customer?
}

@Dao
interface SaleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun sale(s: Sale)
    @Insert
    suspend fun items(items: List<SaleItem>)

    @Query("SELECT invoice, customerName, total, paymentMethod, createdAt, status, customerId, paid FROM sales ORDER BY createdAt DESC")
    suspend fun allSales(): List<SaleWithCustomer>

    @Query("SELECT * FROM sale_items WHERE invoice = :invoice")
    suspend fun itemsForInvoice(invoice: String): List<SaleItem>

    @Query("SELECT * FROM sales WHERE invoice = :invoice LIMIT 1")
    suspend fun findSale(invoice: String): Sale?

    @Query("DELETE FROM sales WHERE invoice = :invoice")
    suspend fun deleteSale(invoice: String)

    @Query("DELETE FROM sale_items WHERE invoice = :invoice")
    suspend fun deleteItems(invoice: String)
}

@Dao
interface PaymentDao {
    @Query("DELETE FROM payments WHERE reference = :ref")
    suspend fun deleteByReference(ref: String)
}

@Dao
interface CashTransactionDao {
    @Query("DELETE FROM cash_transactions WHERE reference = :ref")
    suspend fun deleteByReference(ref: String)
}

@Database(entities = [Product::class, Category::class, UnitEntity::class, User::class, AppSetting::class, Customer::class, Sale::class, SaleItem::class, Payment::class, CashTransaction::class], version = 22, exportSchema = false)
abstract class PosDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun categoryDao(): CategoryDao
    abstract fun unitDao(): UnitDao
    abstract fun userDao(): UserDao
    abstract fun appSettingDao(): AppSettingDao
    abstract fun customerDao(): CustomerDao
    abstract fun saleDao(): SaleDao
    abstract fun paymentDao(): PaymentDao
    abstract fun cashTransactionDao(): CashTransactionDao

    companion object {
        @Volatile private var INSTANCE: PosDatabase? = null

        val MIGRATION_19_22 = object : Migration(19, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE TABLE IF NOT EXISTS users (username TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL DEFAULT '', role TEXT NOT NULL DEFAULT 'cashier', passwordHash TEXT NOT NULL DEFAULT '', active INTEGER NOT NULL DEFAULT 1)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS app_settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL DEFAULT '')")
                    db.execSQL("CREATE TABLE IF NOT EXISTS customers (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL DEFAULT '', phone TEXT NOT NULL DEFAULT '', balance REAL NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS payments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, reference TEXT NOT NULL DEFAULT '', amount REAL NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS cash_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, reference TEXT NOT NULL DEFAULT '', amount REAL NOT NULL DEFAULT 0)")
                    // Add new columns to sales if not exists
                    try { db.execSQL("ALTER TABLE sales ADD COLUMN customerId TEXT") } catch (e: Exception) {}
                    try { db.execSQL("ALTER TABLE sales ADD COLUMN customerName TEXT NOT NULL DEFAULT 'Walk-in'") } catch (e: Exception) {}
                    try { db.execSQL("ALTER TABLE sales ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0") } catch (e: Exception) {}
                    try { db.execSQL("ALTER TABLE sales ADD COLUMN status TEXT NOT NULL DEFAULT 'completed'") } catch (e: Exception) {}
                } catch (e: Exception) {}
            }
        }

        fun get(context: Context): PosDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, PosDatabase::class.java, "pos.db")
                    .addMigrations(MIGRATION_19_22)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

object AppLock {
    fun updateCachedLoginMethod(method: String) {}
}
