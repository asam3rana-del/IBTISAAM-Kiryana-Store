package com.grocerypos.v11

import android.content.Context
import androidx.room.withTransaction
import com.grocerypos.v11.data.PartyRepository
import kotlin.math.abs

// =====================================================================================
// NEW (Flutter parity, batch 1): DB side of Amanat / Loan, follow-up log, Wasooli
// Autopilot and Qist — ported from Flutter's due_reminders_repository.dart,
// due_followup_repository.dart, collection_autopilot_repository.dart, qist_repository.dart
// and payment_repository.dart (_renewDueDates). Every write runs in one transaction and is
// queued for sync in that same transaction (customer row / due_followup / cash_transaction),
// exactly like the Flutter side, so both apps share one set of Firestore docs.
// =====================================================================================

enum class ReturnEntryResult { OK, NOT_FOUND, KIND_MISMATCH, INVALID_AMOUNT, NOTHING_TO_SETTLE }

/** Amanat / Loan party (customers.kind = amanat | loan). closing = LIVE balance (opening + ledger + stuck). */
data class ReturnReminder(
    val customer: Customer,
    val closing: Double,
    val entries: List<ReturnEntry>
) {
    val partyId: Long get() = customer.id
    val partyName: String get() = customer.name
    val partyPhone: String get() = customer.phone
    val kind: String get() = customer.kind
    val isAmanat: Boolean get() = customer.kind == "amanat"
    val returnDate: Long get() = customer.returnDate
    val entriesTotal: Double get() = ReturnEntries.total(entries)
    /** Entries ke bahar ka purana / opening hissa (musbat). */
    val baseAmount: Double
        get() {
            val base = customer.openingBalance - ReturnEntries.signedTotal(kind, entries)
            return if (abs(base) < 0.009) 0.0 else abs(base)
        }
}

data class AutopilotDay(val queue: AutopilotQueue, val collectedToday: Double, val paymentsToday: Int)

object CollectionRepository {

    private fun staff(ctx: Context): String =
        ctx.getSharedPreferences("session", Context.MODE_PRIVATE).getString("username", "") ?: ""

    private fun day(millis: Long): Long = if (millis <= 0) 0L else DayUtil.start(millis)

    // ------------------------------------------------------------------ dues (customers)

    /** Flutter dueSales() (customer side): bills capped by LIVE balance + "Previous balance" rows. */
    suspend fun dueSales(ctx: Context): List<DueBill> {
        val db = PosDatabase.get(ctx)
        val live = PartyRepository(db, ctx.applicationContext).liveCustomerBalances()
        val closings = LinkedHashMap<Long, Triple<String, String, Double>>()
        for (c in db.customerDao().allList()) {
            closings[c.id] = Triple(c.name, c.phone, c.openingBalance + (live[c.id] ?: 0.0) + c.stuckBalance)
        }
        val bills = db.saleDao().dueSales().map {
            DueBill(it.invoice, it.customerId, it.customerName, it.customerPhone, it.total, it.paid, it.dueDate, it.createdAt)
        }
        val capped = Dues.capByBalance(bills, closings.mapValues { it.value.third })
        return Dues.addUnbilledBalance(capped, closings)
    }

    suspend fun dueParties(ctx: Context): List<DueParty> =
        Dues.groupByParty(dueSales(ctx)).filter { it.partyId != null && !it.isAdvance && it.total > 0.009 }

    // ------------------------------------------------------------------ follow-ups

    suspend fun allFollowUpsByParty(db: PosDatabase): Map<Long, List<DueFollowUp>> =
        db.dueFollowUpDao().all().groupBy { it.partyId }

    suspend fun addFollowUp(
        ctx: Context, partyId: Long, partyName: String, method: String,
        outcome: String = FollowUpOutcome.NOTE, amount: Double = 0.0,
        promiseDate: Long = 0L, nextFollowUp: Long = 0L, note: String = ""
    ): Long {
        val db = PosDatabase.get(ctx)
        val now = System.currentTimeMillis()
        val id = db.withTransaction {
            val row = DueFollowUp(
                partyId = partyId, partyName = partyName, method = method, outcome = outcome,
                amount = amount, promiseDate = day(promiseDate), nextFollowUp = day(nextFollowUp),
                note = note.trim(), staff = staff(ctx), createdAt = now, updatedAt = now
            )
            val newId = db.dueFollowUpDao().insert(row)
            SyncQueueHelper.enqueueDueFollowup(db, row.copy(id = newId))
            newId
        }
        SyncQueueHelper.trigger(ctx)
        return id
    }

