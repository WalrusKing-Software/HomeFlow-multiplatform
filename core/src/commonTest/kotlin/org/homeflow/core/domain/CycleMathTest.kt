package org.homeflow.core.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CycleMathTest {
    @Test
    fun cycleDayNumberIsOneBasedFromStart() {
        val start = LocalDate(2024, 1, 15)
        assertEquals(1, cycleDayNumber(start, LocalDate(2024, 1, 15)))
        assertEquals(6, cycleDayNumber(start, LocalDate(2024, 1, 20)))
        assertEquals(28, cycleDayNumber(start, LocalDate(2024, 2, 11)))
    }

    @Test
    fun cycleLengthIsInclusiveAndNullForOpenCycle() {
        // 2024-01-15 .. 2024-02-11 is 28 days inclusive (matches the API example).
        assertEquals(28, cycleLength(LocalDate(2024, 1, 15), LocalDate(2024, 2, 11)))
        assertNull(cycleLength(LocalDate(2024, 1, 15), null))
    }

    @Test
    fun predictPhaseWithDefaultsCoversEveryDay() {
        // avgCycle = 28, avgPeriod = 5 -> ovulationDay = 14.
        for (day in 1..5) assertEquals(CyclePhase.MENSTRUATION, predictPhase(day), "day $day")
        for (day in 6..12) assertEquals(CyclePhase.FOLLICULAR, predictPhase(day), "day $day")
        for (day in 13..14) assertEquals(CyclePhase.OVULATION, predictPhase(day), "day $day")
        for (day in 15..28) assertEquals(CyclePhase.LUTEAL, predictPhase(day), "day $day")
    }

    @Test
    fun predictPhaseMenstruationWinsOverOvulationOnShortCycles() {
        // Short cycle: avgCycle = 14 -> ovulationDay = 0; menstruation still wins early days.
        assertEquals(CyclePhase.MENSTRUATION, predictPhase(cycleDay = 1, avgCycleLength = 14, avgPeriodLength = 5))
    }
}
