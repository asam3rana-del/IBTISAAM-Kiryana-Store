package com.grocerypos.v11

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class DailySales(val day:String,val total:Double)
data class TopProduct(val product:String,val totalQty:Int)
data class PurchaseWithSupplier(val billNo:String,val supplierName:String,val total:Double,val createdAt:Long)
data class SupplierPurchaseTotal(val supplierName:String,val total:Double)
data class SaleWithCustomer(val invoice:String,val customerName:String,val total:Double,val paymentMethod:String,val createdAt:Long)
data class CustomerSalesTotal(val customerName:String,val total:Double)
data class DailyProfit(val day:String,val profit:Double)

@Entity(tableName="units")
data class UnitType(@PrimaryKey val name:String)

@Entity(tableName="categories")
data class Category(@PrimaryKey val name:String)

@Entity(tableName="products")
data class Product(
    @PrimaryKey val barcode:String,
    val name:String,
    val category:String="",
    val cost:Double=0.0,            // purchase rate
    val salePrice:Double=0.0,       // retail sale rate
    val stock:Int=0,
    val reorderLevel:Int=0,
    val expiry:String="",
    val unit:String="pcs",
    val unitSize:Int=1,
    val unitNote:String="",
    val secondaryUnit:String="",
    val secondaryUnitQty:Double=0.0,
    val wholesalePrice:Double=0.0,  // wholesale rate (parchon rate) - added
    val openingStock:Int=0          // added
)

@Entity(tableName="customers")
data class Customer(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val name:String,
    val phone:String="",
    val creditLimit:Double=0.0,
    val openingBalance:Double=0.0,  // added: balance before app usage started
    val balance:Double=0.0          // running balance from transactions since (closing = openingBalance+balance)
)

@Entity(tableName="suppliers")
data class Supplier(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val name:String,
    val phone:String="",
    val openingBalance:Double=0.0,  // added: balance before app usage started
    val balance:Double=0.0          // running balance from transactions since (closing = openingBalance+balance)
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
    val paymentMethod:String,       // cash / bank
    val saleType:String="retail",   // retail / wholesale
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
    val method:String,              // cash / bank
    val note:String="",
    val createdAt:Long=System.currentTimeMillis()
)

@Entity(tableName="purchases")
data class Purchase(
    @PrimaryKey val billNo:String,
    val supplierId:Long?,
    val total:Double,
    val paid:Double,
    val createdAt:Long=System.currentTimeMillis(),
    val subtotal:Double=0.0,   // added
    val discount:Double=0.0    // added
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
    val type:String,                // "sale" or "purchase"
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

// Manual cash in / cash out entries (not tied to a sale or purchase)
@Entity(tableName="cash_transactions")
data class CashTransaction(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val type:String,        // "IN" or "OUT"
    val method:String,      // "cash" or "bank"
    val amount:Double,
    val reason:String="",
    val reference:String="",
    val createdAt:Long=System.currentTimeMillis()
)

// One row per business day: opening/closing cash & bank balances
@Entity(tableName="cash_register")
data class CashRegister(
    @PrimaryKey val date:String,   // yyyy-MM-dd
    val openingCash:Double=0.0,
    val closingCash:Double=0.0,
    val openingBank:Double=0.0,
    val closingBank:Double=0.0,
    val closed:Boolean=false
)

// Generic key-value store for app settings (language, printer, backup, etc.)
@Entity(tableName="app_settings")
data class AppSetting(@PrimaryKey val key:String, val value:String)

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
    @Query("SELECT * FROM products WHERE expiry!='' ORDER BY expiry")
    fun expiring():Flow<List<Product>>
    @Query("SELECT * FROM products ORDER BY name")
    fun all():Flow<List<Product>>
}

@Dao interface UnitDao {
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insert(u:UnitType)
    @Query("SELECT * FROM units ORDER BY name") fun all():Flow<List<UnitType>>
}

@Dao interface CategoryDao {
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insert(c:Category)
    @Query("SELECT * FROM categories ORDER BY name") fun all():Flow<List<Category>>
}

@Dao interface CustomerDao {
    @Insert suspend fun insert(c:Customer):Long
    @Query("SELECT * FROM customers ORDER BY name") fun all():Flow<List<Customer>>
    @Query("UPDATE customers SET balance=balance+:amt WHERE id=:id")
    suspend fun addBalance(id:Long,amt:Double)
    @Query("SELECT COALESCE(name,'Walk-in') as customerName, SUM(total) as total FROM sales LEFT JOIN customers ON sales.customerId=customers.id GROUP BY customerId ORDER BY total DESC")
    suspend fun salesTotalsByCustomer():List<CustomerSalesTotal>
}

@Dao interface SupplierDao {
    @Insert suspend fun insert(s:Supplier):Long
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
    @Query("SELECT COALESCE(SUM(total),0) FROM sales WHERE createdAt BETWEEN :start AND :end")
    suspend fun totalSalesBetween(start:Long,end:Long):Double
    @Query("SELECT COUNT(*) FROM sales WHERE createdAt BETWEEN :start AND :end")
    suspend fun countBetween(start:Long,end:Long):Int
    @Query("SELECT strftime('%Y-%m-%d', createdAt/1000, 'unixepoch') as day, COALESCE(SUM(total),0) as total FROM sales WHERE createdAt BETWEEN :start AND :end GROUP BY day ORDER BY day")
    suspend fun dailySales(start:Long,end:Long):List<DailySales>
    @Query("SELECT product, SUM(qty) as totalQty FROM sale_items WHERE invoice IN (SELECT invoice FROM sales WHERE createdAt BETWEEN :start AND :end) GROUP BY product ORDER BY totalQty DESC LIMIT 5")
    suspend fun topProducts(start:Long,end:Long):List<TopProduct>
    @Query("SELECT invoice, COALESCE((SELECT name FROM customers WHERE customers.id=sales.customerId),'Walk-in') as customerName, total, paymentMethod, createdAt FROM sales ORDER BY createdAt DESC LIMIT 100")
    suspend fun allSales():List<SaleWithCustomer>

