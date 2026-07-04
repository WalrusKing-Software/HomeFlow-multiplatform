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
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.homeflow.AppDependencies
import org.homeflow.config.Config
import org.homeflow.config.DatabaseConfig
import org.homeflow.config.KeycloakConfig
import org.homeflow.config.RateLimitConfig
import org.homeflow.core.dto.CreateCycleRequest
import org.homeflow.core.dto.CreateDailyLogRequest
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.NotesUpdateRequest
import org.homeflow.core.dto.SyncPullResponse
import org.homeflow.core.dto.SyncPushRequest
import org.homeflow.core.dto.SyncPushResponse
import org.homeflow.db.Cycles
import org.homeflow.db.DailyLogs
import org.homeflow.db.SyncChanges
import org.homeflow.db.Users
import org.homeflow.lib.KeycloakAdminClient
import org.homeflow.module
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 16a done-when verification for the server sync foundation
 * (the `GET/POST /api/v1/sync/changes` protocol; see `__docs/API.md`). Same in-process RS256 /
 * Testcontainers harness as the other integration tests. Covers: change stamping on
 * writes; `DELETE` routes producing tombstones (+ cascade for cycles); the
 * `GET/POST /api/v1/sync/changes` protocol with a cursor, last-write-wins conflict
 * resolution, idempotency, and row-scoping to the principal.
 *
 * Requires a running Docker daemon (see __docs/TESTING.md).
 */
class SyncTest {
    @Before
    fun resetState() {
        transaction(db) {
            SyncChanges.deleteAll()
            DailyLogs.deleteAll()
            Cycles.deleteAll()
            Users.deleteAll()
        }
    }

    @Test
    fun `a write records a sync change surfaced by GET sync changes`() =
        withApp { client ->
            client.anchorOn(SUB, "2024-01-20")

            val pull = client.pull(SUB, 0)
            assertTrue(pull.cursor > 0, "the cursor advances after a write")
            assertEquals(1, pull.cycles.size)
            assertEquals("2024-01-15", pull.cycles.single().startDate)
            assertTrue(pull.days.any { it.date == "2024-01-20" && !it.deleted })
        }

    @Test
    fun `deleting a cycle tombstones it and cascades day tombstones`() =
        withApp { client ->
            val cycle = client.createCycle(SUB, "2024-01-15")
            client.createDay(SUB, "2024-01-20", cycle.id)

            val deleted = client.delete("/api/v1/cycles/${cycle.id}") { bearer(SUB) }
            assertEquals(HttpStatusCode.NoContent, deleted.status)

            // Live reads exclude the tombstoned cycle and its cascaded day.
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/daily-logs/2024-01-20") { bearer(SUB) }.status)

            // Both tombstones surface in the sync feed.
            val pull = client.pull(SUB, 0)
            assertTrue(pull.cycles.any { it.id == cycle.id && it.deleted })
            assertTrue(pull.days.any { it.deleted })
        }

    @Test
    fun `deleting a day tombstones it and hides it from live reads`() =
        withApp { client ->
            client.anchorOn(SUB, "2024-01-20")

            val deleted = client.delete("/api/v1/daily-logs/2024-01-20") { bearer(SUB) }
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/daily-logs/2024-01-20") { bearer(SUB) }.status)

            val pull = client.pull(SUB, 0)
            assertTrue(pull.days.any { it.deleted })
        }

    @Test
    fun `pushing a new day for a date that has a tombstone does not collide`() =
        withApp { client ->
            val cycle = client.createCycle(SUB, "2024-02-01")
            client.createDay(SUB, "2024-02-10", cycle.id)
            val original = client.pull(SUB, 0).days.single { it.date == "2024-02-10" }

            // Tombstone the day (as an explicit delete or a cycle-delete cascade does).
            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("/api/v1/daily-logs/2024-02-10") { bearer(SUB) }.status,
            )

            // An offline client legitimately pushes a FRESH day (new id) for the same date.
            // Before the V4 partial-unique fix this aborted the whole push with
            // `duplicate key value violates unique constraint daily_logs_user_id_log_date_key`,
            // which stalled cross-device sync (the cursor never advanced).
            val replacement = original.copy(id = REPLACEMENT_DAY_ID, updatedAt = FUTURE)
            val pushed =
                client.post("/api/v1/sync/changes") {
                    bearer(SUB)
                    contentType(ContentType.Application.Json)
                    setBody(SyncPushRequest(days = listOf(replacement)))
                }
            assertEquals(HttpStatusCode.OK, pushed.status, "the push must not collide with the tombstone")

