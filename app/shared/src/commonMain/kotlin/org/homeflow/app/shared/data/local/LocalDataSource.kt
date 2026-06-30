package org.homeflow.app.shared.data.local

import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.HomeFlowDataSource
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.ErrorCode
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
import kotlin.time.Clock

/**
 * [HomeFlowDataSource] backed by a local SQLDelight database.
 * Phase 13: no mode selection, no DI wiring — tested directly.
 *
 * Construction: caller provides a pre-built [HomeFlowDb] (opened by [LocalDatabaseFactory]
 * with the DEK from [LocalKeyStore] — both are Phase 14 concerns).
 *
 * [LocalBootstrap.seed] must have been called before constructing this.
 */
class LocalDataSource(
    private val db: HomeFlowDb,
) : HomeFlowDataSource {
    private val userId = LocalBootstrap.LOCAL_USER_ID
    private val refData = LocalRefData(db)
    private val cyclesStore = LocalCyclesStore(db, userId)
    private val subsStore = LocalSubsStore(db, refData)
    private val logsStore = LocalDailyLogsStore(db, userId, cyclesStore, subsStore, refData)
    private val prefsStore = LocalPrefsStore(db, userId, refData)
    private val analytics = LocalAnalytics(db, userId, refData, logsStore)

    // ── User ──────────────────────────────────────────────────────────────────

    override suspend fun getMe(): ApiResult<UserDto> {
        val now = Clock.System.now().toString()
        return ApiResult.Success(UserDto(id = userId, createdAt = now))
    }

    /**
     * Hard-wipe: delete all user data rows in FK-safe order.
     * DEK clearing (Phase 14) is handled by the factory/session layer.
     */
    override suspend fun deleteAccount(): ApiResult<Unit> =
        runCatching {
            // Order: deepest FK dependencies first.
            db.painLogsQueries.deleteAllLocations()
            db.painLogsQueries.deleteAllPainLogs()
            db.dailyLogSubsQueries.deleteAllMulti()
            db.dailyLogSubsQueries.deleteAllSingle()
            db.dailyLogSubsQueries.deleteAllSex()
            db.dailyLogsQueries.deleteAll()
            db.cyclesQueries.deleteAll()
            db.preferencesQueries.deleteAll()
            db.syncOutboxQueries.deleteAll()
            db.usersQueries.deleteAll()
            ApiResult.Success(Unit)
        }.getOrElse {
            ApiResult.Failure(
                ErrorCode.INTERNAL_ERROR,
                it.message ?: "Failed to delete account data.",
                500,
            )
        }

    // ── Cycles ────────────────────────────────────────────────────────────────

    override suspend fun getCycles(): ApiResult<CyclesResponse> = cyclesStore.getCycles()

    override suspend fun getCurrentCycle(): ApiResult<CycleDto> = cyclesStore.getCurrentCycle()

    override suspend fun createCycle(startDate: String): ApiResult<CycleDto> = cyclesStore.createCycle(startDate)

    override suspend fun closeCycle(
        cycleId: String,
        endDate: String,
    ): ApiResult<CycleDto> = cyclesStore.closeCycle(cycleId, endDate)

    // ── Daily logs ────────────────────────────────────────────────────────────

    override suspend fun getDailyLog(date: String): ApiResult<DailyLogDto> = logsStore.getDailyLog(date)

    override suspend fun createDailyLog(
        date: String,
        cycleId: String,
    ): ApiResult<Unit> = logsStore.createDailyLog(date, cycleId)

    override suspend fun putOptionIds(
        date: String,
        endpoint: String,
        optionIds: List<String>,
    ): ApiResult<Unit> {
        val log =
            db.dailyLogsQueries.selectByDate(userId, date).executeAsOneOrNull()
                ?: return ApiResult.Failure(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Daily log not found for the given date.",
                    404,
                )
        val result = subsStore.putOptionIds(log.id, endpoint, optionIds)
        if (result is ApiResult.Success) {
            val now = Clock.System.now().toString()
            db.dailyLogsQueries.updateTimestamp(now, log.id, userId)
        }
        return result
    }

    override suspend fun putOptionId(
        date: String,
        endpoint: String,
        optionId: String?,
    ): ApiResult<Unit> {
        val log =
            db.dailyLogsQueries.selectByDate(userId, date).executeAsOneOrNull()
                ?: return ApiResult.Failure(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Daily log not found for the given date.",
                    404,
                )
        val result = subsStore.putOptionId(log.id, endpoint, optionId)
        if (result is ApiResult.Success) {
            val now = Clock.System.now().toString()
            db.dailyLogsQueries.updateTimestamp(now, log.id, userId)
        }
        return result
    }

    override suspend fun patchNotes(
        date: String,
        notes: String?,
    ): ApiResult<Unit> = logsStore.patchNotes(date, notes)

    override suspend fun putPain(
        date: String,
        locations: List<PainLocationDto>,
    ): ApiResult<Unit> = logsStore.putPain(date, locations)

    // ── Analytics ─────────────────────────────────────────────────────────────

    override suspend fun getCycleStats(): ApiResult<CycleStatsDto> = analytics.getCycleStats()

    override suspend fun getPeriodLengthChart(): ApiResult<PeriodLengthChartDto> = analytics.getPeriodLengthChart()

    override suspend fun getOvulationPrediction(): ApiResult<OvulationPredictionDto> =
        analytics.getOvulationPrediction()

    override suspend fun getSleepPredictions(): ApiResult<SleepPredictionsDto> = analytics.getSleepPredictions()

    // ── Preferences ───────────────────────────────────────────────────────────

    override suspend fun getPreferences(): ApiResult<PreferencesDto> = prefsStore.getPreferences()

    override suspend fun putPreferences(categoryOrder: List<String>): ApiResult<PreferencesResponse> =
        prefsStore.putPreferences(categoryOrder)

    // ── Ref data ──────────────────────────────────────────────────────────────

    override suspend fun getSymptomCategories(): ApiResult<SymptomCategoriesResponse> =
        ApiResult.Success(refData.getSymptomCategories())

    override suspend fun getPainRegions(): ApiResult<PainRegionsResponse> = ApiResult.Success(refData.getPainRegions())
}
