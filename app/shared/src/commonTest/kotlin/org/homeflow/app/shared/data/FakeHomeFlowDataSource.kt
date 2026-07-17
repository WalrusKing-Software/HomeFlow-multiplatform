package org.homeflow.app.shared.data

import kotlinx.coroutines.awaitCancellation
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.CycleStatsDto
import org.homeflow.core.dto.CyclesResponse
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.OvulationPredictionDto
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.dto.PainLocationRefDto
import org.homeflow.core.dto.PainRegionDto
import org.homeflow.core.dto.PainRegionsResponse
import org.homeflow.core.dto.PeriodLengthChartDto
import org.homeflow.core.dto.PeriodLengthPoint
import org.homeflow.core.dto.PreferencesDto
import org.homeflow.core.dto.PreferencesResponse
import org.homeflow.core.dto.SleepPhases
import org.homeflow.core.dto.SleepPredictionsDto
import org.homeflow.core.dto.SymptomCategoriesResponse
import org.homeflow.core.dto.SymptomCategoryDto
import org.homeflow.core.dto.SymptomOptionDto
import org.homeflow.core.dto.UserDto

/**
 * Canned-response [HomeFlowDataSource] for screen tests. Every getter returns the
 * mutable field of the same name; set [failure] to make every call fail (error states),
 * or [neverComplete] to suspend forever (a stable Loading state). Writes succeed and
 * return canned values.
 */
@Suppress("TooManyFunctions") // mirrors HomeFlowDataSource one member per route, by design
class FakeHomeFlowDataSource : HomeFlowDataSource {
    /** When non-null, every call returns this failure. */
    var failure: ApiResult.Failure? = null

    /** When true, every call suspends until cancelled — screens stay in Loading. */
    var neverComplete: Boolean = false

    var me: UserDto = UserDto(id = "user-1", createdAt = T0)
    var cycles: CyclesResponse = CyclesResponse(listOf(OPEN_CYCLE))
    var currentCycle: ApiResult<CycleDto> = ApiResult.Success(OPEN_CYCLE)
    var dailyLog: ApiResult<DailyLogDto> = ApiResult.Success(TODAY_LOG)
    var cycleStats: CycleStatsDto =
        CycleStatsDto(averageCycleLength = 28, cycleVariation = 0.0, averagePeriodLength = 5)
    var periodChart: PeriodLengthChartDto = PeriodLengthChartDto(listOf(PeriodLengthPoint("2024-01-01", 5)))
    var ovulation: OvulationPredictionDto = OvulationPredictionDto()
    var sleep: SleepPredictionsDto = SleepPredictionsDto(SleepPhases())
    var preferences: PreferencesDto = PreferencesDto(listOf("emotions", "blood_flow"))
    var symptomCategories: SymptomCategoriesResponse = CATEGORIES
    var painRegions: PainRegionsResponse = REGIONS

    private suspend fun <T> canned(value: () -> T): ApiResult<T> {
        if (neverComplete) awaitCancellation()
        return failure ?: ApiResult.Success(value())
    }

    private suspend fun <T> cannedResult(value: () -> ApiResult<T>): ApiResult<T> {
        if (neverComplete) awaitCancellation()
        return failure ?: value()
    }

    override suspend fun getMe(): ApiResult<UserDto> = canned { me }

    override suspend fun deleteAccount(): ApiResult<Unit> = canned { }

    override suspend fun getCycles(): ApiResult<CyclesResponse> = canned { cycles }

    override suspend fun getCurrentCycle(): ApiResult<CycleDto> = cannedResult { currentCycle }

    override suspend fun createCycle(startDate: String): ApiResult<CycleDto> = canned { OPEN_CYCLE }

    override suspend fun closeCycle(
        cycleId: String,
        endDate: String,
    ): ApiResult<CycleDto> = canned { OPEN_CYCLE.copy(endDate = endDate) }

    override suspend fun deleteCycle(cycleId: String): ApiResult<Unit> = canned { }

    override suspend fun getDailyLog(date: String): ApiResult<DailyLogDto> = cannedResult { dailyLog }

    override suspend fun createDailyLog(
        date: String,
        cycleId: String,
    ): ApiResult<Unit> = canned { }

    override suspend fun deleteDay(date: String): ApiResult<Unit> = canned { }

    override suspend fun putOptionIds(
        date: String,
        endpoint: String,
        optionIds: List<String>,
    ): ApiResult<Unit> = canned { }

    override suspend fun putOptionId(
        date: String,
        endpoint: String,
        optionId: String?,
    ): ApiResult<Unit> = canned { }

    override suspend fun patchNotes(
        date: String,
        notes: String?,
    ): ApiResult<Unit> = canned { }

    override suspend fun putPain(
        date: String,
        locations: List<PainLocationDto>,
    ): ApiResult<Unit> = canned { }

    override suspend fun getCycleStats(): ApiResult<CycleStatsDto> = canned { cycleStats }

    override suspend fun getPeriodLengthChart(): ApiResult<PeriodLengthChartDto> = canned { periodChart }

    override suspend fun getOvulationPrediction(): ApiResult<OvulationPredictionDto> = canned { ovulation }

    override suspend fun getSleepPredictions(): ApiResult<SleepPredictionsDto> = canned { sleep }

    override suspend fun getPreferences(): ApiResult<PreferencesDto> = canned { preferences }

    override suspend fun putPreferences(categoryOrder: List<String>): ApiResult<PreferencesResponse> =
        canned { PreferencesResponse(categoryOrder, T0) }

    override suspend fun getSymptomCategories(): ApiResult<SymptomCategoriesResponse> = canned { symptomCategories }

    override suspend fun getPainRegions(): ApiResult<PainRegionsResponse> = canned { painRegions }

    companion object {
        private const val T0 = "2024-01-01T00:00:00Z"

        val OPEN_CYCLE =
            CycleDto(id = "cyc-1", startDate = "2024-01-01", endDate = null, createdAt = T0, updatedAt = T0)

        val TODAY_LOG =
            DailyLogDto(
                id = "log-1",
                logDate = "2024-01-06",
                cycleId = "cyc-1",
                notes = "Feeling fine.",
                emotions = listOf("o-fine"),
                flow = "o-medium",
                createdAt = T0,
                updatedAt = T0,
            )

        val CATEGORIES =
            SymptomCategoriesResponse(
                listOf(
                    SymptomCategoryDto(
                        "c-emotions",
                        "emotions",
                        "Emotions",
                        "multi",
                        "always",
                        1,
                        listOf(
                            SymptomOptionDto("o-fine", "fine", "Fine", 1),
                            SymptomOptionDto("o-anxious", "anxious", "Anxious", 2),
                        ),
                    ),
                    SymptomCategoryDto(
                        "c-flow",
                        "blood_flow",
                        "Blood Flow",
                        "single",
                        "menstruation",
                        8,
                        listOf(SymptomOptionDto("o-medium", "medium", "Medium", 2)),
                    ),
                ),
            )

        val REGIONS =
            PainRegionsResponse(
                listOf(
                    PainRegionDto(
                        "r-abdomen",
                        "abdomen",
                        "Abdomen",
                        3,
                        listOf(PainLocationRefDto("l-uterus", "uterus", "Uterus", 2)),
                    ),
                ),
            )
    }
}
