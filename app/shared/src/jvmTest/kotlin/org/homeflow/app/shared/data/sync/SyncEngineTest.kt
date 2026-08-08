package org.homeflow.app.shared.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.homeflow.app.shared.auth.OidcTokens
import org.homeflow.app.shared.config.AuthConfig
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.RemoteDataSource
import org.homeflow.app.shared.data.TokenHolder
import org.homeflow.app.shared.data.buildHttpClient
import org.homeflow.app.shared.data.local.LocalBootstrap
import org.homeflow.app.shared.data.local.LocalDataSource
import org.homeflow.app.shared.data.local.TestDbHelper
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.dto.SyncCycle
import org.homeflow.core.dto.SyncDay
import org.homeflow.core.dto.SyncPreferences
import org.homeflow.core.dto.SyncPullResponse
import org.homeflow.core.dto.SyncPushRequest
import org.homeflow.core.dto.SyncPushResponse
import org.homeflow.core.service.MergeWinner
import org.homeflow.core.service.mergeDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Phase 16b — the [SyncEngine] reconciler driven by `syncNow()` against an in-memory
 * [FakeSyncServer]. The fake server reuses the **same `:core` [mergeDecision] (LWW)** the
 * real Ktor server uses, so two devices sharing one server converge exactly as production.
 *
 * Covers (per the spec's Done-when): push marks the outbox synced; a remote aggregate is
 * pulled and applied; a same-day conflict resolves by later `updatedAt` on both devices; a
 * remote tombstone deletes locally without re-enqueuing (no echo loop); a slug-keyed
 * selection round-trips across stores that assigned the option different UUIDs; and an
 * interrupted sync (push ok, pull fails) re-runs to convergence with no duplicates.
 */
class SyncEngineTest {
    // ── Test scaffolding ────────────────────────────────────────────────────────

    /** Per-device knob to simulate a network failure on pulls (for resumability). */
    private class PullControl {
        var failPull: Boolean = false
    }

    /**
     * A minimal in-memory stand-in for the server's `/sync/changes`. Stores the slug-keyed
     * wire DTOs directly (no DB, no encryption, no ref-data mapping needed) and reuses the
     * real LWW rule so merge behavior is identical to the Ktor server.
     */
    private class FakeSyncServer {
        private val cycles = linkedMapOf<String, SyncCycle>()
        private val days = linkedMapOf<String, SyncDay>()
        private var prefs: SyncPreferences? = null
        private val seqByKey = linkedMapOf<String, Long>()
        private var seq = 0L

        private fun bump(key: String) {
            seq += 1
            seqByKey[key] = seq
        }

        fun push(req: SyncPushRequest): SyncPushResponse {
            req.cycles.forEach { incoming ->
                val cur = cycles[incoming.id]
                if (cur == null ||
                    mergeDecision(cur.updatedAt, cur.id, incoming.updatedAt, incoming.id) == MergeWinner.REMOTE
                ) {
                    cycles[incoming.id] = incoming
                    bump("cycle:${incoming.id}")
                }
            }
            req.days.forEach { incoming ->
                val cur = days[incoming.id]
                if (cur == null ||
                    mergeDecision(cur.updatedAt, cur.id, incoming.updatedAt, incoming.id) == MergeWinner.REMOTE
                ) {
                    days[incoming.id] = incoming
                    bump("day:${incoming.id}")
                }
            }
            req.preferences?.let { incoming ->
                val cur = prefs
                if (cur == null ||
                    mergeDecision(cur.updatedAt, PREFS_ID, incoming.updatedAt, PREFS_ID) == MergeWinner.REMOTE
                ) {
                    prefs = incoming
                    bump("preferences:$PREFS_ID")
                }
            }
            // Return the authoritative post-merge state of each pushed aggregate (D-16a.6) so the
            // client adopts server-won values immediately — exactly what the Ktor server does.
            return SyncPushResponse(
                cycles = req.cycles.map { cycles[it.id] ?: it.copy(deleted = true) },
                days = req.days.map { days[it.id] ?: it.copy(deleted = true) },
                preferences = req.preferences?.let { prefs },
                cursor = seq,
            )
        }

        fun pull(cursor: Long): SyncPullResponse {
            val outCycles = mutableListOf<SyncCycle>()
            val outDays = mutableListOf<SyncDay>()
            var outPrefs: SyncPreferences? = null
            seqByKey.forEach { (key, s) ->
                if (s <= cursor) return@forEach
                val (type, id) = key.split(":", limit = 2)
                when (type) {
                    "cycle" -> cycles[id]?.let(outCycles::add)
                    "day" -> days[id]?.let(outDays::add)
                    "preferences" -> prefs?.let { outPrefs = it }
                }
            }
            val newCursor = seqByKey.values.maxOrNull()?.coerceAtLeast(cursor) ?: cursor
            return SyncPullResponse(outCycles, outDays, outPrefs, newCursor)
        }

        fun cycleCount(): Int = cycles.values.count { !it.deleted }

        fun dayCount(): Int = days.values.count { !it.deleted }

        private companion object {
            const val PREFS_ID = "preferences"
        }
    }

    /** A device = its own encrypted-shape local store + a [SyncEngine] over a shared server. */
    private inner class Device(
        server: FakeSyncServer,
    ) {
        val control = PullControl()
        val db: HomeFlowDb = TestDbHelper.inMemory().also { LocalBootstrap.seed(it) }
        val ds = LocalDataSource(db)
        val engine = SyncEngine(db, RemoteDataSource(client(server, control)))

        suspend fun sync() = engine.syncNow()

        fun pendingCount() = LocalOutbox(db).pending().size

        fun optionId(slug: String): String =
            db.refDataQueries
                .selectAllOptions()
                .executeAsList()
                .first { it.slug == slug }
                .id

        fun dayId(date: String): String? =
            db.dailyLogsQueries
                .selectByDate(LocalBootstrap.LOCAL_USER_ID, date)
                .executeAsOneOrNull()
                ?.id

        fun emotionSlugs(date: String): List<String> {
            val logId = dayId(date) ?: return emptyList()
            val slugById =
                db.refDataQueries
                    .selectAllOptions()
                    .executeAsList()
                    .associate { it.id to it.slug }
            return db.dailyLogSubsQueries
                .selectAllMultiByLog(
                    logId,
                ).executeAsList()
                .mapNotNull { slugById[it.option_id] }
        }
    }

    private fun client(
        server: FakeSyncServer,
        control: PullControl,
    ): HttpClient {
        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("sync/changes") && request.method == HttpMethod.Post -> {
                        val body = (request.body as TextContent).text
                        val req = json.decodeFromString(SyncPushRequest.serializer(), body)
                        respond(
                            json.encodeToString(SyncPushResponse.serializer(), server.push(req)),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                    path.endsWith("sync/changes") && request.method == HttpMethod.Get -> {
                        if (control.failPull) {
                            respond(SERVER_ERROR, HttpStatusCode.InternalServerError, jsonHeaders)
                        } else {
                            val cursor = request.url.parameters["cursor"]?.toLong() ?: 0L
                            respond(
                                json.encodeToString(SyncPullResponse.serializer(), server.pull(cursor)),
                                HttpStatusCode.OK,
                                jsonHeaders,
                            )
                        }
                    }
                    else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
                }
            }
        return buildHttpClient(
            config = config,
            tokenHolder = TokenHolder().apply { set(OidcTokens("test-AT", "test-RT", null)) },
            onRefresh = { null },
            engine = engine,
        )
    }

    /** Runs [rounds] full sync cycles on each device — enough for two peers to converge. */
    private suspend fun converge(
        vararg devices: Device,
        rounds: Int = 3,
    ) {
        repeat(rounds) { devices.forEach { it.sync() } }
    }

    private suspend fun Device.seedDayWithCycle(
        cycleStart: String,
        date: String,
    ): String {
        val cycleId = (ds.createCycle(cycleStart) as ApiResult.Success).value.id
        assertIs<ApiResult.Success<*>>(ds.createDailyLog(date, cycleId))
        return cycleId
    }

    // ── Tests ────────────────────────────────────────────────────────────────────

    @Test
    fun `push pulls in a live day's parent cycle when only the day is queued (issue 83)`() =
        runBlocking {
            val server = FakeSyncServer()
            val a = Device(server)
            a.seedDayWithCycle(cycleStart = "2026-01-01", date = "2026-01-03")

            // Simulate the first-connect race: the cycle's outbox entry is already gone, so only
            // the day is pending. Without the fix the day would be pushed alone → server FK 500.
            val outbox = LocalOutbox(a.db)
            outbox
                .pending()
                .filter { it.entity_type == SyncEngine.ENTITY_CYCLE }
                .forEach { outbox.markSynced(it.id) }
            assertTrue(outbox.pending().all { it.entity_type == SyncEngine.ENTITY_DAY })

            a.sync()

            // The engine must have pulled the parent cycle into the same push (FK satisfied).
            assertEquals(1, server.cycleCount())
            assertEquals(1, server.dayCount())
        }

    @Test
    fun `push drops a live day whose parent cycle no longer exists locally (issue 83)`() =
        runBlocking {
            val server = FakeSyncServer()
            val a = Device(server)
            a.seedDayWithCycle(cycleStart = "2026-02-01", date = "2026-02-02")

            // Orphan the day: remove the cycle row entirely, then drop its dangling outbox entry.
            a.db.cyclesQueries.deleteAll()
            val outbox = LocalOutbox(a.db)
            outbox
                .pending()
                .filter { it.entity_type == SyncEngine.ENTITY_CYCLE }
                .forEach { outbox.markSynced(it.id) }

            a.sync()

            // An unsatisfiable day is dropped rather than pushed alone — nothing broken reaches the server.
            assertEquals(0, server.cycleCount())
            assertEquals(0, server.dayCount())
        }

    @Test
    fun `push sends pending outbox entries and marks them synced`() =
        runBlocking {
            val server = FakeSyncServer()
            val a = Device(server)
            a.ds.createCycle("2024-03-01")
            assertTrue(a.pendingCount() > 0, "a write should enqueue an outbox entry")

            a.sync()

            assertEquals(0, a.pendingCount(), "a successful push marks the outbox synced")
            assertEquals(1, server.cycleCount(), "the cycle reached the server")
        }

    @Test
    fun `pull applies a remote aggregate the device does not yet have`() =
        runBlocking {
            val server = FakeSyncServer()
            val a = Device(server)
            val b = Device(server)

            a.ds.createCycle("2024-03-01")
            a.sync() // push A's cycle to the server
            b.sync() // B pulls it

            val cyclesOnB = (b.ds.getCycles() as ApiResult.Success).value.cycles
            assertEquals(1, cyclesOnB.size)
            assertEquals("2024-03-01", cyclesOnB.first().startDate)
        }

    @Test
    fun `same-day conflict resolves to the later updatedAt on both devices`() =
        runBlocking {
            val server = FakeSyncServer()
            val a = Device(server)
            val b = Device(server)

            // Both devices start from the same synced day.
            a.seedDayWithCycle("2024-03-01", "2024-03-05")
            a.ds.patchNotes("2024-03-05", "initial")
            converge(a, b)

            // Offline edits: B first, then A — so A's updatedAt is strictly later (the winner).
            assertIs<ApiResult.Success<*>>(b.ds.patchNotes("2024-03-05", "from-B"))
            // Long enough to guarantee a strictly later ISO timestamp than B's edit
            // (coarse clock resolution would otherwise tie, and LWW can't break a tie on equal id).
            Thread.sleep(50)
            assertIs<ApiResult.Success<*>>(a.ds.patchNotes("2024-03-05", "from-A"))

            converge(a, b)

            val notesOnA = (a.ds.getDailyLog("2024-03-05") as ApiResult.Success).value.notes
            val notesOnB = (b.ds.getDailyLog("2024-03-05") as ApiResult.Success).value.notes
            assertEquals("from-A", notesOnA)
            assertEquals("from-A", notesOnB)
        }

    @Test
    fun `a remote tombstone deletes locally and is not re-enqueued to the outbox`() {
        runBlocking {
            val server = FakeSyncServer()
            val a = Device(server)
            val b = Device(server)

            a.seedDayWithCycle("2024-03-01", "2024-03-05")
            converge(a, b)
            assertIs<ApiResult.Success<*>>(b.ds.getDailyLog("2024-03-05")) // B has the day

            // A deletes the day and syncs the tombstone up.
            assertIs<ApiResult.Success<*>>(a.ds.deleteDay("2024-03-05"))
            a.sync()
            b.sync() // B pulls the tombstone

            assertIs<ApiResult.Failure>(b.ds.getDailyLog("2024-03-05"))
            assertEquals(0, b.pendingCount(), "applying a remote tombstone must NOT enqueue an outbox entry")

            // Re-syncing does not resurrect it.
            converge(a, b)
            assertIs<ApiResult.Failure>(b.ds.getDailyLog("2024-03-05"))
        }
    }

    @Test
    fun `a slug-keyed selection round-trips across stores with different option UUIDs`() =
        runBlocking {
            val server = FakeSyncServer()
            val a = Device(server)
            val b = Device(server)

            a.seedDayWithCycle("2024-03-01", "2024-03-05")
            assertIs<ApiResult.Success<*>>(
                a.ds.putOptionIds("2024-03-05", "emotions", listOf(a.optionId("mood_swings"))),
            )
            converge(a, b)

            // The two stores assigned `mood_swings` different local UUIDs (D4) ...
            assertNotEquals(a.optionId("mood_swings"), b.optionId("mood_swings"))
            // ... yet the selection round-trips by slug.
            assertTrue(b.emotionSlugs("2024-03-05").contains("mood_swings"))
        }

    @Test
    fun `an interrupted sync (push ok, pull fails) re-runs to convergence with no duplicates`() =
        runBlocking {
            val server = FakeSyncServer()
            val a = Device(server)
            val b = Device(server)

            a.ds.createCycle("2024-03-01")
            a.control.failPull = true
            a.sync() // push succeeds, pull fails for the whole cycle

            assertIs<SyncStatus.Error>(a.engine.status.value)
            assertEquals(0, a.pendingCount(), "the push half committed: outbox is drained")
            assertEquals(1, server.cycleCount(), "the cycle reached the server despite the failed pull")

            // Restore connectivity; re-running converges with no duplicate rows anywhere.
            a.control.failPull = false
            converge(a, b)
            assertEquals(1, server.cycleCount())
            assertEquals(1, (a.ds.getCycles() as ApiResult.Success).value.cycles.size)
            assertEquals(1, (b.ds.getCycles() as ApiResult.Success).value.cycles.size)
        }

    @Test
    fun `a paginated pull fetches all pages in one sync run`() =
        runBlocking {
            val db = TestDbHelper.inMemory().also { LocalBootstrap.seed(it) }
            val cycle1 =
                SyncCycle(
                    id = "aaaaaaaa-0000-0000-0000-000000000001",
                    startDate = "2024-01-01",
                    endDate = "2024-01-28",
                    updatedAt = "2024-01-28T00:00:00Z",
                )
            val cycle2 =
                SyncCycle(
                    id = "bbbbbbbb-0000-0000-0000-000000000002",
                    startDate = "2024-02-01",
                    updatedAt = "2024-02-01T00:00:00Z",
                )

            // A server that pages the pull (SEC-02): cursor 0 → page 1 (hasMore), cursor 1 → page 2.
            val pagingEngine =
                MockEngine { request ->
                    val cursor = request.url.parameters["cursor"]?.toLong() ?: 0L
                    val response =
                        when (cursor) {
                            0L -> SyncPullResponse(cycles = listOf(cycle1), cursor = 1, hasMore = true)
                            1L -> SyncPullResponse(cycles = listOf(cycle2), cursor = 2, hasMore = false)
                            else -> SyncPullResponse(cursor = cursor)
                        }
                    respond(
                        json.encodeToString(SyncPullResponse.serializer(), response),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
            val http =
                buildHttpClient(
                    config = config,
                    tokenHolder = TokenHolder().apply { set(OidcTokens("test-AT", "test-RT", null)) },
                    onRefresh = { null },
                    engine = pagingEngine,
                )
            val engine = SyncEngine(db, RemoteDataSource(http))

            engine.syncNow()

            assertIs<SyncStatus.Success>(engine.status.value)
            val cycles = db.cyclesQueries.selectAll(LocalBootstrap.LOCAL_USER_ID).executeAsList()
            assertEquals(2, cycles.size, "both pages must be applied in one sync run")
            assertEquals(2L, db.syncStateQueries.getCursor().executeAsOneOrNull(), "the final page cursor persists")
        }

    private companion object {
        val config = AuthConfig(host = "example.test")
        val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        const val SERVER_ERROR = """{"error":{"code":"INTERNAL_ERROR","message":"simulated network failure"}}"""
    }
}
