package org.homeflow.app.shared.config

import android.content.Context
import org.homeflow.app.shared.platform.AndroidAppContext

/**
 * Android [ServerConfigStore]: plain SharedPreferences (`homeflow_server`).
 * Not encrypted — the host and migrated flag are not sensitive.
 */
class AndroidServerConfigStore(
    context: Context,
) : ServerConfigStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadHost(): String? = prefs.getString(KEY_HOST, null)

    override fun saveHost(host: String) {
        prefs.edit().putString(KEY_HOST, host).apply()
    }

    override fun isMigrated(): Boolean = prefs.getBoolean(KEY_MIGRATED, false)

    override fun setMigrated() {
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    override fun clear() {
        prefs
            .edit()
            .remove(KEY_HOST)
            .remove(KEY_MIGRATED)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "homeflow_server"
        const val KEY_HOST = "server_host"
        const val KEY_MIGRATED = "migrated"
    }
}

actual fun createServerConfigStore(): ServerConfigStore = AndroidServerConfigStore(AndroidAppContext.application)
