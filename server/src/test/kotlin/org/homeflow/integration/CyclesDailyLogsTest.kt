package org.homeflow.integration

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.homeflow.AppDependencies
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
import org.homeflow.integration.IntegrationHarness.bearer
import org.homeflow.integration.IntegrationHarness.generateRsaKeyPair
import org.homeflow.integration.IntegrationHarness.localJwkProvider
import org.homeflow.integration.IntegrationHarness.makeToken
import org.homeflow.integration.IntegrationHarness.migrateAndConnect
import org.homeflow.integration.IntegrationHarness.newPostgres
import org.homeflow.integration.IntegrationHarness.testConfig
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
            val cycles = client.get("/api/v1/cycles") { bearerSub(SUB) }.body<CyclesResponse>().cycles
            assertEquals(listOf("2024-02-12", "2024-01-15"), cycles.map { it.startDate })
        }

    @Test
    fun `starting a new cycle auto-closes the previous open one`() =
        withApp { client ->
            client.createCycle(SUB, "2024-01-15")
            client.createCycle(SUB, "2024-02-12")
            val cycles = client.get("/api/v1/cycles") { bearerSub(SUB) }.body<CyclesResponse>().cycles

            val newest = cycles.first { it.startDate == "2024-02-12" }
            val previous = cycles.first { it.startDate == "2024-01-15" }
            assertNull(newest.endDate, "the newest cycle stays open")
            assertEquals("2024-02-11", previous.endDate, "the previous cycle closes at startDate - 1 day")
        }

    @Test
    fun `current returns the open cycle and 404 when none is open`() =
        withApp { client ->
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/cycles/current") { bearerSub(SUB) }.status)
            val created = client.createCycle(SUB, "2024-01-15").body<CycleDto>()
            val current = client.get("/api/v1/cycles/current") { bearerSub(SUB) }
            assertEquals(HttpStatusCode.OK, current.status)
            assertEquals(created.id, current.body<CycleDto>().id)
        }

    @Test
    fun `get cycle by id returns it, while unknown and malformed ids are 404`() =
        withApp { client ->
            val created = client.createCycle(SUB, "2024-01-15").body<CycleDto>()
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/cycles/${created.id}") { bearerSub(SUB) }.status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/v1/cycles/$UNKNOWN_UUID") { bearerSub(SUB) }.status,
            )
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/cycles/not-a-uuid") { bearerSub(SUB) }.status)
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
            val response = client.get("/api/v1/cycles/${created.id}") { bearerSub(OTHER_SUB) }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, response.body<ApiError>().error.code)
            val otherUserCycles =
                client
                    .get("/api/v1/cycles") { bearerSub(OTHER_SUB) }
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
                    .get("/api/v1/cycles") { bearerSub(SUB) }
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
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/daily-logs/2024-01-20") { bearerSub(SUB) }.status)

            val cycle = client.createCycle(SUB, "2024-01-15").body<CycleDto>()
            client.createAnchor(SUB, "2024-01-20", cycle.id)

            val response = client.get("/api/v1/daily-logs/2024-01-20") { bearerSub(SUB) }
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
            val readBack = client.get("/api/v1/daily-logs/2024-01-20") { bearerSub(SUB) }.body<DailyLogDto>()
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
            assertNull(client.get("/api/v1/daily-logs/2024-01-20") { bearerSub(SUB) }.body<DailyLogDto>().notes)
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
        bearerSub(sub)
        contentType(ContentType.Application.Json)
        setBody(CreateCycleRequest(startDate, id))
    }

    private suspend fun HttpClient.patchCycle(
        sub: String,
        cycleId: String,
        endDate: String,
    ) = patch("/api/v1/cycles/$cycleId") {
        bearerSub(sub)
        contentType(ContentType.Application.Json)
        setBody(UpdateCycleRequest(endDate))
    }

    private suspend fun HttpClient.createAnchor(
        sub: String,
        date: String,
        cycleId: String,
        id: String? = null,
    ) = post("/api/v1/daily-logs") {
        bearerSub(sub)
        contentType(ContentType.Application.Json)
        setBody(CreateDailyLogRequest(date, cycleId, id))
    }

    private suspend fun HttpClient.patchNotes(
        sub: String,
        date: String,
        notes: String?,
    ) = patch("/api/v1/daily-logs/$date/notes") {
        bearerSub(sub)
        contentType(ContentType.Application.Json)
        setBody(NotesUpdateRequest(notes))
    }

    private fun HttpRequestBuilder.bearerSub(sub: String) = bearer(makeToken(privateKey, publicKey, sub))

    companion object {
        private const val SUB = "11111111-1111-1111-1111-111111111111"
        private const val OTHER_SUB = "22222222-2222-2222-2222-222222222222"
        private const val UNKNOWN_UUID = "33333333-3333-3333-3333-333333333333"
        private const val CLIENT_UUID = "44444444-4444-4444-4444-444444444444"

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
