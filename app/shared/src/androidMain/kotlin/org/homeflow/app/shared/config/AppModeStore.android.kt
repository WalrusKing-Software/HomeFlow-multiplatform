package org.homeflow.app.shared.config

import android.content.Context
import org.homeflow.app.shared.platform.AndroidAppContext

/**
 * Android [AppModeStore]: plain (non-encrypted) SharedPreferences.
 * [AppMode] is not sensitive — it just drives which session controller to construct.
 */
class AndroidAppModeStore(
    context: Context,
) : AppModeStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): AppMode? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { AppMode.valueOf(raw) }.getOrNull()
    }

    override fun save(mode: AppMode) {
        prefs.edit().putString(KEY, mode.name).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS_NAME = "homeflow_app_mode"
        const val KEY = "app_mode"
    }
}

actual fun createAppModeStore(): AppModeStore = AndroidAppModeStore(AndroidAppContext.application)
