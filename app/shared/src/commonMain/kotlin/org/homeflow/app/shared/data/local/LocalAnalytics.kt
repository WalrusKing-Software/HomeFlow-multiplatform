package org.homeflow.app.shared.data.local

import kotlinx.datetime.LocalDate
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.domain.ClosedCycleInput
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

class LocalAnalytics(
    private val db: HomeFlowDb,
    private val userId: String,
    private val refData: LocalRefData,
    private val logsStore: LocalDailyLogsStore,
) {
    private val cyclesQ get() = db.cyclesQueries
    private val subsQ get() = db.dailyLogSubsQueries

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun buildClosedCycleInputs(): List<ClosedCycleInput> {
        val closedCycles = cyclesQ.selectAll(userId).executeAsList()
            .filter { it.end_date != null }
        return closedCycles.map { cycle ->
            val start = LocalDate.parse(cycle.start_date)
            val end = LocalDate.parse(cycle.end_date!!)
            val bleedingDays = logsStore.getFlowDatesByCycleId(cycle.id).size
            ClosedCycleInput(start, end, bleedingDays)
        }
    }

    private fun buildSleepDayInputs(): List<SleepDayInput> {
        val allCycles = cyclesQ.selectAll(userId).executeAsList()
        if (allCycles.isEmpty()) return emptyList()

        val stats = cycleStats(buildClosedCycleInputs())
        val avgCycle = stats.averageCycleLength ?: DEFAULT_CYCLE_LENGTH
        val avgPeriod = stats.averagePeriodLength ?: DEFAULT_PERIOD_LENGTH

        val sleepCatId = refData.categoryIdBySlug("sleep_quality") ?: return emptyList()

        val result = mutableListOf<SleepDayInput>()
        for (cycle in allCycles) {
            val start = LocalDate.parse(cycle.start_date)
            val logs = logsStore.getLogsByCycleId(cycle.id)
            for (log in logs) {
                val logDate = LocalDate.parse(log.log_date)
                val dayNum = cycleDayNumber(start, logDate)
                val phase = predictPhase(dayNum, avgCycle, avgPeriod)
                val optionIds = subsQ.selectMultiByLogAndCategory(log.id, sleepCatId).executeAsList()
                result.add(SleepDayInput(phase, optionIds))
            }
        }
        return result
    }

    // ── Public analytics functions ─────────────────────────────────────────────

    fun getCycleStats(): ApiResult<CycleStatsDto> {
        val inputs = buildClosedCycleInputs()
        return ApiResult.Success(cycleStats(inputs))
    }

    fun getPeriodLengthChart(): ApiResult<PeriodLengthChartDto> {
        val inputs = buildClosedCycleInputs()
        return ApiResult.Success(periodLengthChart(inputs))
    }

    fun getOvulationPrediction(): ApiResult<OvulationPredictionDto> {
        val inputs = buildClosedCycleInputs()
        val stats = cycleStats(inputs)
        val avgCycleLength = stats.averageCycleLength

        val predictions = if (avgCycleLength != null) {
            val mostRecentStart = cyclesQ.selectAll(userId).executeAsList()
                .maxOfOrNull { LocalDate.parse(it.start_date) }
            if (mostRecentStart != null) {
                ovulationPredictions(mostRecentStart, avgCycleLength)
            } else null
        } else null

        return ApiResult.Success(
            OvulationPredictionDto(
                averageCycleLength = avgCycleLength,
                predictions = predictions,
            ),
        )
    }

    fun getSleepPredictions(): ApiResult<SleepPredictionsDto> {
        val days = buildSleepDayInputs()
        return ApiResult.Success(bucketSleepByPhase(days))
    }
}