    suspend fun setFollowUpResult(
        ctx: Context, id: Long, outcome: String, amount: Double, promiseDate: Long, nextFollowUp: Long, note: String
    ): Boolean {
        val db = PosDatabase.get(ctx)
        val ok = db.withTransaction {
            val cur = db.dueFollowUpDao().find(id) ?: return@withTransaction false
            val upd = cur.copy(
                outcome = outcome, amount = amount, promiseDate = day(promiseDate),
                nextFollowUp = day(nextFollowUp), note = note.trim(), updatedAt = System.currentTimeMillis()
            )
            db.dueFollowUpDao().update(upd)
            SyncQueueHelper.enqueueDueFollowup(db, upd)
            true
        }
        if (ok) SyncQueueHelper.trigger(ctx)
        return ok
    }

    suspend fun deleteFollowUp(ctx: Context, id: Long): Boolean {
        val db = PosDatabase.get(ctx)
        val ok = db.withTransaction {
            val cur = db.dueFollowUpDao().find(id) ?: return@withTransaction false
            // Entity id BEFORE the delete, so an entry pulled from the other app keeps its real id.
            val eid = SyncQueueHelper.dueFollowupEntityId(cur)
            db.dueFollowUpDao().deleteById(id)
            SyncQueueHelper.enqueueDelete(db, "due_followup", eid)
            true
        }
        if (ok) SyncQueueHelper.trigger(ctx)
        return ok
    }

    // ------------------------------------------------------------------ autopilot

    suspend fun paySchedules(db: PosDatabase): Map<Long, String> =
        db.customerDao().allList().filter { it.paySchedule.isNotBlank() }.associate { it.id to it.paySchedule }

    suspend fun qistActive(db: PosDatabase, now: Long = System.currentTimeMillis()): Map<Long, QistStatus> {
        val out = HashMap<Long, QistStatus>()
        for (c in db.customerDao().allList()) {
            val plan = QistPlan.fromJson(c.qistPlan) ?: continue
            out[c.id] = Qist.status(plan, db.paymentDao().customerPaidSince(c.id, plan.createdAt), now)
        }
        return out
    }

    suspend fun qistStatus(db: PosDatabase, c: Customer, now: Long = System.currentTimeMillis()): QistStatus? {
        val plan = QistPlan.fromJson(c.qistPlan) ?: return null
        return Qist.status(plan, db.paymentDao().customerPaidSince(c.id, plan.createdAt), now)
    }

    suspend fun autopilotToday(ctx: Context, now: Long = System.currentTimeMillis()): AutopilotDay {
        val db = PosDatabase.get(ctx)
        val parties = dueParties(ctx)
        val queue = Autopilot.build(parties, allFollowUpsByParty(db), now, paySchedules(db), qistActive(db, now))
        val start = DayUtil.start(now)
        val end = DayUtil.plusDays(now, 1)
        val collected = runCatching { db.paymentDao().customerCollectedBetween(start, end) }.getOrDefault(0.0)
        val count = runCatching { db.paymentDao().customerPaymentCountBetween(start, end) }.getOrDefault(0)
        return AutopilotDay(queue, collected, count)
    }

    // ------------------------------------------------------------------ customer field edits

    /** Customer row ko badal kar (dirty + updatedAt) sync queue mein daalna — ek transaction. */
    private suspend fun updateCustomer(db: PosDatabase, c: Customer) {
        val upd = c.copy(updatedAt = System.currentTimeMillis(), dirty = true)
        db.customerDao().update(upd)
        SyncQueueHelper.enqueueCustomer(db, upd)
    }

    suspend fun setPaySchedule(ctx: Context, customerId: Long, schedule: String) {
        val db = PosDatabase.get(ctx)
        db.withTransaction {
            val c = db.customerDao().find(customerId) ?: return@withTransaction
            updateCustomer(db, c.copy(paySchedule = schedule))
        }
        SyncQueueHelper.trigger(ctx)
    }

