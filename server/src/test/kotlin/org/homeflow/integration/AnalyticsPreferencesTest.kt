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
import io.ktor.client.request.put
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
import org.homeflow.core.ApiError
import org.homeflow.core.ErrorCode
import org.homeflow.core.dto.CreateCycleRequest
import org.homeflow.core.dto.CreateDailyLogRequest
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.CycleStatsDto
import org.homeflow.core.dto.OptionIdRequest
import org.homeflow.core.dto.OptionIdsRequest
import org.homeflow.core.dto.OvulationPredictionDto
import org.homeflow.core.dto.PeriodLengthChartDto
import org.homeflow.core.dto.PreferencesDto
import org.homeflow.core.dto.PreferencesResponse
import org.homeflow.core.dto.SleepPredictionsDto
import org.homeflow.core.dto.SymptomCategoriesResponse
import org.homeflow.core.dto.UpdateCycleRequest
import org.homeflow.core.dto.UpdatePreferencesRequest
import org.homeflow.db.Cycles
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Phase 6 done-when verification over the real auth + routing stack against a Testcontainers
 * Postgres (same in-process RS256 / local-JWKS approach as the other integration tests).
 * Covers the four analytics routes computing exact values, graceful nulls under two cycles,
 * sleep bucketing by phase, and the preferences default/persist/validate behaviour.
 *
 * Requires a running Docker daemon (see __docs/TESTING.md).
 */
class AnalyticsPreferencesTest {
    @Before
    fun resetState() {
        transaction(db) {
            Cycles.deleteAll()
            Users.deleteAll()
        }
    }

    // ── Analytics: cycle stats ────────────────────────────────────────────────

    @Test
    fun `cycle-stats returns null fields with fewer than two closed cycles`() =
        withApp { client ->
            client.createCycle("2024-01-01") // a single open cycle — no closed history yet
            val stats = client.get("/api/v1/analytics/cycle-stats") { bearer(SUB) }.body<CycleStatsDto>()
            assertNull(stats.averageCycleLength)
            assertNull(stats.cycleVariation)
            assertNull(stats.averagePeriodLength)
        }

    @Test
    fun `cycle-stats computes exact values across two closed cycles`() =
        withApp { client ->
            client.seedTwoClosedCycles()
            val stats = client.get("/api/v1/analytics/cycle-stats") { bearer(SUB) }.body<CycleStatsDto>()
            assertEquals(29, stats.averageCycleLength) // mean(28, 30)
            assertEquals(1.0, stats.cycleVariation) // population stddev of (28, 30)
            assertEquals(4, stats.averagePeriodLength) // mean(3, 5)
        }

    @Test
    fun `period-length-chart returns a bleeding-day point per closed cycle, oldest first`() =
        withApp { client ->
            client.seedTwoClosedCycles()
            val chart =
                client.get("/api/v1/analytics/period-length-chart") { bearer(SUB) }.body<PeriodLengthChartDto>()
            assertEquals(listOf("2024-01-01", "2024-01-29"), chart.dataPoints.map { it.cycleStartDate })
            assertEquals(listOf(3, 5), chart.dataPoints.map { it.bleedingDays })
        }

    // ── Analytics: ovulation prediction ───────────────────────────────────────

    @Test
    fun `ovulation-prediction is null with fewer than two closed cycles`() =
        withApp { client ->
            client.createCycle("2024-01-01")
            val thin =
                client.get("/api/v1/analytics/ovulation-prediction") { bearer(SUB) }.body<OvulationPredictionDto>()
            assertNull(thin.averageCycleLength)
            assertNull(thin.predictions)
        }

    @Test
    fun `ovulation-prediction projects from the most recent cycle start`() =
        withApp { client ->
            client.seedTwoClosedCycles()
            val full =
                client.get("/api/v1/analytics/ovulation-prediction") { bearer(SUB) }.body<OvulationPredictionDto>()
            assertEquals(29, full.averageCycleLength)
            val predictions = assertNotNull(full.predictions)
            assertEquals(3, predictions.size)
            // Projected from the most recent cycle start (2024-01-29) at the 29-day average.
            assertEquals("2024-02-27", predictions.first().predictedPeriodStart)
            assertEquals("2024-02-13", predictions.first().predictedOvulationDate) // start − 14 days
        }

    // ── Analytics: sleep predictions ──────────────────────────────────────────

    @Test
    fun `sleep-predictions buckets logged days by phase and gates on sample size`() =
        withApp { client ->
            // A single open cycle: with no closed history the default 28/5 lengths apply, so
            // cycle days 1..5 are the menstruation phase. Log sleep on those five days.
            val cycle = client.createCycle("2024-03-01")
            val rested = client.optionId("sleep_quality", "woke_rested")
            listOf("2024-03-01", "2024-03-02", "2024-03-03", "2024-03-04", "2024-03-05").forEach { date ->
                client.anchor(date, cycle.id)
                client.putOptions(date, "sleep", listOf(rested))
            }

            val predictions =
                client.get("/api/v1/analytics/sleep-predictions") { bearer(SUB) }.body<SleepPredictionsDto>()
            val menstruation = assertNotNull(predictions.phases.menstruation)
            assertEquals(5, menstruation.sampleSize)
            assertEquals(listOf(rested), menstruation.mostCommon)
            // Phases without enough data stay null.
            assertNull(predictions.phases.luteal)
        }

