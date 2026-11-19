package com.grocerypos.v11

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

// =====================================================================================
// NEW (Flutter parity, batch 5): reminder notifications — port of Flutter's
//   * due_alert_service.dart      → "Aaj ki wasooli" (Wasooli autopilot queue), 10 AM + 6 PM
//   * return_alert_service.dart   → Amanat / Loan return dates due (admin/manager), 10 AM
//   * bill_reminder_service.dart  → Utility bills: a day before / due day / overdue daily, 9 AM
// Run both from the foreground (MainActivity.onResume) and from a 15-min WorkManager job, so they
// also come when the app is closed. "Already shown today" stamps live in SharedPreferences.
// Nothing here may crash the app: every check is wrapped.
// =====================================================================================

/** Pure text / decision helpers (unit-tested). */
object ReminderText {
    const val DUE_HOUR = 10
    const val EVENING_HOUR = 18
    const val RETURN_HOUR = 10
    const val BILL_HOUR = 9

    fun dateKey(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun hourOf(t: Long): Int = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)

    private fun rs(v: Double) = "Rs " + Math.round(v)

    enum class Slot { NONE, MORNING, EVENING }

    /** Flutter DueAlertService rule: morning once after 10, evening once after 6 PM. */
    fun dueSlot(now: Long, lastMorning: String?, lastEvening: String?): Slot {
        val h = hourOf(now)
        if (h < DUE_HOUR) return Slot.NONE
        val today = dateKey(now)
        return if (h >= EVENING_HOUR) { if (lastEvening == today) Slot.NONE else Slot.EVENING }
        else if (lastMorning == today) Slot.NONE else Slot.MORNING
    }

    fun autopilotBody(q: AutopilotQueue, collectedToday: Double = 0.0, paymentsToday: Int = 0, topN: Int = 3): String {
        val b = StringBuilder()
        if (q.todo.isNotEmpty()) {
            b.append("${q.todo.size} parties ko yaad dilana baqi (${rs(q.todoAmount)})")
            if (q.brokenCount > 0) b.append(" • ${q.brokenCount} ka wada toota")
            val never = q.todo.count { it.neverTouched }
            if (never > 0) b.append(" • $never se abhi tak baat nahi hui")
        } else b.append("Aaj ke sab reminder ho chuke")
        if (q.done.isNotEmpty()) b.append(" • ${q.done.size} ko aaj bhej diya")
        if (paymentsToday > 0 || collectedToday > 0.009) {
            b.append(" • aaj wasool: ${rs(collectedToday)}" + (if (paymentsToday > 0) " ($paymentsToday payment)" else ""))
        }
        if (q.todo.isNotEmpty()) {
            b.append(". Pehle: " + q.todo.take(topN).joinToString(", ") { "${it.party.partyName} ${rs(it.total)}" })
            b.append(". Tap karke 1-1 kar ke bhej dein.")
        } else b.append(".")
        return b.toString()
    }

    /** Return date set and today or earlier; oldest first. */
    fun <T> returnDueNow(all: List<T>, now: Long, dateOf: (T) -> Long): List<T> {
        val tomorrow = DayUtil.plusDays(now, 1)
        return all.filter { dateOf(it) in 1 until tomorrow }.sortedBy { dateOf(it) }
    }

    fun returnBody(names: List<String>, overdue: Int): String {
        val b = StringBuilder("${names.size} Deposit/Loan return dates are due: " + names.take(3).joinToString(", "))
        if (names.size > 3) b.append(" +${names.size - 3}")
        if (overdue > 0) b.append(" ($overdue overdue)")
        b.append(". Tap to view.")
        return b.toString()
    }

    /** "pre" (1 day before) / "due" (today) / "over" (passed) / null. */
    fun billStage(s: BillStatus): String? = when {
        s.paid -> null
        s.daysLeft == 1 -> "pre"
        s.daysLeft == 0 -> "due"
        s.daysLeft < 0 -> "over"
        else -> null
    }

    /** One stamp per bill + month + stage; "over" once a day. */
    fun billStamp(s: BillStatus, stage: String, now: Long) = s.month + "|" + (if (stage == "over") "over" + dateKey(now) else stage)

    fun billTitle(name: String, stage: String) = when (stage) {
        "pre" -> "$name ka bill kal due hai"
        "due" -> "$name ka bill aaj due hai"
        else -> "$name ka bill due guzar chuka hai"
    }

    fun billBody(v: BillView, stage: String): String {
        val amt = if (v.suggestedAmount > 0) " (takreeban Rs " + Math.round(v.suggestedAmount) + ")" else ""
        val c = Calendar.getInstance().apply { timeInMillis = v.status.due }
        val date = "${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.YEAR)}"
        return when (stage) {
            "pre" -> "Kal $date ko due hai$amt. Pay karke app mein \"Paid\" kar dein."
            "due" -> "Aaj due date hai$amt. Pay karke app mein \"Paid\" kar dein."
            else -> "${-v.status.daysLeft} din pehle due thi$amt. Jaldi pay karein."
        }
    }
}

object ReminderNotifier {
    const val PREFS = "reminder_prefs"
    const val KEY_ENABLED = "due_alert_enabled"     // same name as Flutter; default ON
    private const val KEY_DUE_MORNING = "due_alert_last_date"
    private const val KEY_DUE_EVENING = "due_alert_last_evening_date"
    private const val KEY_RETURN = "return_alert_last_date"
    private const val KEY_PERMISSION_ASKED = "notif_permission_asked"

    const val EXTRA_OPEN = "reminder_open"
    const val OPEN_QUEUE = "wasooli_queue"
    const val OPEN_RETURNS = "amanat_loan"
    const val OPEN_BILLS = "utility_bills"