    suspend fun saveQistPlan(ctx: Context, customerId: Long, plan: QistPlan?) {
        val db = PosDatabase.get(ctx)
        db.withTransaction {
            val c = db.customerDao().find(customerId) ?: return@withTransaction
            updateCustomer(db, c.copy(qistPlan = plan?.toJson() ?: ""))
        }
        SyncQueueHelper.trigger(ctx)
    }

    // ------------------------------------------------------------------ due renewal

    /** Partial payment ke baad: customer ke baqi (dated) bills ki due date = aaj + muddat (peeche kabhi nahi). */
    suspend fun renewDueDates(ctx: Context, customerId: Long) {
        val db = PosDatabase.get(ctx)
        val now = System.currentTimeMillis()
        val c = db.customerDao().find(customerId) ?: return
        for (s in db.saleDao().dueSales()) {
            if (s.customerId != customerId || s.dueDate <= 0L) continue
            val next = DueRenewal.renewedDueDate(now, s.dueDate, s.createdAt, c.dueDays)
            if (next <= 0L || next == s.dueDate) continue
            db.saleDao().setDueDate(s.invoice, next, now)
            db.saleDao().findSale(s.invoice)?.let { SyncQueueHelper.enqueueSale(db, it) }
        }
        SyncQueueHelper.trigger(ctx)
    }

    // ------------------------------------------------------------------ Amanat / Loan

    suspend fun returnReminders(ctx: Context): List<ReturnReminder> {
        val db = PosDatabase.get(ctx)
        val live = PartyRepository(db, ctx.applicationContext).liveCustomerBalances()
        val list = db.customerDao().returnParties().map {
            ReturnReminder(it, it.openingBalance + (live[it.id] ?: 0.0) + it.stuckBalance, ReturnEntries.decode(it.returnEntries))
        }
        // Date wale pehle (purani pehle), phir bina date (naam se).
        return list.sortedWith(Comparator { a, b ->
            val aNo = a.returnDate <= 0; val bNo = b.returnDate <= 0
            if (aNo != bNo) return@Comparator if (aNo) 1 else -1
            val c = a.returnDate.compareTo(b.returnDate)
            if (c != 0) c else a.partyName.lowercase().compareTo(b.partyName.lowercase())
        })
    }

    suspend fun setReturnDate(ctx: Context, partyId: Long, returnDateMillis: Long): Boolean {
        val db = PosDatabase.get(ctx)
        val ok = db.withTransaction {
            val c = db.customerDao().find(partyId) ?: return@withTransaction false
            updateCustomer(db, c.copy(returnDate = day(returnDateMillis)))
            true
        }
        SyncQueueHelper.trigger(ctx)
        return ok
    }

    suspend fun addReturnEntry(
        ctx: Context, partyId: Long, kind: String, amount: Double, dateMillis: Long, returnDateMillis: Long, note: String
    ): ReturnEntryResult {
        if (!(amount > 0) || amount.isInfinite()) return ReturnEntryResult.INVALID_AMOUNT
        val db = PosDatabase.get(ctx)
        val now = System.currentTimeMillis()
        val res = db.withTransaction {
            val cur = db.customerDao().find(partyId) ?: return@withTransaction ReturnEntryResult.NOT_FOUND
            if (cur.kind.isNotEmpty() && cur.kind != kind) return@withTransaction ReturnEntryResult.KIND_MISMATCH
            val entries = ReturnEntries.decode(cur.returnEntries)
            val entry = ReturnEntry(ReturnEntries.nextId(entries), amount, day(dateMillis), day(returnDateMillis), note.trim())
            entries.add(entry)
            val log = ReturnLog.addGiven(ReturnLog.decode(cur.returnLog), kind, entry, now)
            val earliest = ReturnEntries.earliestReturnDate(entries)
            updateCustomer(db, cur.copy(
                kind = kind,
                openingBalance = cur.openingBalance + ReturnEntries.signed(kind, amount),
                returnEntries = ReturnEntries.encode(entries),
                returnLog = ReturnLog.encode(log),
                returnDate = if (earliest > 0) earliest else cur.returnDate
            ))
            ReturnEntryResult.OK
        }
        SyncQueueHelper.trigger(ctx)
        return res
    }

