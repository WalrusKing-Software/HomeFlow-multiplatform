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
        /** The placeholder secret shipped in `infra/keycloak/realm-export.json` for dev imports. */
        internal const val DEV_BACKEND_SECRET = "dev-only-backend-secret-change-me"

        fun fromEnv(): KeycloakConfig =
            KeycloakConfig(
                internalUrl = requireEnv("KEYCLOAK_INTERNAL_URL").trimEnd('/'),
                publicUrl = requireEnv("PUBLIC_KEYCLOAK_URL").trimEnd('/'),
                realm = requireEnv("PUBLIC_KEYCLOAK_REALM"),
                clientId = requireEnv("KEYCLOAK_CLIENT_ID"),
                clientSecret =
                    validateClientSecret(
                        requireEnv("KEYCLOAK_CLIENT_SECRET"),
                        allowDevSecrets = System.getenv("ALLOW_DEV_SECRETS") == "true",
                    ),
            )

        /**
         * SEC-05: refuse to start on the dev-only secret from the realm export unless
         * `ALLOW_DEV_SECRETS=true` (set only by `docker-compose.dev.yml`) — production
         * must never run on a secret that is committed to the repository.
         */
        internal fun validateClientSecret(
            clientSecret: String,
            allowDevSecrets: Boolean,
        ): String {
            require(allowDevSecrets || clientSecret != DEV_BACKEND_SECRET) {
                "KEYCLOAK_CLIENT_SECRET is the dev-only default from realm-export.json. " +
                    "Regenerate it in Keycloak (Clients → homeflow-backend → Credentials) " +
                    "and update .env, or set ALLOW_DEV_SECRETS=true (dev compose only)."
            }
            return clientSecret
        }
    }
}

/** Shared fail-fast env reader for the config layer — throws (exiting the process) on a missing/blank var. */
internal fun requireEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Required environment variable '$name' is missing or blank")
