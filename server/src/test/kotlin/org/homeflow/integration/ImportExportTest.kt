package org.homeflow.integration

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.homeflow.AppDependencies
import org.homeflow.core.ApiError
import org.homeflow.core.ErrorCode
import org.homeflow.core.dto.CreateCycleRequest
import org.homeflow.core.dto.CreateDailyLogRequest
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.ExportCycle
import org.homeflow.core.dto.ExportDay
import org.homeflow.core.dto.HomeFlowExport
import org.homeflow.core.dto.ImportResultDto
import org.homeflow.core.dto.NotesUpdateRequest
import org.homeflow.core.dto.OptionIdsRequest
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.dto.PainRegionsResponse
import org.homeflow.core.dto.PainUpdateRequest
import org.homeflow.core.dto.SymptomCategoriesResponse
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.testcontainers.containers.PostgreSQLContainer
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 12 done-when verification for the native `homeflow` export/import format
 * (`__docs/API.md` "Import & Export"). Same in-process RS256 / Testcontainers harness
 * as the other integration tests. Covers: slug-keyed + decrypted export (no UUIDs),
 * a lossless round-trip into a fresh account, re-import idempotency, lenient unknown-slug
 * handling, and the closed error set (`400 VALIDATION_ERROR` for a bad source, a
 * malformed file, and an unsupported export format).
 *
 * Requires a running Docker daemon (see __docs/TESTING.md).
 */
class ImportExportTest {
    @Before
    fun resetState() {
        transaction(db) {
            DailyLogs.deleteAll()
            Cycles.deleteAll()
            Users.deleteAll()
        }
    }

    @Test
    fun `export is slug-keyed and decrypted, with no UUIDs anywhere`() =
        withApp { client ->
            client.anchorOn(SUB, "2024-01-20")
            client.putOptions(SUB, "2024-01-20", "emotions", listOf(client.optionId(SUB, "emotions", "mood_swings")))
            client.putOptions(SUB, "2024-01-20", "sex", listOf(client.optionId(SUB, "sex", "protected")))
            client.patchNotes(SUB, "2024-01-20", "Felt better today.")
            client.putPain(SUB, "2024-01-20", listOf(PainLocationDto(client.locationId(SUB, "lower_back"), 6)))

            val response = client.get("/api/v1/export?format=json") { bearerSub(SUB) }
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains("\"homeflow_export\":1"))
            assertFalse(UUID_PATTERN.containsMatchIn(text), "export must not contain any UUID")

