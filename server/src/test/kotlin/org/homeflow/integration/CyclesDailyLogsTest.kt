package org.homeflow.integration

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.homeflow.AppDependencies
import org.homeflow.config.Config
import org.homeflow.config.DatabaseConfig
import org.homeflow.config.KeycloakConfig
import org.homeflow.config.RateLimitConfig
import org.homeflow.core.ApiError
import org.homeflow.core.ErrorCode
import org.homeflow.core.dto.CreateCycleRequest
import org.homeflow.core.dto.CreateDailyLogRequest
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.CyclesResponse
import org.homeflow.core.dto.DailyLogAnchorDto
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.NotesResponse
import org.homeflow.core.dto.NotesUpdateRequest
import org.homeflow.core.dto.UpdateCycleRequest
import org.homeflow.db.Cycles
import org.homeflow.db.DailyLogs
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 done-when verification over the real auth + routing stack against a
 * Testcontainers Postgres (same in-process RS256 / local-JWKS approach as
 * [AuthUsersTest]). Covers cycle create/fetch/close + auto-close, daily-log anchor
 * creation with validation (409 duplicate, 400 out-of-range/bad cycle), notes
 * stored as ciphertext and returned decrypted, the 404 on a missing date, and
 * cross-user isolation (a foreign resource is 404, never revealed).
 *
 * Requires a running Docker daemon (see __docs/TESTING.md).
 */
class CyclesDailyLogsTest {
    @Before
    fun resetState() {
        transaction(db) {
            DailyLogs.deleteAll()
            Cycles.deleteAll()
            Users.deleteAll()
        }
    }

    // ── Cycles ────────────────────────────────────────────────────────────────

    @Test
    fun `create cycle returns 201 with a null end date`() =
        withApp { client ->
            val response = client.createCycle(SUB, "2024-01-15")
            assertEquals(HttpStatusCode.Created, response.status)
            val cycle = response.body<CycleDto>()
            assertEquals("2024-01-15", cycle.startDate)
            assertNull(cycle.endDate)
        }

