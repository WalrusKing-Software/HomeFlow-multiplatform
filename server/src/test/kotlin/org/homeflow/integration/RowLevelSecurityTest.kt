package org.homeflow.integration

import kotlinx.datetime.LocalDate
import org.homeflow.db.Cycles
import org.homeflow.db.Users
import org.homeflow.db.userScopedTransaction
import org.homeflow.integration.IntegrationHarness.migrateAndConnect
import org.homeflow.integration.IntegrationHarness.newPostgres
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.containers.PostgreSQLContainer
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Issue #78 — verifies the PostgreSQL row-level security backstop added in
 * V5__row_level_security.sql actually isolates users at the database, independent of
 * the app-layer `AND user_id = ...` filter.
 *
 * The catch: the Testcontainers bootstrap user is a superuser, and superusers bypass
 * RLS unconditionally — so a test connected as that user would prove nothing. This
 * test therefore provisions a **restricted, non-superuser** role (the analogue of the
 * production `POSTGRES_APP_USER`) with only DML grants, connects a second Exposed
 * [Database] as that role, and exercises RLS through it. Data is seeded via the
 * superuser connection (RLS bypassed) so both users' rows exist regardless of scope.
 *
 * Requires a running Docker daemon (see __docs/TESTING.md).
 */
class RowLevelSecurityTest {
    companion object {
        private const val APP_ROLE = "rls_app_test"
        private const val APP_PASSWORD = "rls_app_test_pw"

        /** Every table V5 places under RLS — kept in lockstep with the migration. */
        private val RLS_TABLES =
            listOf(
                "cycles",
                "daily_logs",
                "daily_log_energy",
                "daily_log_flow",
                "daily_log_collection",
                "daily_log_emotions",
                "daily_log_sleep",
                "daily_log_discharge",
                "daily_log_skin",
                "daily_log_digestion",
                "daily_log_mind",
                "daily_log_sex",
                "pain_logs",
                "pain_log_locations",
                "user_dashboard_preferences",
                "sync_changes",
            )

        private lateinit var postgres: PostgreSQLContainer<*>

        /** Superuser (table owner) connection — used only to migrate, seed, and read catalogs. */
        private lateinit var ownerDb: Database

        /** Restricted non-superuser connection — subject to RLS, like the production app role. */
        private lateinit var appDb: Database

        val userA: UUID = UUID.randomUUID()
        val userB: UUID = UUID.randomUUID()
        val cycleA: UUID = UUID.randomUUID()
        val cycleB: UUID = UUID.randomUUID()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = newPostgres()
            postgres.start()
            ownerDb = migrateAndConnect(postgres)

            // Provision the restricted DML-only role (mirrors infra/postgres/init/02_create_app_role.sh).
            transaction(ownerDb) {
                exec("CREATE ROLE $APP_ROLE LOGIN PASSWORD '$APP_PASSWORD'")
                exec("GRANT USAGE ON SCHEMA public TO $APP_ROLE")
                exec("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $APP_ROLE")
                exec("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO $APP_ROLE")
            }

            // Seed two users, one cycle each, as the superuser (RLS bypassed).
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val start = LocalDate(2026, 1, 1)
            transaction(ownerDb) {
                listOf(userA to "userA-sub", userB to "userB-sub").forEach { (uid, sub) ->
                    Users.insert {
                        it[id] = uid
                        it[keycloakSub] = sub
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
                listOf(cycleA to userA, cycleB to userB).forEach { (cid, uid) ->
                    Cycles.insert {
                        it[id] = cid
                        it[userId] = uid
                        it[startDate] = start
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
            }

            appDb =
                Database.connect(
                    url = postgres.jdbcUrl,
                    driver = "org.postgresql.Driver",
                    user = APP_ROLE,
                    password = APP_PASSWORD,
                )
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            postgres.stop()
        }
    }

    @Test
    fun `an unscoped transaction sees no rows (deny-by-default)`() {
        // No app.current_user_id set → app_current_user_id() is NULL → the policy matches nothing.
        // A query that "forgets" both the app filter AND the scope leaks nothing.
        val rows = transaction(appDb) { Cycles.selectAll().map { it[Cycles.id] } }
        assertEquals(emptyList(), rows, "an unscoped restricted-role query must return zero rows")
    }

    @Test
    fun `a scoped query that omits the app-layer user filter still returns only the scoped user's rows`() {
        // Deliberately NO `.where { Cycles.userId eq userA }` — this simulates a repository
        // query that forgot the app-layer filter. RLS alone must keep userB's row invisible.
        val visible = userScopedTransaction(appDb, userA) { Cycles.selectAll().map { it[Cycles.id] } }
        assertEquals(listOf(cycleA), visible, "RLS must expose only the scoped user's cycle")
        assertTrue(cycleB !in visible, "userB's cycle must never be visible while scoped to userA")
    }

    @Test
    fun `writing a row for another user is rejected by the RLS WITH CHECK`() {
        // Scoped to userA, attempt to insert a cycle owned by userB. The policy's WITH CHECK
        // clause must reject it even though the app-layer code "chose" the wrong user_id.
        assertFailsWith<Exception> {
            userScopedTransaction(appDb, userA) {
                Cycles.insert {
                    it[id] = UUID.randomUUID()
                    it[userId] = userB
                    it[startDate] = LocalDate(2026, 2, 1)
                    it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
                    it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
                }
            }
        }

        // And the errant row must not have landed.
        val bCycles = userScopedTransaction(appDb, userB) { Cycles.selectAll().map { it[Cycles.id] } }
        assertEquals(listOf(cycleB), bCycles, "no extra row should exist for userB")
    }

    @Test
    fun `every user-scoped table has RLS enabled and forced`() {
        // FORCE ROW LEVEL SECURITY makes the policy bind the table owner too, not just the
        // restricted role — the second half of issue #78's DML-role/FORCE requirement.
        transaction(ownerDb) {
            RLS_TABLES.forEach { table ->
                val flags =
                    exec(
                        "SELECT relrowsecurity, relforcerowsecurity FROM pg_class " +
                            "WHERE relname = '$table' AND relnamespace = 'public'::regnamespace",
                    ) { rs -> if (rs.next()) rs.getBoolean(1) to rs.getBoolean(2) else null }
                        ?: error("table $table not found in pg_class")
                assertTrue(flags.first, "$table must have ROW LEVEL SECURITY enabled")
                assertTrue(flags.second, "$table must have FORCE ROW LEVEL SECURITY")
            }
        }
    }
}
