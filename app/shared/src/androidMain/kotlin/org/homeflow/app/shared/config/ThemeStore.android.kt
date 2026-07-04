package org.homeflow.app.shared.config

import android.content.Context
import org.homeflow.app.shared.platform.AndroidAppContext

/**
 * Android [ThemeStore]: plain (non-encrypted) SharedPreferences. The theme choice is not
 * sensitive and must be readable before the app-lock gate.
 */
class AndroidThemeStore(
    context: Context,
) : ThemeStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ThemePreference {
        val raw = prefs.getString(KEY, null) ?: return ThemePreference.SYSTEM
        return runCatching { ThemePreference.valueOf(raw) }.getOrDefault(ThemePreference.SYSTEM)
    }

    override fun save(preference: ThemePreference) {
        prefs.edit().putString(KEY, preference.name).apply()
    }

    private companion object {
        const val PREFS_NAME = "homeflow_theme"
        const val KEY = "theme"
    }
}

actual fun createThemeStore(): ThemeStore = AndroidThemeStore(AndroidAppContext.application)
