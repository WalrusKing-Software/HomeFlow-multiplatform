package org.homeflow.app.shared.data.local

import kotlinx.coroutines.test.runTest
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.db.HomeFlowDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that [LocalExporter] produces a [org.homeflow.core.dto.HomeFlowExport] that is
 * entirely slug-keyed and UUID-free — the invariant required for the interchange format.
 */
class LocalExporterTest {
    private data class Fixture(
        val db: HomeFlowDb,
        val ds: LocalDataSource,
        val refData: LocalRefData,
        val exporter: LocalExporter,
    )

    private fun fixture(): Fixture {
        val db = TestDbHelper.inMemory()
        LocalBootstrap.seed(db)
        val userId = LocalBootstrap.LOCAL_USER_ID
        val refData = LocalRefData(db)
        val cyclesStore = LocalCyclesStore(db, userId)
        val subsStore = LocalSubsStore(db, refData)
        val logsStore = LocalDailyLogsStore(db, userId, cyclesStore, subsStore, refData)
        val ds = LocalDataSource(db)
        val exporter = LocalExporter(db, refData, logsStore)
        return Fixture(db, ds, refData, exporter)
    }

    @Test
    fun `export with no cycles returns empty export`() {
        val f = fixture()
        val export = f.exporter.export()
        assertTrue(export.cycles.isEmpty())
        assertTrue(export.days.isEmpty())
    }

    @Test
    fun `export maps flow option-id to slug`() =
        runTest {
            val f = fixture()
            val catId =
                f.refData.categoryIdBySlug("blood_flow")
                    ?: error("blood_flow category not found in seed")
            val flowId = f.refData.optionIdsForCategory(catId).first()

            val cycle = f.ds.createCycle("2024-03-01").successValue()
            f.ds.createDailyLog("2024-03-01", cycle.id)
            f.ds.putOptionId("2024-03-01", "flow", flowId)

            val export = f.exporter.export()
            assertEquals(1, export.cycles.size)
            assertEquals(1, export.days.size)

            val day = export.days.first()
            assertNotNull(day.flow, "flow must be exported")
            assertIsSlug(day.flow!!)
        }

    @Test
    fun `export day has cycleStartDate matching cycle`() =
        runTest {
            val f = fixture()
            val catId =
                f.refData.categoryIdBySlug("blood_flow")
                    ?: error("blood_flow category not found in seed")
            val flowId = f.refData.optionIdsForCategory(catId).first()

            val cycle = f.ds.createCycle("2024-04-10").successValue()
            f.ds.createDailyLog("2024-04-12", cycle.id)
            f.ds.putOptionId("2024-04-12", "flow", flowId)

            val export = f.exporter.export()
            val day = export.days.first()
            assertEquals("2024-04-10", day.cycleStartDate)
            assertEquals("2024-04-12", day.date)
        }

    @Test
    fun `export multi-value fields contain slugs not UUIDs`() =
        runTest {
            val f = fixture()
            val catId =
                f.refData.categoryIdBySlug("emotions")
                    ?: error("emotions category not found in seed")
            val emotionId = f.refData.optionIdsForCategory(catId).first()

            val cycle = f.ds.createCycle("2024-05-01").successValue()
            f.ds.createDailyLog("2024-05-02", cycle.id)
            f.ds.putOptionIds("2024-05-02", "emotions", listOf(emotionId))

            val export = f.exporter.export()
            val day = export.days.firstOrNull { it.date == "2024-05-02" }
            assertNotNull(day)
            assertTrue(day.emotions.isNotEmpty(), "emotions should have at least one entry")
            day.emotions.forEach { assertIsSlug(it) }
        }

    // --- helpers ---

    private fun <T> ApiResult<T>.successValue(): T = (this as ApiResult.Success<T>).value

    private fun assertIsSlug(value: String) {
        val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        assertTrue(!uuidPattern.matches(value), "Expected slug but got UUID: $value")
    }
}
