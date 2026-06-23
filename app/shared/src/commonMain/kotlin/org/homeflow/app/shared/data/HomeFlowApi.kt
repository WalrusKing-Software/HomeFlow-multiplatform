package org.homeflow.app.shared.data

import io.ktor.client.HttpClient
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.CycleStatsDto
import org.homeflow.core.dto.CyclesResponse
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.OvulationPredictionDto
import org.homeflow.core.dto.PainRegionsResponse
import org.homeflow.core.dto.PeriodLengthChartDto
import org.homeflow.core.dto.PreferencesDto
import org.homeflow.core.dto.SleepPredictionsDto
import org.homeflow.core.dto.SymptomCategoriesResponse
import org.homeflow.core.dto.UserDto

/**
 * Typed calls to the Ktor backend `/api/v1`, using the `:core` DTOs directly (no
 * codegen, no hand-copied models). Phase 8 covers the full **read** surface the four
 * screens need; the write calls arrive in Phase 9.
 *
 * Every method returns an [ApiResult] so callers pattern-match Loading/Loaded/Error
 * uniformly. Routes that 404 on "nothing yet" (current cycle, a day with no log) are
 * surfaced as a `RESOURCE_NOT_FOUND` [ApiResult.Failure]; the repository maps those to
 * an absence rather than an error.
 */
class HomeFlowApi(
    private val client: HttpClient,
) {
    suspend fun getMe(): ApiResult<UserDto> = client.apiGet("users/me")

    // ── Cycles ───────────────────────────────────────────────────────────────
    suspend fun getCycles(): ApiResult<CyclesResponse> = client.apiGet("cycles")

    /** `404 RESOURCE_NOT_FOUND` when no cycle is open. */
    suspend fun getCurrentCycle(): ApiResult<CycleDto> = client.apiGet("cycles/current")

    // ── Daily logs ───────────────────────────────────────────────────────────

    /** [date] is an ISO `yyyy-MM-dd` string. `404` when no log exists for that day. */
    suspend fun getDailyLog(date: String): ApiResult<DailyLogDto> = client.apiGet("daily-logs/$date")

    // ── Analytics (always 200; null fields signal thin data) ─────────────────
    suspend fun getCycleStats(): ApiResult<CycleStatsDto> = client.apiGet("analytics/cycle-stats")

    suspend fun getPeriodLengthChart(): ApiResult<PeriodLengthChartDto> = client.apiGet("analytics/period-length-chart")

    suspend fun getOvulationPrediction(): ApiResult<OvulationPredictionDto> =
        client.apiGet("analytics/ovulation-prediction")

    suspend fun getSleepPredictions(): ApiResult<SleepPredictionsDto> = client.apiGet("analytics/sleep-predictions")

    // ── Preferences ──────────────────────────────────────────────────────────
    suspend fun getPreferences(): ApiResult<PreferencesDto> = client.apiGet("preferences")

    // ── Reference data (fetched once, cached by the repository) ──────────────
    suspend fun getSymptomCategories(): ApiResult<SymptomCategoriesResponse> =
        client.apiGet("ref-data/symptom-categories")

    suspend fun getPainRegions(): ApiResult<PainRegionsResponse> = client.apiGet("ref-data/pain-regions")
}
