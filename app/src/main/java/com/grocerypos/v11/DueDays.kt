package com.grocerypos.v11

/**
 * NEW (Flutter parity, batch 6): udhaar bill ki default muddat — port of Flutter's utils/due_days.dart.
 *
 * Pehle naye udhaar bill par koi due date nahi lagti thi, to bill "date nahi" ban kar wasooli /
 * reminders / aging mein late hi nahi dikhta tha. Ab naye bill par (jis mein kuch udhaar baqi ho):
 * customer ki apni muddat (Customer.dueDays) > 0 ho to wahi, warna dukaan ki default muddat
 * (Settings → Default credit period, default 7 din). 0 likhne par default band (purana tareeqa).
 * Edit kiye gaye bill ki tareekh nahi badalti; Due Reminders se haath se tareekh pehle jaisi lagti hai.
 */
object DueDays {
    const val SETTING_KEY = "default_due_days"
    const val FALLBACK = 7
    const val MAX = 365

    /** Settings text → days. Blank / bad => 7; negative => 0 (off); capped at 365. */
    fun parseShop(raw: String?): Int {
        val n = raw?.trim()?.toIntOrNull() ?: return FALLBACK
        if (n < 0) return 0
        return if (n > MAX) MAX else n
    }

    /** Customer's own period first, else the shop default. */
    fun effective(customerDays: Int, shopDays: Int): Int = if (customerDays > 0) customerDays else shopDays

    /** Due date for a NEW bill: start of the bill's day + days; 0 when nothing is owed or days == 0. */
    fun dueDateFor(saleDateMillis: Long, owed: Double, customerDays: Int, shopDays: Int): Long {
        if (owed <= 0.009) return 0L
        val d = effective(customerDays, shopDays)
        if (d <= 0) return 0L
        return DayUtil.plusDays(DayUtil.start(saleDateMillis), d)
    }

    suspend fun shopDays(db: PosDatabase): Int =
        try { parseShop(db.appSettingDao().get(SETTING_KEY)?.value) } catch (e: Exception) { FALLBACK }

    suspend fun setShopDays(db: PosDatabase, days: Int) {
        db.appSettingDao().set(AppSetting(SETTING_KEY, days.coerceIn(0, MAX).toString()))
    }
}
