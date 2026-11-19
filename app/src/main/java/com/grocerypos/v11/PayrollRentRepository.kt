package com.grocerypos.v11

import android.content.Context
import androidx.room.withTransaction

// =====================================================================================
// NEW (Flutter parity, batch 2): DB side of Workers, Shop Rents and Utility Bills — port of
// Flutter's worker_repository.dart, shop_rent_repository.dart, utility_bill_repository.dart.
// Money always flows through the app's normal Expense + CashTransaction rows (same reference
// convention as ExpenseActivity: cash.reference = expense entity id), so Day Book, Cash, Reports
// and Balance Sheet pick it up with no extra work. Every write is queued for sync in the same
// transaction. Screens check admin / manager before calling these.
// =====================================================================================

/** Thrown for a rule the user must see (e.g. "salary already paid"). [message] is shown as-is. */
class BooksRuleException(message: String) : Exception(message)

object MoneyRows {
    /** Expense + Cash OUT (cash = [cashAmount], default = amount; 0 = no cash row). Returns the stamped expense. */
    suspend fun insertExpenseWithCash(
        db: PosDatabase, category: String, description: String, amount: Double, method: String,
        now: Long, cashAmount: Double = amount
    ): Expense {
        val e = Expense(category = category, description = description, amount = amount, method = method,
            createdAt = now, updatedAt = now)
        val id = db.expenseDao().insert(e)
        val saved = e.copy(id = id)
        SyncQueueHelper.enqueueExpense(db, saved)
        val ref = SyncQueueHelper.expenseEntityId(saved)
        if (cashAmount > RentMath.EPS) {
            val cash = CashTransaction(type = "OUT", method = method, amount = cashAmount,
                reason = "Expense: $category", reference = ref, createdAt = now, updatedAt = now)
            val cid = db.cashTransactionDao().insert(cash)
            SyncQueueHelper.enqueueCashTransaction(db, cash.copy(id = cid))
        }
        return saved.copy(serverId = ref)
    }

    /** Same as ExpenseActivity's delete: expense + its cash rows (+ legacy "expense:<id>" ref if made here). */
    suspend fun deleteExpenseWithCash(db: PosDatabase, e: Expense) {
        val eid = SyncQueueHelper.expenseEntityId(e)
        db.expenseDao().delete(e)
        SyncQueueHelper.enqueueDelete(db, "expense", eid)
        SyncQueueHelper.deleteCashTransactionsByReference(db, eid)
        val madeHere = e.serverId == null || e.serverId.startsWith("expense:${DeviceTag.current}-")
        if (madeHere) SyncQueueHelper.deleteCashTransactionsByReference(db, "expense:${e.id}")
    }

    suspend fun insertCash(db: PosDatabase, type: String, method: String, amount: Double, reason: String, reference: String, now: Long): CashTransaction {
        val c = CashTransaction(type = type, method = method, amount = amount, reason = reason, reference = reference, createdAt = now, updatedAt = now)
        val id = db.cashTransactionDao().insert(c)
        val saved = c.copy(id = id)
        SyncQueueHelper.enqueueCashTransaction(db, saved)
        return saved
    }

    suspend fun deleteCashById(db: PosDatabase, id: Long) {
        val c = db.shopExtrasDao().cashById(id) ?: return
        val eid = SyncQueueHelper.cashTransactionEntityId(c)
        db.shopExtrasDao().deleteCashById(id)
        SyncQueueHelper.enqueueDelete(db, "cash_transaction", eid)
    }

    fun checkMethod(m: String) { if (m != "cash" && m != "bank") throw BooksRuleException("method must be cash or bank") }
    fun checkAmount(a: Double) { if (a.isNaN() || a.isInfinite() || a <= 0) throw BooksRuleException("Amount must be greater than 0") }
}

// ------------------------------------------------------------------------------ Workers

object WorkerRepository {
    suspend fun list(ctx: Context): List<ShopWorker> = PosDatabase.get(ctx).shopExtrasDao().workers()

    suspend fun save(ctx: Context, w: ShopWorker): Long {
        if (w.name.isBlank()) throw BooksRuleException("Name is required")
        if (w.rate.isNaN() || w.rate.isInfinite() || w.rate <= 0) throw BooksRuleException("Salary/rate must be greater than 0")
        val db = PosDatabase.get(ctx)
        val now = System.currentTimeMillis()
        val row = w.copy(name = w.name.trim(), phone = w.phone.trim(),
            payType = if (w.payType == Payroll.DAILY) Payroll.DAILY else Payroll.MONTHLY,
            createdAt = if (w.createdAt == 0L) now else w.createdAt, updatedAt = now)
        val id = db.withTransaction {
            val id = if (row.id == 0L) db.shopExtrasDao().insertWorker(row) else { db.shopExtrasDao().updateWorker(row); row.id }
            SyncQueueHelper.enqueueWorker(db, row.copy(id = id))
            id
        }
        SyncQueueHelper.trigger(ctx)
        return id
    }

