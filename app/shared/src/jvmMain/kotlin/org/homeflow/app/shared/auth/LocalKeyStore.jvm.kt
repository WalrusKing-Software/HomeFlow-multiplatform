package org.homeflow.app.shared.auth

import com.github.javakeyring.Keyring
import java.util.Base64

/**
 * Desktop implementation of [LocalKeyStore]: stores the DEK (Base64-encoded) in the
 * OS keychain via java-keyring (Windows Credential Manager, macOS Keychain, Linux secret-service).
 */
class DesktopLocalKeyStore(
    private val keyring: Keyring = Keyring.create(),
) : LocalKeyStore {

    override fun loadDek(): ByteArray? {
        val encoded = runCatching { keyring.getPassword(SERVICE, ACCOUNT_DEK) }.getOrNull()
            ?: return null
        return Base64.getDecoder().decode(encoded)
    }

    override fun saveDek(dek: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(dek)
        keyring.setPassword(SERVICE, ACCOUNT_DEK, encoded)
    }

    override fun clearDek() {
        runCatching { keyring.deletePassword(SERVICE, ACCOUNT_DEK) }
    }

    private companion object {
        const val SERVICE = "org.homeflow.desktop"
        const val ACCOUNT_DEK = "local_dek"
    }
}

actual fun createLocalKeyStore(): LocalKeyStore = DesktopLocalKeyStore()
