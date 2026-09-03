package com.grocerypos.v11.ui.components

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.grocerypos.v11.ui.theme.AppColors

/**
 * Shared header bar (back chevron + icon badge + title/subtitle on a gradient
 * banner) — was copy-pasted near-identically into HistoryActivity,
 * BalanceSheetActivity, PartyReportsActivity, ReportsActivity, and
 * StockReportActivity (item #24 — architecture duplication).
 *
 * Back-button behaviour is unchanged (calls finish()). [primaryHex]/[primaryDarkHex]
 * default to the shared AppColors palette but can be overridden if a screen
 * ever needs a different gradient.
 */
fun AppCompatActivity.premiumHeader(
    icon: String,
    title: String,
    subtitle: String,
    primaryHex: String = AppColors.primary,
    primaryDarkHex: String = AppColors.primaryDark
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
    }
    header.addView(TextView(this).apply {
        text = "\u2039"
        textSize = 20f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = ovalBg("#33FFFFFF")
        val px = (36 * resources.displayMetrics.density).toInt()
        width = px; height = px
        setOnClickListener { finish() }
    })
    header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(14, 1) })
    header.addView(circleIcon(icon, "#5C4DFF", 42))
    header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
    val headerCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    headerCol.addView(TextView(this).apply {
        text = title
        textSize = 19f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
    })
    headerCol.addView(TextView(this).apply {
        text = subtitle
        textSize = 11f
        setTextColor(Color.parseColor("#D8D3FF"))
        setPadding(0, 4, 0, 0)
    })
    header.addView(headerCol)
    return header
}

fun AppCompatActivity.circleIcon(label: String, colorHex: String, sizeDp: Int): TextView =
    TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        width = px; height = px
    }