    @Query("SELECT * FROM sales WHERE customerId=:customerId ORDER BY createdAt DESC")
    suspend fun salesByCustomer(customerId:Long):List<Sale>

    @Query("SELECT * FROM sales WHERE invoice=:invoice LIMIT 1")
    suspend fun findSale(invoice:String):Sale?

    @Query("SELECT * FROM sale_items WHERE invoice=:invoice")
    suspend fun itemsForInvoice(invoice:String):List<SaleItem>

    @Query("DELETE FROM sale_items WHERE invoice=:invoice")
    suspend fun deleteItems(invoice:String)

    @Query("DELETE FROM sales WHERE invoice=:invoice")
    suspend fun deleteSale(invoice:String)

    // ---- Profit (sale price - cost) ----
    @Query("SELECT COALESCE(SUM((si.unitPrice-si.cost)*si.qty),0) FROM sale_items si JOIN sales s ON si.invoice=s.invoice WHERE s.createdAt BETWEEN :start AND :end")
    suspend fun profitBetween(start:Long,end:Long):Double
    @Query("SELECT strftime('%Y-%m-%d', s.createdAt/1000,'unixepoch') as day, COALESCE(SUM((si.unitPrice-si.cost)*si.qty),0) as profit FROM sale_items si JOIN sales s ON si.invoice=s.invoice WHERE s.createdAt BETWEEN :start AND :end GROUP BY day ORDER BY day")
    suspend fun dailyProfit(start:Long,end:Long):List<DailyProfit>
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

@Dao interface PaymentDao {
    @Insert suspend fun insert(p:Payment)
    @Query("SELECT COALESCE(SUM(amount),0) FROM payments") suspend fun total():Double
    @Query("SELECT COALESCE(SUM(amount),0) FROM payments WHERE method=:method AND createdAt BETWEEN :start AND :end")
    suspend fun totalByMethodBetween(method:String,start:Long,end:Long):Double
}

@Dao interface PurchaseDao {
    @Insert suspend fun purchase(p:Purchase)
    @Insert suspend fun items(items:List<PurchaseItem>)
    @Query("SELECT COALESCE(SUM(total),0) FROM purchases") suspend fun total():Double
    @Query("SELECT COALESCE(SUM(total),0) FROM purchases WHERE createdAt BETWEEN :start AND :end")
    suspend fun totalBetween(start:Long,end:Long):Double
    @Query("SELECT billNo, COALESCE((SELECT name FROM suppliers WHERE suppliers.id=purchases.supplierId),'Cash Purchase') as supplierName, total, createdAt FROM purchases ORDER BY createdAt DESC LIMIT 100")
    suspend fun allPurchases():List<PurchaseWithSupplier>

    @Query("SELECT * FROM purchases WHERE supplierId=:supplierId ORDER BY createdAt DESC")
    suspend fun purchasesBySupplier(supplierId:Long):List<Purchase>

    @Query("SELECT * FROM purchases WHERE billNo=:bill LIMIT 1")
    suspend fun findPurchase(bill:String):Purchase?

    @Query("SELECT * FROM purchase_items WHERE billNo=:bill")
    suspend fun itemsForBill(bill:String):List<PurchaseItem>

    @Query("DELETE FROM purchase_items WHERE billNo=:bill")
    suspend fun deleteItems(bill:String)

    @Query("DELETE FROM purchases WHERE billNo=:bill")
    suspend fun deletePurchase(bill:String)
}

@Dao interface ReturnDao {
    @Insert suspend fun insert(r:ReturnLine)
    @Query("SELECT COALESCE(SUM(amount),0) FROM returns WHERE type=:type") suspend fun totalByType(type:String):Double
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
    @Query("SELECT COALESCE(SUM(amount),0) FROM cash_transactions WHERE type=:type AND method=:method AND createdAt BETWEEN :start AND :end")
    suspend fun totalBetween(type:String,method:String,start:Long,end:Long):Double

    @Query("DELETE FROM cash_transactions WHERE reference=:ref")
    suspend fun deleteByReference(ref:String)
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

@Database(
    entities=[Product::class,Customer::class,Supplier::class,Sale::class,SaleItem::class,
        Payment::class,Purchase::class,PurchaseItem::class,ReturnLine::class,User::class,Audit::class,
        Expense::class,HeldBill::class,UnitType::class,Category::class,CashTransaction::class,
        CashRegister::class,AppSetting::class],
    version=14, exportSchema=false
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
    companion object{
        @Volatile private var INSTANCE:PosDatabase?=null
        fun get(c:Context)=INSTANCE?: synchronized(this){
            INSTANCE?:Room.databaseBuilder(c.applicationContext,PosDatabase::class.java,"grocery_pos_v11.db")
                .fallbackToDestructiveMigration().build().also{INSTANCE=it}
        }
    }
}