    suspend fun editReturnEntry(
        ctx: Context, partyId: Long, entryId: Int, amount: Double, dateMillis: Long, returnDateMillis: Long, note: String
    ): ReturnEntryResult {
        if (!(amount > 0) || amount.isInfinite()) return ReturnEntryResult.INVALID_AMOUNT
        val db = PosDatabase.get(ctx)
        val now = System.currentTimeMillis()
        val res = db.withTransaction {
            val cur = db.customerDao().find(partyId) ?: return@withTransaction ReturnEntryResult.NOT_FOUND
            val kind = cur.kind
            val entries = ReturnEntries.decode(cur.returnEntries)
            val i = entries.indexOfFirst { it.id == entryId }
            if (i < 0) return@withTransaction ReturnEntryResult.NOT_FOUND
            val old = entries[i]
            entries[i] = old.copy(amount = amount, date = day(dateMillis), returnDate = day(returnDateMillis), note = note.trim())
            val log = ReturnLog.editEntry(ReturnLog.decode(cur.returnLog), kind, old, entries[i], now)
            val delta = ReturnEntries.signed(kind, amount) - ReturnEntries.signed(kind, old.amount)
            updateCustomer(db, cur.copy(
                openingBalance = cur.openingBalance + delta,
                returnEntries = ReturnEntries.encode(entries),
                returnLog = ReturnLog.encode(log),
                returnDate = ReturnEntries.earliestReturnDate(entries)
            ))
            ReturnEntryResult.OK
        }
        SyncQueueHelper.trigger(ctx)
        return res
    }

    suspend fun deleteReturnEntry(ctx: Context, partyId: Long, entryId: Int): ReturnEntryResult {
        val db = PosDatabase.get(ctx)
        val now = System.currentTimeMillis()
        val res = db.withTransaction {
            val cur = db.customerDao().find(partyId) ?: return@withTransaction ReturnEntryResult.NOT_FOUND
            val kind = cur.kind
            val entries = ReturnEntries.decode(cur.returnEntries)
            val i = entries.indexOfFirst { it.id == entryId }
            if (i < 0) return@withTransaction ReturnEntryResult.NOT_FOUND
            val old = entries.removeAt(i)
            val log = ReturnLog.removeEntry(ReturnLog.decode(cur.returnLog), kind, old, now)
            updateCustomer(db, cur.copy(
                openingBalance = cur.openingBalance - ReturnEntries.signed(kind, old.amount),
                returnEntries = ReturnEntries.encode(entries),
                returnLog = ReturnLog.encode(log),
                returnDate = if (entries.isNotEmpty()) ReturnEntries.earliestReturnDate(entries) else cur.returnDate
            ))
            ReturnEntryResult.OK
        }
        SyncQueueHelper.trigger(ctx)
        return res
    }

    /**
     * Amanat wapas (Cash OUT) / Loan wasool (Cash IN). Entries FIFO se kam, opening balance 0 ki taraf,
     * aur Cash register mein ek row (reference "manual-return-entry-<partyId>-<tag>-<ms>", Flutter jaisa).
     */
    suspend fun settleReturn(ctx: Context, partyId: Long, amount: Double, method: String, note: String): ReturnEntryResult {
        if (!(amount > 0) || amount.isInfinite()) return ReturnEntryResult.INVALID_AMOUNT
        val m = if (method == "bank") "bank" else "cash"
        val db = PosDatabase.get(ctx)
        val now = System.currentTimeMillis()
        val res = db.withTransaction {
            val cur = db.customerDao().find(partyId) ?: return@withTransaction ReturnEntryResult.NOT_FOUND
            val kind = cur.kind
            if (kind != "amanat" && kind != "loan") return@withTransaction ReturnEntryResult.KIND_MISMATCH
            val entries = ReturnEntries.decode(cur.returnEntries)
            if (entries.isEmpty()) return@withTransaction ReturnEntryResult.NOTHING_TO_SETTLE
            val (remaining, settled) = ReturnEntries.settleFifo(entries, amount)
            if (!(settled > 0)) return@withTransaction ReturnEntryResult.NOTHING_TO_SETTLE
            val delta = -ReturnEntries.signed(kind, settled)
            val log = ReturnLog.recovered(ReturnLog.decode(cur.returnLog), kind, settled, now, note.trim())
            updateCustomer(db, cur.copy(
                openingBalance = cur.openingBalance + delta,
                returnEntries = ReturnEntries.encode(remaining),
                returnLog = ReturnLog.encode(log),
                returnDate = ReturnEntries.earliestReturnDate(remaining)
            ))
            val isAmanat = kind == "amanat"
            val n = note.trim()
            val tx = CashTransaction(
                type = if (isAmanat) "OUT" else "IN",
                method = m,
                amount = settled,
                reason = (if (isAmanat) "Amanat wapas" else "Loan wasool") + ": ${cur.name}" + (if (n.isEmpty()) "" else " | $n"),
                reference = "manual-return-entry-$partyId-${DeviceTag.current}-$now",
                createdAt = now,
                updatedAt = now
            )
            val txId = db.cashTransactionDao().insert(tx)
            SyncQueueHelper.enqueueCashTransaction(db, tx.copy(id = txId))
            ReturnEntryResult.OK
        }
        SyncQueueHelper.trigger(ctx)
        return res
    }

