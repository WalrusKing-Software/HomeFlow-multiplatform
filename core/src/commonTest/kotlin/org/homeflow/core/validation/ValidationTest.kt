package org.homeflow.core.validation

import kotlinx.datetime.LocalDate
import org.homeflow.core.dto.PainLocationDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidationTest {
    private val today = LocalDate(2024, 6, 1)

    @Test
    fun cycleStartRejectsFutureDates() {
        assertTrue(validateCycleStart(LocalDate(2024, 5, 31), today).isValid)
        assertTrue(validateCycleStart(today, today).isValid)
        assertFalse(validateCycleStart(LocalDate(2024, 6, 2), today).isValid)
    }

    @Test
    fun cycleEndMustBeOnOrAfterStartAndNotFuture() {
        val start = LocalDate(2024, 5, 1)
        assertTrue(validateCycleEnd(start, LocalDate(2024, 5, 20), today).isValid)
        assertFalse(validateCycleEnd(start, LocalDate(2024, 4, 30), today).isValid) // before start
        assertFalse(validateCycleEnd(start, LocalDate(2024, 6, 2), today).isValid) // future
    }

    @Test
    fun dailyLogMustFallWithinItsCycle() {
        val start = LocalDate(2024, 5, 1)
        assertTrue(validateDailyLogWithinCycle(LocalDate(2024, 5, 10), start, LocalDate(2024, 5, 28)).isValid)
        assertTrue(validateDailyLogWithinCycle(LocalDate(2024, 5, 10), start, null).isValid) // open cycle
        assertFalse(validateDailyLogWithinCycle(LocalDate(2024, 4, 30), start, null).isValid) // before start
        assertFalse(validateDailyLogWithinCycle(LocalDate(2024, 5, 29), start, LocalDate(2024, 5, 28)).isValid)
    }

    @Test
    fun notesRespectMaxLength() {
        assertTrue(validateNotes(null).isValid)
        assertTrue(validateNotes("a".repeat(MAX_NOTES_LENGTH)).isValid)
        assertFalse(validateNotes("a".repeat(MAX_NOTES_LENGTH + 1)).isValid)
    }

    @Test
    fun severityRangeIsOneToTenOrNull() {
        assertTrue(validateSeverity(null).isValid)
        assertTrue(validateSeverity(MIN_SEVERITY).isValid)
        assertTrue(validateSeverity(MAX_SEVERITY).isValid)
        assertFalse(validateSeverity(0).isValid)
        assertFalse(validateSeverity(MAX_SEVERITY + 1).isValid)
    }

    @Test
    fun painLocationsRejectDuplicatesAndBadSeverity() {
        assertTrue(
            validatePainLocations(
                listOf(PainLocationDto("loc-a", 5), PainLocationDto("loc-b", null)),
            ).isValid,
        )
        assertFalse(
            validatePainLocations(
                listOf(PainLocationDto("loc-a", 5), PainLocationDto("loc-a", 3)),
            ).isValid,
        )
        assertFalse(validatePainLocations(listOf(PainLocationDto("loc-a", 99))).isValid)
    }

    @Test
    fun categoryOrderMustBeEverySlugExactlyOnce() {
        val all = setOf("emotions", "energy", "sleep_quality")
        assertTrue(validateCategoryOrder(listOf("energy", "emotions", "sleep_quality"), all).isValid)
        assertFalse(validateCategoryOrder(listOf("energy", "emotions"), all).isValid) // missing
        assertFalse(validateCategoryOrder(listOf("energy", "energy", "emotions", "sleep_quality"), all).isValid)
        assertFalse(validateCategoryOrder(listOf("energy", "emotions", "sleep_quality", "extra"), all).isValid)
    }
}
