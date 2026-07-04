package org.homeflow.app.shared.auth

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.homeflow.app.shared.platform.AndroidAppContext

/**
 * Android implementation of [LocalKeyStore]: stores the DEK (Base64-encoded) in
 * Keystore-backed [EncryptedSharedPreferences]. The underlying AES-256-GCM master key
 * is hardware-backed where the device supports it.
 */
class AndroidLocalKeyStore(
    context: Context,
) : LocalKeyStore {
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

    override fun loadDek(): ByteArray? {
        val encoded = prefs.getString(KEY_DEK, null) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    override fun saveDek(dek: ByteArray) {
        val encoded = Base64.encodeToString(dek, Base64.NO_WRAP)
        prefs.edit().putString(KEY_DEK, encoded).apply()
    }

    override fun clearDek() {
        prefs.edit().remove(KEY_DEK).apply()
    }

    private companion object {
        const val PREFS_NAME = "homeflow_local_key_store"
        const val KEY_DEK = "local_dek"
    }
}

actual fun createLocalKeyStore(): LocalKeyStore = AndroidLocalKeyStore(AndroidAppContext.application)
