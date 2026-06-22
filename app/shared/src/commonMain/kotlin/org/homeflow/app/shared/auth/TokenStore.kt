package org.homeflow.app.shared.auth

/**
 * Persists **only the offline refresh token**, and only in platform secure storage
 * (Android: Keystore-backed `EncryptedSharedPreferences`; desktop: OS keychain via
 * java-keyring). The access token is never persisted. Never logged. See CLAUDE.md.
 */
interface TokenStore {
    fun saveRefreshToken(token: String)

    fun loadRefreshToken(): String?

    fun clear()
}

expect fun createTokenStore(): TokenStore
