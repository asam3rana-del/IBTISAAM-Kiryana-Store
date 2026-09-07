package com.grocerypos.v11.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout

/**
 * Shared "premium UI" building blocks (item #24 — architecture duplication).
 *
 * strokedBg/spacer/applyElevation/ovalBg/gradientBg/lighten were each
 * copy-pasted, near-verbatim, into 15-25 different Activity files. Consolidated
 * here as Context extension functions so any Activity can call e.g.
 * `strokedBg(border, cardBg, 18)` after `import com.grocerypos.v11.ui.components.*`
 * instead of declaring its own private copy.
 *
 * CORRECTION (re-audit while doing the full migration): this file previously
 * claimed HistoryActivity was the lone 1.4dp outlier against a 1.2dp majority
 * and kept 1.2dp on that basis. A full sweep of all 28 strokedBg copies shows
 * the opposite — 15 files used 1.4dp (BalanceSheet, BillPreview,
 * CategoriesUnits, DueReminders, History, InventoryInsights, Items, Login,
 * PartyReports, Reports, StockAdjustment, StockMovement, StockReport,
 * UserManagement, Zakat) vs. 13 at 1.2dp. 1.4dp is the true majority, so the
 * shared version below now uses that value; the 13 minority screens will get
 * a ~0.2dp thicker border after migrating, which is intentionally the
 * majority look rather than a regression — worth a quick visual sanity check
 * but not a bug.
 *
 * `radius` defaults to 12 on strokedBg / 14 on roundedBg to match the one
 * screen (BillScan) that always called these with a fixed corner radius
 * baked in instead of passing one — everywhere else still passes radius
 * explicitly, so the default only ever applies there.
 */

fun Context.strokedBg(strokeHex: String, fillHex: String, radius: Int = 12): GradientDrawable =
    GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.4 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }

/**
 * [strokeHex] is optional — PartyDashboardActivity was the one screen that
 * needed an outlined oval (e.g. a white circle with a subtle border) rather
 * than a flat-fill one; every other caller omits it and gets the plain fill.
 */
fun ovalBg(colorHex: String, strokeHex: String? = null): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(Color.parseColor(colorHex))
    if (strokeHex != null) setStroke(2, Color.parseColor(strokeHex))
}

fun roundedBg(colorHex: String, radius: Int = 14): GradientDrawable = GradientDrawable().apply {
    setColor(Color.parseColor(colorHex))
    cornerRadius = radius.toFloat()
}

fun Context.gradientBg(startHex: String, endHex: String, cornerTop: Int = 0, cornerBottom: Int = 0): GradientDrawable =
    GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
    ).apply {
        val density = resources.displayMetrics.density
        cornerRadii = floatArrayOf(
            cornerTop * density, cornerTop * density,
            cornerTop * density, cornerTop * density,
            cornerBottom * density, cornerBottom * density,
            cornerBottom * density, cornerBottom * density
        )
    }

fun applyElevation(view: View, dp: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        view.elevation = dp * view.resources.displayMetrics.density
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }
}

fun Context.spacer(heightDp: Int): View = View(this).apply {
    val px = (heightDp * resources.displayMetrics.density).toInt()
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
}

fun Context.spacerH(widthDp: Int): View = View(this).apply {
    val px = (widthDp * resources.displayMetrics.density).toInt()
    layoutParams = LinearLayout.LayoutParams(px, LinearLayout.LayoutParams.MATCH_PARENT)
}

fun lighten(hex: String, factor: Float): Int {
    val base = Color.parseColor(hex)
    val r = (Color.red(base) + (255 - Color.red(base)) * factor).toInt()
    val g = (Color.green(base) + (255 - Color.green(base)) * factor).toInt()
    val bl = (Color.blue(base) + (255 - Color.blue(base)) * factor).toInt()
    return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), bl.coerceIn(0, 255))
}
