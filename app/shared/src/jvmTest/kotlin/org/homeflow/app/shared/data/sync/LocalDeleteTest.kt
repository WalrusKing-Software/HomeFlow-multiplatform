package org.homeflow.app.shared.data.sync

import kotlinx.coroutines.test.runTest
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.local.LocalBootstrap
import org.homeflow.app.shared.data.local.LocalDataSource
import org.homeflow.app.shared.data.local.TestDbHelper
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 16b — the local delete operations (`deleteCycle`/`deleteDay`) that produce
 * tombstones + outbox `delete` entries the [SyncEngine] later propagates (D-16b.6).
 *
 * Asserts: tombstoned aggregates disappear from live reads, a cycle delete cascades to
 * its days, every delete enqueues an outbox `delete`, and deleting something absent →
 * `RESOURCE_NOT_FOUND`.
 */
class LocalDeleteTest {
    private fun newStore(): Pair<HomeFlowDb, LocalDataSource> {
        val db = TestDbHelper.inMemory()
        LocalBootstrap.seed(db)
        return db to LocalDataSource(db)
    }

    private fun HomeFlowDb.dayId(date: String): String? =
        dailyLogsQueries.selectByDate(LocalBootstrap.LOCAL_USER_ID, date).executeAsOneOrNull()?.id

    private fun HomeFlowDb.pending() = LocalOutbox(this).pending()

    @Test
    fun `deleteCycle tombstones the cycle, cascades its days, and hides them from live reads`() =
        runTest {
            val (db, ds) = newStore()
            val cycleId = (ds.createCycle("2024-03-01") as ApiResult.Success).value.id
            assertIs<ApiResult.Success<*>>(ds.createDailyLog("2024-03-05", cycleId))
            val dayId = db.dayId("2024-03-05")!!

            assertIs<ApiResult.Success<*>>(ds.deleteCycle(cycleId))

            // Live reads no longer surface the cycle or its cascaded day.
            assertIs<ApiResult.Failure>(ds.getCurrentCycle())
            assertEquals(0, (ds.getCycles() as ApiResult.Success).value.cycles.size)
            assertIs<ApiResult.Failure>(ds.getDailyLog("2024-03-05"))

            // The outbox carries a delete for the cycle AND its cascaded day.
            val deletes = db.pending().filter { it.op == OP_DELETE }
            assertTrue(deletes.any { it.entity_type == SyncEngine.ENTITY_CYCLE && it.entity_id == cycleId })
            assertTrue(deletes.any { it.entity_type == SyncEngine.ENTITY_DAY && it.entity_id == dayId })
        }

    @Test
    fun `deleteDay tombstones the day, hides it from live reads, and enqueues a delete`() =
        runTest {
            val (db, ds) = newStore()
            val cycleId = (ds.createCycle("2024-03-01") as ApiResult.Success).value.id
            assertIs<ApiResult.Success<*>>(ds.createDailyLog("2024-03-05", cycleId))
            val dayId = db.dayId("2024-03-05")!!

            assertIs<ApiResult.Success<*>>(ds.deleteDay("2024-03-05"))

            assertIs<ApiResult.Failure>(ds.getDailyLog("2024-03-05"))
            // The cycle itself is untouched.
            assertEquals(1, (ds.getCycles() as ApiResult.Success).value.cycles.size)

            val deletes = db.pending().filter { it.op == OP_DELETE }
            assertTrue(deletes.any { it.entity_type == SyncEngine.ENTITY_DAY && it.entity_id == dayId })
        }

    @Test
    fun `deleteCycle on an unknown id returns RESOURCE_NOT_FOUND`() =
        runTest {
            val (_, ds) = newStore()
            val result = ds.deleteCycle("00000000-0000-0000-0000-000000000000")
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, result.code)
        }

    @Test
    fun `deleteDay on a date with no log returns RESOURCE_NOT_FOUND`() =
        runTest {
            val (_, ds) = newStore()
            val result = ds.deleteDay("2024-03-05")
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.RESOURCE_NOT_FOUND, result.code)
        }
}
