package org.homeflow.integration

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.homeflow.AppDependencies
import org.homeflow.config.Config
import org.homeflow.config.DatabaseConfig
import org.homeflow.config.KeycloakConfig
import org.homeflow.config.RateLimitConfig
import org.homeflow.core.ApiError
import org.homeflow.core.ErrorCode
import org.homeflow.core.dto.UserDto
import org.homeflow.db.Users
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
import org.testcontainers.utility.DockerImageName
import java.math.BigInteger
import java.security.KeyPairGenerator
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

    private fun HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    /** Records the subs it was asked to delete instead of calling a live Keycloak. */
    private class RecordingAdminClient : KeycloakAdminClient {
        val deleted = mutableListOf<String>()

        override suspend fun deleteUser(keycloakSub: String) {
            deleted.add(keycloakSub)
        }
    }

    companion object {
        private const val KID = "test-key"
        private const val SUB = "11111111-1111-1111-1111-111111111111"
        private const val ISSUER = "https://test.homeflow.local/realms/homeflow"
        private const val AUDIENCE = "homeflow-backend"
        private const val RSA_KEY_SIZE = 2048
        private const val ENCRYPTION_KEY_BYTES = 32
        private const val TOKEN_TTL_MILLIS = 3_600_000L
        private const val EXPIRED_OFFSET_MILLIS = 120_000L
        private const val HIGH_RATE_LIMIT = 100_000

        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("period_tracker_test")

        private lateinit var db: Database
        private lateinit var publicKey: RSAPublicKey
        private lateinit var privateKey: RSAPrivateKey
        private lateinit var deps: AppDependencies
        private lateinit var adminClient: RecordingAdminClient

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres.start()
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            db =
                Database.connect(
                    url = postgres.jdbcUrl,
                    driver = "org.postgresql.Driver",
                    user = postgres.username,
                    password = postgres.password,
                )

            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_SIZE) }.generateKeyPair()
            publicKey = keyPair.public as RSAPublicKey
            privateKey = keyPair.private as RSAPrivateKey

            adminClient = RecordingAdminClient()
            deps =
                AppDependencies(
                    config = testConfig(),
                    database = db,
                    jwkProvider = localJwkProvider(),
                    keycloakAdminClient = adminClient,
                )
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            postgres.stop()
        }

        private fun testConfig(): Config =
            Config(
                apiPort = 0,
                logLevel = "info",
                // Unused — the test injects the Database and admin client directly.
                database = DatabaseConfig("unused", 0, "unused", "unused", "unused"),
                keycloak =
                    KeycloakConfig(
                        internalUrl = "http://unused",
                        publicUrl = "https://test.homeflow.local",
                        realm = "homeflow",
                        clientId = AUDIENCE,
                        clientSecret = "unused",
                    ),
                rateLimit = RateLimitConfig(maxRequests = HIGH_RATE_LIMIT, windowMillis = TOKEN_TTL_MILLIS),
                encryptionKey = Base64.getEncoder().encodeToString(ByteArray(ENCRYPTION_KEY_BYTES)),
            )

        private fun localJwkProvider(): JwkProvider =
            JwkProvider { keyId ->
                Jwk.fromValues(
                    mapOf(
                        "kid" to keyId,
                        "kty" to "RSA",
                        "alg" to "RS256",
                        "use" to "sig",
                        "n" to base64Url(unsigned(publicKey.modulus)),
                        "e" to base64Url(unsigned(publicKey.publicExponent)),
                    ),
                )
            }

        private fun makeToken(
            subject: String,
            audience: String = AUDIENCE,
            issuer: String = ISSUER,
            expiresAt: Date = Date(System.currentTimeMillis() + TOKEN_TTL_MILLIS),
        ): String =
            JWT
                .create()
                .withKeyId(KID)
                .withIssuer(issuer)
                .withAudience(audience)
                .withSubject(subject)
                .withIssuedAt(Date())
                .withExpiresAt(expiresAt)
                .sign(Algorithm.RSA256(publicKey, privateKey))

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

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** Big-endian magnitude without a sign byte, as JWK n/e expect. */
        private fun unsigned(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
    }
}
