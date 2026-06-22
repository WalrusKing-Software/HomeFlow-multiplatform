package org.homeflow.modules.analytics

import org.homeflow.core.domain.DEFAULT_CYCLE_LENGTH
import org.homeflow.core.domain.DEFAULT_PERIOD_LENGTH
import org.homeflow.core.domain.SleepDayInput
import org.homeflow.core.domain.bucketSleepByPhase
import org.homeflow.core.domain.cycleDayNumber
import org.homeflow.core.domain.cycleStats
import org.homeflow.core.domain.ovulationPredictions
import org.homeflow.core.domain.periodLengthChart
import org.homeflow.core.domain.predictPhase
import org.homeflow.core.dto.CycleStatsDto
import org.homeflow.core.dto.OvulationPredictionDto
import org.homeflow.core.dto.PeriodLengthChartDto
import org.homeflow.core.dto.SleepPredictionsDto
import org.homeflow.modules.users.UserPrincipal

/**
 * The four read-only analytics routes (`__docs/API.md`). Every statistic is computed by the
 * shared `:core` domain math over the user's history — this service only fetches the inputs
 * (row-scoped, via [AnalyticsRepository]) and delegates. Analytics always return `200`; thin
 * history surfaces as `null` fields, never `404`.
 */
class AnalyticsService(
    private val analyticsRepository: AnalyticsRepository,
) {
    /** Average cycle length, variation, and average period length (all null under 2 cycles). */
    fun getCycleStats(principal: UserPrincipal): CycleStatsDto =
        cycleStats(analyticsRepository.closedCycles(principal.id))

    /** One bleeding-day data point per closed cycle, oldest first (empty when none are closed). */
    fun getPeriodLengthChart(principal: UserPrincipal): PeriodLengthChartDto =
        periodLengthChart(analyticsRepository.closedCycles(principal.id))

    /**
     * Predicted period starts and ovulation dates for the next 3 cycles, projected from the
     * most recent cycle start at the average cycle length. Null predictions under 2 closed
     * cycles (no reliable average yet).
     */
    fun getOvulationPrediction(principal: UserPrincipal): OvulationPredictionDto {
        val stats = cycleStats(analyticsRepository.closedCycles(principal.id))
        val averageCycleLength = stats.averageCycleLength ?: return OvulationPredictionDto()
        val mostRecentStart = analyticsRepository.mostRecentCycleStart(principal.id) ?: return OvulationPredictionDto()
        return OvulationPredictionDto(
            averageCycleLength = averageCycleLength,
            predictions = ovulationPredictions(mostRecentStart, averageCycleLength),
        )
    }

    /**
     * Most-common sleep options per cycle phase. Each logged sleep day is bucketed by the
     * phase derived from its cycle day (using the user's average cycle/period lengths, or the
     * defaults when history is thin); the shared math then ranks options and gates each phase
     * on sample size. A phase with too little data comes back null.
     */
    fun getSleepPredictions(principal: UserPrincipal): SleepPredictionsDto {
        val stats = cycleStats(analyticsRepository.closedCycles(principal.id))
        val averageCycleLength = stats.averageCycleLength ?: DEFAULT_CYCLE_LENGTH
        val averagePeriodLength = stats.averagePeriodLength ?: DEFAULT_PERIOD_LENGTH

        val days =
            analyticsRepository
                .sleepLogs(principal.id)
                .groupBy { it.dailyLogId }
                .map { (_, rows) ->
                    val day = rows.first()
                    val cycleDay = cycleDayNumber(day.cycleStart, day.logDate)
                    SleepDayInput(
                        phase = predictPhase(cycleDay, averageCycleLength, averagePeriodLength),
                        optionIds = rows.map { it.optionId.toString() },
                    )
                }
        return bucketSleepByPhase(days)
    }
}