            val export = response.body<HomeFlowExport>()
            val day = export.days.single()
            assertEquals(listOf("mood_swings"), day.emotions)
            assertEquals(listOf("protected"), day.sex)
            assertEquals("Felt better today.", day.notes)
            assertEquals("lower_back", day.pain.single().location)
            assertEquals(6, day.pain.single().severity)
        }

    @Test
    fun `a round-trip import into a fresh account is lossless`() =
        withApp { client ->
            seedFullDay(client, SUB, "2024-01-20")
            val original = client.get("/api/v1/export?format=json") { bearerSub(SUB) }.body<HomeFlowExport>()

            val result = client.importJson(OTHER_SUB, original)
            assertTrue(result.cyclesCreated > 0)
            assertTrue(result.dailyLogsCreated > 0)

            val reExported = client.get("/api/v1/export?format=json") { bearerSub(OTHER_SUB) }.body<HomeFlowExport>()
            assertEquals(original.cycles.toSet(), reExported.cycles.toSet())
            assertEquals(original.days.toSet(), reExported.days.toSet())
        }

    @Test
    fun `re-importing the same file is idempotent`() =
        withApp { client ->
            seedFullDay(client, SUB, "2024-01-20")
            val export = client.get("/api/v1/export?format=json") { bearerSub(SUB) }.body<HomeFlowExport>()
            client.importJson(OTHER_SUB, export)

            val second = client.importJson(OTHER_SUB, export)
            assertEquals(0, second.cyclesCreated)
            assertEquals(0, second.dailyLogsCreated)
            assertEquals(export.days.size, second.dailyLogsSkipped)

            // No duplicate days were created for OTHER_SUB by the second import.
            val otherSubDayCount =
                transaction(db) {
                    (DailyLogs innerJoin Users)
                        .selectAll()
                        .where { Users.keycloakSub eq OTHER_SUB }
                        .count()
                }
            assertEquals(export.days.size.toLong(), otherSubDayCount)
        }

    @Test
    fun `an unknown option slug is dropped and counted, the rest of the day still imports`() =
        withApp { client ->
            val export =
                HomeFlowExport(
                    cycles = listOf(ExportCycle(startDate = "2024-01-15", endDate = null)),
                    days =
                        listOf(
                            ExportDay(
                                date = "2024-01-20",
                                cycleStartDate = "2024-01-15",
                                emotions = listOf("not_a_real_slug", "fine"),
                            ),
                        ),
                )
            val result = client.importJson(SUB, export)
            assertTrue(result.warnings.isNotEmpty())
            assertEquals(1, result.dailyLogsCreated)

            val day = client.get("/api/v1/daily-logs/2024-01-20") { bearerSub(SUB) }.body<DailyLogDto>()
            assertEquals(listOf(client.optionId(SUB, "emotions", "fine")), day.emotions)
        }

    @Test
    fun `an unsupported import source is 400`() =
        withApp { client ->
            val response =
                client.post("/api/v1/import?source=clue") {
                    bearerSub(SUB)
                    setBody(MultiPartFormDataContent(formData {}))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCode.VALIDATION_ERROR, response.body<ApiError>().error.code)
        }

    @Test
    fun `a malformed import file is 400`() =
        withApp { client ->
            val response = client.postImportFile(SUB, "{ not json")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCode.VALIDATION_ERROR, response.body<ApiError>().error.code)
        }

    @Test
    fun `an unsupported export format is 400`() =
        withApp { client ->
            val response = client.get("/api/v1/export?format=csv") { bearerSub(SUB) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCode.VALIDATION_ERROR, response.body<ApiError>().error.code)
        }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /** A day with one selection per category, notes, and pain — enough to prove round-tripping. */
    private suspend fun seedFullDay(
        client: HttpClient,
        sub: String,
        date: String,
    ) {
        client.anchorOn(sub, date)
        client.putOptions(sub, date, "emotions", listOf(client.optionId(sub, "emotions", "mood_swings")))
        client.putOptions(sub, date, "sex", listOf(client.optionId(sub, "sex", "protected")))
        client.patchNotes(sub, date, "Felt better today.")
        client.putPain(sub, date, listOf(PainLocationDto(client.locationId(sub, "lower_back"), 6)))
    }

    private val testJson = Json { ignoreUnknownKeys = true }

    private suspend fun HttpClient.importJson(
        sub: String,
        export: HomeFlowExport,
    ): ImportResultDto = postImportFile(sub, testJson.encodeToString(HomeFlowExport.serializer(), export)).body()

    private suspend fun HttpClient.postImportFile(
        sub: String,
        json: String,
    ) = post("/api/v1/import?source=homeflow") {
        bearerSub(sub)
        setBody(
            MultiPartFormDataContent(
                formData {
                    append(
                        "file",
                        json.toByteArray(Charsets.UTF_8),
                        Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"import.json\"") },
                    )
                },
            ),
        )
    }

    /** Creates a cycle and the daily-log anchor for [date] so sub-log PUTs can target it. */
    private suspend fun HttpClient.anchorOn(
        sub: String,
        date: String,
    ) {
        val cycle =
            post("/api/v1/cycles") {
                bearerSub(sub)
                contentType(ContentType.Application.Json)
                setBody(CreateCycleRequest("2024-01-15"))
            }.body<CycleDto>()
        post("/api/v1/daily-logs") {
            bearerSub(sub)
            contentType(ContentType.Application.Json)
            setBody(CreateDailyLogRequest(date, cycle.id))
        }
    }

    private suspend fun HttpClient.putOptions(
        sub: String,
        date: String,
        category: String,
        optionIds: List<String>,
    ) = put("/api/v1/daily-logs/$date/$category") {
        bearerSub(sub)
        contentType(ContentType.Application.Json)
        setBody(OptionIdsRequest(optionIds))
    }

    private suspend fun HttpClient.putPain(
        sub: String,
        date: String,
        locations: List<PainLocationDto>,
    ) = put("/api/v1/daily-logs/$date/pain") {
        bearerSub(sub)
        contentType(ContentType.Application.Json)
        setBody(PainUpdateRequest(locations))
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

    /** Resolves a symptom option's UUID by its category + option slug via the ref-data route. */
    private suspend fun HttpClient.optionId(
        sub: String,
        categorySlug: String,
        optionSlug: String,
    ): String {
        val categories = get("/api/v1/ref-data/symptom-categories") { bearerSub(sub) }.body<SymptomCategoriesResponse>()
        return categories.categories
            .first { it.slug == categorySlug }
            .options
            .first { it.slug == optionSlug }
            .id
    }

    /** Resolves a pain location's UUID by its slug via the ref-data route. */
    private suspend fun HttpClient.locationId(
        sub: String,
        locationSlug: String,
    ): String {
        val regions = get("/api/v1/ref-data/pain-regions") { bearerSub(sub) }.body<PainRegionsResponse>()
        return regions.regions
            .flatMap { it.locations }
            .first { it.slug == locationSlug }
            .id
    }

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
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-")

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
