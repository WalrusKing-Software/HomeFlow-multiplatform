package org.homeflow.app.shared.data.local

import kotlinx.coroutines.test.runTest
import org.homeflow.app.shared.data.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parity tests — verifies LocalDataSource behavior mirrors documented RemoteDataSource
 * contract (same return types, same absent-resource semantics).
 */
class LocalDataSourceParityTest {

    private fun ds() = TestDbHelper.seededDataSource()

    @Test
    fun `empty category fields are null not empty list in daily log`() = runTest {
        val ds = ds()
        val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
        ds.createDailyLog("2024-01-15", cycle.id)

        val log = (ds.getDailyLog("2024-01-15") as ApiResult.Success).value
        // All symptom fields start null (not empty list).
        assertNull(log.emotions)
        assertNull(log.sleep)
        assertNull(log.energy)
        assertNull(log.sex)
        assertNull(log.discharge)
        assertNull(log.skin)
        assertNull(log.digestion)
        assertNull(log.flow)
        assertNull(log.collection)
        assertNull(log.mind)
        assertNull(log.pain)
        assertNull(log.notes)
    }

    @Test
    fun `analytics getters always return Success even with no data`() = runTest {
        val ds = ds()
        assertIs<ApiResult.Success<*>>(ds.getCycleStats())
        assertIs<ApiResult.Success<*>>(ds.getPeriodLengthChart())
        assertIs<ApiResult.Success<*>>(ds.getOvulationPrediction())
        assertIs<ApiResult.Success<*>>(ds.getSleepPredictions())
    }

    @Test
    fun `getCycleStats returns all nulls with fewer than 2 closed cycles`() = runTest {
        val ds = ds()
        ds.createCycle("2024-01-15")  // open cycle, not counted

        val stats = (ds.getCycleStats() as ApiResult.Success).value
        assertNull(stats.averageCycleLength)
        assertNull(stats.cycleVariation)
        assertNull(stats.averagePeriodLength)
    }

    @Test
    fun `getCycles returns newest cycle first`() = runTest {
        val ds = ds()
        ds.createCycle("2024-01-15")
        ds.createCycle("2024-02-12")
        ds.createCycle("2024-03-10")

        val cycles = (ds.getCycles() as ApiResult.Success).value.cycles
        assertEquals(3, cycles.size)
        // newest first
        assertEquals("2024-03-10", cycles[0].startDate)
        assertEquals("2024-02-12", cycles[1].startDate)
        assertEquals("2024-01-15", cycles[2].startDate)
    }

    @Test
    fun `putOptionIds replacing options removes previous selection`() = runTest {
        val ds = ds()
        val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
        ds.createDailyLog("2024-01-15", cycle.id)

        val cats = (ds.getSymptomCategories() as ApiResult.Success).value
        val emotionsCat = cats.categories.find { it.slug == "emotions" }!!
        val fineId = emotionsCat.options.find { it.slug == "fine" }!!.id
        val anxiousId = emotionsCat.options.find { it.slug == "anxious" }!!.id
        val sadId = emotionsCat.options.find { it.slug == "sad_depressed" }!!.id

        ds.putOptionIds("2024-01-15", "emotions", listOf(fineId, anxiousId))
        ds.putOptionIds("2024-01-15", "emotions", listOf(sadId))

        val log = (ds.getDailyLog("2024-01-15") as ApiResult.Success).value
        assertEquals(listOf(sadId), log.emotions)
    }

    @Test
    fun `sex payload stored as option ids and read back correctly`() = runTest {
        val ds = ds()
        val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
        ds.createDailyLog("2024-01-15", cycle.id)

        val cats = (ds.getSymptomCategories() as ApiResult.Success).value
        val sexCat = cats.categories.find { it.slug == "sex" }!!
        val protectedId = sexCat.options.find { it.slug == "protected" }!!.id
        val orgasmId = sexCat.options.find { it.slug == "orgasm" }!!.id

        ds.putOptionIds("2024-01-15", "sex", listOf(protectedId, orgasmId))

        val log = (ds.getDailyLog("2024-01-15") as ApiResult.Success).value
        assertNotNull(log.sex)
        assertEquals(setOf(protectedId, orgasmId), log.sex!!.toSet())
    }

    @Test
    fun `putOptionIds with empty list clears the selection`() = runTest {
        val ds = ds()
        val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
        ds.createDailyLog("2024-01-15", cycle.id)

        val cats = (ds.getSymptomCategories() as ApiResult.Success).value
        val emotionsCat = cats.categories.find { it.slug == "emotions" }!!
        val fineId = emotionsCat.options.find { it.slug == "fine" }!!.id

        ds.putOptionIds("2024-01-15", "emotions", listOf(fineId))
        ds.putOptionIds("2024-01-15", "emotions", emptyList())

        val log = (ds.getDailyLog("2024-01-15") as ApiResult.Success).value
        assertNull(log.emotions)  // empty cleared = null in DTO
    }

    @Test
    fun `daily log id and cycleId survive round-trip`() = runTest {
        val ds = ds()
        val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
        ds.createDailyLog("2024-01-15", cycle.id)

        val log = (ds.getDailyLog("2024-01-15") as ApiResult.Success).value
        assertTrue(log.id.isNotBlank())
        assertEquals(cycle.id, log.cycleId)
    }

    @Test
    fun `closeCycle on already-closed cycle updates end date`() = runTest {
        val ds = ds()
        val cycle = (ds.createCycle("2024-01-15") as ApiResult.Success).value
        ds.closeCycle(cycle.id, "2024-02-10")
        // Re-close with different end date.
        val result = ds.closeCycle(cycle.id, "2024-02-11")
        assertIs<ApiResult.Success<*>>(result)
        assertEquals("2024-02-11", (result as ApiResult.Success).value.endDate)
    }
}
