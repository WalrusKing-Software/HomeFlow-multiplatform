package org.homeflow.core.domain

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.homeflow.core.dto.CycleStatsDto
import org.homeflow.core.dto.OvulationPrediction
import org.homeflow.core.dto.PeriodLengthChartDto
import org.homeflow.core.dto.PeriodLengthPoint
import org.homeflow.core.dto.PhaseSleepPrediction
import org.homeflow.core.dto.SleepPhases
import org.homeflow.core.dto.SleepPredictionsDto
import kotlin.math.round
import kotlin.math.sqrt

/** Minimum closed cycles before cycle-length stats are meaningful. */
const val MIN_CYCLES_FOR_STATS: Int = 2

/** Window (days, ~6 months) over which cycle-length variation is measured. */
const val CYCLE_VARIATION_WINDOW_DAYS: Int = 183

/** Minimum days of data in a phase before a sleep prediction is produced. */
const val MIN_SLEEP_SAMPLE_SIZE: Int = 5

/** How many sleep options the prediction reports per phase. */
const val TOP_SLEEP_OPTIONS: Int = 3

/** How many future cycles the ovulation prediction covers. */
const val OVULATION_PREDICTION_COUNT: Int = 3

private const val ONE_DECIMAL_FACTOR: Double = 10.0

/**
 * A closed cycle plus its bleeding-day count, as fetched by the server. `bleedingDays` is
 * the number of distinct dates in the cycle that carried a flow log.
 */
data class ClosedCycleInput(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val bleedingDays: Int,
)

/** One logged day's cycle phase + the sleep option ids recorded that day. */
data class SleepDayInput(
    val phase: CyclePhase,
    val optionIds: List<String>,
)

/**
 * Summary cycle statistics. Returns all-null when there are fewer than
 * [MIN_CYCLES_FOR_STATS] closed cycles.
 *
 * - averageCycleLength: mean inclusive cycle length, rounded to the nearest day.
 * - cycleVariation: population standard deviation of inclusive cycle lengths over the
 *   last ~6 months (relative to the most recent cycle start), rounded to one decimal;
 *   null if fewer than 2 cycles fall in that window.
 * - averagePeriodLength: mean bleeding-day count across cycles that have flow data,
 *   rounded to the nearest day; null if none do.
 */
fun cycleStats(closedCycles: List<ClosedCycleInput>): CycleStatsDto {
    if (closedCycles.size < MIN_CYCLES_FOR_STATS) return CycleStatsDto()

    val lengths = closedCycles.map { it.startDate.daysUntil(it.endDate) + 1 }
    val averageCycleLength = round(lengths.average()).toInt()

    val mostRecentStart = closedCycles.maxOf { it.startDate }
    val windowStart = mostRecentStart.minus(DatePeriod(days = CYCLE_VARIATION_WINDOW_DAYS))
    val recentLengths =
        closedCycles
            .filter { it.startDate >= windowStart }
            .map { it.startDate.daysUntil(it.endDate) + 1 }
    val cycleVariation =
        if (recentLengths.size >= MIN_CYCLES_FOR_STATS) {
            val mean = recentLengths.average()
            val variance = recentLengths.sumOf { (it - mean) * (it - mean) } / recentLengths.size
            round(sqrt(variance) * ONE_DECIMAL_FACTOR) / ONE_DECIMAL_FACTOR
        } else {
            null
        }

    val withFlow = closedCycles.filter { it.bleedingDays > 0 }
    val averagePeriodLength =
        if (withFlow.isNotEmpty()) round(withFlow.map { it.bleedingDays }.average()).toInt() else null

    return CycleStatsDto(averageCycleLength, cycleVariation, averagePeriodLength)
}

/**
 * One data point per closed cycle (cycle start + bleeding-day count), oldest first.
 */
fun periodLengthChart(closedCycles: List<ClosedCycleInput>): PeriodLengthChartDto =
    PeriodLengthChartDto(
        closedCycles
            .sortedBy { it.startDate }
            .map { PeriodLengthPoint(it.startDate.toString(), it.bleedingDays) },
    )

/**
 * Predict the next [OVULATION_PREDICTION_COUNT] period starts and ovulation dates from the
 * most recent cycle start and the average cycle length. Ovulation = period start −
 * [LUTEAL_PHASE_LENGTH] days.
 */
fun ovulationPredictions(
    mostRecentStart: LocalDate,
    avgCycleLength: Int,
): List<OvulationPrediction> =
    (1..OVULATION_PREDICTION_COUNT).map { n ->
        val periodStart = mostRecentStart.plus(DatePeriod(days = avgCycleLength * n))
        OvulationPrediction(
            predictedPeriodStart = periodStart.toString(),
            predictedOvulationDate = periodStart.minus(DatePeriod(days = LUTEAL_PHASE_LENGTH)).toString(),
        )
    }

/**
 * Bucket per-day sleep logs by cycle phase. For each phase with at least
 * [MIN_SLEEP_SAMPLE_SIZE] days of data, report the [TOP_SLEEP_OPTIONS] most frequently
 * logged option ids (ties broken by id for determinism) and the day count; otherwise null.
 */
fun bucketSleepByPhase(days: List<SleepDayInput>): SleepPredictionsDto =
    SleepPredictionsDto(
        SleepPhases(
            menstruation = sleepPredictionFor(days, CyclePhase.MENSTRUATION),
            follicular = sleepPredictionFor(days, CyclePhase.FOLLICULAR),
            ovulation = sleepPredictionFor(days, CyclePhase.OVULATION),
            luteal = sleepPredictionFor(days, CyclePhase.LUTEAL),
        ),
    )

private fun sleepPredictionFor(
    days: List<SleepDayInput>,
    phase: CyclePhase,
): PhaseSleepPrediction? {
    val phaseDays = days.filter { it.phase == phase }
    if (phaseDays.size < MIN_SLEEP_SAMPLE_SIZE) return null

    val frequency = mutableMapOf<String, Int>()
    for (day in phaseDays) {
        for (optionId in day.optionIds) {
            frequency[optionId] = (frequency[optionId] ?: 0) + 1
        }
    }
    val mostCommon =
        frequency.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_SLEEP_OPTIONS)
            .map { it.key }

    return PhaseSleepPrediction(mostCommon, phaseDays.size)
}
