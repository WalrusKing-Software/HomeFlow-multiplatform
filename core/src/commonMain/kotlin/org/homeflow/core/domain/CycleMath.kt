package org.homeflow.core.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/*
 * Pure cycle math, ported once from the web app's `cycle.ts` + `analytics.service.ts`
 * and deduplicated (SHARED-MODULE.md). No I/O — the server and clients both call
 * these over data they have already fetched.
 */

/** Fallback cycle length (days) when there is no per-user average yet. */
const val DEFAULT_CYCLE_LENGTH: Int = 28

/** Fallback period length (days) when there is no per-user average yet. */
const val DEFAULT_PERIOD_LENGTH: Int = 5

/**
 * Days from ovulation to the next period start. Ovulation is estimated as
 * `nextPeriodStart - LUTEAL_PHASE_LENGTH`, i.e. cycle day `cycleLength - 14`.
 */
const val LUTEAL_PHASE_LENGTH: Int = 14

/**
 * 1-based day number within a cycle: the start date is day 1.
 * Returns values < 1 if [on] precedes [start] (caller's responsibility to avoid).
 */
fun cycleDayNumber(
    start: LocalDate,
    on: LocalDate,
): Int = start.daysUntil(on) + 1

/**
 * Inclusive length of a cycle in days (start and end both counted), or null for the
 * open cycle (no end date). A cycle 2024-01-15 → 2024-02-11 is 28 days.
 *
 * Reconciliation note: the data-model prose says `end - start`, but the API example and
 * SHARED-MODULE.md specify the **inclusive** count (28 for the example above). Inclusive
 * is the single definition used everywhere; do not reintroduce the exclusive variant.
 */
fun cycleLength(
    start: LocalDate,
    end: LocalDate?,
): Int? = end?.let { start.daysUntil(it) + 1 }

/**
 * Predict the cycle phase for a given 1-based [cycleDay].
 *
 * Reconciliation note (SHARED-MODULE.md): the web app had two divergent ovulation
 * windows. The single definition here matches the API's phase ranges:
 *  - Menstruation: days `1..avgPeriod`
 *  - Ovulation:    the 2 days `[ovulationDay - 1, ovulationDay]`, where
 *                  `ovulationDay = avgCycle - LUTEAL_PHASE_LENGTH`
 *  - Luteal:       days after `ovulationDay`
 *  - Follicular:   everything in between
 *
 * Menstruation is checked first, so a short cycle whose ovulation window would overlap
 * the period still reports menstruation for those early days.
 */
fun predictPhase(
    cycleDay: Int,
    avgCycleLength: Int = DEFAULT_CYCLE_LENGTH,
    avgPeriodLength: Int = DEFAULT_PERIOD_LENGTH,
): CyclePhase {
    val ovulationDay = avgCycleLength - LUTEAL_PHASE_LENGTH
    return when {
        cycleDay <= avgPeriodLength -> CyclePhase.MENSTRUATION
        cycleDay in (ovulationDay - 1)..ovulationDay -> CyclePhase.OVULATION
        cycleDay > ovulationDay -> CyclePhase.LUTEAL
        else -> CyclePhase.FOLLICULAR
    }
}
