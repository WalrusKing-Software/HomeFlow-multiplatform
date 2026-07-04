package org.homeflow.app.shared.data.local

import kotlinx.coroutines.test.runTest
import org.homeflow.app.shared.data.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalAnalyticsTest {
    private fun ds() = TestDbHelper.seededDataSource()

    private suspend fun LocalDataSource.createClosedCycleWithFlow(
        startDate: String,
        endDate: String,
        flowDates: List<String>,
    ) {
        val cats = (getSymptomCategories() as ApiResult.Success).value
        val flowCat = cats.categories.find { it.slug == "blood_flow" }!!
        val mediumId = flowCat.options.find { it.slug == "medium" }!!.id

        val cycle = (createCycle(startDate) as ApiResult.Success).value
        for (date in flowDates) {
            createDailyLog(date, cycle.id)
            putOptionId(date, "flow", mediumId)
        }
        closeCycle(cycle.id, endDate)
    }

    @Test
    fun `cycleStats returns nulls with zero closed cycles`() =
        runTest {
            val stats = (ds().getCycleStats() as ApiResult.Success).value
            assertNull(stats.averageCycleLength)
        }

    @Test
    fun `cycleStats returns nulls with one closed cycle (below threshold)`() =
        runTest {
            val ds = ds()
            ds.createClosedCycleWithFlow(
                "2024-01-15",
                "2024-02-11",
                listOf("2024-01-15", "2024-01-16", "2024-01-17", "2024-01-18", "2024-01-19"),
            )
            val stats = (ds.getCycleStats() as ApiResult.Success).value
            assertNull(stats.averageCycleLength)
        }

    @Test
    fun `cycleStats computes average with two closed cycles`() =
        runTest {
            val ds = ds()
            ds.createClosedCycleWithFlow(
                "2024-01-15",
                "2024-02-11",
                listOf("2024-01-15", "2024-01-16", "2024-01-17", "2024-01-18", "2024-01-19"),
            )
            ds.createClosedCycleWithFlow(
                "2024-02-12",
                "2024-03-12",
                listOf("2024-02-12", "2024-02-13", "2024-02-14"),
            )

            val stats = (ds.getCycleStats() as ApiResult.Success).value
            assertNotNull(stats.averageCycleLength)
            // (28 + 30) / 2 = 29
            assertEquals(29, stats.averageCycleLength)
            assertNotNull(stats.averagePeriodLength)
        }

    @Test
    fun `periodLengthChart returns one point per closed cycle oldest first`() =
        runTest {
            val ds = ds()
            ds.createClosedCycleWithFlow(
                "2024-01-15",
                "2024-02-11",
                listOf("2024-01-15", "2024-01-16"),
            )
            ds.createClosedCycleWithFlow(
                "2024-02-12",
                "2024-03-12",
                listOf("2024-02-12"),
            )

            val chart = (ds.getPeriodLengthChart() as ApiResult.Success).value
            assertEquals(2, chart.dataPoints.size)
            assertEquals("2024-01-15", chart.dataPoints[0].cycleStartDate)
            assertEquals(2, chart.dataPoints[0].bleedingDays)
            assertEquals("2024-02-12", chart.dataPoints[1].cycleStartDate)
            assertEquals(1, chart.dataPoints[1].bleedingDays)
        }

    @Test
    fun `getOvulationPrediction returns null predictions with insufficient data`() =
        runTest {
            val stats = (ds().getOvulationPrediction() as ApiResult.Success).value
            assertNull(stats.predictions)
            assertNull(stats.averageCycleLength)
        }

    @Test
    fun `getOvulationPrediction returns 3 predictions with sufficient data`() =
        runTest {
            val ds = ds()
            ds.createClosedCycleWithFlow("2024-01-15", "2024-02-11", listOf("2024-01-15"))
            ds.createClosedCycleWithFlow("2024-02-12", "2024-03-12", listOf("2024-02-12"))

            val result = (ds.getOvulationPrediction() as ApiResult.Success).value
            assertNotNull(result.predictions)
            assertEquals(3, result.predictions!!.size)
        }

    @Test
    fun `getSleepPredictions always returns success`() =
        runTest {
            val result = ds().getSleepPredictions()
            assertIs<ApiResult.Success<*>>(result)
        }

    @Test
    fun `getSleepPredictions returns null phases with insufficient data`() =
        runTest {
            val predictions = (ds().getSleepPredictions() as ApiResult.Success).value
            assertNull(predictions.phases.menstruation)
            assertNull(predictions.phases.follicular)
            assertNull(predictions.phases.ovulation)
            assertNull(predictions.phases.luteal)
        }
}