    @Test
    fun `a future start date is rejected 400`() =
        withApp { client ->
            val response = client.createCycle(SUB, "3000-01-01")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCode.VALIDATION_ERROR, response.body<ApiError>().error.code)
        }

    @Test
    fun `cycles list is newest first`() =
        withApp { client ->
            client.createCycle(SUB, "2024-01-15")
            client.createCycle(SUB, "2024-02-12")
            val cycles = client.get("/api/v1/cycles") { bearer(SUB) }.body<CyclesResponse>().cycles
            assertEquals(listOf("2024-02-12", "2024-01-15"), cycles.map { it.startDate })
        }

    @Test
    fun `starting a new cycle auto-closes the previous open one`() =
        withApp { client ->
            client.createCycle(SUB, "2024-01-15")
            client.createCycle(SUB, "2024-02-12")
            val cycles = client.get("/api/v1/cycles") { bearer(SUB) }.body<CyclesResponse>().cycles

            val newest = cycles.first { it.startDate == "2024-02-12" }
            val previous = cycles.first { it.startDate == "2024-01-15" }
            assertNull(newest.endDate, "the newest cycle stays open")
            assertEquals("2024-02-11", previous.endDate, "the previous cycle closes at startDate - 1 day")
        }

    @Test
    fun `current returns the open cycle and 404 when none is open`() =
        withApp { client ->
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/cycles/current") { bearer(SUB) }.status)
            val created = client.createCycle(SUB, "2024-01-15").body<CycleDto>()
            val current = client.get("/api/v1/cycles/current") { bearer(SUB) }
            assertEquals(HttpStatusCode.OK, current.status)
            assertEquals(created.id, current.body<CycleDto>().id)
        }

    @Test
    fun `get cycle by id returns it, while unknown and malformed ids are 404`() =
        withApp { client ->
            val created = client.createCycle(SUB, "2024-01-15").body<CycleDto>()
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/cycles/${created.id}") { bearer(SUB) }.status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/v1/cycles/$UNKNOWN_UUID") { bearer(SUB) }.status,
            )
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/cycles/not-a-uuid") { bearer(SUB) }.status)
        }

    @Test
    fun `patch closes a cycle, while bad end dates are 400`() =
        withApp { client ->
            val created = client.createCycle(SUB, "2024-01-15").body<CycleDto>()

            val ok = client.patchCycle(SUB, created.id, "2024-01-20")
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals("2024-01-20", ok.body<CycleDto>().endDate)

            assertEquals(HttpStatusCode.BadRequest, client.patchCycle(SUB, created.id, "2024-01-10").status)
            assertEquals(HttpStatusCode.BadRequest, client.patchCycle(SUB, created.id, "3000-01-01").status)
        }

    @Test
    fun `a cycle belonging to another user is not visible`() =
        withApp { client ->
            val created = client.createCycle(SUB, "2024-01-15").body<CycleDto>()
            // Second user (different sub) must not see the first user's cycle.
            val response = client.get("/api/v1/cycles/${created.id}") { bearer(OTHER_SUB) }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, response.body<ApiError>().error.code)
            val otherUserCycles =
                client
                    .get("/api/v1/cycles") { bearer(OTHER_SUB) }
                    .body<CyclesResponse>()
                    .cycles
            assertTrue(otherUserCycles.isEmpty())
        }

    // ── Daily-log anchor ──────────────────────────────────────────────────────

    @Test
    fun `anchor create validates the cycle and date, then 409s on duplicate`() =
        withApp { client ->
            val cycle = client.createCycle(SUB, "2024-01-15").body<CycleDto>()

            val created = client.createAnchor(SUB, "2024-01-20", cycle.id)
            assertEquals(HttpStatusCode.Created, created.status)
            val anchor = created.body<DailyLogAnchorDto>()
            assertEquals("2024-01-20", anchor.logDate)
            assertEquals(cycle.id, anchor.cycleId)

            // Same date again → 409.
            val duplicate = client.createAnchor(SUB, "2024-01-20", cycle.id)
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertEquals(ErrorCode.CONFLICT, duplicate.body<ApiError>().error.code)
        }

    @Test
    fun `anchor create rejects an out-of-range date 400`() =
        withApp { client ->
            // A second cycle closes the first at 2024-02-11, giving it a closed [start, end] range.
            client.createCycle(SUB, "2024-01-15")
            client.createCycle(SUB, "2024-02-12")
            val firstCycle =
                client
                    .get("/api/v1/cycles") { bearer(SUB) }
                    .body<CyclesResponse>()
                    .cycles
                    .first { it.startDate == "2024-01-15" }

            assertEquals(HttpStatusCode.BadRequest, client.createAnchor(SUB, "2024-01-10", firstCycle.id).status)
            assertEquals(HttpStatusCode.BadRequest, client.createAnchor(SUB, "2024-03-01", firstCycle.id).status)
        }

    @Test
    fun `anchor create rejects bad and foreign cycle ids 400`() =
        withApp { client ->
            assertEquals(HttpStatusCode.BadRequest, client.createAnchor(SUB, "2024-01-20", "not-a-uuid").status)
            assertEquals(HttpStatusCode.BadRequest, client.createAnchor(SUB, "2024-01-20", UNKNOWN_UUID).status)

            // A cycle owned by another user must not be usable as an anchor's cycle.
            val foreign = client.createCycle(OTHER_SUB, "2024-01-15").body<CycleDto>()
            val response = client.createAnchor(SUB, "2024-01-20", foreign.id)
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `get daily-log is 404 when missing and assembles the day when present`() =
        withApp { client ->
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/daily-logs/2024-01-20") { bearer(SUB) }.status)

            val cycle = client.createCycle(SUB, "2024-01-15").body<CycleDto>()
            client.createAnchor(SUB, "2024-01-20", cycle.id)

            val response = client.get("/api/v1/daily-logs/2024-01-20") { bearer(SUB) }
            assertEquals(HttpStatusCode.OK, response.status)
            val day = response.body<DailyLogDto>()
            assertEquals("2024-01-20", day.logDate)
            assertEquals(cycle.id, day.cycleId)
            // Sub-logs are not assembled until Phase 5.
            assertNull(day.notes)
            assertNull(day.emotions)
            assertNull(day.pain)
        }

    // ── Notes (encryption) ──────────────────────────────────────────────────────

    @Test
    fun `notes are stored as ciphertext and returned decrypted`() =
        withApp { client ->
            val cycle = client.createCycle(SUB, "2024-01-15").body<CycleDto>()
            client.createAnchor(SUB, "2024-01-20", cycle.id)

            val noteText = "Cramping started in the afternoon."
            val patched = client.patchNotes(SUB, "2024-01-20", noteText)
            assertEquals(HttpStatusCode.OK, patched.status)
            assertEquals(noteText, patched.body<NotesResponse>().notes)

            // The plaintext round-trips on read…
            val readBack = client.get("/api/v1/daily-logs/2024-01-20") { bearer(SUB) }.body<DailyLogDto>()
            assertEquals(noteText, readBack.notes)

            // …but the column itself holds ciphertext, never the plaintext.
            val stored =
                transaction(db) {
                    DailyLogs
                        .selectAll()
                        .where { DailyLogs.logDate eq LocalDate.parse("2024-01-20") }
                        .single()[DailyLogs.notes]
                }
            assertTrue(stored != null && stored.isNotBlank())
            assertFalse(noteText in stored, "notes must be encrypted at rest")
        }

    @Test
    fun `notes can be cleared with null`() =
        withApp { client ->
            val cycle = client.createCycle(SUB, "2024-01-15").body<CycleDto>()
            client.createAnchor(SUB, "2024-01-20", cycle.id)
            client.patchNotes(SUB, "2024-01-20", "to be cleared")

            val cleared = client.patchNotes(SUB, "2024-01-20", null)
            assertEquals(HttpStatusCode.OK, cleared.status)
            assertNull(cleared.body<NotesResponse>().notes)
            assertNull(client.get("/api/v1/daily-logs/2024-01-20") { bearer(SUB) }.body<DailyLogDto>().notes)
        }

    @Test
    fun `patching notes for a missing date is 404`() =
        withApp { client ->
            val response = client.patchNotes(SUB, "2024-01-20", "no anchor yet")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, response.body<ApiError>().error.code)
        }

    // ── Client-supplied id (Phase 11 / D1 groundwork) ────────────────────────────

    @Test
    fun `create cycle honors a client-supplied id`() =
        withApp { client ->
            val response = client.createCycle(SUB, "2024-01-15", id = CLIENT_UUID)
            assertEquals(HttpStatusCode.Created, response.status)
            val cycle = response.body<CycleDto>()
            assertEquals(CLIENT_UUID, cycle.id)
            val stored =
                transaction(db) {
                    Cycles.selectAll().where { Cycles.id eq UUID.fromString(CLIENT_UUID) }.single()
                }
            assertEquals(CLIENT_UUID, stored[Cycles.id].toString())
        }

    @Test
    fun `create cycle rejects a malformed client-supplied id 400`() =
        withApp { client ->
            val response = client.createCycle(SUB, "2024-01-15", id = "not-a-uuid")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCode.VALIDATION_ERROR, response.body<ApiError>().error.code)
        }

    @Test
    fun `create cycle generates an id when none is supplied`() =
        withApp { client ->
            val cycle = client.createCycle(SUB, "2024-01-15").body<CycleDto>()
            assertTrue(runCatching { UUID.fromString(cycle.id) }.isSuccess)
        }

    @Test
    fun `create daily-log anchor honors a client-supplied id`() =
        withApp { client ->
            val cycle = client.createCycle(SUB, "2024-01-15").body<CycleDto>()
            val response = client.createAnchor(SUB, "2024-01-20", cycle.id, id = CLIENT_UUID)
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(CLIENT_UUID, response.body<DailyLogAnchorDto>().id)
        }

    @Test
    fun `create daily-log anchor rejects a malformed client-supplied id 400`() =
        withApp { client ->
            val cycle = client.createCycle(SUB, "2024-01-15").body<CycleDto>()
            val response = client.createAnchor(SUB, "2024-01-20", cycle.id, id = "not-a-uuid")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCode.VALIDATION_ERROR, response.body<ApiError>().error.code)
        }

    @Test
    fun `create daily-log anchor generates an id when none is supplied`() =
        withApp { client ->
            val cycle = client.createCycle(SUB, "2024-01-15").body<CycleDto>()
            val anchor = client.createAnchor(SUB, "2024-01-20", cycle.id).body<DailyLogAnchorDto>()
            assertTrue(runCatching { UUID.fromString(anchor.id) }.isSuccess)
        }

    // ── Harness ─────────────────────────────────────────────────────────────────

    private fun withApp(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { module(deps) }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            block(client)
        }

    private suspend fun HttpClient.createCycle(
        sub: String,
        startDate: String,
        id: String? = null,
    ) = post("/api/v1/cycles") {
        bearer(sub)
        contentType(ContentType.Application.Json)
        setBody(CreateCycleRequest(startDate, id))
    }

    private suspend fun HttpClient.patchCycle(
        sub: String,
        cycleId: String,
        endDate: String,
    ) = patch("/api/v1/cycles/$cycleId") {
        bearer(sub)
        contentType(ContentType.Application.Json)
        setBody(UpdateCycleRequest(endDate))
    }

    private suspend fun HttpClient.createAnchor(
        sub: String,
        date: String,
        cycleId: String,
        id: String? = null,
    ) = post("/api/v1/daily-logs") {
        bearer(sub)
        contentType(ContentType.Application.Json)
        setBody(CreateDailyLogRequest(date, cycleId, id))
    }

    private suspend fun HttpClient.patchNotes(
        sub: String,
        date: String,
        notes: String?,
    ) = patch("/api/v1/daily-logs/$date/notes") {
        bearer(sub)
        contentType(ContentType.Application.Json)
        setBody(NotesUpdateRequest(notes))
    }

    private fun HttpRequestBuilder.bearer(sub: String) {
        header(HttpHeaders.Authorization, "Bearer ${makeToken(sub)}")
    }

    /** A no-op Keycloak admin client — account deletion is exercised in [AuthUsersTest], not here. */
    private class NoopAdminClient : KeycloakAdminClient {
        override suspend fun deleteUser(keycloakSub: String) = Unit
    }

    companion object {
        private const val KID = "test-key"
        private const val SUB = "11111111-1111-1111-1111-111111111111"
        private const val OTHER_SUB = "22222222-2222-2222-2222-222222222222"
        private const val UNKNOWN_UUID = "33333333-3333-3333-3333-333333333333"
        private const val CLIENT_UUID = "44444444-4444-4444-4444-444444444444"
        private const val ISSUER = "https://test.homeflow.local/realms/homeflow"
        private const val AUDIENCE = "homeflow-backend"
        private const val RSA_KEY_SIZE = 2048
        private const val ENCRYPTION_KEY_BYTES = 32
        private const val TOKEN_TTL_MILLIS = 3_600_000L
        private const val HIGH_RATE_LIMIT = 100_000

        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("period_tracker_test")

        private lateinit var db: Database
        private lateinit var publicKey: RSAPublicKey
        private lateinit var privateKey: RSAPrivateKey
        private lateinit var deps: AppDependencies

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

            deps =
                AppDependencies(
                    config = testConfig(),
                    database = db,
                    jwkProvider = localJwkProvider(),
                    keycloakAdminClient = NoopAdminClient(),
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
                encryptionKey = Base64.getEncoder().encodeToString(ByteArray(ENCRYPTION_KEY_BYTES) { 7 }),
                serverVersion = "test",
                minClientVersion = "0.0.0",
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

        private fun makeToken(subject: String): String =
            JWT
                .create()
                .withKeyId(KID)
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject(subject)
                .withIssuedAt(Date())
                .withExpiresAt(Date(System.currentTimeMillis() + TOKEN_TTL_MILLIS))
                .sign(Algorithm.RSA256(publicKey, privateKey))

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun unsigned(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
    }
}
