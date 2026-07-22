package org.homeflow.integration

import org.flywaydb.core.Flyway
import org.homeflow.db.SyncChanges
import org.homeflow.db.Users
import org.homeflow.modules.sync.ChangeLogRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards the change-log atomicity contract: [ChangeLogRepository.record] refuses to run
 * outside a transaction, and a failure inside the data write's transaction rolls the
 * change row back with it. Requires a running Docker daemon (see __docs/TESTING.md).
 */
class ChangeLogAtomicityTest {
    @Before
    fun resetState() {
        transaction(db) {
            SyncChanges.deleteAll()
            Users.deleteAll()
        }
    }

    @Test
    fun `record outside a transaction throws instead of opening its own`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                changeLog.record(
                    userId = UUID.randomUUID(),
                    entityType = ChangeLogRepository.TYPE_DAY,
                    entityId = UUID.randomUUID(),
                    updatedAt = OffsetDateTime.now(ZoneOffset.UTC),
                    deleted = false,
                )
            }
        assertTrue(ex.message.orEmpty().contains("requires an active transaction"))
    }

    @Test
    fun `record inside a transaction upserts the change row`() {
        val userId = insertUser()
        val entityId = UUID.randomUUID()

        transaction(db) {
            changeLog.record(
                userId,
                ChangeLogRepository.TYPE_DAY,
                entityId,
                OffsetDateTime.now(ZoneOffset.UTC),
                deleted = false,
            )
        }

        assertEquals(1, countChanges(userId))
    }

    @Test
    fun `a failed data write rolls the change row back with it`() {
        val userId = insertUser()

        assertFailsWith<SimulatedWriteFailure> {
            transaction(db) {
                changeLog.record(
                    userId,
                    ChangeLogRepository.TYPE_DAY,
                    UUID.randomUUID(),
                    OffsetDateTime.now(ZoneOffset.UTC),
                    deleted = false,
                )
                throw SimulatedWriteFailure()
            }
        }

        assertEquals(0, countChanges(userId))
    }

    private class SimulatedWriteFailure : RuntimeException("simulated data-write failure")

    private fun insertUser(): UUID =
        transaction(db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            Users.insert {
                it[keycloakSub] = UUID.randomUUID().toString()
                it[createdAt] = now
                it[updatedAt] = now
            } get Users.id
        }

    private fun countChanges(userId: UUID): Long =
        transaction(db) {
            SyncChanges.selectAll().where { SyncChanges.userId eq userId }.count()
        }

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("period_tracker_test")

        private lateinit var db: Database
        private lateinit var changeLog: ChangeLogRepository

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
            changeLog = ChangeLogRepository(db)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            postgres.stop()
        }
    }
}
