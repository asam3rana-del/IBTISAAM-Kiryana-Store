package com.grocerypos.v11

import android.content.Context
import androidx.room.withTransaction
import java.util.Calendar
import java.util.Locale

/**
 * Daily Rent — ported from the Flutter app's rent_repository.dart.
 *
 * A customer can be charged a fixed rent every day (e.g. Rs 150) which is added to their Daily Bill
 * and balance by itself. A day or two a year the shop is closed — that day's rent is then removed.
 *
 * HOW: every day's rent is an ORDINARY credit Sale (invoice `RENT-yyyyMMdd-<customerId>`, no items,
 * paid 0). So the customer balance, Ledger/Statement, Party Reports, Daily Bills and cloud sync all
 * pick it up with no special casing. "Holiday" = flip that bill to status 'returned' (the balance
 * goes back, it drops out of the reports); the bill row stays, so the rent is never re-created.
 *
 * The rent SETTING (who / how much / from which day) lives in the local `customer_rent` table and is
 * not synced — set rent on ONE device only; the generated bills sync to the other devices as sales.
 *
 * The date maths is in pure functions ([rentInvoice], [missingRentDays]) so it can be unit tested
 * without a database. See RentLogicTest.kt.
 */
object RentLogic {
    const val PREFIX = "RENT-"

    fun isRentInvoice(invoice: String) = invoice.startsWith(PREFIX)

    /** Local midnight (millis) of the day that contains [millis]. */
    fun startOfDay(millis: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** `RENT-20261012-5` (day + customer id): one day + one customer = exactly one bill. */
    fun rentInvoice(customerId: Long, dayMillis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = dayMillis }
        return String.format(
            Locale.US, "%s%04d%02d%02d-%d", PREFIX,
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), customerId
        )
    }

    /**
     * Days from [startMillis] to [todayMillis] (both inclusive, as local midnights) whose rent bill
     * does not exist yet. A returned (holiday) bill counts as existing, so a holiday is not re-created.
     */
    fun missingRentDays(startMillis: Long, todayMillis: Long, customerId: Long, existing: Set<String>): List<Long> {
        val out = mutableListOf<Long>()
        val cal = Calendar.getInstance().apply { timeInMillis = startOfDay(startMillis) }
        val last = startOfDay(todayMillis)
        while (cal.timeInMillis <= last) {
            val d = cal.timeInMillis
            if (rentInvoice(customerId, d) !in existing) out.add(d)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return out
    }
}

data class RentConfig(val customerId: Long, val name: String, val dailyRent: Double, val startDay: Long)

class RentRepository(private val context: Context) {

    private val db get() = PosDatabase.get(context)

    private fun requireStaff() {
        val role = context.getSharedPreferences("session", Context.MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        if (role != "admin" && role != "manager") throw IllegalStateException("Only Admin/Manager can manage daily rent")
    }

    /** Customers who have a daily rent set. */
    suspend fun listConfigs(): List<RentConfig> =
        db.customerRentDao().all().mapNotNull { r ->
            val c = db.customerDao().find(r.customerId) ?: return@mapNotNull null
            RentConfig(r.customerId, c.name, r.dailyRent, r.startDay)
        }.sortedBy { it.name.lowercase() }

    /** Set / change a rent. A changed rent only applies to FUTURE days; bills already made don't change. */
    suspend fun setConfig(customerId: Long, dailyRent: Double, startDay: Long) {
        requireStaff()
        require(dailyRent.isFinite() && dailyRent > 0) { "Rent must be more than 0" }
        db.customerRentDao().upsert(CustomerRent(customerId, dailyRent, RentLogic.startOfDay(startDay)))
    }

    /** Stop rent: no new bills from now on. Old bills / balance stay as they are. */
    suspend fun removeConfig(customerId: Long) {
        requireStaff()
        db.customerRentDao().delete(customerId)
    }

    /**
     * For every rent customer create the rent bills for all days up to [todayMillis] that don't exist
     * yet (runs when Daily Bills opens, so even several days with the app closed get filled in).
     * Returns how many bills were created.
     */
    suspend fun ensureRentUpTo(todayMillis: Long): Int {
        val configs = db.customerRentDao().all()
        if (configs.isEmpty()) return 0
        var created = 0
        db.withTransaction {
            for (cfg in configs) {
                val cid = cfg.customerId
                if (db.customerDao().find(cid) == null) continue // customer was deleted
                val existing = db.saleDao().invoicesLike("${RentLogic.PREFIX}%-$cid").toSet()
                val days = RentLogic.missingRentDays(cfg.startDay, todayMillis, cid, existing)
                for (d in days) {
                    val now = System.currentTimeMillis()
                    val invoice = RentLogic.rentInvoice(cid, d)
                    val sale = Sale(
                        invoice = invoice,
                        customerId = cid,
                        subtotal = cfg.dailyRent,
                        discount = 0.0,
                        tax = 0.0,
                        total = cfg.dailyRent,
                        paid = 0.0,
                        paymentMethod = "credit",
                        saleType = "retail",
                        createdAt = d + 1000L, // 00:00:01 of that day
                        status = "active",
                        updatedAt = now
                    )
                    db.saleDao().sale(sale)
                    SyncQueueHelper.adjustCustomerBalance(db, cid, cfg.dailyRent)
                    SyncQueueHelper.enqueueSale(db, sale)
                    created++
                }
            }
        }
        if (created > 0) SyncQueueHelper.trigger(context)
        return created
    }

    /**
     * Holiday: remove ([off] = true) or bring back ([off] = false) the rent of [invoice].
     * The customer's balance moves by the same amount and the change syncs to the other devices.
     */
    suspend fun setRentBillOff(invoice: String, off: Boolean) {
        requireStaff()
        require(RentLogic.isRentInvoice(invoice)) { "This is not a rent bill" }
        db.withTransaction {
            val sale = db.saleDao().findSale(invoice) ?: throw IllegalArgumentException("Rent bill not found")
            val isOff = sale.status == "returned"
            if (isOff == off) return@withTransaction // already in that state
            val delta = if (off) -sale.total else sale.total
            sale.customerId?.let { SyncQueueHelper.adjustCustomerBalance(db, it, delta) }
            db.saleDao().setStatus(invoice, if (off) "returned" else "active", System.currentTimeMillis())
            db.saleDao().findSale(invoice)?.let { SyncQueueHelper.enqueueSale(db, it) }
        }
        SyncQueueHelper.trigger(context)
    }

    /** Holiday for the whole shop on [dayMillis]: every rent customer's bill for that day. Returns how many changed. */
    suspend fun setDayOff(dayMillis: Long, off: Boolean): Int {
        requireStaff()
        var n = 0
        for (cfg in db.customerRentDao().all()) {
            val invoice = RentLogic.rentInvoice(cfg.customerId, dayMillis)
            val sale = db.saleDao().findSale(invoice) ?: continue
            if ((sale.status == "returned") == off) continue
            setRentBillOff(invoice, off)
            n++
        }
        return n
    }
}