    suspend fun delete(ctx: Context, workerId: Long) {
        val db = PosDatabase.get(ctx)
        val x = db.shopExtrasDao()
        db.withTransaction {
            val w = x.worker(workerId) ?: return@withTransaction
            if (x.advanceCount(workerId) + x.salaryCount(workerId) > 0)
                throw BooksRuleException("This worker has advance/salary records, so it cannot be deleted. Mark as Left instead.")
            val eid = SyncQueueHelper.workerEntityId(w)
            RecycleBin.record(db, ctx, RecycleBin.WORKER, "$workerId", w.name, w.phone, w.rate,
                RecycleBinData(workers = listOf(w), attendance = x.attendanceOf(workerId)))
            for (a in x.attendanceOf(workerId)) SyncQueueHelper.enqueueWorkerAttendanceDelete(db, workerId, a.day)
            x.deleteAttendanceOf(workerId)
            x.deleteWorker(workerId)
            SyncQueueHelper.enqueueDelete(db, "worker", eid)
        }
        SyncQueueHelper.trigger(ctx)
    }

    /** day (1..31) -> status for this month. */
    suspend fun monthAttendance(ctx: Context, workerId: Long, year: Int, month: Int): Map<Int, String> {
        val rows = PosDatabase.get(ctx).shopExtrasDao().attendanceBetween(workerId,
            Ym.dayKey(year, month, 1), Ym.dayKey(year, month, Ym.daysIn(year, month)))
        val out = HashMap<Int, String>()
        for (r in rows) r.day.substring(8, 10).toIntOrNull()?.let { out[it] = r.status }
        return out
    }

    suspend fun isMonthPaid(ctx: Context, workerId: Long, year: Int, month: Int): Boolean =
        PosDatabase.get(ctx).shopExtrasDao().salaryFor(workerId, Ym.key(year, month)) != null

    /** [status] null = clear the mark. Locked once that month's salary is paid. */
    suspend fun setAttendance(ctx: Context, workerId: Long, year: Int, month: Int, day: Int, status: String?) {
        if (status != null && status !in Payroll.STATUSES) throw BooksRuleException("Invalid status")
        if (isMonthPaid(ctx, workerId, year, month)) throw BooksRuleException("Salary for this month is already paid")
        val db = PosDatabase.get(ctx)
        val key = Ym.dayKey(year, month, day)
        db.withTransaction {
            if (status == null) {
                db.shopExtrasDao().deleteAttendance(workerId, key)
                SyncQueueHelper.enqueueWorkerAttendanceDelete(db, workerId, key)
            } else {
                db.shopExtrasDao().upsertAttendance(WorkerAttendance(workerId, key, status, System.currentTimeMillis()))
                SyncQueueHelper.enqueueWorkerAttendance(db, workerId, key)
            }
        }
        SyncQueueHelper.trigger(ctx)
    }

    /** Mark every unmarked day up to [upToDay] (inclusive) as present. */
    suspend fun markUnmarkedPresent(ctx: Context, workerId: Long, year: Int, month: Int, upToDay: Int) {
        if (isMonthPaid(ctx, workerId, year, month)) throw BooksRuleException("Salary for this month is already paid")
        val marks = monthAttendance(ctx, workerId, year, month)
        val db = PosDatabase.get(ctx)
        val now = System.currentTimeMillis()
        db.withTransaction {
            for (d in 1..minOf(upToDay, Ym.daysIn(year, month))) {
                if (marks.containsKey(d)) continue
                val key = Ym.dayKey(year, month, d)
                db.shopExtrasDao().upsertAttendance(WorkerAttendance(workerId, key, "P", now))
                SyncQueueHelper.enqueueWorkerAttendance(db, workerId, key)
            }
        }
        SyncQueueHelper.trigger(ctx)
    }

    suspend fun advanceBalance(ctx: Context, workerId: Long): Double {
        val x = PosDatabase.get(ctx).shopExtrasDao()
        val bal = x.advances(workerId).sumOf { it.amount } - x.salaries(workerId).sumOf { it.advanceCut }
        return if (bal < 0) 0.0 else Math.round(bal * 100) / 100.0
    }

