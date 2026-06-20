package org.homeflow.core.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyticsTest {
    @Test
    fun cycleStatsNullWithFewerThanTwoCycles() {
        val stats = cycleStats(listOf(ClosedCycleInput(LocalDate(2024, 1, 1), LocalDate(2024, 1, 28), 5)))
        assertNull(stats.averageCycleLength)
        assertNull(stats.cycleVariation)
        assertNull(stats.averagePeriodLength)
    }

    @Test
    fun cycleStatsComputesExactValues() {
        val cycles =
            listOf(
                // inclusive length 28, 5 bleeding days
                ClosedCycleInput(LocalDate(2024, 1, 1), LocalDate(2024, 1, 28), 5),
                // inclusive length 30, 7 bleeding days
                ClosedCycleInput(LocalDate(2024, 1, 29), LocalDate(2024, 2, 27), 7),
            )
        val stats = cycleStats(cycles)
        assertEquals(29, stats.averageCycleLength) // mean(28, 30)
        assertEquals(1.0, stats.cycleVariation) // population stddev of (28, 30)
        assertEquals(6, stats.averagePeriodLength) // mean(5, 7)
    }

    @Test
    fun ovulationPredictionsMatchTheApiExample() {
        val predictions = ovulationPredictions(LocalDate(2024, 1, 15), avgCycleLength = 28)
        assertEquals(3, predictions.size)
        assertEquals("2024-02-12", predictions[0].predictedPeriodStart)
        assertEquals("2024-01-29", predictions[0].predictedOvulationDate)
        assertEquals("2024-03-11", predictions[1].predictedPeriodStart)
        assertEquals("2024-02-26", predictions[1].predictedOvulationDate)
        assertEquals("2024-04-08", predictions[2].predictedPeriodStart)
        assertEquals("2024-03-25", predictions[2].predictedOvulationDate)
    }

    @Test
    fun periodLengthChartIsOrderedOldestFirst() {
        val chart =
            periodLengthChart(
                listOf(
                    ClosedCycleInput(LocalDate(2024, 2, 1), LocalDate(2024, 2, 28), 6),
                    ClosedCycleInput(LocalDate(2024, 1, 1), LocalDate(2024, 1, 28), 5),
                ),
            )
        assertEquals(listOf("2024-01-01", "2024-02-01"), chart.dataPoints.map { it.cycleStartDate })
        assertEquals(listOf(5, 6), chart.dataPoints.map { it.bleedingDays })
    }

    @Test
    fun bucketSleepByPhaseRanksTopOptionsAndGatesOnSampleSize() {
        val menstruationDays =
            listOf(
                SleepDayInput(CyclePhase.MENSTRUATION, listOf("a", "b")),
                SleepDayInput(CyclePhase.MENSTRUATION, listOf("a")),
                SleepDayInput(CyclePhase.MENSTRUATION, listOf("a", "c")),
                SleepDayInput(CyclePhase.MENSTRUATION, listOf("b")),
                SleepDayInput(CyclePhase.MENSTRUATION, listOf("a", "b")),
            )
        // Only 4 follicular days -> below the min sample size, so null.
        val follicularDays = List(4) { SleepDayInput(CyclePhase.FOLLICULAR, listOf("a")) }

        val result = bucketSleepByPhase(menstruationDays + follicularDays)

        val menstruation = result.phases.menstruation
        assertEquals(listOf("a", "b", "c"), menstruation?.mostCommon) // a:4, b:3, c:1
        assertEquals(5, menstruation?.sampleSize)
        assertNull(result.phases.follicular)
        assertNull(result.phases.ovulation)
    }
}
