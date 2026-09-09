package com.grocerypos.v11.ui.components

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.grocerypos.v11.util.ThemeManager

/**
 * Shared header bar (back chevron + icon badge + title/subtitle on a flat
 * bordered surface) — was copy-pasted near-identically into HistoryActivity,
 * BalanceSheetActivity, PartyReportsActivity, ReportsActivity, and
 * StockReportActivity (item #24 — architecture duplication).
 *
<<<<<<< HEAD
 * FLAT REDESIGN: this used to render a primary→primaryDark gradient banner.
 * Converted to the flat white-card/soft-pastel-badge look used by MainActivity
 * and the screens that already got their own private flat header (Reports,
 * DueReminders, InventoryInsights) — this shared version is what every OTHER
 * caller (BalanceSheet, History, Items, PartyReports, StockAdjustment,
 * StockMovement, StockReport, Zakat) still falls back to, so fixing it here
 * flattens all of those screens' headers in one place instead of eight.
 *
 * [primaryHex] is now used as the badge's icon/text tint on a light badge
 * background, instead of as one end of a gradient; [primaryDarkHex] is kept
 * as a parameter for source compatibility with existing call sites but is no
 * longer used for painting (no gradient to blend into). [onBack] is optional —
 * StockMovementActivity needed the back chevron to pop out of a drill-down
 * (product detail) rather than always finishing the Activity, so it passes a
 * custom lambda; every other caller omits it and keeps the plain finish()
 * behaviour.
=======
 * Back-button behaviour defaults to finish(). [primaryHex]/[primaryDarkHex]
 * default to the shared AppColors palette but can be overridden if a screen
 * ever needs a different gradient. [onBack] is optional — StockMovementActivity
 * needed the back chevron to pop out of a drill-down (product detail) rather
 * than always finishing the Activity, so it passes a custom lambda; every
 * other caller omits it and keeps the plain finish() behaviour.
>>>>>>> cc8b3ed1c3be113f6b2a67aab0b9727c246553fa
 */
fun AppCompatActivity.premiumHeader(
    icon: String,
    title: String,
    subtitle: String,
<<<<<<< HEAD
    primaryHex: String = ThemeManager.palette(this).flatPurpleFg,
    primaryDarkHex: String = primaryHex,
=======
    primaryHex: String = AppColors.primary,
    primaryDarkHex: String = AppColors.primaryDark,
>>>>>>> cc8b3ed1c3be113f6b2a67aab0b9727c246553fa
    onBack: (() -> Unit)? = null
): LinearLayout {
    val p = ThemeManager.palette(this)
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(20, 18, 20, 18)
        background = strokedBg(p.border, p.cardWhite, 22)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 20) }
    }
    header.addView(TextView(this).apply {
        text = "\u2039"
        textSize = 20f
        setTextColor(Color.parseColor(p.textDark))
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = ovalBg(p.border)
        val px = (36 * resources.displayMetrics.density).toInt()
        layoutParams = android.view.ViewGroup.LayoutParams(px, px)
        setOnClickListener { onBack?.invoke() ?: finish() }
    })
    header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(14, 1) })
    header.addView(circleIcon(icon, primaryHex, 42))
    header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
    val headerCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    headerCol.addView(TextView(this).apply {
        text = title
        textSize = 19f
        setTextColor(Color.parseColor(p.textDark))
        setTypeface(typeface, Typeface.BOLD)
    })
    headerCol.addView(TextView(this).apply {
        text = subtitle
        textSize = 11f
        setTextColor(Color.parseColor(p.textMuted))
        setPadding(0, 4, 0, 0)
    })
    header.addView(headerCol)
    return header
}

/** Flat badge: light tinted circle (fixed soft lavender, matching the rest of the
 *  app's badge tint) with the icon/emoji drawn in [colorHex] on top of it — was a
 *  solid [colorHex] circle with white text/icon before the flat redesign. */
fun AppCompatActivity.circleIcon(label: String, colorHex: String, sizeDp: Int): TextView =
    TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor(colorHex))
        background = ovalBg(ThemeManager.palette(this@circleIcon).flatPurpleBg)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        layoutParams = android.view.ViewGroup.LayoutParams(px, px)
    }

