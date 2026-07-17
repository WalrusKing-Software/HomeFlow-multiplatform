package org.homeflow.integration

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.homeflow.AppDependencies
import org.homeflow.config.RateLimitConfig
import org.homeflow.integration.IntegrationHarness.generateRsaKeyPair
import org.homeflow.integration.IntegrationHarness.localJwkProvider
import org.homeflow.integration.IntegrationHarness.migrateAndConnect
import org.homeflow.integration.IntegrationHarness.newPostgres
import org.homeflow.integration.IntegrationHarness.testConfig
import org.homeflow.module
import org.jetbrains.exposed.sql.Database
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.containers.PostgreSQLContainer
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that the Ktor rate-limit plugin rejects a 4th request in the same window
 * with `429 Too Many Requests` when the bucket is sized to 3. The public `/health`
 * route is used so no authentication is needed. All requests in a `testApplication`
 * share one client address, so they hit the same per-address bucket.
 *
 * Requires a running Docker daemon (see __docs/TESTING.md).
 */
class RateLimitE2eTest {
    @Test
    fun `4th request in a window of 3 is rejected with 429`() =
        testApplication {
            application { module(deps) }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

            // The first three should succeed.
            repeat(3) { assertEquals(HttpStatusCode.OK, client.get("/health").status) }

            // The 4th must be rejected.
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/health").status)
        }

    companion object {
        private val postgres: PostgreSQLContainer<*> = newPostgres()
        private lateinit var deps: AppDependencies

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres.start()
            val db: Database = migrateAndConnect(postgres)

            val keyPair = generateRsaKeyPair()
            val publicKey = keyPair.public as RSAPublicKey

            deps =
                AppDependencies(
                    config = testConfig(rateLimit = RateLimitConfig(maxRequests = 3, windowMillis = 60_000)),
                    database = db,
                    jwkProvider = localJwkProvider(publicKey),
                    keycloakAdminClient = NoopAdminClient(),
                )
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            postgres.stop()
        }
    }
}
