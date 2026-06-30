package org.homeflow.integration

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import org.homeflow.lib.KeycloakAdminClient
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
import org.testcontainers.utility.DockerImageName
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date
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

            val response = client.get("/api/v1/export?format=json") { bearer(SUB) }
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
            val original = client.get("/api/v1/export?format=json") { bearer(SUB) }.body<HomeFlowExport>()

            val result = client.importJson(OTHER_SUB, original)
            assertTrue(result.cyclesCreated > 0)
            assertTrue(result.dailyLogsCreated > 0)

            val reExported = client.get("/api/v1/export?format=json") { bearer(OTHER_SUB) }.body<HomeFlowExport>()
            assertEquals(original.cycles.toSet(), reExported.cycles.toSet())
            assertEquals(original.days.toSet(), reExported.days.toSet())
        }

    @Test
    fun `re-importing the same file is idempotent`() =
        withApp { client ->
            seedFullDay(client, SUB, "2024-01-20")
            val export = client.get("/api/v1/export?format=json") { bearer(SUB) }.body<HomeFlowExport>()
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

            val day = client.get("/api/v1/daily-logs/2024-01-20") { bearer(SUB) }.body<DailyLogDto>()
            assertEquals(listOf(client.optionId(SUB, "emotions", "fine")), day.emotions)
        }

    @Test
    fun `an unsupported import source is 400`() =
        withApp { client ->
            val response =
                client.post("/api/v1/import?source=clue") {
                    bearer(SUB)
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
            val response = client.get("/api/v1/export?format=csv") { bearer(SUB) }
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
        bearer(sub)
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
                bearer(sub)
                contentType(ContentType.Application.Json)
                setBody(CreateCycleRequest("2024-01-15"))
            }.body<CycleDto>()
        post("/api/v1/daily-logs") {
            bearer(sub)
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
        bearer(sub)
        contentType(ContentType.Application.Json)
        setBody(OptionIdsRequest(optionIds))
    }

    private suspend fun HttpClient.putPain(
        sub: String,
        date: String,
        locations: List<PainLocationDto>,
    ) = put("/api/v1/daily-logs/$date/pain") {
        bearer(sub)
        contentType(ContentType.Application.Json)
        setBody(PainUpdateRequest(locations))
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

    /** Resolves a symptom option's UUID by its category + option slug via the ref-data route. */
    private suspend fun HttpClient.optionId(
        sub: String,
        categorySlug: String,
        optionSlug: String,
    ): String {
        val categories = get("/api/v1/ref-data/symptom-categories") { bearer(sub) }.body<SymptomCategoriesResponse>()
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
        val regions = get("/api/v1/ref-data/pain-regions") { bearer(sub) }.body<PainRegionsResponse>()
        return regions.regions
            .flatMap { it.locations }
            .first { it.slug == locationSlug }
            .id
    }

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
        private const val ISSUER = "https://test.homeflow.local/realms/homeflow"
        private const val AUDIENCE = "homeflow-backend"
        private const val RSA_KEY_SIZE = 2048
        private const val ENCRYPTION_KEY_BYTES = 32
        private const val TOKEN_TTL_MILLIS = 3_600_000L
        private const val HIGH_RATE_LIMIT = 100_000
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-")

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
