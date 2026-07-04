package org.homeflow.core.dto

import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/analytics/cycle-stats`. All fields are null when there is insufficient
 * history (fewer than 2 closed cycles). Analytics routes always return 200 + nulls,
 * never 404.
 */
@Serializable
data class CycleStatsDto(
    val averageCycleLength: Int? = null,
    val cycleVariation: Double? = null,
    val averagePeriodLength: Int? = null,
)

/** `GET /api/v1/analytics/period-length-chart` — one point per closed cycle, oldest first. */
@Serializable
data class PeriodLengthChartDto(
    val dataPoints: List<PeriodLengthPoint>,
)

@Serializable
data class PeriodLengthPoint(
    val cycleStartDate: String,
    val bleedingDays: Int,
)

/**
 * `GET /api/v1/analytics/ovulation-prediction` — next 3 predicted period starts and
 * ovulation dates. `predictions` is null when fewer than 2 closed cycles exist.
 */
@Serializable
data class OvulationPredictionDto(
    val averageCycleLength: Int? = null,
    val predictions: List<OvulationPrediction>? = null,
)

@Serializable
data class OvulationPrediction(
    val predictedPeriodStart: String,
    val predictedOvulationDate: String,
)

/**
 * `GET /api/v1/analytics/sleep-predictions` — most-common sleep options per cycle phase.
 * A phase is null when fewer than 5 days of data exist for it.
 */
@Serializable
data class SleepPredictionsDto(
    val phases: SleepPhases,
)

@Serializable
data class SleepPhases(
    val menstruation: PhaseSleepPrediction? = null,
    val follicular: PhaseSleepPrediction? = null,
    val ovulation: PhaseSleepPrediction? = null,
    val luteal: PhaseSleepPrediction? = null,
)

@Serializable
data class PhaseSleepPrediction(
    val mostCommon: List<String>,
    val sampleSize: Int,
)
