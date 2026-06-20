// This file intentionally holds the app entry point (main), the Ktor module wiring
// (Application.module), and the manually-wired AppDependencies graph together — the
// single declaration name therefore won't match the filename.
@file:Suppress("MatchingDeclarationName")

package org.homeflow

import com.auth0.jwk.JwkProvider
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.homeflow.config.Config
import org.homeflow.config.connectDatabase
import org.homeflow.lib.HttpKeycloakAdminClient
import org.homeflow.lib.KeycloakAdminClient
import org.homeflow.modules.users.UsersRepository
import org.homeflow.modules.users.UsersService
import org.homeflow.plugins.buildJwkProvider
import org.homeflow.plugins.configureAuthentication
import org.homeflow.plugins.configureRateLimiting
import org.homeflow.plugins.configureRouting
import org.homeflow.plugins.configureSerialization
import org.homeflow.plugins.configureStatusPages
import org.jetbrains.exposed.sql.Database

/**
 * Manually-wired dependency graph for the server. Constructed once from the
 * environment ([fromEnv]) for `main`; tests build it directly with a Testcontainers
 * [Database], a local [JwkProvider], and a fake [KeycloakAdminClient] (no live
 * Postgres/Keycloak needed beyond the DB container). No DI framework — explicit
 * wiring keeps the layering obvious.
 */
class AppDependencies(
    val config: Config,
    database: Database,
    val jwkProvider: JwkProvider,
    keycloakAdminClient: KeycloakAdminClient,
) {
    private val usersRepository = UsersRepository(database)
    val usersService = UsersService(usersRepository, keycloakAdminClient)

    companion object {
        fun fromEnv(): AppDependencies {
            val config = Config.fromEnv()
            return AppDependencies(
                config = config,
                database = connectDatabase(config.database),
                jwkProvider = buildJwkProvider(config.keycloak),
                keycloakAdminClient = HttpKeycloakAdminClient(config.keycloak),
            )
        }
    }
}

fun main() {
    val deps = AppDependencies.fromEnv()
    embeddedServer(Netty, port = deps.config.apiPort, host = "0.0.0.0") {
        module(deps)
    }.start(wait = true)
}

/**
 * Installs every plugin and mounts the routes. Kept dependency-injected (no
 * `System.getenv` here) so `testApplication { application { module(testDeps) } }`
 * exercises the real auth + routing stack.
 */
fun Application.module(deps: AppDependencies) {
    configureSerialization()
    configureStatusPages()
    configureRateLimiting(deps.config.rateLimit)
    configureAuthentication(deps.config.keycloak, deps.jwkProvider, deps.usersService)
    configureRouting(deps.usersService)
}