    suspend fun allAdvanceBalances(ctx: Context): Map<Long, Double> {
        val x = PosDatabase.get(ctx).shopExtrasDao()
        val out = HashMap<Long, Double>()
        for (a in x.allAdvances()) out[a.workerId] = (out[a.workerId] ?: 0.0) + a.amount
        for (p in x.allSalaries()) out[p.workerId] = (out[p.workerId] ?: 0.0) - p.advanceCut
        return out.mapValues { if (it.value < 0) 0.0 else it.value }
    }

    suspend fun advances(ctx: Context, workerId: Long) = PosDatabase.get(ctx).shopExtrasDao().advances(workerId)
    suspend fun payments(ctx: Context, workerId: Long) = PosDatabase.get(ctx).shopExtrasDao().salaries(workerId)

    /** Advance: plain Cash/Bank OUT (no expense — it is cut from salary later). */
    suspend fun addAdvance(ctx: Context, w: ShopWorker, amount: Double, method: String, note: String) {
        MoneyRows.checkMethod(method); MoneyRows.checkAmount(amount)
        val db = PosDatabase.get(ctx)
        val now = System.currentTimeMillis()
        db.withTransaction {
            val a = WorkerAdvance(workerId = w.id, amount = amount, method = method, note = note.trim(), createdAt = now, updatedAt = now)
            val id = db.shopExtrasDao().insertAdvance(a)
            val stamped = SyncQueueHelper.enqueueWorkerAdvance(db, a.copy(id = id))
            MoneyRows.insertCash(db, "OUT", method, amount, "Worker Advance: ${w.name}",
                SyncQueueHelper.workerAdvanceEntityId(stamped), now)
        }
        SyncQueueHelper.trigger(ctx)
    }

    suspend fun deleteAdvance(ctx: Context, a: WorkerAdvance) {
        if (advanceBalance(ctx, a.workerId) + 0.005 < a.amount) throw BooksRuleException("This advance is already deducted from a salary")
        val db = PosDatabase.get(ctx)
        db.withTransaction {
            val eid = SyncQueueHelper.workerAdvanceEntityId(a)
            db.shopExtrasDao().deleteAdvance(a.id)
            SyncQueueHelper.enqueueDelete(db, "worker_advance", eid)
            SyncQueueHelper.deleteCashTransactionsByReference(db, eid)
            SyncQueueHelper.deleteCashTransactionsByReference(db, "wadv:${a.id}")
        }
        SyncQueueHelper.trigger(ctx)
    }

    /** Salary: Expense (Salaries / Wages, full earned) + Cash OUT (net only). Earned is recomputed here. */
    suspend fun paySalary(ctx: Context, w: ShopWorker, year: Int, month: Int, advanceCut: Double, method: String) {
        MoneyRows.checkMethod(method)
        if (advanceCut.isNaN() || advanceCut < 0) throw BooksRuleException("Invalid advance deduction")
        if (isMonthPaid(ctx, w.id, year, month)) throw BooksRuleException("Salary for this month is already paid")
        val calc = Payroll.earned(w.payType, w.rate, year, month, Payroll.counts(monthAttendance(ctx, w.id, year, month), year, month))
        val earned = calc.earned
        if (earned <= 0) throw BooksRuleException("Nothing earned for this month")
        val balance = advanceBalance(ctx, w.id)
        if (advanceCut > balance + 0.005) throw BooksRuleException("Advance deduction is more than the balance")
        if (advanceCut > earned + 0.005) throw BooksRuleException("Advance deduction is more than the earned salary")
        val net = Math.round((earned - advanceCut) * 100) / 100.0
        val monthKey = Ym.key(year, month)
        val category = if (w.payType == Payroll.DAILY) "Wages" else "Salaries"
        val db = PosDatabase.get(ctx)
        val now = System.currentTimeMillis()
        db.withTransaction {
            val e = MoneyRows.insertExpenseWithCash(db, category, "${w.name} | $monthKey", earned, method, now, cashAmount = net)
            val p = WorkerSalaryPayment(workerId = w.id, month = monthKey, earned = earned, advanceCut = advanceCut, net = net,
                method = method, expenseId = e.id, expenseServerId = e.serverId, createdAt = now, updatedAt = now)
            val id = db.shopExtrasDao().insertSalary(p)
            SyncQueueHelper.enqueueWorkerSalaryPayment(db, p.copy(id = id))
        }
        SyncQueueHelper.trigger(ctx)
    }