    @Test
    fun `analytics require authentication`() =
        withApp { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/analytics/cycle-stats").status)
        }

    // ── Preferences ───────────────────────────────────────────────────────────

    @Test
    fun `preferences default to the reference category sort order until saved`() =
        withApp { client ->
            client.bootstrapUser()
            val order = client.get("/api/v1/preferences") { bearer(SUB) }.body<PreferencesDto>().categoryOrder
            assertEquals(client.categorySlugsInSortOrder(), order)
            assertEquals("emotions", order.first())
        }

    @Test
    fun `preferences persist a full reordering and reject an incomplete one`() =
        withApp { client ->
            client.bootstrapUser()
            val reordered = client.categorySlugsInSortOrder().reversed()

            val saved =
                client.put("/api/v1/preferences") {
                    bearer(SUB)
                    contentType(ContentType.Application.Json)
                    setBody(UpdatePreferencesRequest(reordered))
                }
            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals(reordered, saved.body<PreferencesResponse>().categoryOrder)

            // The new order is read back on the next GET.
            val reread = client.get("/api/v1/preferences") { bearer(SUB) }.body<PreferencesDto>().categoryOrder
            assertEquals(reordered, reread)

            // An order missing a slug is rejected.
            val invalid =
                client.put("/api/v1/preferences") {
                    bearer(SUB)
                    contentType(ContentType.Application.Json)
                    setBody(UpdatePreferencesRequest(reordered.drop(1)))
                }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals(ErrorCode.VALIDATION_ERROR, invalid.body<ApiError>().error.code)
        }

    @Test
    fun `preferences require authentication`() =
        withApp { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/preferences").status)
        }

    // ── Harness ─────────────────────────────────────────────────────────────────

    private fun withApp(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { module(deps) }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            block(client)
        }

    /** Any authenticated call upserts the user row; this makes that bootstrap explicit. */
    private suspend fun HttpClient.bootstrapUser() {
        get("/api/v1/users/me") { bearer(SUB) }
    }

    private suspend fun HttpClient.createCycle(start: String): CycleDto =
        post("/api/v1/cycles") {
            bearer(SUB)
            contentType(ContentType.Application.Json)
            setBody(CreateCycleRequest(start))
        }.body()

    /**
     * Two closed cycles: 2024-01-01→2024-01-28 (inclusive length 28, 3 bleeding days) and
     * 2024-01-29→2024-02-27 (length 30, 5 bleeding days). Starting the second auto-closes the
     * first; the second is closed explicitly. Flow logs supply the bleeding-day counts.
     */
    private suspend fun HttpClient.seedTwoClosedCycles() {
        val first = createCycle("2024-01-01")
        val second = createCycle("2024-01-29") // auto-closes the first at 2024-01-28
        patch("/api/v1/cycles/${second.id}") {
            bearer(SUB)
            contentType(ContentType.Application.Json)
            setBody(UpdateCycleRequest("2024-02-27"))
        }
        val flow = optionId("blood_flow", "medium")
        listOf("2024-01-01", "2024-01-02", "2024-01-03").forEach { logFlow(it, first.id, flow) }
        listOf("2024-01-29", "2024-01-30", "2024-01-31", "2024-02-01", "2024-02-02").forEach {
            logFlow(it, second.id, flow)
        }
    }

    private suspend fun HttpClient.logFlow(
        date: String,
        cycleId: String,
        optionId: String,
    ) {
        anchor(date, cycleId)
        putOption(date, "flow", optionId)
    }

    private suspend fun HttpClient.anchor(
        date: String,
        cycleId: String,
    ) {
        post("/api/v1/daily-logs") {
            bearer(SUB)
            contentType(ContentType.Application.Json)
            setBody(CreateDailyLogRequest(date, cycleId))
        }
    }

    private suspend fun HttpClient.putOption(
        date: String,
        category: String,
        optionId: String?,
    ) = put("/api/v1/daily-logs/$date/$category") {
        bearer(SUB)
        contentType(ContentType.Application.Json)
        setBody(OptionIdRequest(optionId))
    }

    private suspend fun HttpClient.putOptions(
        date: String,
        category: String,
        optionIds: List<String>,
    ) = put("/api/v1/daily-logs/$date/$category") {
        bearer(SUB)
        contentType(ContentType.Application.Json)
        setBody(OptionIdsRequest(optionIds))
    }

    private suspend fun HttpClient.categories(): SymptomCategoriesResponse =
        get("/api/v1/ref-data/symptom-categories") { bearer(SUB) }.body()

    private suspend fun HttpClient.categorySlugsInSortOrder(): List<String> = categories().categories.map { it.slug }

    private suspend fun HttpClient.optionId(
        categorySlug: String,
        optionSlug: String,
    ): String =
        categories()
            .categories
            .first { it.slug == categorySlug }
            .options
            .first { it.slug == optionSlug }
            .id

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
