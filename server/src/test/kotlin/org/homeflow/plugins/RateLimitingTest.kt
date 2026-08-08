package org.homeflow.plugins

import com.auth0.jwk.JwkProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.homeflow.AppDependencies
import org.homeflow.config.Config
import org.homeflow.config.DatabaseConfig
import org.homeflow.config.KeycloakConfig
import org.homeflow.config.RateLimitConfig
import org.homeflow.lib.KeycloakAdminClient
import org.homeflow.module
import org.jetbrains.exposed.sql.Database
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the rate limiter buckets **per client address** (SEC-01): the
 * `XForwardedHeaders` plugin resolves `X-Forwarded-For` into the request origin,
 * so exhausting one client's bucket must not affect another client — and requests
 * without the header fall back to the socket address.
 *
 * Uses the public `/health` route only, so no database or JWKS is ever touched
 * (Exposed connects lazily; the JwkProvider throws if consulted).
 */
class RateLimitingTest {
    @Test
    fun `exhausting one client's bucket does not rate-limit another client`() =
        withApp { client ->
            repeat(MAX_REQUESTS) {
                val response = client.get("/health") { header(X_FORWARDED_FOR, CLIENT_A) }
                assertEquals(HttpStatusCode.OK, response.status, "request ${it + 1} within the limit must pass")
            }
            val limited = client.get("/health") { header(X_FORWARDED_FOR, CLIENT_A) }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status, "client A must be rate-limited")

            val otherClient = client.get("/health") { header(X_FORWARDED_FOR, CLIENT_B) }
            assertEquals(HttpStatusCode.OK, otherClient.status, "client B must have its own bucket")
        }

    @Test
    fun `requests without a forwarded header fall back to the socket address`() =
        withApp { client ->
            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
        }

    private fun withApp(block: suspend (io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application { module(deps()) }
            block(client)
        }

    private fun deps(): AppDependencies =
        AppDependencies(
            config = testConfig(),
            // Never used: /health performs no DB access and Exposed connects lazily.
            database =
                Database.connect(
                    url = "jdbc:postgresql://unused:5432/unused",
                    driver = "org.postgresql.Driver",
                    user = "unused",
                    password = "unused",
                ),
            jwkProvider = JwkProvider { error("JWKS must not be consulted on the public health route") },
            keycloakAdminClient = NoopAdminClient,
        )

    private object NoopAdminClient : KeycloakAdminClient {
        override suspend fun deleteUser(keycloakSub: String) = Unit
    }

    companion object {
        private const val X_FORWARDED_FOR = "X-Forwarded-For"
        private const val CLIENT_A = "10.0.0.1"
        private const val CLIENT_B = "10.0.0.2"
        private const val MAX_REQUESTS = 3
        private const val WINDOW_MILLIS = 60_000L
        private const val ENCRYPTION_KEY_BYTES = 32

        private fun testConfig(): Config =
            Config(
                apiPort = 0,
                logLevel = "info",
                database = DatabaseConfig("unused", 0, "unused", "unused", "unused"),
                keycloak =
                    KeycloakConfig(
                        internalUrl = "http://unused",
                        publicUrl = "https://test.homeflow.local",
                        realm = "homeflow",
                        clientId = "homeflow-backend",
                        clientSecret = "unused",
                    ),
                rateLimit = RateLimitConfig(maxRequests = MAX_REQUESTS, windowMillis = WINDOW_MILLIS),
                encryptionKey = Base64.getEncoder().encodeToString(ByteArray(ENCRYPTION_KEY_BYTES)),
                serverVersion = "test",
                minClientVersion = "0.0.0",
            )
    }
}