    /** Undo salary: its expense + cash OUT go, the advance cut comes back, attendance unlocks. */
    suspend fun deletePayment(ctx: Context, p: WorkerSalaryPayment) {
        val db = PosDatabase.get(ctx)
        db.withTransaction {
            val e = p.expenseId?.let { db.shopExtrasDao().expenseById(it) }
                ?: p.expenseServerId?.takeIf { it.isNotBlank() }?.let { db.expenseDao().findByServerId(it) }
            if (e != null) MoneyRows.deleteExpenseWithCash(db, e)
            val eid = SyncQueueHelper.workerSalaryPaymentEntityId(p)
            db.shopExtrasDao().deleteSalary(p.id)
            SyncQueueHelper.enqueueDelete(db, "worker_salary_payment", eid)
        }
        SyncQueueHelper.trigger(ctx)
    }
}

// ------------------------------------------------------------------------------ Shop rents

object ShopRentRepository {
    private fun desc(shop: RentedShop, ym: String, note: String): String {
        val base = shop.shopName + (if (shop.landlord.isBlank()) "" else " (${shop.landlord})") + " - $ym"
        return if (note.isBlank()) base else "$base | ${note.trim()}"
    }

    private suspend fun termsOf(db: PosDatabase, shopId: Long): Map<String, Double> =
        db.shopExtrasDao().terms(shopId).associate { it.fromYm to it.monthlyRent }

    suspend fun summaries(ctx: Context, now: Long = System.currentTimeMillis()): List<ShopRentSummary> {
        val db = PosDatabase.get(ctx)
        val x = db.shopExtrasDao()
        return x.shops().map { RentMath.summarize(it, termsOf(db, it.id), x.validLedger(it.id), now) }
    }

    suspend fun history(ctx: Context, shopId: Long): List<RentLedgerEntry> =
        PosDatabase.get(ctx).shopExtrasDao().validLedger(shopId).reversed()

    suspend fun monthReport(ctx: Context, ym: String): List<MonthReportRow> {
        val x = PosDatabase.get(ctx).shopExtrasDao()
        val out = mutableListOf<MonthReportRow>()
        for (s in x.shops()) {
            var rent = 0.0; var paid = 0.0; var any = false
            for (e in x.validLedger(s.id)) {
                if (e.ym != ym) continue
                if (e.kind == "charge") { rent += e.amount; any = true }
                else if (e.kind == "payment" || e.kind == "applied") paid += e.amount
            }
            if (any) out.add(MonthReportRow(s, rent, paid))
        }
        return out
    }

    /** New shop. [openingAdvance] = advance already with the landlord (no cash entry — money left earlier). */
    suspend fun addShop(
        ctx: Context, shopName: String, landlord: String, phone: String, monthlyRent: Double, dueDay: Int,
        startYm: String, openingAdvance: Double, autoApply: Boolean, note: String
    ): Long {
        if (shopName.isBlank()) throw BooksRuleException("Dukaan ka naam likhein")
        if (monthlyRent.isNaN() || monthlyRent <= 0) throw BooksRuleException("Rent 0 se zyada hona chahiye")
        if (dueDay !in 1..31) throw BooksRuleException("Tareekh 1 se 31 ke beech ho")
        if (openingAdvance.isNaN() || openingAdvance < 0) throw BooksRuleException("Advance ghalat hai")
        val db = PosDatabase.get(ctx)
        val x = db.shopExtrasDao()
        val now = System.currentTimeMillis()
        val id = db.withTransaction {
            val s = RentedShop(shopName = shopName.trim(), landlord = landlord.trim(), phone = phone.trim(), dueDay = dueDay,
                startYm = startYm, autoApply = autoApply, active = true, note = note.trim(), createdAt = now, updatedAt = now)
            val id = x.insertShop(s)
            x.upsertTerm(RentedShopTerm(id, startYm, monthlyRent))
            SyncQueueHelper.enqueueShopRent(db, s.copy(id = id))
            if (openingAdvance > RentMath.EPS) {
                val adv = RentLedgerEntry(shopId = id, kind = "advance", ym = "", amount = openingAdvance, method = "cash",
                    note = "Opening advance", createdAt = now, updatedAt = now)
                val advId = x.insertEntry(adv)
                SyncQueueHelper.enqueueShopRentEntry(db, adv.copy(id = advId))
            }
            id
        }
        ensureCharges(ctx, now)
        return id
    }

    suspend fun updateShop(ctx: Context, shopId: Long, shopName: String, landlord: String, phone: String, dueDay: Int, autoApply: Boolean, note: String) {
        if (shopName.isBlank()) throw BooksRuleException("Dukaan ka naam likhein")
        if (dueDay !in 1..31) throw BooksRuleException("Tareekh 1 se 31 ke beech ho")
        val db = PosDatabase.get(ctx)
        db.withTransaction {
            val s = db.shopExtrasDao().shop(shopId) ?: return@withTransaction
            val u = s.copy(shopName = shopName.trim(), landlord = landlord.trim(), phone = phone.trim(), dueDay = dueDay,
                autoApply = autoApply, note = note.trim(), updatedAt = System.currentTimeMillis())
            db.shopExtrasDao().updateShop(u)
            SyncQueueHelper.enqueueShopRent(db, u)
        }
        SyncQueueHelper.trigger(ctx)
    }

