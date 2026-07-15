// Filename is fixed by __docs/ARCHITECTURE-server.md (config/Database.kt); it
// intentionally groups DatabaseConfig with the connection helpers.
@file:Suppress("MatchingDeclarationName")

package org.homeflow.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

/**
 * Connection settings for the application database. Read from the environment by
 * [DatabaseConfig.fromEnv]; in Phase 3 this folds into the central, fail-fast
 * `config/Config.kt`. The runtime connects as the **restricted app role**
 * (`POSTGRES_APP_USER`), which has DML only — DDL (Flyway migrations) runs
 * separately as the superuser. See `__docs/ARCHITECTURE-server.md` / `DOCKER.md`.
 */
data class DatabaseConfig(
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE,
    val sslMode: String = DEFAULT_SSL_MODE,
) {
    init {
        require(sslMode in ALLOWED_SSL_MODES) {
            "POSTGRES_SSLMODE must be one of $ALLOWED_SSL_MODES, got '$sslMode'"
        }
    }

    // The DB host comes from POSTGRES_HOST config, not an HTTP request header, so the
    // generic nginx request-host rule does not apply (suppressed inline below).
    val jdbcUrl: String get() = "jdbc:postgresql://$host:$port/$database?sslmode=$sslMode" // nosemgrep

    companion object {
        private const val DEFAULT_PORT = 5432
        private const val DEFAULT_MAX_POOL_SIZE = 10

        /**
         * SEC-04: `disable` is correct for the single-host Docker network (postgres has no
         * published port and TLS on it would be self-signed churn); set `verify-full` via
         * `POSTGRES_SSLMODE` if the database ever moves to a remote host.
         */
        private const val DEFAULT_SSL_MODE = "disable"
        private val ALLOWED_SSL_MODES = setOf("disable", "require", "verify-ca", "verify-full")

        /** Builds config from the `POSTGRES_*` env vars, failing fast if any required one is missing. */
        fun fromEnv(): DatabaseConfig =
            DatabaseConfig(
                host = requireEnv("POSTGRES_HOST"),
                port = System.getenv("POSTGRES_PORT")?.toIntOrNull() ?: DEFAULT_PORT,
                database = requireEnv("POSTGRES_DB"),
                user = requireEnv("POSTGRES_USER"),
                password = requireEnv("POSTGRES_PASSWORD"),
                sslMode = System.getenv("POSTGRES_SSLMODE")?.takeIf { it.isNotBlank() } ?: DEFAULT_SSL_MODE,
            )

        private fun requireEnv(name: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: error("Required environment variable '$name' is missing or blank")
    }
}

/**
 * Builds the single HikariCP-backed [DataSource]. Kept separate from
 * [connectDatabase] so tests can point Exposed at a Testcontainers-provided
 * `DataSource` without going through env vars.
 */
fun buildDataSource(config: DatabaseConfig): DataSource {
    val hikari =
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            poolName = "homeflow-pool"
        }
    return HikariDataSource(hikari)
}

/** Creates the single Exposed [Database] for the app over a Hikari pool. */
fun connectDatabase(config: DatabaseConfig): Database = Database.connect(buildDataSource(config))
