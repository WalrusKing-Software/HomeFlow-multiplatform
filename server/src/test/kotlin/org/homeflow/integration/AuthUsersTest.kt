package org.homeflow.integration

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.homeflow.AppDependencies
import org.homeflow.core.ApiError
import org.homeflow.core.ErrorCode
import org.homeflow.core.dto.UserDto
import org.homeflow.db.Users
import org.homeflow.integration.IntegrationHarness.AUDIENCE
import org.homeflow.integration.IntegrationHarness.ENCRYPTION_KEY_BYTES
import org.homeflow.integration.IntegrationHarness.ISSUER
import org.homeflow.integration.IntegrationHarness.TOKEN_TTL_MILLIS
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
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.testcontainers.containers.PostgreSQLContainer
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3 done-when verification (Ktor `testApplication` over the real auth +
 * routing stack, against a Testcontainers Postgres). Tokens are minted in-process
 * and signed with an RSA key whose public half is served by a local [JwkProvider],
 * so JWT validation runs for real without a live Keycloak. The Keycloak Admin API
 * is faked by [RecordingAdminClient].
 *
 * Covers: no/expired/tampered/wrong-audience JWT → 401 (ApiError shape); a valid
 * JWT reaches the handler and `users/me` returns the record; first login upserts
 * with no duplicates; account deletion removes the row and calls Keycloak.
 *
 * Requires a running Docker daemon (see __docs/TESTING.md).
 */
class AuthUsersTest {
    @Before
    fun resetState() {
        transaction(db) { Users.deleteAll() }
        adminClient.deleted.clear()
    }

    @Test
    fun `health check is public and returns ok`() =
        withApp { client ->
            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
        }

    @Test
    fun `request without a token is rejected 401 in the ApiError shape`() =
        withApp { client ->
            val response = client.get("/api/v1/users/me")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ApiError>().error.code)
        }

    @Test
    fun `expired token is rejected 401`() =
        withApp { client ->
            val token = makeToken(SUB, expiresAt = Date(System.currentTimeMillis() - EXPIRED_OFFSET_MILLIS))
            val response = client.get("/api/v1/users/me") { bearer(token) }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `token for the wrong audience is rejected 401`() =
        withApp { client ->
            val token = makeToken(SUB, audience = "some-other-client")
            val response = client.get("/api/v1/users/me") { bearer(token) }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `tampered token is rejected 401`() =
        withApp { client ->
            val response = client.get("/api/v1/users/me") { bearer(tamper(makeToken(SUB))) }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `valid token reaches the handler and users me returns the upserted record`() =
        withApp { client ->
            val response = client.get("/api/v1/users/me") { bearer(makeToken(SUB)) }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = response.body<UserDto>()
            assertTrue(body.id.isNotBlank(), "expected a non-blank internal user id")
            assertTrue(body.createdAt.isNotBlank(), "expected a createdAt timestamp")
            assertEquals(1, userCount(SUB), "first login should create exactly one user row")
        }

    @Test
    fun `repeated logins upsert the same user without duplicates`() =
        withApp { client ->
            repeat(3) {
                val response = client.get("/api/v1/users/me") { bearer(makeToken(SUB)) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
            assertEquals(1, userCount(SUB), "repeated logins must not create duplicate user rows")
        }

    @Test
    fun `account deletion removes the user row and deletes the Keycloak account`() =
        withApp { client ->
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/users/me") { bearer(makeToken(SUB)) }.status)
            assertEquals(1, userCount(SUB))

            val response = client.delete("/api/v1/users/me") { bearer(makeToken(SUB)) }
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(0, userCount(SUB), "the user row must be gone after deletion")
            assertContains(adminClient.deleted, SUB, "Keycloak deletion must be called with the user's sub")
        }

    private fun withApp(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { module(deps) }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            block(client)
        }

    /** Records the subs it was asked to delete instead of calling a live Keycloak. */
    private class RecordingAdminClient : KeycloakAdminClient {
        val deleted = mutableListOf<String>()

        override suspend fun deleteUser(keycloakSub: String) {
            deleted.add(keycloakSub)
        }
    }

    companion object {
        private const val SUB = "11111111-1111-1111-1111-111111111111"
        private const val EXPIRED_OFFSET_MILLIS = 120_000L

        private val postgres: PostgreSQLContainer<*> = newPostgres()

        private lateinit var db: Database
        private lateinit var publicKey: RSAPublicKey
        private lateinit var privateKey: RSAPrivateKey
        private lateinit var deps: AppDependencies
        private lateinit var adminClient: RecordingAdminClient

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres.start()
            db = migrateAndConnect(postgres)

            val keyPair = generateRsaKeyPair()
            publicKey = keyPair.public as RSAPublicKey
            privateKey = keyPair.private as RSAPrivateKey

            adminClient = RecordingAdminClient()
            deps =
                AppDependencies(
                    // AuthUsersTest uses a zero-byte encryption key (intentional: tests the
                    // config path, not the encryption value) — override the harness default.
                    config =
                        testConfig().copy(
                            encryptionKey = Base64.getEncoder().encodeToString(ByteArray(ENCRYPTION_KEY_BYTES)),
                        ),
                    database = db,
                    jwkProvider = localJwkProvider(publicKey),
                    keycloakAdminClient = adminClient,
                )
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            postgres.stop()
        }

        private fun makeToken(
            subject: String,
            audience: String = AUDIENCE,
            issuer: String = ISSUER,
            expiresAt: Date = Date(System.currentTimeMillis() + TOKEN_TTL_MILLIS),
        ): String = makeToken(privateKey, publicKey, subject, audience, issuer, expiresAt)

        /**
         * Corrupts the signature segment so the token parses but fails verification.
         * Flips the *first* signature character, whose six bits are all significant.
         * (The last base64url char of a 256-byte signature carries padding bits, so
         * flipping it often decodes to identical signature bytes — leaving the token
         * validly signed and the test flaky.)
         */
        private fun tamper(token: String): String {
            val parts = token.split(".")
            val sig = parts[2]
            val flipped = (if (sig.first() == 'A') 'B' else 'A') + sig.drop(1)
            return "${parts[0]}.${parts[1]}.$flipped"
        }

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