    suspend fun setActive(ctx: Context, shopId: Long, active: Boolean) {
        val db = PosDatabase.get(ctx)
        db.withTransaction {
            val s = db.shopExtrasDao().shop(shopId) ?: return@withTransaction
            val u = s.copy(active = active, updatedAt = System.currentTimeMillis())
            db.shopExtrasDao().updateShop(u)
            SyncQueueHelper.enqueueShopRent(db, u)
        }
        SyncQueueHelper.trigger(ctx)
    }

    /** Only when there is no payment / advance (just rent charges). */
    suspend fun deleteShop(ctx: Context, shopId: Long) {
        val db = PosDatabase.get(ctx)
        val x = db.shopExtrasDao()
        db.withTransaction {
            if (x.moneyEntryCount(shopId) > 0)
                throw BooksRuleException("Is dukaan ka hisaab (payment/advance) maujood hai — hatane ke bajaye \"Band karein\" istemal karein")
            val s = x.shop(shopId) ?: return@withTransaction
            val shopEid = SyncQueueHelper.shopRentEntityId(s)
            RecycleBin.record(db, ctx, RecycleBin.SHOP_RENT, "$shopId", s.shopName, s.landlord, 0.0,
                RecycleBinData(shops = listOf(s), terms = x.terms(shopId), ledger = x.entriesOf(shopId)))
            val entryEids = x.entriesOf(shopId).mapNotNull { SyncQueueHelper.shopRentEntryEntityIdFor(db, it) }
            x.deleteEntriesOf(shopId); x.deleteTerms(shopId); x.deleteShop(shopId)
            for (eid in entryEids) SyncQueueHelper.enqueueDelete(db, "shop_rent_entry", eid)
            SyncQueueHelper.enqueueDelete(db, "shop_rent", shopEid)
        }
        SyncQueueHelper.trigger(ctx)
    }

    /** New rent from [fromYm]; earlier months unchanged; later unpaid (and not waived) charges re-priced. */
    suspend fun changeRent(ctx: Context, shopId: Long, fromYm: String, newRent: Double) {
        if (newRent.isNaN() || newRent <= 0) throw BooksRuleException("Rent 0 se zyada hona chahiye")
        val db = PosDatabase.get(ctx)
        val x = db.shopExtrasDao()
        val now = System.currentTimeMillis()
        db.withTransaction {
            x.upsertTerm(RentedShopTerm(shopId, fromYm, newRent))
            val terms = termsOf(db, shopId)
            for (c in x.entriesOf(shopId)) {
                if (c.kind != "charge" || c.ym < fromYm) continue
                if (x.paidEntryCount(shopId, c.ym) > 0) continue
                if (c.amount <= RentMath.EPS) continue
                val u = c.copy(amount = RentMath.rentForMonth(terms, c.ym), updatedAt = now)
                x.updateEntry(u)
                SyncQueueHelper.enqueueShopRentEntry(db, u)
            }
            val s = x.shop(shopId) ?: return@withTransaction
            val u = s.copy(updatedAt = now)
            x.updateShop(u)
            SyncQueueHelper.enqueueShopRent(db, u)
        }
        SyncQueueHelper.trigger(ctx)
    }

    /** Waive a month's rent (charge → 0). Only if nothing was paid for it. */
    suspend fun waiveMonth(ctx: Context, shopId: Long, ym: String) {
        val db = PosDatabase.get(ctx)
        val x = db.shopExtrasDao()
        db.withTransaction {
            if (x.paidEntryCount(shopId, ym) > 0) throw BooksRuleException("Is mahine mein payment ho chuki hai — pehle wo entry hatayen")
            val c = x.entryFor(shopId, "charge", ym) ?: throw BooksRuleException("Is mahine ka rent nahi bana")
            val u = c.copy(amount = 0.0, updatedAt = System.currentTimeMillis())
            x.updateEntry(u)
            SyncQueueHelper.enqueueShopRentEntry(db, u)
        }
        SyncQueueHelper.trigger(ctx)
    }

