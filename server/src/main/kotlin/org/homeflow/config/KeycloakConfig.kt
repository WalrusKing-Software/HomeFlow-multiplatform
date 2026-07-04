package org.homeflow.config

/**
 * Keycloak coordinates for JWT validation and Admin API calls, derived from env by
 * [fromEnv]. Two base URLs are kept deliberately distinct (see
 * `__docs/ARCHITECTURE-server.md` / `KEYCLOAK.md`):
 *
 * - [internalUrl] (`http://keycloak:8080`) — container-to-container, used for JWKS
 *   fetching, the service-account token, and Admin API calls. Stays on the Docker network.
 * - [publicUrl] (`https://<APP_HOSTNAME>`) — the hostname Keycloak stamps into the
 *   token `iss`, so [issuer] must be built from it or every JWT 401s on `iss`.
 *
 * The backend only ever *validates* tokens — it never runs a browser/OIDC flow.
 */
data class KeycloakConfig(
    val internalUrl: String,
    val publicUrl: String,
    val realm: String,
    val clientId: String,
    val clientSecret: String,
) {
    /** Token `iss` claim — stamped by Keycloak from its public hostname. */
    val issuer: String get() = "$publicUrl/realms/$realm"

    /** Required `aud` member — the backend's own client id. */
    val audience: String get() = clientId

    /** JWKS endpoint (internal) the server caches public keys from to verify signatures. */
    val jwksUrl: String get() = "$internalUrl/realms/$realm/protocol/openid-connect/certs"

    /** Token endpoint (internal) for the backend's client-credentials service-account token. */
    val tokenUrl: String get() = "$internalUrl/realms/$realm/protocol/openid-connect/token"

    /** Admin API endpoint (internal) for deleting a Keycloak user by its `sub`. */
    fun adminUserUrl(keycloakSub: String): String = "$internalUrl/admin/realms/$realm/users/$keycloakSub"

    companion object {
        fun fromEnv(): KeycloakConfig =
            KeycloakConfig(
                internalUrl = requireEnv("KEYCLOAK_INTERNAL_URL").trimEnd('/'),
                publicUrl = requireEnv("PUBLIC_KEYCLOAK_URL").trimEnd('/'),
                realm = requireEnv("PUBLIC_KEYCLOAK_REALM"),
                clientId = requireEnv("KEYCLOAK_CLIENT_ID"),
                clientSecret = requireEnv("KEYCLOAK_CLIENT_SECRET"),
            )
    }
}

/** Shared fail-fast env reader for the config layer — throws (exiting the process) on a missing/blank var. */
internal fun requireEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Required environment variable '$name' is missing or blank")
