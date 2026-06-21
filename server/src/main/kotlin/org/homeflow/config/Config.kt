package org.homeflow.config

/**
 * The single, fail-fast application configuration. Every environment variable the
 * server needs is read and validated **once** here at startup ([fromEnv]); if any
 * required one is missing or malformed the process exits before serving traffic.
 * Nothing else in the app reads `System.getenv` directly — see
 * `__docs/ARCHITECTURE-server.md` (Config — fail fast).
 *
 * [encryptionKey] is the base64 `APP_ENCRYPTION_KEY` for application-layer column
 * encryption; it is validated (must decode to 32 bytes) when `lib/Encryption.kt` is
 * constructed from it, so a malformed key still fails fast at startup.
 */
data class Config(
    val apiPort: Int,
    val logLevel: String,
    val database: DatabaseConfig,
    val keycloak: KeycloakConfig,
    val rateLimit: RateLimitConfig,
    val encryptionKey: String,
) {
    companion object {
        private const val DEFAULT_API_PORT = 8080
        private const val DEFAULT_LOG_LEVEL = "info"

        fun fromEnv(): Config =
            Config(
                apiPort = System.getenv("API_PORT")?.toIntOrNull() ?: DEFAULT_API_PORT,
                logLevel = System.getenv("LOG_LEVEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_LOG_LEVEL,
                database = DatabaseConfig.fromEnv(),
                keycloak = KeycloakConfig.fromEnv(),
                rateLimit = RateLimitConfig.fromEnv(),
                encryptionKey = requireEnv("APP_ENCRYPTION_KEY"),
            )
    }
}

/**
 * Optional per-client request rate limiting (defaults mirror `.env.example`). A
 * coarse safety net against brute-force/abuse of the API surface; Keycloak handles
 * login brute-force separately.
 */
data class RateLimitConfig(
    val maxRequests: Int,
    val windowMillis: Long,
) {
    companion object {
        private const val DEFAULT_MAX_REQUESTS = 100
        private const val DEFAULT_WINDOW_MILLIS = 60_000L

        fun fromEnv(): RateLimitConfig =
            RateLimitConfig(
                maxRequests = System.getenv("RATE_LIMIT_MAX")?.toIntOrNull() ?: DEFAULT_MAX_REQUESTS,
                windowMillis = System.getenv("RATE_LIMIT_TIME_WINDOW_MS")?.toLongOrNull() ?: DEFAULT_WINDOW_MILLIS,
            )
    }
}