    /** Create every missing monthly charge up to this month for active shops; auto-apply advance. */
    suspend fun ensureCharges(ctx: Context, now: Long = System.currentTimeMillis()): Int {
        val db = PosDatabase.get(ctx)
        val x = db.shopExtrasDao()
        val nowYm = Ym.of(now)
        var created = 0
        db.withTransaction {
            cleanOrphans(db)
            for (shop in x.shops().filter { it.active }) {
                val terms = termsOf(db, shop.id)
                if (terms.isEmpty()) continue
                val have = x.entriesOf(shop.id).filter { it.kind == "charge" }.map { it.ym }.toHashSet()
                var made = 0
                for (ym in Ym.range(shop.startYm, nowYm)) {
                    if (ym in have) continue
                    val rent = RentMath.rentForMonth(terms, ym)
                    if (rent <= 0) continue
                    val c = RentLedgerEntry(shopId = shop.id, kind = "charge", ym = ym, amount = rent, method = "cash", note = "",
                        createdAt = Ym.dateOf(ym, 1),
                        serverId = SyncQueueHelper.shopRentChargeEntityId(SyncQueueHelper.shopRentEntityId(shop), ym),
                        updatedAt = now)
                    val cid = x.insertEntry(c)
                    SyncQueueHelper.enqueueShopRentEntry(db, c.copy(id = cid))
                    made++
                }
                created += made
                if (made > 0 && shop.autoApply) applyAdvanceIn(db, shop, now)
            }
        }
        if (created > 0) SyncQueueHelper.trigger(ctx)
        return created
    }

    private suspend fun cleanOrphans(db: PosDatabase) {
        val x = db.shopExtrasDao()
        for (e in x.orphanEntries()) {
            if (e.kind == "applied" && e.cashId != null) MoneyRows.deleteCashById(db, e.cashId)
            val eid = SyncQueueHelper.shopRentEntryEntityIdFor(db, e)
            x.deleteEntry(e.id)
            if (eid != null) SyncQueueHelper.enqueueDelete(db, "shop_rent_entry", eid)
        }
    }

    private suspend fun insertAdvance(db: PosDatabase, shop: RentedShop, amount: Double, method: String, note: String, now: Long) {
        val cash = MoneyRows.insertCash(db, "OUT", method, amount,
            "Rent Advance: ${shop.shopName}" + (if (note.isBlank()) "" else " | ${note.trim()}"), "", now)
        val adv = RentLedgerEntry(shopId = shop.id, kind = "advance", ym = "", amount = amount, method = method,
            note = note.trim(), cashId = cash.id, createdAt = now, updatedAt = now)
        val id = db.shopExtrasDao().insertEntry(adv)
        SyncQueueHelper.enqueueShopRentEntry(db, adv.copy(id = id))
    }

    /** Pay rent: oldest due month first; extra becomes advance. Returns (toRent, toAdvance). */
    suspend fun pay(ctx: Context, shopId: Long, amount: Double, method: String, note: String, at: Long = System.currentTimeMillis()): Pair<Double, Double> {
        MoneyRows.checkMethod(method); MoneyRows.checkAmount(amount)
        val db = PosDatabase.get(ctx)
        val x = db.shopExtrasDao()
        var toRent = 0.0; var toAdvance = 0.0
        db.withTransaction {
            val shop = x.shop(shopId) ?: throw BooksRuleException("Dukaan nahi mili")
            val sum = RentMath.summarize(shop, termsOf(db, shopId), x.validLedger(shopId), at)
            val (allocs, extra) = RentMath.allocateFifo(sum.dues, amount)
            for (a in allocs) {
                val e = MoneyRows.insertExpenseWithCash(db, RentMath.CATEGORY, desc(shop, a.ym, note), a.amount, method, at)
                val p = RentLedgerEntry(shopId = shopId, kind = "payment", ym = a.ym, amount = a.amount, method = method,
                    note = note.trim(), expenseId = e.id, createdAt = at, updatedAt = at)
                val pid = x.insertEntry(p)
                SyncQueueHelper.enqueueShopRentEntry(db, p.copy(id = pid))
                toRent += a.amount
            }
            if (extra > RentMath.EPS) {
                insertAdvance(db, shop, extra, method, if (note.isBlank()) "Extra rent" else note, at)
                toAdvance = extra
            }
        }
        SyncQueueHelper.trigger(ctx)
        return Pair(toRent, toAdvance)
    }

    /** Advance with the landlord: Cash/Bank OUT, NOT an expense. */
    suspend fun giveAdvance(ctx: Context, shopId: Long, amount: Double, method: String, note: String) {
        MoneyRows.checkMethod(method); MoneyRows.checkAmount(amount)
        val db = PosDatabase.get(ctx)
        db.withTransaction {
            val shop = db.shopExtrasDao().shop(shopId) ?: throw BooksRuleException("Dukaan nahi mili")
            insertAdvance(db, shop, amount, method, note, System.currentTimeMillis())
        }
        SyncQueueHelper.trigger(ctx)
    }

