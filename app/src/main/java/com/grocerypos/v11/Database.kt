package com.grocerypos.v11

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

@Entity(tableName = "sales")
data class Sale(
    @PrimaryKey val invoice: String,
    val subtotal: Double = 0.0,
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    val total: Double = 0.0,
    val paid: Double = 0.0,
    val paymentMethod: String = "cash",
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
interface SaleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun sale(s: Sale)
    @Insert
    suspend fun items(items: List<SaleItem>)
}

@Database(entities = [Product::class, Category::class, UnitEntity::class, User::class, Sale::class, SaleItem::class], version = 20, exportSchema = false)
abstract class PosDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun categoryDao(): CategoryDao
    abstract fun unitDao(): UnitDao
    abstract fun userDao(): UserDao
    abstract fun saleDao(): SaleDao

    companion object {
        @Volatile private var INSTANCE: PosDatabase? = null
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE TABLE IF NOT EXISTS users (username TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL DEFAULT '', role TEXT NOT NULL DEFAULT 'cashier', passwordHash TEXT NOT NULL DEFAULT '', active INTEGER NOT NULL DEFAULT 1)")
                    db.execSQL("UPDATE products SET stock = CAST(stock * secondaryUnitQty * tertiaryUnitQty AS INTEGER) WHERE tertiaryUnitQty > 0 AND secondaryUnitQty > 0 AND stock > 0")
                } catch (e: Exception) {}
            }
        }
        fun get(context: Context): PosDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, PosDatabase::class.java, "pos.db")
                    .addMigrations(MIGRATION_19_20)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
