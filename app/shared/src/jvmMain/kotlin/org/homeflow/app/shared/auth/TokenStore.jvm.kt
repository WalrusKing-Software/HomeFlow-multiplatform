package org.homeflow.app.shared.auth

import com.github.javakeyring.Keyring

/**
 * OS-keychain refresh-token store via java-keyring (Windows Credential Manager, macOS
 * Keychain, or Linux secret-service). The access token is never persisted.
 */
class DesktopTokenStore(
    private val keyring: Keyring = Keyring.create(),
) : TokenStore {
    override fun saveRefreshToken(token: String) {
        keyring.setPassword(SERVICE, ACCOUNT_REFRESH, token)
    }

    override fun loadRefreshToken(): String? =
        try {
            keyring.getPassword(SERVICE, ACCOUNT_REFRESH)
        } catch (e: Exception) {
            // SEC-12: never fail silently — a broken keychain looks like a logout otherwise.
            authDebugLog("TokenStore: keyring read failed: ${e.describe()}")
            null
        }

    override fun clear() {
        try {
            keyring.deletePassword(SERVICE, ACCOUNT_REFRESH)
        } catch (e: Exception) {
            authDebugLog("TokenStore: keyring delete failed: ${e.describe()}")
        }
    }

    internal companion object {
        const val SERVICE = "org.homeflow.desktop"
        const val ACCOUNT_REFRESH = "refresh_token"
    }
}

actual fun createTokenStore(): TokenStore = DesktopTokenStore()
