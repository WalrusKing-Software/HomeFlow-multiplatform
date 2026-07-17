package org.homeflow.integration

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import org.flywaydb.core.Flyway
import org.homeflow.config.Config
import org.homeflow.config.DatabaseConfig
import org.homeflow.config.KeycloakConfig
import org.homeflow.config.RateLimitConfig
import org.homeflow.lib.KeycloakAdminClient
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date

/**
 * Shared constants and factory functions used by every integration-test class. Each test
 * class keeps its own container instance, lifecycle (@BeforeClass/@AfterClass), and
 * [org.homeflow.AppDependencies] construction (they differ in admin client, page size,
 * etc.) — only the duplicated helper bodies live here.
 */
object IntegrationHarness {
    const val KID = "test-key"
    const val ISSUER = "https://test.homeflow.local/realms/homeflow"
    const val AUDIENCE = "homeflow-backend"
    const val RSA_KEY_SIZE = 2048
    const val ENCRYPTION_KEY_BYTES = 32
    const val TOKEN_TTL_MILLIS = 3_600_000L
    const val HIGH_RATE_LIMIT = 100_000

    /** A fresh `postgres:16-alpine` container targeting the test DB name. */
    fun newPostgres(): PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("period_tracker_test")

    /** Run Flyway migrations against [postgres] and return a connected [Database]. */
    fun migrateAndConnect(postgres: PostgreSQLContainer<*>): Database {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        return Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password,
        )
    }

    /** Generates a fresh RSA keypair for in-process JWT signing. */
    fun generateRsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_SIZE) }.generateKeyPair()

    /**
     * Builds a [JwkProvider] backed by the given [publicKey] (no network call).
     * The key id returned by [getKeyId] must match [KID].
     */
    fun localJwkProvider(publicKey: RSAPublicKey): JwkProvider =
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

    /**
     * Mints a signed RS256 JWT for [subject] against the test keypair.
     * Optional overrides: [audience], [issuer], [expiresAt].
     */
    fun makeToken(
        privateKey: RSAPrivateKey,
        publicKey: RSAPublicKey,
        subject: String,
        audience: String = AUDIENCE,
        issuer: String = ISSUER,
        expiresAt: Date = Date(System.currentTimeMillis() + TOKEN_TTL_MILLIS),
    ): String =
        JWT
            .create()
            .withKeyId(KID)
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(subject)
            .withIssuedAt(Date())
            .withExpiresAt(expiresAt)
            .sign(Algorithm.RSA256(publicKey, privateKey))

    /**
     * Builds a test [Config] with the shared constants and the supplied [rateLimit].
     * The database section is unused (tests inject a [Database] directly).
     */
    fun testConfig(rateLimit: RateLimitConfig = RateLimitConfig(HIGH_RATE_LIMIT, TOKEN_TTL_MILLIS)): Config =
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
            rateLimit = rateLimit,
            encryptionKey = Base64.getEncoder().encodeToString(ByteArray(ENCRYPTION_KEY_BYTES) { 7 }),
            serverVersion = "test",
            minClientVersion = "0.0.0",
        )

    /** Attaches a Bearer [token] to an [HttpRequestBuilder]. */
    fun HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    /** Encodes [bytes] as URL-safe base64 without padding — the JWK n/e format. */
    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Big-endian magnitude without a leading sign byte, as JWK n/e require. */
    fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }
}

/** A no-op [KeycloakAdminClient] for tests that don't exercise account deletion. */
class NoopAdminClient : KeycloakAdminClient {
    override suspend fun deleteUser(keycloakSub: String) = Unit
}