    /** Maujooda customer ko tag karna / hatana ("" = normal). [amount] opening balance mein jama (signed). */
    suspend fun setKind(ctx: Context, partyId: Long, kind: String, returnDateMillis: Long, signedAmount: Double = 0.0): Boolean {
        val db = PosDatabase.get(ctx)
        val ok = db.withTransaction {
            val c = db.customerDao().find(partyId) ?: return@withTransaction false
            updateCustomer(db, c.copy(
                kind = kind,
                returnDate = day(returnDateMillis),
                openingBalance = c.openingBalance + signedAmount
            ))
            true
        }
        SyncQueueHelper.trigger(ctx)
        return ok
    }

    /** Nayi Amanat / Loan party. Khali naam => null. */
    suspend fun addNewReturnParty(
        ctx: Context, name: String, phone: String, kind: String, returnDateMillis: Long,
        amount: Double, dateMillis: Long, note: String
    ): Long? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val db = PosDatabase.get(ctx)
        val now = System.currentTimeMillis()
        val entries = if (amount > 0 && !amount.isInfinite())
            listOf(ReturnEntry(1, amount, day(dateMillis), day(returnDateMillis), note.trim())) else emptyList()
        val id = db.withTransaction {
            val c = Customer(
                name = trimmed, phone = phone.trim(),
                openingBalance = if (entries.isEmpty()) 0.0 else ReturnEntries.signed(kind, amount),
                kind = kind, returnDate = day(returnDateMillis),
                returnEntries = ReturnEntries.encode(entries),
                returnLog = ReturnLog.encode(ReturnLog.backfill(kind, entries, now)),
                updatedAt = now, dirty = true
            )
            val newId = db.customerDao().insert(c)
            SyncQueueHelper.enqueueCustomer(db, c.copy(id = newId))
            newId
        }
        SyncQueueHelper.trigger(ctx)
        return id
    }

    /** Naam / phone / type / wapsi tarikh / purana (base) amount. Type sirf tab badle jab koi entry na ho. */
    suspend fun editReturnParty(
        ctx: Context, partyId: Long, name: String, phone: String, kind: String, returnDateMillis: Long, baseAmount: Double?
    ): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val db = PosDatabase.get(ctx)
        db.withTransaction {
            val cur = db.customerDao().find(partyId) ?: return@withTransaction
            val entries = ReturnEntries.decode(cur.returnEntries)
            val useKind = if (entries.isNotEmpty() && cur.kind.isNotEmpty()) cur.kind else kind
            var newOpening = cur.openingBalance
            if (baseAmount != null && baseAmount >= 0 && !baseAmount.isInfinite()) {
                val base = cur.openingBalance - ReturnEntries.signedTotal(useKind, entries)
                if (abs(baseAmount - abs(base)) > 0.009) {
                    newOpening = ReturnEntries.signedTotal(useKind, entries) + ReturnEntries.signed(useKind, baseAmount)
                }
            }
            updateCustomer(db, cur.copy(
                name = trimmed, phone = phone.trim(), kind = useKind, openingBalance = newOpening,
                returnDate = if (entries.isNotEmpty()) ReturnEntries.earliestReturnDate(entries) else day(returnDateMillis)
            ))
        }
        SyncQueueHelper.trigger(ctx)
        return true
    }

    suspend fun deleteReturnParty(ctx: Context, partyId: Long) {
        val db = PosDatabase.get(ctx)
        val c = db.customerDao().find(partyId) ?: return
        PartyRepository(db, ctx.applicationContext).deleteCustomer(c)
    }
}
