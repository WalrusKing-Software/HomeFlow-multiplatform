package org.homeflow.app.shared.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.homeflow.app.shared.platform.AndroidAppContext

/**
 * Keystore-backed [EncryptedSharedPreferences] — the refresh token is encrypted at rest
 * with a hardware-backed AES-256-GCM master key. The access token is never stored here.
 */
class AndroidTokenStore(
    context: Context,
) : TokenStore {
    private val prefs by lazy {
        val masterKey =
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun saveRefreshToken(token: String) {
        prefs.edit().putString(KEY_REFRESH, token).apply()
    }

    override fun loadRefreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    override fun clear() {
        prefs.edit().remove(KEY_REFRESH).apply()
    }

    private companion object {
        const val PREFS_NAME = "homeflow_secure_prefs"
        const val KEY_REFRESH = "refresh_token"
    }
}

actual fun createTokenStore(): TokenStore = AndroidTokenStore(AndroidAppContext.application)