    suspend fun applyAdvance(ctx: Context, shopId: Long): Double {
        val db = PosDatabase.get(ctx)
        var applied = 0.0
        db.withTransaction {
            val shop = db.shopExtrasDao().shop(shopId) ?: throw BooksRuleException("Dukaan nahi mili")
            applied = applyAdvanceIn(db, shop, System.currentTimeMillis())
        }
        SyncQueueHelper.trigger(ctx)
        return applied
    }

    /** Equal cash IN + OUT (balance unchanged — money left with the advance) + this month's Rent expense. */
    private suspend fun applyAdvanceIn(db: PosDatabase, shop: RentedShop, t: Long): Double {
        val x = db.shopExtrasDao()
        val ledger = x.validLedger(shop.id)
        val sum = RentMath.summarize(shop, termsOf(db, shop.id), ledger, t)
        if (sum.advanceBalance <= RentMath.EPS || sum.dues.isEmpty()) return 0.0
        val (allocs, _) = RentMath.allocateFifo(sum.dues, sum.advanceBalance)
        var method = "cash"
        for (e in ledger) if (e.kind == "advance") method = e.method
        var total = 0.0
        for (a in allocs) {
            val cin = MoneyRows.insertCash(db, "IN", method, a.amount, "Rent adjusted from advance: ${shop.shopName} (${a.ym})", "", t)
            val e = MoneyRows.insertExpenseWithCash(db, RentMath.CATEGORY, desc(shop, a.ym, "") + " | from advance", a.amount, method, t)
            val ap = RentLedgerEntry(shopId = shop.id, kind = "applied", ym = a.ym, amount = a.amount, method = method,
                note = "Advance se kata", expenseId = e.id, cashId = cin.id, createdAt = t, updatedAt = t)
            val id = x.insertEntry(ap)
            SyncQueueHelper.enqueueShopRentEntry(db, ap.copy(id = id))
            total += a.amount
        }
        return total
    }

    /** Remove a wrong payment / advance / advance-adjust (its Expense + Cash go too). */
    suspend fun deleteEntry(ctx: Context, entryId: Long) {
        val db = PosDatabase.get(ctx)
        val x = db.shopExtrasDao()
        db.withTransaction {
            val e = x.entry(entryId) ?: return@withTransaction
            val eid = SyncQueueHelper.shopRentEntryEntityIdFor(db, e)
            when (e.kind) {
                "payment", "applied" -> {
                    val ex = e.expenseId?.let { x.expenseById(it) }
                        ?: e.expenseServerId?.takeIf { it.isNotBlank() }?.let { db.expenseDao().findByServerId(it) }
                    if (ex != null) MoneyRows.deleteExpenseWithCash(db, ex)
                    if (e.kind == "applied" && e.cashId != null) MoneyRows.deleteCashById(db, e.cashId)
                }
                "advance" -> {
                    val shop = x.shop(e.shopId) ?: throw BooksRuleException("Dukaan nahi mili")
                    val sum = RentMath.summarize(shop, termsOf(db, e.shopId), x.validLedger(e.shopId), System.currentTimeMillis())
                    if (sum.advanceBalance + RentMath.EPS < e.amount)
                        throw BooksRuleException("Is advance mein se rent kata ja chuka hai — pehle \"Advance se kata\" wali entry hatayen")
                    if (e.cashId != null) MoneyRows.deleteCashById(db, e.cashId)
                }
                else -> throw BooksRuleException("Ye entry hatai nahi ja sakti (rent maaf karne ke liye \"Rent maaf\" istemal karein)")
            }
            x.deleteEntry(entryId)
            if (eid != null) SyncQueueHelper.enqueueDelete(db, "shop_rent_entry", eid)
        }
        SyncQueueHelper.trigger(ctx)
    }
}

// ------------------------------------------------------------------------------ Utility bills

data class BillView(val bill: UtilityBill, val status: BillStatus, val lastAmount: Double?) {
    val suggestedAmount: Double get() = lastAmount ?: bill.avgAmount
}