    private const val CH_DUE = "due_alerts"
    private const val CH_RETURN = "return_alerts"
    private const val CH_BILL = "bill_reminders"
    private const val ID_DUE = 7301
    private const val ID_RETURN = 7302
    private const val ID_BILL_BASE = 7400

    private const val WORK_NAME = "grocery_pos_reminders"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, true)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply() }

    private fun role(ctx: Context): String? {
        val s = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
        if (s.getString("username", null) == null) return null
        return s.getString("role", "cashier") ?: "cashier"
    }

    private fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_DUE, "Due reminders", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Jin parties ne payment nahi di, roz yaad dihani" })
        nm.createNotificationChannel(NotificationChannel(CH_RETURN, "Deposit / Loan reminders", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Reminders for deposit return and loan repayment dates" })
        nm.createNotificationChannel(NotificationChannel(CH_BILL, "Bill reminders", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Utility bills (bijli, gas, pani, internet) ki due date ki yaad dihani" })
    }

    private fun tapIntent(ctx: Context, open: String, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN, open)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, channel: String, title: String, body: String, open: String) {
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(ctx, open, id))
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    /** Runs all three checks. Safe to call often (foreground + worker). */
    suspend fun checkAll(ctx: Context, now: Long = System.currentTimeMillis()) {
        val app = ctx.applicationContext
        val r = role(app) ?: return
        // NEW: no reminders while the app license does not allow use.
        if (!LicenseManager.state(app, now).canUse) return
        if (!canPost(app)) return
        try { ensureChannels(app) } catch (e: Exception) { return }
        val adminOrManager = r == "admin" || r == "manager"
        try { checkDue(app, now) } catch (e: Exception) { android.util.Log.w("Reminders", "due", e) }
        if (adminOrManager) {
            try { checkReturns(app, now) } catch (e: Exception) { android.util.Log.w("Reminders", "returns", e) }
            try { checkBills(app, now) } catch (e: Exception) { android.util.Log.w("Reminders", "bills", e) }
        }
    }

    private suspend fun checkDue(ctx: Context, now: Long) {
        if (!isEnabled(ctx)) return
        val p = prefs(ctx)
        val today = ReminderText.dateKey(now)
        val morningDone = p.getString(KEY_DUE_MORNING, null) == today
        val slot = ReminderText.dueSlot(now, p.getString(KEY_DUE_MORNING, null), p.getString(KEY_DUE_EVENING, null))
        if (slot == ReminderText.Slot.NONE) return
        val day = CollectionRepository.autopilotToday(ctx, now)
        if (day.queue.todo.isEmpty()) return
        val evening = slot == ReminderText.Slot.EVENING
        post(ctx, ID_DUE, CH_DUE, if (evening && morningDone) "Aaj ki wasooli abhi baaki hai" else "Aaj ki wasooli",
            ReminderText.autopilotBody(day.queue, day.collectedToday, day.paymentsToday), OPEN_QUEUE)
        val e = p.edit().putString(if (evening) KEY_DUE_EVENING else KEY_DUE_MORNING, today)
        if (evening) e.putString(KEY_DUE_MORNING, today)
        e.apply()
    }

    private suspend fun checkReturns(ctx: Context, now: Long) {
        if (!isEnabled(ctx)) return
        val p = prefs(ctx)
        if (ReminderText.hourOf(now) < ReminderText.RETURN_HOUR) return
        val today = ReminderText.dateKey(now)
        if (p.getString(KEY_RETURN, null) == today) return
        val due = ReminderText.returnDueNow(CollectionRepository.returnReminders(ctx), now) { it.returnDate }
        if (due.isEmpty()) return
        val startToday = DayUtil.start(now)
        post(ctx, ID_RETURN, CH_RETURN, "Deposit / Loan returns",
            ReminderText.returnBody(due.map { it.partyName }, due.count { it.returnDate < startToday }), OPEN_RETURNS)
        p.edit().putString(KEY_RETURN, today).apply()
    }

    private suspend fun checkBills(ctx: Context, now: Long) {
        if (ReminderText.hourOf(now) < ReminderText.BILL_HOUR) return
        val p = prefs(ctx)
        for (v in UtilityBillRepository.views(ctx, now, activeOnly = true)) {
            if (!v.bill.reminder) continue
            val stage = ReminderText.billStage(v.status) ?: continue
            val key = "bill_reminder_last_${v.bill.id}"
            val stamp = ReminderText.billStamp(v.status, stage, now)
            if (p.getString(key, null) == stamp) continue
            post(ctx, ID_BILL_BASE + (v.bill.id % 1000).toInt(), CH_BILL, ReminderText.billTitle(v.bill.name, stage), ReminderText.billBody(v, stage), OPEN_BILLS)
            p.edit().putString(key, stamp).apply()
        }
    }

    // ------------------------------------------------------------------ scheduling / permission

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Android 13+: ask once for notification permission (from MainActivity). */
    fun askPermissionOnce(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val p = prefs(activity)
        if (p.getBoolean(KEY_PERMISSION_ASKED, false)) return
        p.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7300)
    }

    /** Where a tapped notification should go (MainActivity calls this). */
    fun targetFor(open: String?): Class<*>? = when (open) {
        OPEN_QUEUE -> com.grocerypos.v11.ui.CollectionActivity::class.java
        OPEN_RETURNS -> com.grocerypos.v11.ui.AmanatLoanActivity::class.java
        OPEN_BILLS -> com.grocerypos.v11.ui.UtilityBillsActivity::class.java
        else -> null
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try { ReminderNotifier.checkAll(applicationContext) } catch (e: Exception) { }
        return Result.success()
    }
}
