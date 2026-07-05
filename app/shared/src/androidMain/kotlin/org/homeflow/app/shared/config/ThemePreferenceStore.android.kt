package org.homeflow.app.shared.config

import android.content.Context
import org.homeflow.app.shared.platform.AndroidAppContext

/**
 * Android [ThemePreferenceStore]: plain (non-encrypted) SharedPreferences.
 * The appearance preference is not sensitive — it only drives which color scheme to apply.
 */
class AndroidThemePreferenceStore(
    context: Context,
) : ThemePreferenceStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ThemeMode {
        val raw = prefs.getString(KEY, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
    }

    override fun save(mode: ThemeMode) {
        prefs.edit().putString(KEY, mode.name).apply()
    }

    private companion object {
        const val PREFS_NAME = "homeflow_theme"
        const val KEY = "theme_mode"
    }
}

actual fun createThemePreferenceStore(): ThemePreferenceStore =
    AndroidThemePreferenceStore(AndroidAppContext.application)
