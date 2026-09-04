package com.grocerypos.v11.ui.theme l

/**
 * Single source of truth for the "premium" palette that ReportsActivity,
 * StockReportActivity, BalanceSheetActivity, PartyReportsActivity,
 * HistoryActivity, and others each re-declared as identical private vals
 * (item #24 — architecture duplication).
 *
 * Usage in an Activity: `import com.grocerypos.v11.ui.theme.AppColors`
 * then reference AppColors.primary, AppColors.border, etc. — values are
 * unchanged from what every screen already used, so this is a pure
 * find-and-replace with zero visual change.
 */
object AppColors {
    const val bg = "#F3F2FA"
    const val cardBg = "#FFFFFF"
    const val primary = "#4A3AFF"
    const val primaryDark = "#3527D6"
    const val amber = "#F5A524"
    const val teal = "#0F9B8E"
    const val red = "#E5484D"
    const val textDark = "#1A1A2E"
    const val textGray = "#8A8A9E"
    const val border = "#E7E5F3"
}
