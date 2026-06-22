package org.homeflow.app.shared.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import org.homeflow.app.shared.config.AuthConfig

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
            ).body<TokenResponse>()
            .toTokens()

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
            ).body<TokenResponse>()
            .toTokens()

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
