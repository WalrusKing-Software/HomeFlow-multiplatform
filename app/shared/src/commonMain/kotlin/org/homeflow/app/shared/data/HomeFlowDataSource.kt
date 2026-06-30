package org.homeflow.app.shared.data

import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.CycleStatsDto
import org.homeflow.core.dto.CyclesResponse
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.OvulationPredictionDto
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.dto.PainRegionsResponse
import org.homeflow.core.dto.PeriodLengthChartDto
import org.homeflow.core.dto.PreferencesDto
import org.homeflow.core.dto.PreferencesResponse
import org.homeflow.core.dto.SleepPredictionsDto
import org.homeflow.core.dto.SymptomCategoriesResponse
import org.homeflow.core.dto.UserDto

/**
 * The per-route read/write surface the repository renders off. Two implementations:
 * [RemoteDataSource] (HTTP, this phase) and LocalDataSource (SQLDelight, Phase 13).
 *
 * BEHAVIORAL CONTRACT every implementation MUST honor (today's HTTP behavior):
 * - [getCurrentCycle], [getDailyLog]: absence → ApiResult.Failure(RESOURCE_NOT_FOUND).
 * - [createDailyLog]: a log already exists for the date → ApiResult.Failure(CONFLICT).
 * - [putOptionIds]/[putOptionId]/[patchNotes]/[putPain]: no anchor for the date →
 *   ApiResult.Failure(RESOURCE_NOT_FOUND).
 * - analytics getters: always ApiResult.Success (null fields signal thin data); never NOT_FOUND.
 * - [createCycle]: auto-closes any currently open cycle (end = start − 1 day).
 */
interface HomeFlowDataSource {
    suspend fun getMe(): ApiResult<UserDto>

    suspend fun deleteAccount(): ApiResult<Unit>

    suspend fun getCycles(): ApiResult<CyclesResponse>

    suspend fun getCurrentCycle(): ApiResult<CycleDto>

    suspend fun createCycle(startDate: String): ApiResult<CycleDto>

    suspend fun closeCycle(
        cycleId: String,
        endDate: String,
    ): ApiResult<CycleDto>

    suspend fun getDailyLog(date: String): ApiResult<DailyLogDto>

    suspend fun createDailyLog(
        date: String,
        cycleId: String,
    ): ApiResult<Unit>

    suspend fun putOptionIds(
        date: String,
        endpoint: String,
        optionIds: List<String>,
    ): ApiResult<Unit>

    suspend fun putOptionId(
        date: String,
        endpoint: String,
        optionId: String?,
    ): ApiResult<Unit>

    suspend fun patchNotes(
        date: String,
        notes: String?,
    ): ApiResult<Unit>

    suspend fun putPain(
        date: String,
        locations: List<PainLocationDto>,
    ): ApiResult<Unit>

    suspend fun getCycleStats(): ApiResult<CycleStatsDto>

    suspend fun getPeriodLengthChart(): ApiResult<PeriodLengthChartDto>

    suspend fun getOvulationPrediction(): ApiResult<OvulationPredictionDto>

    suspend fun getSleepPredictions(): ApiResult<SleepPredictionsDto>

    suspend fun getPreferences(): ApiResult<PreferencesDto>

    suspend fun putPreferences(categoryOrder: List<String>): ApiResult<PreferencesResponse>

    suspend fun getSymptomCategories(): ApiResult<SymptomCategoriesResponse>

    suspend fun getPainRegions(): ApiResult<PainRegionsResponse>
}
