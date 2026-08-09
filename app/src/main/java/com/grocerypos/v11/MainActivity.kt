package com.grocerypos.v11

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class DailySales(val day:String,val total:Double)
data class TopProduct(val product:String,val totalQty:Int)

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
    val unitNote:String=""
)

@Entity(tableName="customers")
data class Customer(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val name:String,
    val phone:String="",
    val creditLimit:Double=0.0,
    val balance:Double=0.0
)

@Entity(tableName="suppliers")
data class Supplier(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val name:String,
    val phone:String="",
    val balance:Double=0.0
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
    val createdAt:Long=System.currentTimeMillis()
)

@Entity(tableName="sale_items")
data class SaleItem(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val invoice:String,
    val barcode:String,
    val product:String,
    val qty:Int,
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
    val createdAt:Long=System.currentTimeMillis()
)

@Entity(tableName="purchases")
data class Purchase(
    @PrimaryKey val billNo:String,
    val supplierId:Long?,
    val total:Double,
    val paid:Double,
    val createdAt:Long=System.currentTimeMillis()
)

@Entity(tableName="purchase_items")
data class PurchaseItem(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val billNo:String,
    val barcode:String,
    val qty:Int,
    val unitCost:Double,
    val amount:Double
)

@Entity(tableName="returns")
data class ReturnLine(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val reference:String,
    val type:String,
    val barcode:String,
    val qty:Int,
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
    val createdAt:Long=System.currentTimeMillis()
)

@Entity(tableName="held_bills")
data class HeldBill(
    @PrimaryKey val holdId:String,
    val payload:String,
    val createdAt:Long=System.currentTimeMillis()
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE barcode=:code LIMIT 1")
    suspend fun find(code:String):Product?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(p:Product)
    @Query("UPDATE products SET stock=stock-:qty WHERE barcode=:code AND stock>=:qty")
    suspend fun decrease(code:String,qty:Int):Int
    @Query("UPDATE products SET stock=stock+:qty WHERE barcode=:code")
    suspend fun increase(code:String,qty:Int)
    @Query("UPDATE products SET unit=:unit WHERE barcode=:code")
    suspend fun updateUnit(code:String,unit:String)
    @Query("SELECT * FROM products WHERE stock<=reorderLevel ORDER BY name")
    fun lowStock():Flow<List<Product>>
    @Query("SELECT * FROM products ORDER BY name")
    fun all():Flow<List<Product>>
}

@Dao interface CustomerDao {
    @Insert suspend fun insert(c:Customer):Long
    @Query("SELECT * FROM customers ORDER BY name") fun all():Flow<List<Customer>>
}

@Dao interface SupplierDao {
    @Insert suspend fun insert(s:Supplier):Long
    @Query("SELECT * FROM suppliers ORDER BY name") fun all():Flow<List<Supplier>>
}

@Dao interface SaleDao {
    @Insert suspend fun sale(s:Sale)
    @Insert suspend fun items(items:List<SaleItem>)
    @Query("SELECT COUNT(*) FROM sales") suspend fun count():Int
    @Query("SELECT COALESCE(SUM(total),0) FROM sales") suspend fun totalSales():Double
    @Query("SELECT COALESCE(SUM(total),0) FROM sales WHERE createdAt BETWEEN :start AND :end")
    suspend fun totalSalesBetween(start:Long,end:Long):Double
    @Query("SELECT COUNT(*) FROM sales WHERE createdAt BETWEEN :start AND :end")
    suspend fun countBetween(start:Long,end:Long):Int
    @Query("SELECT strftime('%Y-%m-%d', createdAt/1000, 'unixepoch') as day, COALESCE(SUM(total),0) as total FROM sales WHERE createdAt BETWEEN :start AND :end GROUP BY day ORDER BY day")
    suspend fun dailySales(start:Long,end:Long):List<DailySales>
    @Query("SELECT product, SUM(qty) as totalQty FROM sale_items WHERE invoice IN (SELECT invoice FROM sales WHERE createdAt BETWEEN :start AND :end) GROUP BY product ORDER BY totalQty DESC LIMIT 5")
    suspend fun topProducts(start:Long,end:Long):List<TopProduct>
}

@Dao interface ExpenseDao {
    @Insert suspend fun insert(e:Expense)
    @Query("SELECT COALESCE(SUM(amount),0) FROM expenses") suspend fun total():Double
    @Query("SELECT COALESCE(SUM(amount),0) FROM expenses WHERE createdAt BETWEEN :start AND :end")
    suspend fun totalBetween(start:Long,end:Long):Double
}

@Dao interface HeldDao {
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun hold(h:HeldBill)
    @Query("SELECT * FROM held_bills ORDER BY createdAt DESC") fun all():Flow<List<HeldBill>>
    @Delete suspend fun delete(h:HeldBill)
}

@Dao interface PaymentDao { @Insert suspend fun insert(p:Payment); @Query("SELECT COALESCE(SUM(amount),0) FROM payments") suspend fun total():Double }
@Dao interface PurchaseDao { @Insert suspend fun purchase(p:Purchase); @Insert suspend fun items(items:List<PurchaseItem>); @Query("SELECT COALESCE(SUM(total),0) FROM purchases") suspend fun total():Double }
@Dao interface ReturnDao { @Insert suspend fun insert(r:ReturnLine); @Query("SELECT COALESCE(SUM(amount),0) FROM returns") suspend fun total():Double }
@Dao interface UserDao { @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(u:User); @Query("SELECT * FROM users WHERE username=:u AND active=1 LIMIT 1") suspend fun find(u:String):User? }
@Dao interface AuditDao { @Insert suspend fun insert(a:Audit) }

@Database(
    entities=[Product::class,Customer::class,Supplier::class,Sale::class,SaleItem::class,
        Payment::class,Purchase::class,PurchaseItem::class,ReturnLine::class,User::class,Audit::class,
        Expense::class,HeldBill::class],
    version=10, exportSchema=false
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
    companion object{
        @Volatile private var INSTANCE:PosDatabase?=null
        fun get(c:Context)=INSTANCE?: synchronized(this){
            INSTANCE?:Room.databaseBuilder(c.applicationContext,PosDatabase::class.java,"grocery_pos_v11.db")
                .fallbackToDestructiveMigration().build().also{INSTANCE=it}
        }
    }
}
