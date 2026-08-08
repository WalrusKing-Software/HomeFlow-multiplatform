package org.homeflow.integration

import org.flywaydb.core.Flyway
import org.homeflow.db.DailyLogs
import org.homeflow.db.RefPainLocations
import org.homeflow.db.RefPainRegions
import org.homeflow.db.RefSymptomCategories
import org.homeflow.db.RefSymptomOptions
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 2 done-when verification: against a real Postgres (Testcontainers), the
 * Flyway migrations create the schema and seed reference data, re-running migrate
 * is a clean no-op, and Exposed connects and reads the seeded rows.
 *
 * Flyway runs here as the container's superuser (DDL). The restricted DML-only app
 * role is provisioned by `infra/postgres/init/` in the real Docker stack, not here.
 *
 * Requires a running Docker daemon (see __docs/TESTING.md).
 */
class SchemaMigrationTest {
    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("period_tracker_test")

        private lateinit var db: Database

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres.start()
            db =
                Database.connect(
                    url = postgres.jdbcUrl,
                    driver = "org.postgresql.Driver",
                    user = postgres.username,
                    password = postgres.password,
                )
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            postgres.stop()
        }

        private fun flyway(): Flyway =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .load()
    }

    @Test
    fun `migrations apply, re-run cleanly, and seed reference data that Exposed can read`() {
        val first = flyway().migrate()
        assertEquals(5, first.migrationsExecuted, "expected V1 + V2 + V3 + V4 + V5 to apply on a fresh DB")

        // Re-running migrate against an already-migrated DB must be a clean no-op.
        val second = flyway().migrate()
        assertEquals(0, second.migrationsExecuted, "re-running migrate should apply nothing")

        // Exposed connects as the app would and reads the seeded reference rows.
        transaction(db) {
            assertEquals(10, RefSymptomCategories.selectAll().count(), "symptom categories")
            assertEquals(65, RefSymptomOptions.selectAll().count(), "symptom options")
            assertEquals(5, RefPainRegions.selectAll().count(), "pain regions")
            assertEquals(19, RefPainLocations.selectAll().count(), "pain locations")

            // A health-data table exists and is empty on a fresh DB.
            assertEquals(0, DailyLogs.selectAll().count(), "daily_logs starts empty")
        }
    }
}
