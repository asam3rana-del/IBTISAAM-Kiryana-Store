package com.grocerypos.v11.util

import android.content.Context

/**
 * One color palette for the whole app — light or dark.
 * Every activity should pull its hex colors from here instead of hardcoding them,
 * so adding/adjusting a theme only ever needs to happen in ONE place.
 */
data class AppPalette(
    val bg: String,
    val cardWhite: String,
    val navy: String,
    val teal: String,
    val red: String,
    val textDark: String,
    val textMuted: String,
    val border: String,
    val amber: String,
    val fieldFill: String,
    val headerSubtitleColor: String,
    val headerBadgeOverlay: String,
    val savedHighlightBg: String
)

/**
 * App-wide theme state. This is the ONLY place dark_mode is read from / written to
 * SharedPreferences — every activity should go through this object rather than rolling
 * its own prefs logic, so a toggle in one screen is guaranteed to be seen everywhere else.
 */
object ThemeManager {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_DARK_MODE = "dark_mode"

    val LIGHT = AppPalette(
        bg = "#F4F6F8",
        cardWhite = "#FFFFFF",
        navy = "#0B2545",
        teal = "#0F9B8E",
        red = "#E5484D",
        textDark = "#0B2545",
        textMuted = "#7C8798",
        border = "#E3E8EE",
        amber = "#F5A524",
        fieldFill = "#FAFBFC",
        headerSubtitleColor = "#9FB4CC",
        headerBadgeOverlay = "#33FFFFFF",
        savedHighlightBg = "#E9FBF9"
    )

    val DARK = AppPalette(
        bg = "#10151F",
        cardWhite = "#1B2334",
        navy = "#0B2545",
        teal = "#14B8A6",
        red = "#F0666B",
        textDark = "#EAEFF7",
        textMuted = "#8B95A8",
        border = "#2A3346",
        amber = "#F5A524",
        fieldFill = "#161D2C",
        headerSubtitleColor = "#9FB4CC",
        headerBadgeOverlay = "#33FFFFFF",
        savedHighlightBg = "#12332F"
    )

    fun isDarkMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, false)

    fun setDarkMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_MODE, enabled)
            .apply()
    }

    /** Flips the current setting and returns the new value. */
    fun toggleDarkMode(context: Context): Boolean {
        val newValue = !isDarkMode(context)
        setDarkMode(context, newValue)
        return newValue
    }

    fun palette(context: Context): AppPalette =
        if (isDarkMode(context)) DARK else LIGHT
}