object UtilityBillRepository {
    suspend fun save(ctx: Context, b: UtilityBill): Long {
        if (b.name.isBlank()) throw BooksRuleException("Bill name is required")
        if (b.dueDay !in 1..31) throw BooksRuleException("Due day must be between 1 and 31")
        if (b.avgAmount.isNaN() || b.avgAmount < 0) throw BooksRuleException("Invalid amount")
        if (b.kind !in Bills.KINDS) throw BooksRuleException("Invalid bill type")
        val db = PosDatabase.get(ctx)
        val now = System.currentTimeMillis()
        val row = b.copy(name = b.name.trim(), createdAt = if (b.createdAt == 0L) now else b.createdAt, updatedAt = now)
        val id = db.withTransaction {
            val id = if (row.id == 0L) db.shopExtrasDao().insertBill(row) else { db.shopExtrasDao().updateBill(row); row.id }
            SyncQueueHelper.enqueueUtilityBill(db, row.copy(id = id))
            id
        }
        SyncQueueHelper.trigger(ctx)
        return id
    }

    /** Bill + its payment history go; the expenses already recorded stay. */
    suspend fun deleteBill(ctx: Context, billId: Long) {
        val db = PosDatabase.get(ctx)
        val x = db.shopExtrasDao()
        db.withTransaction {
            val b = x.bill(billId) ?: return@withTransaction
            val billEid = SyncQueueHelper.utilityBillEntityId(b)
            for (p in x.billPayments(billId)) {
                SyncQueueHelper.utilityBillPaymentEntityIdFor(db, p)?.let { SyncQueueHelper.enqueueDelete(db, "utility_bill_payment", it) }
            }
            x.deleteBillPaymentsOf(billId)
            x.deleteBill(billId)
            SyncQueueHelper.enqueueDelete(db, "utility_bill", billEid)
        }
        SyncQueueHelper.trigger(ctx)
    }

    suspend fun payments(ctx: Context, billId: Long) = PosDatabase.get(ctx).shopExtrasDao().billPayments(billId)

    suspend fun views(ctx: Context, now: Long = System.currentTimeMillis(), activeOnly: Boolean = false): List<BillView> {
        val x = PosDatabase.get(ctx).shopExtrasDao()
        val out = mutableListOf<BillView>()
        for (b in x.bills()) {
            if (activeOnly && !b.active) continue
            val pays = x.billPayments(b.id)
            out.add(BillView(b, Bills.status(b.dueDay, b.createdAt, pays.map { it.month }.toSet(), now), pays.firstOrNull()?.amount))
        }
        return out.sortedWith(Comparator { a, c ->
            if (a.bill.active != c.bill.active) return@Comparator if (a.bill.active) -1 else 1
            if (a.status.paid != c.status.paid) return@Comparator if (a.status.paid) 1 else -1
            val d = a.status.due.compareTo(c.status.due)
            if (d != 0) d else a.bill.name.lowercase().compareTo(c.bill.name.lowercase())
        })
    }

    suspend fun pay(ctx: Context, bill: UtilityBill, month: String, amount: Double, method: String, note: String, recordExpense: Boolean) {
        MoneyRows.checkMethod(method); MoneyRows.checkAmount(amount)
        if (!Regex("^\\d{4}-\\d{2}$").matches(month)) throw BooksRuleException("Invalid month")
        val db = PosDatabase.get(ctx)
        val x = db.shopExtrasDao()
        val now = System.currentTimeMillis()
        db.withTransaction {
            if (x.billPaymentFor(bill.id, month) != null) throw BooksRuleException("This month is already paid")
            val e = if (recordExpense) MoneyRows.insertExpenseWithCash(db, Bills.CATEGORY, "${bill.name} | $month", amount, method, now) else null
            val p = UtilityBillPayment(billId = bill.id, month = month, amount = amount, method = method, note = note.trim(),
                expenseId = e?.id, expenseServerId = e?.serverId, paidAt = now, updatedAt = now)
            val id = x.insertBillPayment(p)
            SyncQueueHelper.enqueueUtilityBillPayment(db, p.copy(id = id))
        }
        SyncQueueHelper.trigger(ctx)
    }

    /** Undo a payment; its expense + cash row go too (if still there). */
    suspend fun undoPayment(ctx: Context, p: UtilityBillPayment) {
        val db = PosDatabase.get(ctx)
        val x = db.shopExtrasDao()
        db.withTransaction {
            val e = p.expenseId?.let { x.expenseById(it) }
                ?: p.expenseServerId?.takeIf { it.isNotBlank() }?.let { db.expenseDao().findByServerId(it) }
            if (e != null) MoneyRows.deleteExpenseWithCash(db, e)
            val eid = SyncQueueHelper.utilityBillPaymentEntityIdFor(db, p)
            x.deleteBillPayment(p.id)
            if (eid != null) SyncQueueHelper.enqueueDelete(db, "utility_bill_payment", eid)
        }
        SyncQueueHelper.trigger(ctx)
    }
}