// ---- Vector-icon variants (item-6 UI improvement pass): same header/badge, but takes a
// drawable resource instead of an emoji string. Added as an overload rather than replacing
// the String version above, since premiumHeader()/circleIcon() are shared across five screens
// (History, BalanceSheet, PartyReports, Reports, StockReport) and only some have been migrated
// off emoji so far — the String overload stays until every caller has moved over. ----
fun AppCompatActivity.premiumHeader(
    iconRes: Int,
    title: String,
    subtitle: String,
<<<<<<< HEAD
    primaryHex: String = ThemeManager.palette(this).flatPurpleFg,
    primaryDarkHex: String = primaryHex,
    onBack: (() -> Unit)? = null
): LinearLayout {
    val p = ThemeManager.palette(this)
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(20, 18, 20, 18)
        background = strokedBg(p.border, p.cardWhite, 22)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 20) }
=======
    primaryHex: String = AppColors.primary,
    primaryDarkHex: String = AppColors.primaryDark,
    onBack: (() -> Unit)? = null
): LinearLayout {
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(26, 22, 26, 22)
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(primaryHex), Color.parseColor(primaryDarkHex))
        ).apply { cornerRadius = 22f }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 20) }
        applyElevation(this, 10f)
>>>>>>> cc8b3ed1c3be113f6b2a67aab0b9727c246553fa
    }
    header.addView(TextView(this).apply {
        text = "\u2039"
        textSize = 20f
<<<<<<< HEAD
        setTextColor(Color.parseColor(p.textDark))
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = ovalBg(p.border)
=======
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = ovalBg("#33FFFFFF")
>>>>>>> cc8b3ed1c3be113f6b2a67aab0b9727c246553fa
        val px = (36 * resources.displayMetrics.density).toInt()
        layoutParams = android.view.ViewGroup.LayoutParams(px, px)
        setOnClickListener { onBack?.invoke() ?: finish() }
    })
    header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(14, 1) })
<<<<<<< HEAD
    header.addView(circleIconDrawable(iconRes, primaryHex, 42))
=======
    header.addView(circleIconDrawable(iconRes, "#5C4DFF", 42))
>>>>>>> cc8b3ed1c3be113f6b2a67aab0b9727c246553fa
    header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
    val headerCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    headerCol.addView(TextView(this).apply {
        text = title
        textSize = 19f
<<<<<<< HEAD
        setTextColor(Color.parseColor(p.textDark))
=======
        setTextColor(Color.WHITE)
>>>>>>> cc8b3ed1c3be113f6b2a67aab0b9727c246553fa
        setTypeface(typeface, Typeface.BOLD)
    })
    headerCol.addView(TextView(this).apply {
        text = subtitle
        textSize = 11f
<<<<<<< HEAD
        setTextColor(Color.parseColor(p.textMuted))
=======
        setTextColor(Color.parseColor("#D8D3FF"))
>>>>>>> cc8b3ed1c3be113f6b2a67aab0b9727c246553fa
        setPadding(0, 4, 0, 0)
    })
    header.addView(headerCol)
    return header
}

<<<<<<< HEAD
/** Flat badge: light tinted circle (fixed soft lavender) with the vector icon
 *  tinted [colorHex] on top — was a solid [colorHex] circle with a white icon
 *  before the flat redesign. */
fun AppCompatActivity.circleIconDrawable(iconRes: Int, colorHex: String, sizeDp: Int): android.widget.ImageView =
    android.widget.ImageView(this).apply {
        val d = androidx.core.content.ContextCompat.getDrawable(this@circleIconDrawable, iconRes)?.mutate()
        d?.setTint(Color.parseColor(colorHex))
        setImageDrawable(d)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        background = ovalBg(ThemeManager.palette(this@circleIconDrawable).flatPurpleBg)
=======
fun AppCompatActivity.circleIconDrawable(iconRes: Int, colorHex: String, sizeDp: Int): android.widget.ImageView =
    android.widget.ImageView(this).apply {
        val d = androidx.core.content.ContextCompat.getDrawable(this@circleIconDrawable, iconRes)?.mutate()
        d?.setTint(Color.WHITE)
        setImageDrawable(d)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        background = ovalBg(colorHex)
>>>>>>> cc8b3ed1c3be113f6b2a67aab0b9727c246553fa
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        layoutParams = android.view.ViewGroup.LayoutParams(px, px)
    }
