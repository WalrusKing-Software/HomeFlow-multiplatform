package org.homeflow.integration

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import org.homeflow.AppDependencies
import org.homeflow.core.dto.CreateCycleRequest
import org.homeflow.core.dto.CreateDailyLogRequest
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.CyclesResponse
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.NotesUpdateRequest
import org.homeflow.core.dto.SyncCycle
import org.homeflow.core.dto.SyncPullResponse
import org.homeflow.core.dto.SyncPushRequest
import org.homeflow.core.dto.SyncPushResponse
import org.homeflow.db.Cycles
import org.homeflow.db.DailyLogEmotions
import org.homeflow.db.DailyLogFlow
import org.homeflow.db.DailyLogs
import org.homeflow.db.SyncChanges
import org.homeflow.db.Users
import org.homeflow.integration.IntegrationHarness.ENCRYPTION_KEY_BYTES
import org.homeflow.integration.IntegrationHarness.bearer
import org.homeflow.integration.IntegrationHarness.generateRsaKeyPair
import org.homeflow.integration.IntegrationHarness.localJwkProvider
import org.homeflow.integration.IntegrationHarness.makeToken
import org.homeflow.integration.IntegrationHarness.migrateAndConnect
import org.homeflow.integration.IntegrationHarness.newPostgres
import org.homeflow.integration.IntegrationHarness.testConfig
import org.homeflow.lib.Encryption
import org.homeflow.module
import org.homeflow.modules.cycles.CyclesRepository
import org.homeflow.modules.dailylogs.DailyLogSubsRepository
import org.homeflow.modules.dailylogs.DailyLogsRepository
import org.homeflow.modules.preferences.PreferencesRepository
import org.homeflow.modules.refdata.RefDataRepository
import org.homeflow.modules.sync.ChangeLogRepository
import org.homeflow.modules.sync.SyncService
import org.homeflow.modules.users.UserPrincipal
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
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
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

            val deleted = client.delete("/api/v1/cycles/${cycle.id}") { bearerSub(SUB) }
            assertEquals(HttpStatusCode.NoContent, deleted.status)

            // Live reads exclude the tombstoned cycle and its cascaded day.
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/daily-logs/2024-01-20") { bearerSub(SUB) }.status)

            // Both tombstones surface in the sync feed.
            val pull = client.pull(SUB, 0)
            assertTrue(pull.cycles.any { it.id == cycle.id && it.deleted })
            assertTrue(pull.days.any { it.deleted })
        }

    @Test
    fun `deleting a day tombstones it and hides it from live reads`() =
        withApp { client ->
            client.anchorOn(SUB, "2024-01-20")

            val deleted = client.delete("/api/v1/daily-logs/2024-01-20") { bearerSub(SUB) }
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/daily-logs/2024-01-20") { bearerSub(SUB) }.status)

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
                client.delete("/api/v1/daily-logs/2024-02-10") { bearerSub(SUB) }.status,
            )

            // An offline client legitimately pushes a FRESH day (new id) for the same date.
            // Before the V4 partial-unique fix this aborted the whole push with
            // `duplicate key value violates unique constraint daily_logs_user_id_log_date_key`,
            // which stalled cross-device sync (the cursor never advanced).
            val replacement = original.copy(id = REPLACEMENT_DAY_ID, updatedAt = FUTURE)
            val pushed =
                client.post("/api/v1/sync/changes") {
                    bearerSub(SUB)
                    contentType(ContentType.Application.Json)
                    setBody(SyncPushRequest(days = listOf(replacement)))
                }
            assertEquals(HttpStatusCode.OK, pushed.status, "the push must not collide with the tombstone")

            // The new day is live and readable, and it surfaces in the change feed.
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/daily-logs/2024-02-10") { bearerSub(SUB) }.status,
            )
            assertTrue(client.pull(SUB, 0).days.any { it.id == REPLACEMENT_DAY_ID && !it.deleted })
        }

    @Test
    fun `pushing a day whose parent cycle does not exist returns 400 not 500 (issue 83)`() =
        withApp { client ->
            client.anchorOn(SUB, "2024-01-20")
            val day = client.pull(SUB, 0).days.single { it.date == "2024-01-20" }

            // A day referencing a cycle that isn't on the server (dangling FK) must be rejected
            // cleanly with a 400 — not surface as an opaque 500 that stalls the client's sync.
            val orphan = day.copy(id = ORPHAN_DAY_ID, cycleId = MISSING_CYCLE_ID, updatedAt = FUTURE)
            val response =
                client.post("/api/v1/sync/changes") {
                    bearerSub(SUB)
                    contentType(ContentType.Application.Json)
                    setBody(SyncPushRequest(days = listOf(orphan)))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
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

    @Test
    fun `pull is paginated with hasMore and a resumable cursor`() =
        withApp { client ->
            client.anchorOn(SUB, "2024-01-20") // records two changes: one cycle + one day

            // A page size of 1 exercises the paging logic without seeding 500+ rows.
            val paged = pagedSyncService(pageSize = 1)
            val principal = principalFor(SUB)

            val first = paged.pull(principal, 0)
            assertEquals(1, first.cycles.size + first.days.size, "the page is capped at pageSize")
            assertTrue(first.hasMore, "a capped page must report more changes")

            val second = paged.pull(principal, first.cursor)
            assertEquals(1, second.cycles.size + second.days.size)
            assertEquals(false, second.hasMore, "the final page reports no more changes")

            // The union of pages equals the unpaginated pull — no gaps, no duplicates.
            val full = client.pull(SUB, 0)
            assertEquals(
                full.cycles.map { it.id }.toSet(),
                (first.cycles + second.cycles).map { it.id }.toSet(),
            )
            assertEquals(
                full.days.map { it.id }.toSet(),
                (first.days + second.days).map { it.id }.toSet(),
            )

            val empty = paged.pull(principal, second.cursor)
            assertTrue(empty.cycles.isEmpty() && empty.days.isEmpty())
            assertEquals(second.cursor, empty.cursor, "an empty page echoes the request cursor")
            assertEquals(false, empty.hasMore)
        }

    @Test
    fun `pushed sub-logs carry the pushed updatedAt not wall-clock`() =
        withApp { client ->
            client.anchorOn(SUB, "2024-01-20")
            val day = client.pull(SUB, 0).days.single { it.date == "2024-01-20" }

            client.push(
                SUB,
                SyncPushRequest(
                    days = listOf(day.copy(emotions = listOf("fine"), flow = "medium", updatedAt = FUTURE)),
                ),
            )

            val expected = OffsetDateTime.parse(FUTURE).toInstant()
            transaction(db) {
                val anchorId = UUID.fromString(day.id)
                val emotionCreatedAt =
                    DailyLogEmotions
                        .selectAll()
                        .where { DailyLogEmotions.dailyLogId eq anchorId }
                        .single()[DailyLogEmotions.createdAt]
                val flowCreatedAt =
                    DailyLogFlow
                        .selectAll()
                        .where { DailyLogFlow.dailyLogId eq anchorId }
                        .single()[DailyLogFlow.createdAt]
                assertEquals(expected, emotionCreatedAt.toInstant())
                assertEquals(expected, flowCreatedAt.toInstant())
            }
        }

    @Test
    fun `concurrent pushes with two open cycles converge to exactly one open cycle`() =
        withApp { client ->
            val cycleAId = "cccccccc-cccc-cccc-cccc-cccccccccccc"
            val cycleBId = "dddddddd-dddd-dddd-dddd-dddddddddddd"

            val cycleA =
                SyncCycle(
                    id = cycleAId,
                    startDate = "2024-03-01",
                    updatedAt = FUTURE,
                )
            val cycleB =
                SyncCycle(
                    id = cycleBId,
                    startDate = "2024-03-05",
                    updatedAt = FUTURE,
                )

            // Fire both pushes concurrently — each device believes it started the open cycle.
            coroutineScope {
                listOf(
                    async { client.push(SUB, SyncPushRequest(cycles = listOf(cycleA))) },
                    async { client.push(SUB, SyncPushRequest(cycles = listOf(cycleB))) },
                ).awaitAll()
            }

            // One final empty push drives another reconcileServerOpenCycles pass so the
            // server's post-merge state is fully settled before we assert.
            client.push(SUB, SyncPushRequest())

            val allCycles = client.get("/api/v1/cycles") { bearerSub(SUB) }.body<CyclesResponse>().cycles
            val openCycles = allCycles.filter { it.endDate == null }
            assertEquals(1, openCycles.size, "exactly one open cycle must survive concurrent pushes")
            assertEquals("2024-03-05", openCycles.single().startDate, "the later-starting cycle must remain open")
        }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /** The [UserPrincipal] the auth plugin would resolve for [sub] (row must already exist). */
    private fun principalFor(sub: String): UserPrincipal =
        transaction(db) {
            val id =
                Users
                    .selectAll()
                    .where { Users.keycloakSub eq sub }
                    .single()[Users.id]
            UserPrincipal(id = id, keycloakSub = sub)
        }

    /** A [SyncService] over the same test DB with an injected tiny pull page (SEC-02). */
    private fun pagedSyncService(pageSize: Int): SyncService {
        val changeLog = ChangeLogRepository(db)
        return SyncService(
            cyclesRepository = CyclesRepository(db, changeLog),
            dailyLogsRepository = DailyLogsRepository(db, changeLog),
            dailyLogSubsRepository = DailyLogSubsRepository(db, changeLog),
            preferencesRepository = PreferencesRepository(db, changeLog),
            changeLogRepository = changeLog,
            refDataRepository = RefDataRepository(db),
            encryption = Encryption(Base64.getEncoder().encodeToString(ByteArray(ENCRYPTION_KEY_BYTES) { 7 })),
            pullPageSize = pageSize,
        )
    }

    private suspend fun HttpClient.pull(
        sub: String,
        cursor: Long,
    ): SyncPullResponse = get("/api/v1/sync/changes?cursor=$cursor") { bearerSub(sub) }.body()

    private suspend fun HttpClient.push(
        sub: String,
        request: SyncPushRequest,
    ): SyncPushResponse =
        post("/api/v1/sync/changes") {
            bearerSub(sub)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    private suspend fun HttpClient.createCycle(
        sub: String,
        startDate: String,
    ): CycleDto =
        post("/api/v1/cycles") {
            bearerSub(sub)
            contentType(ContentType.Application.Json)
            setBody(CreateCycleRequest(startDate))
        }.body()

    private suspend fun HttpClient.createDay(
        sub: String,
        date: String,
        cycleId: String,
    ) {
        post("/api/v1/daily-logs") {
            bearerSub(sub)
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
        bearerSub(sub)
        contentType(ContentType.Application.Json)
        setBody(NotesUpdateRequest(notes))
    }

    private suspend fun HttpClient.dailyLog(
        sub: String,
        date: String,
    ): DailyLogDto = get("/api/v1/daily-logs/$date") { bearerSub(sub) }.body()

    private fun HttpRequestBuilder.bearerSub(sub: String) = bearer(makeToken(privateKey, publicKey, sub))

    private fun withApp(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { module(deps) }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            block(client)
        }

    companion object {
        private const val SUB = "11111111-1111-1111-1111-111111111111"
        private const val OTHER_SUB = "22222222-2222-2222-2222-222222222222"
        private const val REPLACEMENT_DAY_ID = "33333333-3333-3333-3333-333333333333"
        private const val ORPHAN_DAY_ID = "44444444-4444-4444-4444-444444444444"
        private const val MISSING_CYCLE_ID = "55555555-5555-5555-5555-555555555555"

        // Fixed bounds well outside any real server timestamp — ISO instants compare
        // lexicographically == chronologically, so these are an unambiguous winner/loser.
        private const val FUTURE = "2099-01-01T00:00:00Z"
        private const val PAST = "2000-01-01T00:00:00Z"

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

        private fun makeToken(subject: String): String = makeToken(privateKey, publicKey, subject)
    }
}