            // The new day is live and readable, and it surfaces in the change feed.
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/daily-logs/2024-02-10") { bearer(SUB) }.status,
            )
            assertTrue(client.pull(SUB, 0).days.any { it.id == REPLACEMENT_DAY_ID && !it.deleted })
        }

    @Test
    fun `GET sync changes since a cursor returns only newer changes`() =
        withApp { client ->
            client.anchorOn(SUB, "2024-01-20")
            val first = client.pull(SUB, 0)

            client.patchNotes(SUB, "2024-01-20", "added later")
            val sinceFirst = client.pull(SUB, first.cursor)
            assertTrue(sinceFirst.days.any { it.date == "2024-01-20" }, "the later edit is returned")

            // A pull caught up to the newest cursor returns nothing.
            val sinceLatest = client.pull(SUB, sinceFirst.cursor)
            assertTrue(sinceLatest.cycles.isEmpty() && sinceLatest.days.isEmpty())
        }

    @Test
    fun `POST sync changes resolves conflicts by last-write-wins`() =
        withApp { client ->
            client.anchorOn(SUB, "2024-01-20")
            val day = client.pull(SUB, 0).days.single { it.date == "2024-01-20" }

            // A strictly-later updatedAt wins (lexicographic == chronological on ISO instants).
            client.push(SUB, SyncPushRequest(days = listOf(day.copy(notes = "newer-wins", updatedAt = FUTURE))))
            assertEquals("newer-wins", client.dailyLog(SUB, "2024-01-20").notes)

            // An older updatedAt loses; the server keeps the newer value.
            client.push(SUB, SyncPushRequest(days = listOf(day.copy(notes = "older-loses", updatedAt = PAST))))
            assertEquals("newer-wins", client.dailyLog(SUB, "2024-01-20").notes)
        }

    @Test
    fun `re-pushing the same batch is idempotent`() =
        withApp { client ->
            client.anchorOn(SUB, "2024-01-20")
            val day = client.pull(SUB, 0).days.single { it.date == "2024-01-20" }
            val batch = SyncPushRequest(days = listOf(day.copy(notes = "once", updatedAt = FUTURE)))

            client.push(SUB, batch)
            client.push(SUB, batch)

            assertEquals("once", client.dailyLog(SUB, "2024-01-20").notes)
            // No duplicate aggregate: the day appears exactly once in the change feed.
            assertEquals(1, client.pull(SUB, 0).days.count { it.id == day.id })
        }

    @Test
    fun `sync changes are row-scoped to the principal`() =
        withApp { client ->
            client.anchorOn(SUB, "2024-01-20")

            val otherPull = client.pull(OTHER_SUB, 0)
            assertTrue(otherPull.cycles.isEmpty(), "another user sees none of SUB's cycles")
            assertTrue(otherPull.days.isEmpty(), "another user sees none of SUB's days")
        }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private suspend fun HttpClient.pull(
        sub: String,
        cursor: Long,
    ): SyncPullResponse = get("/api/v1/sync/changes?cursor=$cursor") { bearer(sub) }.body()

    private suspend fun HttpClient.push(
        sub: String,
        request: SyncPushRequest,
    ): SyncPushResponse =
        post("/api/v1/sync/changes") {
            bearer(sub)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    private suspend fun HttpClient.createCycle(
        sub: String,
        startDate: String,
    ): CycleDto =
        post("/api/v1/cycles") {
            bearer(sub)
            contentType(ContentType.Application.Json)
            setBody(CreateCycleRequest(startDate))
        }.body()

    private suspend fun HttpClient.createDay(
        sub: String,
        date: String,
        cycleId: String,
    ) {
        post("/api/v1/daily-logs") {
            bearer(sub)
            contentType(ContentType.Application.Json)
            setBody(CreateDailyLogRequest(date, cycleId))
        }
    }

    /** Creates a cycle (2024-01-15) and the daily-log anchor for [date]. */
    private suspend fun HttpClient.anchorOn(
        sub: String,
        date: String,
    ) {
        val cycle = createCycle(sub, "2024-01-15")
        createDay(sub, date, cycle.id)
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

    private suspend fun HttpClient.dailyLog(
        sub: String,
        date: String,
    ): DailyLogDto = get("/api/v1/daily-logs/$date") { bearer(sub) }.body()

    private fun HttpRequestBuilder.bearer(sub: String) {
        header(HttpHeaders.Authorization, "Bearer ${makeToken(sub)}")
    }

    private fun withApp(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { module(deps) }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            block(client)
        }

    /** A no-op Keycloak admin client — account deletion is exercised in [AuthUsersTest], not here. */
    private class NoopAdminClient : KeycloakAdminClient {
        override suspend fun deleteUser(keycloakSub: String) = Unit
    }

    companion object {
        private const val KID = "test-key"
        private const val SUB = "11111111-1111-1111-1111-111111111111"
        private const val OTHER_SUB = "22222222-2222-2222-2222-222222222222"
        private const val REPLACEMENT_DAY_ID = "33333333-3333-3333-3333-333333333333"
        private const val ISSUER = "https://test.homeflow.local/realms/homeflow"
        private const val AUDIENCE = "homeflow-backend"
        private const val RSA_KEY_SIZE = 2048
        private const val ENCRYPTION_KEY_BYTES = 32
        private const val TOKEN_TTL_MILLIS = 3_600_000L
        private const val HIGH_RATE_LIMIT = 100_000

        // Fixed bounds well outside any real server timestamp — ISO instants compare
        // lexicographically == chronologically, so these are an unambiguous winner/loser.
        private const val FUTURE = "2099-01-01T00:00:00Z"
        private const val PAST = "2000-01-01T00:00:00Z"

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
