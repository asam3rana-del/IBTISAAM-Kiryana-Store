package com.grocerypos.v11

import android.content.Context
import java.util.Calendar
import java.util.Locale

/**
 * "Daily Bills" ("Aaj ke Bills") — ported from the Flutter app's daily_bills_repository.dart.
 *
 * One day's separate sales for a customer shown as ONE bill, with that day's previous balance and
 * the balance after the day. Display / share only: no sale, stock or balance is changed here
 * (the only exception is Daily Rent bills being created when the screen loads — see RentRepository).
 *
 * Balance rule: opening + bills (total - paid) - payments that are NOT tied to a bill. Returned
 * bills are left out. The customer's "stuck" balance is deliberately NOT included (it stays in
 * Party Reports / Statement, same as the Flutter app).
 *
 * The maths is in PURE functions ([buildDailyLedger], [buildDailyParty], [dailyBillText]) so it can
 * be unit tested without a database — see DailyBillsTest.kt.
 */

data class DayBillItem(val product: String, val qty: Double, val unit: String, val unitPrice: Double, val amount: Double)

data class DayBill(
    val invoice: String,
    val time: Long,
    val total: Double,
    val paid: Double,
    val items: List<DayBillItem> = emptyList()
) {
    val due: Double get() = total - paid
}

data class DayPayment(val time: Long, val amount: Double)

/** One day's account of one customer. */
data class DailyParty(
    val id: Long,
    val name: String,
    /** Balance before the day started (opening + earlier sales - earlier payments). Stuck not included. */
    val previousBalance: Double,
    val bills: List<DayBill>,
    /** Payments received that day that are NOT part of a bill ("Payment received"). */
    val payments: List<DayPayment>,
    /** Balance after the day. */
    val closingBalance: Double
) {
    val salesTotal: Double get() = bills.sumOf { it.total }
    val billPaid: Double get() = bills.sumOf { it.paid }
    val paymentsTotal: Double get() = payments.sumOf { it.amount }
    /** Total received today: paid with the bill + separate payments. */
    val receivedTotal: Double get() = billPaid + paymentsTotal
    val billCount: Int get() = bills.size
}

/** A sale as the ledger needs it (id = invoice). */
data class DailyBillRow(val id: String, val total: Double, val paid: Double, val createdAt: Long, val status: String = "active") {
    val isReturned: Boolean get() = status == "returned"
}

data class DailyPayRow(val reference: String, val billReference: String, val amount: Double, val createdAt: Long)

data class DailyLedgerLine(val time: Long, val dr: Double, val cr: Double, val delta: Double, val isPayment: Boolean = false)

/**
 * Same merge Party Reports' Ledger uses (and PartyRepository.trueCustomerBalance): non-returned bills
 * plus the general payments, oldest first. A payment is skipped when its reference, or its billReference,
 * is one of this customer's own bills (returned ones included) — it is already inside that bill's `paid`.
 * The sort is stable, so at equal time bills come before payments.
 */
fun buildDailyLedger(bills: List<DailyBillRow>, payments: List<DailyPayRow>): List<DailyLedgerLine> {
    val ownBillIds = bills.map { it.id }.toHashSet()
    val lines = mutableListOf<DailyLedgerLine>()
    for (b in bills) if (!b.isReturned) lines.add(DailyLedgerLine(b.createdAt, b.total, b.paid, b.total - b.paid))
    for (p in payments) {
        if (p.reference in ownBillIds) continue
        if (p.billReference.isNotEmpty() && p.billReference in ownBillIds) continue
        lines.add(DailyLedgerLine(p.createdAt, 0.0, p.amount, -p.amount, isPayment = true))
    }
    return lines.sortedBy { it.time }
}

/**
 * One customer's day. [dayStart] <= time < [dayEnd] are that day's lines.
 * Returns null when the customer had no bill and no payment that day.
 */
fun buildDailyParty(
    id: Long,
    name: String,
    opening: Double,
    bills: List<DailyBillRow>,
    payments: List<DailyPayRow>,
    dayStart: Long,
    dayEnd: Long,
    itemsByInvoice: Map<String, List<DayBillItem>> = emptyMap()
): DailyParty? {
    val lines = buildDailyLedger(bills, payments)
    var previous = opening
    var closing = opening
    val dayPayments = mutableListOf<DayPayment>()
    for (l in lines) {
        if (l.time < dayStart) {
            previous += l.delta
            closing += l.delta
        } else if (l.time < dayEnd) {
            closing += l.delta
            if (l.isPayment) dayPayments.add(DayPayment(l.time, l.cr))
        }
    }
    val dayBills = bills
        .filter { !it.isReturned && it.createdAt >= dayStart && it.createdAt < dayEnd }
        .map { b ->
            val items = itemsByInvoice[b.id].orEmpty()
            DayBill(
                invoice = b.id,
                time = b.createdAt,
                total = b.total,
                paid = b.paid,
                items = if (items.isNotEmpty()) items
                else if (RentLogic.isRentInvoice(b.id)) listOf(DayBillItem("Daily Rent", 1.0, "", b.total, b.total))
                else emptyList()
            )
        }
        .sortedBy { it.time }
    if (dayBills.isEmpty() && dayPayments.isEmpty()) return null
    return DailyParty(id, name, previous, dayBills, dayPayments, closing)
}

