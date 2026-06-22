@file:OptIn(ExperimentalTime::class)

package org.homeflow.app.shared.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The tokens the client holds after an OIDC exchange/refresh. The **access token is
 * held in memory only**; the **refresh token is the only value persisted**, and only
 * in platform secure storage (CLAUDE.md, `ARCHITECTURE-client.md`).
 */
data class OidcTokens(
    val accessToken: String,
    val refreshToken: String?,
    val accessTokenExpiresAt: Instant?,
)

/** Keycloak token endpoint response (`application/json`). Snake-case on the wire. */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_expires_in") val refreshExpiresIn: Long? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("scope") val scope: String? = null,
) {
    fun toTokens(now: Instant = Clock.System.now()): OidcTokens =
        OidcTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresAt = expiresIn?.let { now.plus(it.seconds) },
        )
}
