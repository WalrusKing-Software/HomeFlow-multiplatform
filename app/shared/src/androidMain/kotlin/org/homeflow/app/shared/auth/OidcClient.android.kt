@file:OptIn(ExperimentalTime::class)

package org.homeflow.app.shared.auth

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import org.homeflow.app.shared.config.AuthConfig
import org.homeflow.app.shared.config.platformOidc
import org.homeflow.app.shared.platform.AndroidAppContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import net.openid.appauth.TokenResponse as AppAuthTokenResponse

/**
 * Android OIDC via AppAuth + Chrome Custom Tab. AppAuth generates and verifies the
 * PKCE(S256) challenge itself and owns the pending intent that captures the
 * custom-scheme redirect; we bridge its authorization intent through the host
 * Activity's result launcher ([AndroidAppContext.authLauncher]).
 */
class AndroidOidcClient(
    private val config: AuthConfig,
) : OidcClient {
    private val clientId = platformOidc.clientId
    private val redirectUri = platformOidc.redirectUri

    private val serviceConfig =
        AuthorizationServiceConfiguration(
            Uri.parse(config.authorizationEndpoint),
            Uri.parse(config.tokenEndpoint),
            null,
            Uri.parse(config.endSessionEndpoint),
        )

    override suspend fun login(): OidcTokens {
        val launch = AndroidAppContext.authLauncher ?: error("No Activity available for login")
        val service = AuthorizationService(AndroidAppContext.application)
        try {
            val request =
                AuthorizationRequest
                    .Builder(serviceConfig, clientId, ResponseTypeValues.CODE, Uri.parse(redirectUri))
                    .setScope(config.scopeString)
                    .build()
            val resultData =
                launch(service.getAuthorizationRequestIntent(request))
                    ?: error("Login canceled")
            val response =
                AuthorizationResponse.fromIntent(resultData)
                    ?: throw (AuthorizationException.fromIntent(resultData) ?: error("No authorization response"))
            return service.performTokenRequestSuspending(response.createTokenExchangeRequest())
        } finally {
            service.dispose()
        }
    }

    override suspend fun refresh(refreshToken: String): OidcTokens {
        val service = AuthorizationService(AndroidAppContext.application)
        try {
            val request =
                TokenRequest
                    .Builder(serviceConfig, clientId)
                    .setGrantType(GrantTypeValues.REFRESH_TOKEN)
                    .setRefreshToken(refreshToken)
                    .build()
            return service.performTokenRequestSuspending(request)
        } finally {
            service.dispose()
        }
    }

    override suspend fun logout(refreshToken: String) {
        // AppAuth has no revoke helper; POST the refresh token to the end-session endpoint.
        withContext(Dispatchers.IO) {
            runCatching {
                val body =
                    "client_id=${enc(clientId)}&refresh_token=${enc(refreshToken)}"
                (URL(config.endSessionEndpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    OutputStreamWriter(outputStream).use { it.write(body) }
                    responseCode // force the request
                    disconnect()
                }
            }
        }
    }

    private suspend fun AuthorizationService.performTokenRequestSuspending(request: TokenRequest): OidcTokens =
        suspendCancellableCoroutine { cont ->
            performTokenRequest(request) { response: AppAuthTokenResponse?, ex: AuthorizationException? ->
                when {
                    response?.accessToken != null -> cont.resume(response.toOidcTokens())
                    ex != null -> cont.resumeWithException(ex)
                    else -> cont.resumeWithException(IllegalStateException("Empty token response"))
                }
            }
        }

    private fun AppAuthTokenResponse.toOidcTokens(): OidcTokens =
        OidcTokens(
            accessToken = accessToken ?: error("No access token"),
            refreshToken = refreshToken,
            accessTokenExpiresAt = accessTokenExpirationTime?.let { Instant.fromEpochMilliseconds(it) },
        )

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

actual fun createOidcClient(config: AuthConfig): OidcClient = AndroidOidcClient(config)
