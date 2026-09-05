package com.grocerypos.v11

import android.content.Context

/**
 * Stores the shop's default Retail and Wholesale markup percentages ("% above
 * Purchase Rate"), used to auto-calculate Retail/Wholesale rate wherever rates
 * are edited (see PartyDashboardActivity.showEditRatesDialog() /
 * showMarkupSettingsDialog()).
 *
 * WHY THIS EXISTS: with many items in stock, Retail/Wholesale rates were being
 * typed in one item at a time, and every time a Purchase Rate changed the
 * other two had to be recomputed and retyped by hand. Storing a "% above cost"
 * once here lets those two fields auto-fill (while remaining fully editable)
 * instead of always starting blank or stale.
 *
 * A percent of 0.0 (the default for a fresh install) means "no markup
 * configured" — auto-fill is simply skipped for that rate, so nothing changes
 * for a shop that hasn't opted in via the Markup Settings dialog.
 *
 * Plain SharedPreferences, like BranchConfigStore/CloudConfigStore — no Room
 * schema change or migration needed for a single pair of shop-wide numbers.
 */
object RateMarkupSettings {
    private const val PREFS = "rate_markup_prefs"
    private const val KEY_RETAIL_PCT = "retail_markup_pct"
    private const val KEY_WHOLESALE_PCT = "wholesale_markup_pct"

    fun getRetailMarkupPercent(context: Context): Double =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_RETAIL_PCT, 0f).toDouble()

    fun setRetailMarkupPercent(context: Context, percent: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_RETAIL_PCT, percent.toFloat()).apply()
    }

    fun getWholesaleMarkupPercent(context: Context): Double =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_WHOLESALE_PCT, 0f).toDouble()

    fun setWholesaleMarkupPercent(context: Context, percent: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_WHOLESALE_PCT, percent.toFloat()).apply()
    }

    /** cost + cost * percent / 100 — or 0.0 if there's nothing sensible to compute. */
    fun computeFromCost(cost: Double, percent: Double): Double {
        if (cost <= 0.0 || percent <= 0.0) return 0.0
        return cost + (cost * percent / 100.0)
    }
}
