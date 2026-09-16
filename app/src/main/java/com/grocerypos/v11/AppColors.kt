package com.grocerypos.v11.ui.theme

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
    const val bg = "#F4F5F9"
    const val cardBg = "#FFFFFF"
    const val primary = "#0D1B4C"
    const val primaryDark = "#081334"
    const val amber = "#FF8A00"
    const val teal = "#0F9B8E"
    const val red = "#E5484D"
    const val textDark = "#14162B"
    const val textGray = "#7C8798"
    const val border = "#E7E9F2"
}
