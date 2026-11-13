package org.chronoscompanion.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * App theme chosen on the main screen: follow the phone, always light or always dark.
 * The dark theme uses a pure black background, which on OLED screens keeps those
 * pixels off.
 */
object ThemePrefs {
    private const val PREFS = "navi_theme"
    private const val KEY = "mode"

    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    fun get(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, SYSTEM)

    fun set(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply()
        apply(mode)
    }

    fun apply(ctx: Context) = apply(get(ctx))

    private fun apply(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
