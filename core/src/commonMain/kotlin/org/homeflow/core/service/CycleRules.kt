package org.homeflow.core.service

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * When a new cycle starts on [newCycleStart], any currently open cycle is auto-closed
 * with end_date = the day before. Shared by the server (CyclesService) and LocalDataSource
 * (Phase 13) — so the auto-close rule has exactly one definition.
 */
fun autoCloseEndDate(newCycleStart: LocalDate): LocalDate = newCycleStart.minus(DatePeriod(days = 1))

/**
 * A cycle boundary for use in the reconciler — only the fields relevant to
 * open-cycle detection and date-range computation.
 */
data class CycleBoundary(
    val id: String,
    val startDate: LocalDate,
    val endDate: LocalDate?,
)

/**
 * After a sync merge, there must be at most one open cycle. If two or more cycles
 * have `endDate == null`, the one with the **latest** `startDate` stays open; all
 * earlier ones are auto-closed with `endDate = autoCloseEndDate(nextStartDate)`.
 *
 * Returns the entire list with any corrections applied. Unchanged cycles are
 * returned as-is. Pure — no I/O, no clock.
 *
 * Reused by both the server push handler (Phase 16a) and the client reconciler
 * (Phase 16b).
 */
fun reconcileOpenCycles(cycles: List<CycleBoundary>): List<CycleBoundary> {
    val opens = cycles.filter { it.endDate == null }.sortedBy { it.startDate }
    if (opens.size <= 1) return cycles

    val corrections = mutableMapOf<String, LocalDate>()
    for (i in 0 until opens.size - 1) {
        val next = opens[i + 1]
        corrections[opens[i].id] = autoCloseEndDate(next.startDate)
    }

    return cycles.map { c ->
        val corrected = corrections[c.id]
        if (corrected != null) c.copy(endDate = corrected) else c
    }
}
