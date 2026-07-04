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

    override fun loadRefreshToken(): String? = runCatching { keyring.getPassword(SERVICE, ACCOUNT_REFRESH) }.getOrNull()

    override fun clear() {
        runCatching { keyring.deletePassword(SERVICE, ACCOUNT_REFRESH) }
    }

    internal companion object {
        const val SERVICE = "org.homeflow.desktop"
        const val ACCOUNT_REFRESH = "refresh_token"
    }
}

actual fun createTokenStore(): TokenStore = DesktopTokenStore()