private fun n(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
private fun qty(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

private fun hm(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val h24 = c.get(Calendar.HOUR_OF_DAY)
    val h = if (h24 % 12 == 0) 12 else h24 % 12
    return String.format(Locale.US, "%d:%02d %s", h, c.get(Calendar.MINUTE), if (h24 >= 12) "PM" else "AM")
}

/** Plain-text bill for Share / print. */
fun dailyBillText(p: DailyParty, dayMillis: Long, shopName: String = ""): String {
    val c = Calendar.getInstance().apply { timeInMillis = dayMillis }
    val sb = StringBuilder()
    if (shopName.isNotBlank()) sb.appendLine(shopName.trim())
    sb.appendLine("Daily Bill - ${p.name}")
    sb.appendLine(
        String.format(
            Locale.US, "Date: %02d-%02d-%04d",
            c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR)
        )
    )
    sb.appendLine("--------------------------")
    sb.appendLine("Previous balance: Rs ${n(p.previousBalance)}")
    sb.appendLine("--------------------------")
    for (b in p.bills) {
        sb.appendLine("${b.invoice}  (${hm(b.time)})")
        for (i in b.items) {
            sb.appendLine("  ${i.product}  ${qty(i.qty)}${if (i.unit.isEmpty()) "" else " ${i.unit}"} x ${n(i.unitPrice)} = ${n(i.amount)}")
        }
        sb.appendLine("  Bill total: ${n(b.total)}${if (b.paid > 0) "   Paid: ${n(b.paid)}" else ""}")
    }
    sb.appendLine("--------------------------")
    sb.appendLine("Today's sale: Rs ${n(p.salesTotal)}")
    if (p.receivedTotal > 0) sb.appendLine("Received today: Rs ${n(p.receivedTotal)}")
    sb.appendLine("Closing balance: Rs ${n(p.closingBalance)}")
    return sb.toString()
}

class DailyBillsRepository(private val context: Context) {

    private val db get() = PosDatabase.get(context)

    /** Set when the last [loadDay] could not create the Daily Rent bills (null = fine). The screen shows it. */
    var lastRentError: String? = null
        private set

    /**
     * Customers who had a sale or a payment on the local day containing [dayMillis], most sales first.
     * Admin/Manager only — the screen checks the role too.
     */
    suspend fun loadDay(dayMillis: Long): List<DailyParty> {
        val role = context.getSharedPreferences("session", Context.MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        if (role != "admin" && role != "manager") throw IllegalStateException("Only Admin/Manager can view daily bills")

        // Daily Rent: any missing rent bills up to today are made now. A rent problem must not hide the
        // rest of the day, so it is only reported, not thrown.
        lastRentError = try {
            RentRepository(context).ensureRentUpTo(System.currentTimeMillis())
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }

        val start = RentLogic.startOfDay(dayMillis)
        val cal = Calendar.getInstance().apply { timeInMillis = start; add(Calendar.DAY_OF_MONTH, 1) }
        val end = cal.timeInMillis

        val ids = (db.saleDao().customerIdsWithSalesBetween(start, end) + db.paymentDao().customerIdsWithPaymentsBetween(start, end)).toSet()
        if (ids.isEmpty()) return emptyList()

        // All of the day's sale lines in one query.
        val itemsByInvoice = db.saleDao().itemsForSalesBetween(start, end)
            .groupBy({ it.invoice }, { DayBillItem(it.product, it.qty, it.unit, it.unitPrice, it.amount) })

        val out = mutableListOf<DailyParty>()
        for (c in db.customerDao().allList()) {
            if (c.id !in ids) continue
            val bills = db.saleDao().salesByCustomer(c.id).map { DailyBillRow(it.invoice, it.total, it.paid, it.createdAt, it.status) }
            val pays = db.paymentDao().listByParty("customer", c.id).map { DailyPayRow(it.reference, it.billReference, it.amount, it.createdAt) }
            // stuckBalance is deliberately left out (see class comment).
            buildDailyParty(c.id, c.name, c.openingBalance, bills, pays, start, end, itemsByInvoice)?.let { out.add(it) }
        }
        return out.sortedByDescending { it.salesTotal }
    }

    suspend fun shopName(): String = db.appSettingDao().get("shop_name")?.value.orEmpty()
}
