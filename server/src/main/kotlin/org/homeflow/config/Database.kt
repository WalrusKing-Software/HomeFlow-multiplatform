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
) {
    // `$host` here is the configured DB host (POSTGRES_HOST), not an attacker-controlled
    // HTTP Host header. The generic nginx request-host rule is a false positive here.
    // nosemgrep: generic.nginx.security.request-host-used
    val jdbcUrl: String get() = "jdbc:postgresql://$host:$port/$database"

    companion object {
        private const val DEFAULT_PORT = 5432
        private const val DEFAULT_MAX_POOL_SIZE = 10

        /** Builds config from the `POSTGRES_*` env vars, failing fast if any required one is missing. */
        fun fromEnv(): DatabaseConfig =
            DatabaseConfig(
                host = requireEnv("POSTGRES_HOST"),
                port = System.getenv("POSTGRES_PORT")?.toIntOrNull() ?: DEFAULT_PORT,
                database = requireEnv("POSTGRES_DB"),
                user = requireEnv("POSTGRES_USER"),
                password = requireEnv("POSTGRES_PASSWORD"),
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
