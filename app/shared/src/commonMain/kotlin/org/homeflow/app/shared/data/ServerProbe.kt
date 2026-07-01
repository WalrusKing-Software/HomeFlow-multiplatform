package org.homeflow.app.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.url
import io.ktor.serialization.kotlinx.json.json
import org.homeflow.app.shared.auth.appJson
import org.homeflow.app.shared.config.authConfigForHost
import org.homeflow.core.dto.VersionDto
import org.homeflow.core.validation.semverAtLeast

/**
 * The outcome of a pre-login reachability check against a user-entered server host. Advisory
 * only — the connect flow may proceed even on [Unreachable] (LAN/Tailscale hosts can block the
 * probe while still serving the real OIDC/API traffic), matching the "reachability validation
 * deliberately absent" note on [org.homeflow.app.shared.ui.ServerConnectScreen].
 */
sealed interface ProbeResult {
    /** The host answered `/version` (or predates it). [serverVersion] is `"unknown"` for pre-`/version` servers. */
    data class Reachable(
        val serverVersion: String,
    ) : ProbeResult

    /** The host answered, but requires a newer client than this build ([minClient]). */
    data class Incompatible(
        val minClient: String,
    ) : ProbeResult

    /** The host could not be reached (DNS/TLS/connect failure). [reason] is a short diagnostic. */
    data class Unreachable(
        val reason: String,
    ) : ProbeResult
}

/**
 * Probe [host] before committing to it, using the **unauthenticated** `GET /api/v1/version`
 * endpoint (the same one [org.homeflow.app.shared.auth.AuthController.loadUser] leniency-checks).
 * Builds a short-lived, tokenless client via [platformHttpEngine] so the desktop dev-CA TLS trust
 * is honored, and always closes it.
 *
 * Mapping mirrors the login-time compatibility check:
 * - `Success` → [ProbeResult.Reachable] when this client satisfies `minClientVersion`, else
 *   [ProbeResult.Incompatible].
 * - `404` → [ProbeResult.Reachable] (server predates `/version`; do not block).
 * - transport failure (`httpStatus == 0`) → [ProbeResult.Unreachable].
 * - any other HTTP failure → [ProbeResult.Reachable] (the server responded; auth validates later).
 *
 * Never logs the response body (health/config posture, CLAUDE.md).
 *
 * [engine] defaults to the per-platform engine; tests inject a `MockEngine`.
 */
suspend fun probeServer(
    host: String,
    clientVersion: String,
    engine: HttpClientEngine = platformHttpEngine(),
): ProbeResult {
    val client =
        HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(appJson) }
            install(DefaultRequest) { url(authConfigForHost(host).apiBaseUrl) }
        }
    return try {
        when (val result = client.apiGet<VersionDto>("version")) {
            is ApiResult.Success -> {
                val ver = result.value
                if (semverAtLeast(clientVersion, ver.minClientVersion)) {
                    ProbeResult.Reachable(ver.serverVersion)
                } else {
                    ProbeResult.Incompatible(ver.minClientVersion)
                }
            }
            is ApiResult.Failure ->
                when (result.httpStatus) {
                    // Server predates /version — treat as reachable (login is lenient too).
                    404 -> ProbeResult.Reachable("unknown")
                    // apiGet maps transport exceptions to httpStatus 0.
                    0 -> ProbeResult.Unreachable(result.message)
                    // Server answered with some other error — reachable; auth will validate.
                    else -> ProbeResult.Reachable("unknown")
                }
        }
    } finally {
        client.close()
    }
}
