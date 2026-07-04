package org.homeflow.core.service

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CycleRulesTest {
    @Test
    fun autoCloseEndDateIsOneDayBeforeTheNewCycleStart() {
        assertEquals(LocalDate(2024, 3, 9), autoCloseEndDate(LocalDate(2024, 3, 10)))
    }

    @Test
    fun autoCloseEndDateHandlesYearBoundary() {
        assertEquals(LocalDate(2023, 12, 31), autoCloseEndDate(LocalDate(2024, 1, 1)))
    }
}
