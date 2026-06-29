package org.homeflow.app.shared.config

/**
 * The single place the apps key off for hostnames, realm, and OIDC scopes
 * (see `ARCHITECTURE-client.md` "Configuration & hostnames").
 *
 * [host] is the canonical Caddy/Tailscale hostname the client reaches the stack at.
 * It drives both [apiBaseUrl] and [issuer] — they must line up with what Keycloak
 * stamps in the token `iss`, or every backend call 401s (see KEYCLOAK.md gotchas).
 */
data class AuthConfig(
    val host: String,
    val realm: String = "homeflow",
    val scopes: List<String> = listOf("openid", "offline_access"),
    val scheme: String = "https",
) {
    val issuer: String = "$scheme://$host/realms/$realm" // nosemgrep
    val authorizationEndpoint: String = "$issuer/protocol/openid-connect/auth"
    val tokenEndpoint: String = "$issuer/protocol/openid-connect/token"
    val endSessionEndpoint: String = "$issuer/protocol/openid-connect/logout"

    /** Trailing slash so relative paths (`users/me`) resolve under `/api/v1/`. */
    val apiBaseUrl: String = "$scheme://$host/api/v1/" // nosemgrep

    val scopeString: String get() = scopes.joinToString(" ")
}

/**
 * Per-platform OIDC identity. The `clientId` is the public Keycloak client for the
 * platform; `redirectUri` is its registered redirect (custom scheme on Android, a
 * loopback base on desktop — the desktop actual appends the ephemeral port); and
 * `defaultHost` is the dev host the platform reaches (the Android emulator sees the
 * dev box at `10.0.2.2`, not `localhost`). All overridable for a real deployment.
 */
data class PlatformOidc(
    val clientId: String,
    val redirectUri: String,
    val defaultHost: String,
)

expect val platformOidc: PlatformOidc

/** Convenience: the default config for the running platform. */
fun defaultAuthConfig(): AuthConfig = AuthConfig(host = platformOidc.defaultHost)

/** Build a config for a user-entered [host] (all other fields use defaults). */
fun authConfigForHost(host: String): AuthConfig = AuthConfig(host = host)
