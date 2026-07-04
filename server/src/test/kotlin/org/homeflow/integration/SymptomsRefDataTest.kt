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
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.EmotionsResponse
import org.homeflow.core.dto.EnergyResponse
import org.homeflow.core.dto.OptionIdRequest
import org.homeflow.core.dto.OptionIdsRequest
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.dto.PainRegionsResponse
import org.homeflow.core.dto.PainResponse
import org.homeflow.core.dto.PainUpdateRequest
import org.homeflow.core.dto.SexResponse
import org.homeflow.core.dto.SymptomCategoriesResponse
import org.homeflow.db.Cycles
import org.homeflow.db.DailyLogSex
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 5 done-when verification over the real auth + routing stack against a
 * Testcontainers Postgres (same in-process RS256 / local-JWKS approach as the other
 * integration tests). Covers the two ref-data routes, every symptom sub-log PUT
 * (multi-select, single-select, sex, pain) saving and clearing, option-id validation
 * (bad/foreign-category IDs → 400), the fully assembled day on GET, sex encrypted at
 * rest + decrypted on read, pain severity/locations (null valid), and the 404 when no
 * anchor exists for the date.
 *
 * Requires a running Docker daemon (see __docs/TESTING.md).
 */
class SymptomsRefDataTest {
    @Before
    fun resetState() {
        transaction(db) {
            DailyLogs.deleteAll()
            Cycles.deleteAll()
            Users.deleteAll()
        }
    }

    // ── Reference data ──────────────────────────────────────────────────────────

    @Test
    fun `symptom-categories returns every category with nested options`() =
        withApp { client ->
            val response = client.get("/api/v1/ref-data/symptom-categories") { bearer(SUB) }
            assertEquals(HttpStatusCode.OK, response.status)
            val categories = response.body<SymptomCategoriesResponse>().categories
            assertEquals(10, categories.size)
            // Default display order from sort_order.
            assertEquals("emotions", categories.first().slug)
            val bloodFlow = categories.first { it.slug == "blood_flow" }
            assertEquals("single", bloodFlow.selectionType)
            assertEquals("menstruation", bloodFlow.phase)
            assertEquals(4, bloodFlow.options.size)
            assertEquals("light", bloodFlow.options.first().slug)
        }

    @Test
    fun `pain-regions returns every region with nested locations`() =
        withApp { client ->
            val response = client.get("/api/v1/ref-data/pain-regions") { bearer(SUB) }
            assertEquals(HttpStatusCode.OK, response.status)
            val regions = response.body<PainRegionsResponse>().regions
            assertEquals(5, regions.size)
            assertEquals("head_neck", regions.first().slug)
            val abdomen = regions.first { it.slug == "abdomen" }
            assertEquals(setOf("stomach", "uterus", "ovaries", "pelvis"), abdomen.locations.map { it.slug }.toSet())
        }

    @Test
    fun `ref-data requires authentication`() =
        withApp { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/ref-data/symptom-categories").status)
        }

    // ── Multi-select sub-logs ─────────────────────────────────────────────────

    @Test
    fun `emotions save, assemble on the day, and clear with an empty array`() =
        withApp { client ->
            client.anchorOn("2024-01-20")
            val fine = client.optionId("emotions", "fine")
            val anxious = client.optionId("emotions", "anxious")

            val saved = client.putOptions("2024-01-20", "emotions", listOf(fine, anxious))
            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals(setOf(fine, anxious), saved.body<EmotionsResponse>().emotions.toSet())

            val day = client.getDay("2024-01-20")
            assertEquals(setOf(fine, anxious), day.emotions?.toSet())

            // Empty array clears the category → the field reads back as null.
            assertEquals(HttpStatusCode.OK, client.putOptions("2024-01-20", "emotions", emptyList()).status)
            assertNull(client.getDay("2024-01-20").emotions)
        }

