package org.homeflow.core.validation

import kotlinx.datetime.LocalDate
import org.homeflow.core.dto.PainLocationDto

/** Maximum length of the free-text notes field. */
const val MAX_NOTES_LENGTH: Int = 5000

/** Inclusive severity bounds for a pain location. */
const val MIN_SEVERITY: Int = 1
const val MAX_SEVERITY: Int = 10

/**
 * Result of a shared validation rule. The client runs these for fast UX feedback; the
 * server **re-runs the same rules** as the real security boundary (never trust the client).
 */
sealed interface ValidationResult {
    data object Valid : ValidationResult

    data class Invalid(
        val message: String,
    ) : ValidationResult

    val isValid: Boolean get() = this is Valid
}

/** A cycle start date must not be in the future. */
fun validateCycleStart(
    start: LocalDate,
    today: LocalDate,
): ValidationResult =
    if (start > today) {
        ValidationResult.Invalid("Cycle start date cannot be in the future.")
    } else {
        ValidationResult.Valid
    }

/** A cycle end date must be on or after the start date and must not be in the future. */
fun validateCycleEnd(
    start: LocalDate,
    end: LocalDate,
    today: LocalDate,
): ValidationResult =
    when {
        end < start -> ValidationResult.Invalid("Cycle end date cannot be before the start date.")
        end > today -> ValidationResult.Invalid("Cycle end date cannot be in the future.")
        else -> ValidationResult.Valid
    }

/** A daily-log date must fall within its cycle (on/after start, on/before end if the cycle is closed). */
fun validateDailyLogWithinCycle(
    date: LocalDate,
    cycleStart: LocalDate,
    cycleEnd: LocalDate?,
): ValidationResult =
    when {
        date < cycleStart -> ValidationResult.Invalid("Daily log date is before the cycle start.")
        cycleEnd != null && date > cycleEnd -> ValidationResult.Invalid("Daily log date is after the cycle end.")
        else -> ValidationResult.Valid
    }

/** Notes may be null (cleared) or at most [MAX_NOTES_LENGTH] characters. */
fun validateNotes(notes: String?): ValidationResult =
    if (notes != null && notes.length > MAX_NOTES_LENGTH) {
        ValidationResult.Invalid("Notes must be at most $MAX_NOTES_LENGTH characters.")
    } else {
        ValidationResult.Valid
    }

/** A pain severity may be null (selected but unrated) or in [MIN_SEVERITY]..[MAX_SEVERITY]. */
fun validateSeverity(severity: Int?): ValidationResult =
    if (severity != null && severity !in MIN_SEVERITY..MAX_SEVERITY) {
        ValidationResult.Invalid("Severity must be between $MIN_SEVERITY and $MAX_SEVERITY.")
    } else {
        ValidationResult.Valid
    }

/** A pain log: no duplicate locations, and every severity within range. */
fun validatePainLocations(locations: List<PainLocationDto>): ValidationResult {
    val ids = locations.map { it.locationId }
    if (ids.size != ids.toSet().size) {
        return ValidationResult.Invalid("Duplicate pain location.")
    }
    for (location in locations) {
        val severityResult = validateSeverity(location.severity)
        if (severityResult is ValidationResult.Invalid) return severityResult
    }
    return ValidationResult.Valid
}

/**
 * The dashboard category order must contain every known category slug exactly once —
 * no duplicates, no unknown slugs, no omissions.
 */
fun validateCategoryOrder(
    order: List<String>,
    allSlugs: Set<String>,
): ValidationResult {
    val distinct = order.toSet()
    if (order.size != distinct.size) {
        return ValidationResult.Invalid("Category order contains duplicate slugs.")
    }
    if (distinct != allSlugs) {
        val missing = allSlugs - distinct
        val unknown = distinct - allSlugs
        val detail =
            buildList {
                if (missing.isNotEmpty()) add("missing ${missing.sorted()}")
                if (unknown.isNotEmpty()) add("unknown ${unknown.sorted()}")
            }.joinToString("; ")
        return ValidationResult.Invalid("Category order must list every category exactly once ($detail).")
    }
    return ValidationResult.Valid
}
