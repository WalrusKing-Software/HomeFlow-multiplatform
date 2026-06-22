@file:OptIn(ExperimentalTime::class)

package org.homeflow.app.shared.data

import org.homeflow.app.shared.auth.OidcTokens
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The **in-memory** access-token holder shared between [org.homeflow.app.shared.auth.AuthController]
 * and the Ktor `Auth`/`bearer` provider. The access token lives only here — never in
 * secure storage, files, or logs. The refresh token is mirrored here for the bearer
 * provider's convenience but its durable home is the [org.homeflow.app.shared.auth.TokenStore].
 */
class TokenHolder {
    var accessToken: String? = null
        private set
    var refreshToken: String? = null
        private set
    var accessTokenExpiresAt: Instant? = null
        private set

    fun set(tokens: OidcTokens) {
        accessToken = tokens.accessToken
        refreshToken = tokens.refreshToken ?: refreshToken
        accessTokenExpiresAt = tokens.accessTokenExpiresAt
    }

    fun clear() {
        accessToken = null
        refreshToken = null
        accessTokenExpiresAt = null
    }
}
