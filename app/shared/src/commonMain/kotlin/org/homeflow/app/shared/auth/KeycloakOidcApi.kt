package org.homeflow.app.shared.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.homeflow.app.shared.config.AuthConfig

/** OAuth 2.0 / OIDC error body from the token endpoint (`{"error":...,"error_description":...}`). */
@Serializable
data class OidcErrorResponse(
    @SerialName("error") val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

/**
 * A non-success response from a Keycloak token/end-session call. Carries the HTTP status and
 * the parsed OAuth error so callers can distinguish a rejected grant (re-login needed) from a
 * transient network/server problem (keep the stored session). Without this the client parsed the
 * error JSON as a success [TokenResponse] and surfaced a cryptic "access_token is required" error.
 */
class OidcException(
    val statusCode: Int,
    val oauthError: String?,
    val description: String?,
) : Exception(description ?: oauthError ?: "OIDC request failed (HTTP $statusCode)") {
    /** The stored/presented grant was rejected — the session is gone; force a fresh login. */
    val isGrantRejected: Boolean
        get() =
            statusCode == 400 ||
                statusCode == 401 ||
                oauthError in setOf("invalid_grant", "invalid_token", "unauthorized_client")
}

/**
 * Direct talk to the Keycloak OIDC endpoints (authorization URL construction + the
 * token/end-session POSTs). Used by the **desktop** OIDC actual, which runs the
 * Authorization Code + PKCE(S256) flow itself; the **Android** actual uses AppAuth
 * instead. No tokens or codes are ever logged here.
 */
class KeycloakOidcApi(
    private val config: AuthConfig,
    private val http: HttpClient,
) {
    /** The browser URL that starts Authorization Code + PKCE(S256). */
    fun buildAuthorizationUrl(
        clientId: String,
        redirectUri: String,
        challenge: PkceChallenge,
    ): String =
        URLBuilder(config.authorizationEndpoint)
            .apply {
                parameters.append("client_id", clientId)
                parameters.append("redirect_uri", redirectUri)
                parameters.append("response_type", "code")
                parameters.append("scope", config.scopeString)
                parameters.append("code_challenge", challenge.challenge)
                parameters.append("code_challenge_method", "S256")
                parameters.append("state", challenge.state)
            }.buildString()

    suspend fun exchangeCode(
        clientId: String,
        redirectUri: String,
        code: String,
        verifier: String,
    ): OidcTokens =
        http
            .submitForm(
                url = config.tokenEndpoint,
                formParameters =
                    Parameters.build {
                        append("grant_type", "authorization_code")
                        append("client_id", clientId)
                        append("code", code)
                        append("redirect_uri", redirectUri)
                        append("code_verifier", verifier)
                    },
            ).tokensOrThrow()

    suspend fun refresh(
        clientId: String,
        refreshToken: String,
    ): OidcTokens =
        http
            .submitForm(
                url = config.tokenEndpoint,
                formParameters =
                    Parameters.build {
                        append("grant_type", "refresh_token")
                        append("client_id", clientId)
                        append("refresh_token", refreshToken)
                    },
            ).tokensOrThrow()

    /**
     * Parse a token-endpoint response, throwing [OidcException] on any non-2xx so an OAuth error
     * body is never mis-parsed as a success [TokenResponse]. Requires `expectSuccess = false` on
     * the client (the desktop OIDC client sets this) so this code — not Ktor — owns error handling.
     */
    private suspend fun HttpResponse.tokensOrThrow(): OidcTokens {
        if (!status.isSuccess()) {
            val err = runCatching { body<OidcErrorResponse>() }.getOrNull()
            throw OidcException(status.value, err?.error, err?.errorDescription)
        }
        return body<TokenResponse>().toTokens()
    }

    /** Best-effort refresh-token revocation at the end-session endpoint. */
    suspend fun logout(
        clientId: String,
        refreshToken: String,
    ) {
        http.submitForm(
            url = config.endSessionEndpoint,
            formParameters =
                Parameters.build {
                    append("client_id", clientId)
                    append("refresh_token", refreshToken)
                },
        )
    }
}
