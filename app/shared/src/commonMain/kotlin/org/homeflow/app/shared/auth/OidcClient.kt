package org.homeflow.app.shared.auth

import org.homeflow.app.shared.config.AuthConfig

/**
 * One-time interactive login plus token refresh/revocation. The browser interaction
 * is platform-specific (Android: AppAuth + Chrome Custom Tab, custom-scheme redirect;
 * desktop: system browser + loopback listener), so this is created per platform via
 * [createOidcClient]. See `ARCHITECTURE-client.md` "Auth flow".
 */
interface OidcClient {
    /** Runs Authorization Code + PKCE(S256) in the browser and returns tokens. */
    suspend fun login(): OidcTokens

    /** Exchanges the offline refresh token for a fresh access token. */
    suspend fun refresh(refreshToken: String): OidcTokens

    /** Best-effort revocation at the realm end-session endpoint. */
    suspend fun logout(refreshToken: String)
}

expect fun createOidcClient(config: AuthConfig): OidcClient
