package org.homeflow.app.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.url
import io.ktor.serialization.kotlinx.json.json
import org.homeflow.app.shared.auth.OidcTokens
import org.homeflow.app.shared.auth.appJson
import org.homeflow.app.shared.config.AuthConfig

/**
 * Per-platform Ktor engine **instance**: OkHttp on Android, CIO on desktop. Returned
 * as a configured instance (not a factory) so the desktop actual can attach an extra
 * dev CA to the TLS trust when reaching a Caddy-internal-CA host (see `DesktopTls`).
 */
expect fun platformHttpEngine(): HttpClientEngine

/**
 * The single configured API client. Attaches `Authorization: Bearer <access token>`
 * from the in-memory [TokenHolder] and refreshes once reactively on a `401` via
 * [onRefresh]. **No `Logging` plugin at BODY level** — tokens and health payloads
 * must never reach a log sink (CLAUDE.md). [onRefresh] returns the new tokens (already
 * persisted by the caller) or null when refresh fails. Tests pass a `MockEngine`.
 */
fun buildHttpClient(
    config: AuthConfig,
    tokenHolder: TokenHolder,
    onRefresh: suspend (refreshToken: String) -> OidcTokens?,
    engine: HttpClientEngine = platformHttpEngine(),
): HttpClient = HttpClient(engine) { configure(config, tokenHolder, onRefresh) }

private fun HttpClientConfig<*>.configure(
    config: AuthConfig,
    tokenHolder: TokenHolder,
    onRefresh: suspend (refreshToken: String) -> OidcTokens?,
) {
    expectSuccess = false
    install(ContentNegotiation) { json(appJson) }
    install(HttpRequestRetry) {
        maxRetries = 1
        retryOnExceptionIf { _, cause -> cause.isTransient() }
    }
    install(Auth) {
        bearer {
            loadTokens {
                tokenHolder.accessToken?.let { BearerTokens(it, tokenHolder.refreshToken ?: "") }
            }
            refreshTokens {
                val rt = tokenHolder.refreshToken ?: oldTokens?.refreshToken
                if (rt.isNullOrBlank()) {
                    null
                } else {
                    onRefresh(rt)?.let { new ->
                        BearerTokens(new.accessToken, new.refreshToken ?: rt)
                    }
                }
            }
            // Single-host API behind TLS — attach the bearer proactively rather
            // than waiting for a 401 challenge round-trip.
            sendWithoutRequest { true }
        }
    }
    install(DefaultRequest) { url(config.apiBaseUrl) }
}

private fun Throwable.isTransient(): Boolean =
    this is io.ktor.client.network.sockets.ConnectTimeoutException ||
        this is io.ktor.client.network.sockets.SocketTimeoutException
