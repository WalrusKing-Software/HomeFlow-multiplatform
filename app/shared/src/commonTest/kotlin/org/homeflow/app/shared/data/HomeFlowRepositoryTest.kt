package org.homeflow.app.shared.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.homeflow.app.shared.auth.OidcTokens
import org.homeflow.app.shared.config.AuthConfig
import org.homeflow.core.ErrorCode
import org.homeflow.core.domain.CyclePhase
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.CycleStatsDto
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.PainDto
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.dto.SymptomCategoryDto
import org.homeflow.core.dto.SymptomOptionDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeFlowRepositoryTest {
    private val config = AuthConfig(host = "example.test")

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private val categoriesJson =
        """
        {"categories":[
          {"id":"c-emotions","slug":"emotions","label":"Emotions","selectionType":"multi","phase":"always","sortOrder":1,
           "options":[{"id":"o-fine","slug":"fine","label":"Fine","sortOrder":1},
                      {"id":"o-anxious","slug":"anxious","label":"Anxious","sortOrder":2}]},
          {"id":"c-flow","slug":"blood_flow","label":"Blood Flow","selectionType":"single","phase":"menstruation","sortOrder":8,
           "options":[{"id":"o-medium","slug":"medium","label":"Medium","sortOrder":2}]}
        ]}
        """.trimIndent()

    private val regionsJson =
        """
        {"regions":[
          {"id":"r-abdomen","slug":"abdomen","label":"Abdomen","sortOrder":3,
           "locations":[{"id":"l-uterus","slug":"uterus","label":"Uterus","sortOrder":2}]}
        ]}
        """.trimIndent()

    private fun MockRequestHandleScope.json(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, jsonHeaders())

    private fun MockRequestHandleScope.notFound(): HttpResponseData =
        respond(
            """{"error":{"code":"RESOURCE_NOT_FOUND","message":"Not found."}}""",
            HttpStatusCode.NotFound,
            jsonHeaders(),
        )

    private fun repository(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HomeFlowRepository {
        val holder = TokenHolder().apply { set(OidcTokens("AT", "RT", null)) }
        val client =
            buildHttpClient(
                config,
                holder,
                onRefresh = { null },
                engine =
                    MockEngine { request ->
                        handler(request)
                    },
            )
        return HomeFlowRepository(HomeFlowApi(client))
    }

    private val cycleJson =
        """{"id":"cyc-1","startDate":"2024-01-01","endDate":null,"createdAt":"2024-01-01T00:00:00Z","updatedAt":"2024-01-01T00:00:00Z"}"""

    private val todayLogJson =
        """
        {"id":"log-1","logDate":"2024-01-06","cycleId":"cyc-1","notes":"Feeling fine.",
         "emotions":["o-fine"],"flow":"o-medium","createdAt":"2024-01-06T00:00:00Z","updatedAt":"2024-01-06T00:00:00Z"}
        """.trimIndent()

    @Test
    fun dashboard_composes_phase_and_resolves_today() =
        runTest {
            val repo =
                repository { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/ref-data/symptom-categories" -> json(categoriesJson)
                        "/api/v1/ref-data/pain-regions" -> json(regionsJson)
                        "/api/v1/cycles/current" -> json(cycleJson)
                        "/api/v1/analytics/cycle-stats" ->
                            json(
                                """{"averageCycleLength":null,"cycleVariation":null,"averagePeriodLength":null}""",
                            )
                        "/api/v1/daily-logs/2024-01-06" -> json(todayLogJson)
                        else -> notFound()
                    }
                }

            val result = repo.loadDashboard(LocalDate(2024, 1, 6))

            val data = assertIs<ApiResult.Success<DashboardData>>(result).value
            assertEquals("cyc-1", data.currentCycle?.id)
            assertEquals(6, data.cycleDay)
            // Day 6, default 28/5 stats → follicular (past the 5-day period, before ovulation window).
            assertEquals(CyclePhase.FOLLICULAR, data.phase)
            val today = data.today!!
            assertEquals(
                listOf("Emotions" to listOf("Fine"), "Blood Flow" to listOf("Medium")),
                today.categories.map {
                    it.label to
                        it.values
                },
            )
            assertEquals("Feeling fine.", today.notes)
        }

    @Test
    fun dashboard_treats_no_open_cycle_and_no_today_log_as_absence() =
        runTest {
            val repo =
                repository { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/ref-data/symptom-categories" -> json(categoriesJson)
                        "/api/v1/ref-data/pain-regions" -> json(regionsJson)
                        "/api/v1/analytics/cycle-stats" ->
                            json(
                                """{"averageCycleLength":28,"cycleVariation":2.0,"averagePeriodLength":5}""",
                            )
                        else -> notFound() // current cycle + today log both 404
                    }
                }

            val data = assertIs<ApiResult.Success<DashboardData>>(repo.loadDashboard(LocalDate(2024, 1, 6))).value
            assertNull(data.currentCycle)
            assertNull(data.cycleDay)
            assertNull(data.phase)
            assertNull(data.today)
            assertEquals(28, data.stats.averageCycleLength)
        }

    @Test
    fun ref_data_is_fetched_once_and_cached() =
        runTest {
            var categoryFetches = 0
            val repo =
                repository { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/ref-data/symptom-categories" -> {
                            categoryFetches++
                            json(categoriesJson)
                        }
                        "/api/v1/ref-data/pain-regions" -> json(regionsJson)
                        else -> notFound()
                    }
                }

            repo.ensureRefData()
            repo.ensureRefData()

            assertEquals(1, categoryFetches)
        }

    @Test
    fun cycles_failure_propagates() =
        runTest {
            val repo =
                repository {
                    respond(
                        """{"error":{"code":"INTERNAL_ERROR","message":"boom"}}""",
                        HttpStatusCode.InternalServerError,
                        jsonHeaders(),
                    )
                }

            val failure = assertIs<ApiResult.Failure>(repo.loadCycles())
            assertEquals(ErrorCode.INTERNAL_ERROR, failure.code)
        }

    // ── Pure shaping helpers ─────────────────────────────────────────────────

    private fun labels() =
        buildLabels(
            categories =
                org.homeflow.core.dto.SymptomCategoriesResponse(
                    listOf(
                        SymptomCategoryDto(
                            "c-emotions",
                            "emotions",
                            "Emotions",
                            "multi",
                            "always",
                            1,
                            listOf(SymptomOptionDto("o-fine", "fine", "Fine", 1)),
                        ),
                    ),
                ),
            regions =
                org.homeflow.core.dto.PainRegionsResponse(
                    listOf(
                        org.homeflow.core.dto.PainRegionDto(
                            "r-abdomen",
                            "abdomen",
                            "Abdomen",
                            3,
                            listOf(
                                org.homeflow.core.dto
                                    .PainLocationRefDto("l-uterus", "uterus", "Uterus", 2),
                            ),
                        ),
                    ),
                ),
        )

    @Test
    fun resolveDay_resolves_labels_and_pain_and_skips_empty_categories() {
        val log =
            DailyLogDto(
                id = "l1",
                logDate = "2024-01-06",
                cycleId = "c1",
                notes = "note",
                emotions = listOf("o-fine"),
                pain = PainDto("p1", listOf(PainLocationDto("l-uterus", 7), PainLocationDto("l-uterus", null))),
                createdAt = "x",
                updatedAt = "y",
            )

        val content = resolveDay(log, labels())

        assertEquals(listOf("Emotions"), content.categories.map { it.label })
        assertEquals(listOf("Fine"), content.categories.single().values)
        assertEquals(listOf(7, null), content.pain.map { it.severity })
        assertEquals("Uterus", content.pain.first().label)
        assertEquals("note", content.notes)
    }

    @Test
    fun cycleDayPhase_uses_default_lengths_when_stats_thin() {
        val result = cycleDayPhase("2024-01-01", LocalDate(2024, 1, 6), CycleStatsDto())
        assertEquals(6, result.day)
        assertEquals(CyclePhase.FOLLICULAR, result.phase)
    }

    @Test
    fun optional_maps_404_to_null_but_keeps_other_failures() {
        val absent = (ApiResult.Failure(ErrorCode.RESOURCE_NOT_FOUND, "x", 404) as ApiResult<String>).optional()
        assertEquals(null, assertIs<ApiResult.Success<String?>>(absent).value)

        val real = (ApiResult.Failure(ErrorCode.INTERNAL_ERROR, "x", 500) as ApiResult<String>).optional()
        assertTrue(real is ApiResult.Failure)
    }

    // ── Write surface (Phase 9) ──────────────────────────────────────────────

    private val cyclesJson =
        """{"cycles":[{"id":"cyc-1","startDate":"2024-01-01","endDate":null,"createdAt":"x","updatedAt":"y"}]}"""

    private val prefsJson = """{"categoryOrder":["emotions","blood_flow"]}"""

    private val editorLogJson =
        """
        {"id":"log-1","logDate":"2024-01-06","cycleId":"cyc-1","notes":"x","emotions":["o-fine"],
         "createdAt":"x","updatedAt":"y"}
        """.trimIndent()

    /** Records every request (method, path, body) so writes can be asserted. */
    private class Recorder {
        val calls = mutableListOf<Triple<String, String, String>>()

        fun record(request: HttpRequestData) {
            calls += Triple(request.method.value, request.url.encodedPath, (request.body as? TextContent)?.text ?: "")
        }
    }

    @Test
    fun loadDayEditor_resolves_cycle_and_prepopulates_selections() =
        runTest {
            val repo =
                repository { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/ref-data/symptom-categories" -> json(categoriesJson)
                        "/api/v1/ref-data/pain-regions" -> json(regionsJson)
                        "/api/v1/preferences" -> json(prefsJson)
                        "/api/v1/cycles" -> json(cyclesJson)
                        "/api/v1/daily-logs/2024-01-06" -> json(editorLogJson)
                        else -> notFound()
                    }
                }

            val editor = assertIs<ApiResult.Success<DayEditor>>(repo.loadDayEditor(LocalDate(2024, 1, 6))).value

            assertEquals("cyc-1", editor.cycleId)
            assertTrue(editor.canLog)
            assertEquals(listOf("o-fine"), editor.initial.selections["emotions"])
            assertEquals("x", editor.initial.notes)
        }

    @Test
    fun saveDay_creates_anchor_then_puts_only_changed_categories() =
        runTest {
            val recorder = Recorder()
            val repo =
                repository { request ->
                    recorder.record(request)
                    when {
                        request.url.encodedPath == "/api/v1/ref-data/symptom-categories" -> json(categoriesJson)
                        request.url.encodedPath == "/api/v1/ref-data/pain-regions" -> json(regionsJson)
                        request.url.encodedPath == "/api/v1/preferences" -> json(prefsJson)
                        request.url.encodedPath == "/api/v1/cycles" -> json(cyclesJson)
                        request.url.encodedPath == "/api/v1/daily-logs/2024-01-06" &&
                            request.method.value == "GET" -> json(editorLogJson)
                        request.url.encodedPath == "/api/v1/daily-logs" ->
                            respond(
                                """{"error":{"code":"CONFLICT","message":"exists"}}""",
                                HttpStatusCode.Conflict,
                                jsonHeaders(),
                            )
                        else -> json("{}")
                    }
                }
            val editor = assertIs<ApiResult.Success<DayEditor>>(repo.loadDayEditor(LocalDate(2024, 1, 6))).value
            val edits =
                editor.initial.copy(
                    selections =
                        editor.initial.selections +
                            ("emotions" to listOf("o-anxious")) +
                            ("blood_flow" to listOf("o-medium")),
                )

            recorder.calls.clear()
            assertIs<ApiResult.Success<Unit>>(repo.saveDay(LocalDate(2024, 1, 6), editor, edits))

            val writes = recorder.calls.map { it.first to it.second }
            // The anchor is created (a tolerated 409) before any sub-log PUT.
            assertEquals("POST" to "/api/v1/daily-logs", writes.first())
            assertTrue(writes.contains("PUT" to "/api/v1/daily-logs/2024-01-06/emotions"))
            assertTrue(writes.contains("PUT" to "/api/v1/daily-logs/2024-01-06/flow"))
            // Notes (unchanged) and pain (empty, unchanged) are not sent.
            assertTrue(writes.none { it.second.endsWith("/notes") })
            assertTrue(writes.none { it.second.endsWith("/pain") })
            assertTrue(
                recorder.calls
                    .first { it.second.endsWith("/emotions") }
                    .third
                    .contains("o-anxious"),
            )
            assertTrue(
                recorder.calls
                    .first { it.second.endsWith("/flow") }
                    .third
                    .contains("o-medium"),
            )
        }

    @Test
    fun saveDay_is_a_noop_when_nothing_changed() =
        runTest {
            val recorder = Recorder()
            val repo =
                repository { request ->
                    recorder.record(request)
                    when (request.url.encodedPath) {
                        "/api/v1/ref-data/symptom-categories" -> json(categoriesJson)
                        "/api/v1/ref-data/pain-regions" -> json(regionsJson)
                        "/api/v1/preferences" -> json(prefsJson)
                        "/api/v1/cycles" -> json(cyclesJson)
                        "/api/v1/daily-logs/2024-01-06" -> json(editorLogJson)
                        else -> json("{}")
                    }
                }
            val editor = assertIs<ApiResult.Success<DayEditor>>(repo.loadDayEditor(LocalDate(2024, 1, 6))).value

            recorder.calls.clear()
            assertIs<ApiResult.Success<Unit>>(repo.saveDay(LocalDate(2024, 1, 6), editor, editor.initial))

            assertTrue(recorder.calls.isEmpty())
        }

    @Test
    fun saveDay_without_a_cycle_fails_validation() =
        runTest {
            val repo = repository { json("{}") }
            val editor =
                DayEditor(cycleId = null, categories = emptyList(), painRegions = emptyList(), initial = noEdits())

            val failure = assertIs<ApiResult.Failure>(repo.saveDay(LocalDate(2024, 1, 6), editor, noEdits()))
            assertEquals(ErrorCode.VALIDATION_ERROR, failure.code)
        }

    private val newCycleJson =
        """{"id":"cyc-2","startDate":"2024-03-01","endDate":null,"createdAt":"x","updatedAt":"y"}"""

    private val closedCycleJson =
        """{"id":"cyc-1","startDate":"2024-01-01","endDate":"2024-02-01","createdAt":"x","updatedAt":"y"}"""

    @Test
    fun start_and_close_cycle_hit_the_right_routes() =
        runTest {
            val recorder = Recorder()
            val repo =
                repository { request ->
                    recorder.record(request)
                    when (request.url.encodedPath) {
                        "/api/v1/cycles" -> json(newCycleJson)
                        "/api/v1/cycles/cyc-1" -> json(closedCycleJson)
                        else -> notFound()
                    }
                }

            val started = assertIs<ApiResult.Success<CycleDto>>(repo.startCycle(LocalDate(2024, 3, 1)))
            val closed = assertIs<ApiResult.Success<CycleDto>>(repo.closeCycle("cyc-1", LocalDate(2024, 2, 1)))

            assertEquals("cyc-2", started.value.id)
            assertEquals("2024-02-01", closed.value.endDate)
            assertEquals("POST" to "/api/v1/cycles", recorder.calls.map { it.first to it.second }.first())
            assertTrue(recorder.calls.any { it.first == "PATCH" && it.second == "/api/v1/cycles/cyc-1" })
            assertTrue(
                recorder.calls
                    .first { it.second == "/api/v1/cycles" }
                    .third
                    .contains("2024-03-01"),
            )
        }

    @Test
    fun savePreferences_returns_the_stored_order() =
        runTest {
            val repo =
                repository {
                    json("""{"categoryOrder":["energy","emotions"],"updatedAt":"y"}""")
                }

            val saved = assertIs<ApiResult.Success<List<String>>>(repo.savePreferences(listOf("energy", "emotions")))
            assertEquals(listOf("energy", "emotions"), saved.value)
        }

    private fun noEdits() = DayEdits(selections = emptyMap(), notes = null, pain = emptyList())
}
