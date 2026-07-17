package org.homeflow.integration

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.homeflow.AppDependencies
import org.homeflow.core.ApiError
import org.homeflow.core.ErrorCode
import org.homeflow.db.Users
import org.homeflow.integration.IntegrationHarness.bearer
import org.homeflow.integration.IntegrationHarness.generateRsaKeyPair
import org.homeflow.integration.IntegrationHarness.localJwkProvider
import org.homeflow.integration.IntegrationHarness.makeToken
import org.homeflow.integration.IntegrationHarness.migrateAndConnect
import org.homeflow.integration.IntegrationHarness.newPostgres
import org.homeflow.integration.IntegrationHarness.testConfig
import org.homeflow.lib.KeycloakAdminClient
import org.homeflow.module
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.testcontainers.containers.PostgreSQLContainer
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the DB-first ordering of account deletion when Keycloak is unavailable.
 *
 * The `UsersService.deleteAccount` contract (see `__docs/API.md` and the service
 * KDoc): delete the DB row first (cascading all health data), then call Keycloak.
 * If Keycloak deletion throws, the service rethrows — the status-pages plugin maps
 * any unhandled [Throwable] to `500 INTERNAL_ERROR`. The DB row must already be gone
 * at that point (the DB delete is not rolled back).
 *
 * Requires a running Docker daemon (see __docs/TESTING.md).
 */
class AccountDeletionFailureTest {
    @Before
    fun resetState() {
        transaction(db) { Users.deleteAll() }
    }

    @Test
    fun `keycloak failure after db delete surfaces 500 and the db row stays deleted`() =
        withApp { client ->
            // Upsert the user row via the live endpoint.
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/users/me") { bearer(makeToken(SUB)) }.status,
            )
            assertEquals(1, userCount(SUB), "precondition: user row must exist before deletion")

            // DELETE /api/v1/users/me — the FailingAdminClient throws after the DB delete.
            val response = client.delete("/api/v1/users/me") { bearer(makeToken(SUB)) }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(ErrorCode.INTERNAL_ERROR, response.body<ApiError>().error.code)

            // The DB row must be gone even though Keycloak deletion failed: DB-first ordering held.
            assertEquals(
                0,
                userCount(SUB),
                "the users row must be deleted even when Keycloak deletion throws",
            )
        }

    private fun withApp(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { module(deps) }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            block(client)
        }

    /**
     * Simulates a Keycloak Admin API outage: always throws, so the service's
     * `runCatching { ... }.onFailure { rethrow }` path is exercised.
     */
    private class FailingAdminClient : KeycloakAdminClient {
        override suspend fun deleteUser(keycloakSub: String): Unit = error("keycloak unavailable")
    }

    companion object {
        private const val SUB = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

        private val postgres: PostgreSQLContainer<*> = newPostgres()

        private lateinit var db: Database
        private lateinit var publicKey: RSAPublicKey
        private lateinit var privateKey: RSAPrivateKey
        private lateinit var deps: AppDependencies

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres.start()
            db = migrateAndConnect(postgres)

            val keyPair = generateRsaKeyPair()
            publicKey = keyPair.public as RSAPublicKey
            privateKey = keyPair.private as RSAPrivateKey

            deps =
                AppDependencies(
                    config = testConfig(),
                    database = db,
                    jwkProvider = localJwkProvider(publicKey),
                    keycloakAdminClient = FailingAdminClient(),
                )
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            postgres.stop()
        }

        private fun makeToken(subject: String): String = makeToken(privateKey, publicKey, subject)

        private fun userCount(sub: String): Int =
            transaction(db) {
                Users
                    .selectAll()
                    .where { Users.keycloakSub eq sub }
                    .count()
                    .toInt()
            }
    }
}