    @Test
    fun `a multi-select rejects an option from the wrong category and a malformed id`() =
        withApp { client ->
            client.anchorOn("2024-01-20")
            // A real option id, but it belongs to sleep_quality, not emotions.
            val sleepOption = client.optionId("sleep_quality", "woke_rested")
            val wrongCategory = client.putOptions("2024-01-20", "emotions", listOf(sleepOption))
            assertEquals(HttpStatusCode.BadRequest, wrongCategory.status)
            assertEquals(ErrorCode.VALIDATION_ERROR, wrongCategory.body<ApiError>().error.code)

            assertEquals(HttpStatusCode.BadRequest, client.putOptions("2024-01-20", "emotions", listOf("nope")).status)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.putOptions("2024-01-20", "emotions", listOf(UNKNOWN_UUID)).status,
            )
        }

    // ── Single-select sub-logs ────────────────────────────────────────────────

    @Test
    fun `energy saves a single value and clears with null`() =
        withApp { client ->
            client.anchorOn("2024-01-20")
            val tired = client.optionId("energy", "tired")

            val saved = client.putOption("2024-01-20", "energy", tired)
            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals(tired, saved.body<EnergyResponse>().energy)
            assertEquals(tired, client.getDay("2024-01-20").energy)

            val cleared = client.putOption("2024-01-20", "energy", null)
            assertEquals(HttpStatusCode.OK, cleared.status)
            assertNull(cleared.body<EnergyResponse>().energy)
            assertNull(client.getDay("2024-01-20").energy)
        }

    @Test
    fun `flow rejects an option from the wrong category`() =
        withApp { client ->
            client.anchorOn("2024-01-20")
            val emotionOption = client.optionId("emotions", "fine")
            assertEquals(HttpStatusCode.BadRequest, client.putOption("2024-01-20", "flow", emotionOption).status)
        }

    // ── Sex (encrypted) ───────────────────────────────────────────────────────

    @Test
    fun `sex selections are stored as ciphertext and returned decrypted`() =
        withApp { client ->
            client.anchorOn("2024-01-20")
            val protectedOption = client.optionId("sex", "protected")
            val orgasm = client.optionId("sex", "orgasm")

            val saved = client.putOptions("2024-01-20", "sex", listOf(protectedOption, orgasm))
            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals(setOf(protectedOption, orgasm), saved.body<SexResponse>().sex.toSet())

            // Round-trips decrypted on read…
            assertEquals(setOf(protectedOption, orgasm), client.getDay("2024-01-20").sex?.toSet())

            // …but the column holds ciphertext, never the plaintext option ids.
            val stored =
                transaction(db) {
                    DailyLogSex.selectAll().single()[DailyLogSex.encryptedPayload]
                }
            assertTrue(stored.isNotBlank())
            assertFalse(protectedOption in stored, "sex option ids must be encrypted at rest")
            assertFalse(orgasm in stored, "sex option ids must be encrypted at rest")

            // Clearing removes the row entirely.
            assertEquals(HttpStatusCode.OK, client.putOptions("2024-01-20", "sex", emptyList()).status)
            assertNull(client.getDay("2024-01-20").sex)
            assertEquals(0, transaction(db) { DailyLogSex.selectAll().count() })
        }

    // ── Pain ──────────────────────────────────────────────────────────────────

    @Test
    fun `pain saves per-location severities, allows null, and clears with an empty list`() =
        withApp { client ->
            client.anchorOn("2024-01-20")
            val lowerBack = client.locationId("lower_back")
            val ovaries = client.locationId("ovaries")

            val saved =
                client.putPain(
                    "2024-01-20",
                    listOf(
                        PainLocationDto(locationId = lowerBack, severity = 7),
                        PainLocationDto(locationId = ovaries, severity = null),
                    ),
                )
            assertEquals(HttpStatusCode.OK, saved.status)
            val pain = assertNotNull(saved.body<PainResponse>().pain)
            assertEquals(
                mapOf(lowerBack to 7, ovaries to null),
                pain.locations.associate { it.locationId to it.severity },
            )

            val dayPain = assertNotNull(client.getDay("2024-01-20").pain)
            assertEquals(
                mapOf(lowerBack to 7, ovaries to null),
                dayPain.locations.associate { it.locationId to it.severity },
            )

            // Empty list clears the pain log → null on read.
            assertEquals(HttpStatusCode.OK, client.putPain("2024-01-20", emptyList()).status)
            assertNull(client.putPain("2024-01-20", emptyList()).body<PainResponse>().pain)
            assertNull(client.getDay("2024-01-20").pain)
        }

    @Test
    fun `pain rejects bad locations, duplicates, and out-of-range severity`() =
        withApp { client ->
            client.anchorOn("2024-01-20")
            val lowerBack = client.locationId("lower_back")

            assertEquals(
                HttpStatusCode.BadRequest,
                client.putPain("2024-01-20", listOf(PainLocationDto(UNKNOWN_UUID, 3))).status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .putPain(
                        "2024-01-20",
                        listOf(PainLocationDto(lowerBack, 3), PainLocationDto(lowerBack, 5)),
                    ).status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.putPain("2024-01-20", listOf(PainLocationDto(lowerBack, 11))).status,
            )
        }

    // ── Assembly + anchor gating ──────────────────────────────────────────────

    @Test
    fun `the full day assembles every logged category`() =
        withApp { client ->
            client.anchorOn("2024-01-20")
            val anxious = client.optionId("emotions", "anxious")
            val energetic = client.optionId("energy", "energetic")
            val creamy = client.optionId("discharge", "creamy")
            val noSex = client.optionId("sex", "no_sex")
            val uterus = client.locationId("uterus")

            client.putOptions("2024-01-20", "emotions", listOf(anxious))
            client.putOption("2024-01-20", "energy", energetic)
            client.putOptions("2024-01-20", "discharge", listOf(creamy))
            client.putOptions("2024-01-20", "sex", listOf(noSex))
            client.putPain("2024-01-20", listOf(PainLocationDto(uterus, 4)))

            val day = client.getDay("2024-01-20")
            assertEquals(listOf(anxious), day.emotions)
            assertEquals(energetic, day.energy)
            assertEquals(listOf(creamy), day.discharge)
            assertEquals(listOf(noSex), day.sex)
            assertEquals(
                uterus,
                day.pain
                    ?.locations
                    ?.single()
                    ?.locationId,
            )
            // Untouched categories stay null.
            assertNull(day.sleep)
            assertNull(day.flow)
        }

    @Test
    fun `a sub-log PUT for a date with no anchor is 404`() =
        withApp { client ->
            val response = client.putOptions("2024-01-20", "emotions", emptyList())
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, response.body<ApiError>().error.code)
        }

    // ── Harness ─────────────────────────────────────────────────────────────────

    private fun withApp(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { module(deps) }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            block(client)
        }

    /** Creates a cycle and the daily-log anchor for [date] so sub-log PUTs can target it. */
    private suspend fun HttpClient.anchorOn(date: String) {
        val cycle =
            post("/api/v1/cycles") {
                bearer(SUB)
                contentType(ContentType.Application.Json)
                setBody(CreateCycleRequest("2024-01-15"))
            }.body<CycleDto>()
        post("/api/v1/daily-logs") {
            bearer(SUB)
            contentType(ContentType.Application.Json)
            setBody(CreateDailyLogRequest(date, cycle.id))
        }
    }

    private suspend fun HttpClient.getDay(date: String): DailyLogDto =
        get("/api/v1/daily-logs/$date") { bearer(SUB) }.body()

    private suspend fun HttpClient.putOptions(
        date: String,
        category: String,
        optionIds: List<String>,
    ) = put("/api/v1/daily-logs/$date/$category") {
        bearer(SUB)
        contentType(ContentType.Application.Json)
        setBody(OptionIdsRequest(optionIds))
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

    private suspend fun HttpClient.putPain(
        date: String,
        locations: List<PainLocationDto>,
    ) = put("/api/v1/daily-logs/$date/pain") {
        bearer(SUB)
        contentType(ContentType.Application.Json)
        setBody(PainUpdateRequest(locations))
    }

    /** Resolves a symptom option's UUID by its category + option slug via the ref-data route. */
    private suspend fun HttpClient.optionId(
        categorySlug: String,
        optionSlug: String,
    ): String {
        val categories = get("/api/v1/ref-data/symptom-categories") { bearer(SUB) }.body<SymptomCategoriesResponse>()
        return categories.categories
            .first { it.slug == categorySlug }
            .options
            .first { it.slug == optionSlug }
            .id
    }

    /** Resolves a pain location's UUID by its slug via the ref-data route. */
    private suspend fun HttpClient.locationId(locationSlug: String): String {
        val regions = get("/api/v1/ref-data/pain-regions") { bearer(SUB) }.body<PainRegionsResponse>()
        return regions.regions
            .flatMap { it.locations }
            .first { it.slug == locationSlug }
            .id
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
        private const val UNKNOWN_UUID = "33333333-3333-3333-3333-333333333333"
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
