
package com.grocerypos.v11

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.grocerypos.v11.util.Loc

@Entity(tableName = "products")
data class Product(
    @PrimaryKey val barcode: String,
    val name: String,
    val category: String = "General",
    val cost: Double = 0.0,
    val salePrice: Double = 0.0,
    val wholesalePrice: Double = 0.0,
    // Stock is ALWAYS in smallest unit now: dabbi/pcs/gram/ml
    val stock: Int = 0,
    val openingStock: Int = 0,
    // Largest unit
    val unit: String = "ctn",
    // Middle unit
    val secondaryUnit: String = "",
    val secondaryUnitQty: Double = 0.0, // 1 primary = X secondary
    // Smallest unit
    val tertiaryUnit: String = "",
    val tertiaryUnitQty: Double = 0.0 // 1 secondary = X tertiary
)

@Entity(tableName = "categories")
data class Category(@PrimaryKey val name: String)

@Entity(tableName = "units")
data class UnitEntity(@PrimaryKey val name: String)

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
    val qty: Int, // qty in SOLD unit
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

    // Decrease stock which is in smallest unit
    @Query("UPDATE products SET stock = stock - :qty WHERE barcode = :code")
    suspend fun decreaseStock(code: String, qty: Int)

    @Query("UPDATE products SET stock = stock + :qty WHERE barcode = :code")
    suspend fun increaseStock(code: String, qty: Int)

    @Query("UPDATE products SET stock = stock - :qty WHERE barcode = :code")
    suspend fun decreaseForce(code: String, qty: Int): Int

    @Query("SELECT * FROM products WHERE name LIKE '%' || :q || '%' LIMIT 20")
    suspend fun search(q: String): List<Product>

    @Query("UPDATE products SET unit = :unit, secondaryUnit = :secUnit, secondaryUnitQty = :secQty, tertiaryUnit = :terUnit, tertiaryUnitQty = :terQty WHERE barcode = :code")
    suspend fun updateUnits(code: String, unit: String, secUnit: String, secQty: Double, terUnit: String, terQty: Double)
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
interface SaleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun sale(s: Sale)
    @Insert
    suspend fun items(items: List<SaleItem>)
}

@Database(entities = [Product::class, Category::class, UnitEntity::class, Sale::class, SaleItem::class], version = 20, exportSchema = false)
abstract class PosDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun categoryDao(): CategoryDao
    abstract fun unitDao(): UnitDao
    abstract fun saleDao(): SaleDao

    companion object {
        @Volatile private var INSTANCE: PosDatabase? = null

        // Migration 19->20: Convert existing stock (which was in primary unit) to smallest unit
        // old stock was in primary (ctn/bag/kg). New stock must be in smallest (dabbi/pcs/gram)
        // Formula: newStock = oldStock * secQty * terQty (if ter exists) else oldStock * secQty
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // For products that already have secondary/tertiary, convert stock
                    // Example: ctn 50 outer 10 dabbi: sec=50, ter=10, stock=2 ctn -> new=2*50*10=1000 dabbi
                    db.execSQL("UPDATE products SET stock = CAST(stock * secondaryUnitQty * tertiaryUnitQty AS INTEGER) WHERE tertiaryUnitQty > 0 AND secondaryUnitQty > 0 AND stock > 0")
                    db.execSQL("UPDATE products SET openingStock = CAST(openingStock * secondaryUnitQty * tertiaryUnitQty AS INTEGER) WHERE tertiaryUnitQty > 0 AND secondaryUnitQty > 0 AND openingStock > 0")
                    // For 2-tier products (bag 50kg): stock kg -> gram etc: sec only
                    db.execSQL("UPDATE products SET stock = CAST(stock * secondaryUnitQty AS INTEGER) WHERE (tertiaryUnitQty = 0 OR tertiaryUnit = '') AND secondaryUnitQty > 0 AND stock > 0 AND secondaryUnit IN ('kg','g','gram','pcs','outer','lari')")
                    // Actually for 2-tier we also want conversion, but only if secondary is smallest
                    // Safer: if tertiary empty, convert primary to secondary as smallest
                    db.execSQL("UPDATE products SET stock = CAST(stock * secondaryUnitQty AS INTEGER) WHERE (tertiaryUnit = '' OR tertiaryUnitQty = 0) AND secondaryUnitQty > 0 AND unit IN ('bag','ctn') AND stock > 0")
                } catch (e: Exception) {
                    // ignore
                }
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
