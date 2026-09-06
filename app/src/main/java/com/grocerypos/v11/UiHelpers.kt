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
 * NOTE ON DIVERGENCE: while auditing the duplicates, HistoryActivity.kt's
 * copy of strokedBg used a 1.4dp stroke width where every other file used
 * 1.2dp. That's almost certainly an accidental drift from copy-pasting
 * rather than an intentional design choice, so this shared version keeps
 * 1.2dp (the majority/original value) — worth a quick visual check on
 * History's cards after migrating that file, since the border will get
 * very slightly thinner.
 */

fun Context.strokedBg(strokeHex: String, fillHex: String, radius: Int): GradientDrawable =
    GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }

fun ovalBg(colorHex: String): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(Color.parseColor(colorHex))
}

fun roundedBg(colorHex: String, radius: Int): GradientDrawable = GradientDrawable().apply {
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
