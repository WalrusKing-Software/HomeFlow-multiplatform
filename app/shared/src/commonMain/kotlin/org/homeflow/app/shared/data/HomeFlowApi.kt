package org.homeflow.app.shared.data

import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import org.homeflow.core.dto.CreateCycleRequest
import org.homeflow.core.dto.CreateDailyLogRequest
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.CycleStatsDto
import org.homeflow.core.dto.CyclesResponse
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.NotesUpdateRequest
import org.homeflow.core.dto.OptionIdRequest
import org.homeflow.core.dto.OptionIdsRequest
import org.homeflow.core.dto.OvulationPredictionDto
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.dto.PainRegionsResponse
import org.homeflow.core.dto.PainUpdateRequest
import org.homeflow.core.dto.PeriodLengthChartDto
import org.homeflow.core.dto.PreferencesDto
import org.homeflow.core.dto.PreferencesResponse
import org.homeflow.core.dto.SleepPredictionsDto
import org.homeflow.core.dto.SymptomCategoriesResponse
import org.homeflow.core.dto.UpdateCycleRequest
import org.homeflow.core.dto.UpdatePreferencesRequest
import org.homeflow.core.dto.UserDto

/**
 * Typed calls to the Ktor backend `/api/v1`, using the `:core` DTOs directly (no
 * codegen, no hand-copied models). Phase 8 covered the **read** surface; Phase 9 adds the
 * **write** surface — the daily-log anchor + every sub-log replace, cycle start/close, and
 * preference reorder.
 *
 * Every method returns an [ApiResult] so callers pattern-match Loading/Loaded/Error
 * uniformly. Routes that 404 on "nothing yet" (current cycle, a day with no log) are
 * surfaced as a `RESOURCE_NOT_FOUND` [ApiResult.Failure]; the repository maps those to
 * an absence rather than an error. Sub-log replaces echo a response the client doesn't
 * need (it reloads the day), so they return [ApiResult]`<Unit>`.
 */
class HomeFlowApi(
    private val client: HttpClient,
) {
    suspend fun getMe(): ApiResult<UserDto> = client.apiGet("users/me")

    /**
     * Permanently delete the account: all health data + the Keycloak identity (server-side).
     * The server replies `204 No Content`, so this is an [ApiResult]`<Unit>`.
     */
    suspend fun deleteAccount(): ApiResult<Unit> = client.apiSendEmpty(HttpMethod.Delete, "users/me")

    // ── Cycles ───────────────────────────────────────────────────────────────
    suspend fun getCycles(): ApiResult<CyclesResponse> = client.apiGet("cycles")

    /** `404 RESOURCE_NOT_FOUND` when no cycle is open. */
    suspend fun getCurrentCycle(): ApiResult<CycleDto> = client.apiGet("cycles/current")

    /** Start a new cycle on [startDate]; the server auto-closes any open cycle. */
    suspend fun createCycle(startDate: String): ApiResult<CycleDto> =
        client.apiSendReceiving(HttpMethod.Post, "cycles", CreateCycleRequest(startDate))

    /** Close [cycleId] by setting its end date. */
    suspend fun closeCycle(
        cycleId: String,
        endDate: String,
    ): ApiResult<CycleDto> = client.apiSendReceiving(HttpMethod.Patch, "cycles/$cycleId", UpdateCycleRequest(endDate))

    // ── Daily logs ───────────────────────────────────────────────────────────

    /** [date] is an ISO `yyyy-MM-dd` string. `404` when no log exists for that day. */
    suspend fun getDailyLog(date: String): ApiResult<DailyLogDto> = client.apiGet("daily-logs/$date")

    /** Create the anchor row for [date] in [cycleId]; `409 CONFLICT` if it already exists. */
    suspend fun createDailyLog(
        date: String,
        cycleId: String,
    ): ApiResult<Unit> = client.apiSend(HttpMethod.Post, "daily-logs", CreateDailyLogRequest(date, cycleId))

    /** Replace a multi-select sub-log ([endpoint] = `emotions`, `sleep`, …); empty list clears it. */
    suspend fun putOptionIds(
        date: String,
        endpoint: String,
        optionIds: List<String>,
    ): ApiResult<Unit> = client.apiSend(HttpMethod.Put, "daily-logs/$date/$endpoint", OptionIdsRequest(optionIds))

    /** Replace a single-select sub-log ([endpoint] = `energy`, `flow`, `collection`); null clears it. */
    suspend fun putOptionId(
        date: String,
        endpoint: String,
        optionId: String?,
    ): ApiResult<Unit> = client.apiSend(HttpMethod.Put, "daily-logs/$date/$endpoint", OptionIdRequest(optionId))

    /** Replace the free-text notes for [date]; null clears them. */
    suspend fun patchNotes(
        date: String,
        notes: String?,
    ): ApiResult<Unit> = client.apiSend(HttpMethod.Patch, "daily-logs/$date/notes", NotesUpdateRequest(notes))

    /** Replace the whole pain log for [date]; an empty [locations] clears it. */
    suspend fun putPain(
        date: String,
        locations: List<PainLocationDto>,
    ): ApiResult<Unit> = client.apiSend(HttpMethod.Put, "daily-logs/$date/pain", PainUpdateRequest(locations))

    // ── Analytics (always 200; null fields signal thin data) ─────────────────
    suspend fun getCycleStats(): ApiResult<CycleStatsDto> = client.apiGet("analytics/cycle-stats")

    suspend fun getPeriodLengthChart(): ApiResult<PeriodLengthChartDto> = client.apiGet("analytics/period-length-chart")

    suspend fun getOvulationPrediction(): ApiResult<OvulationPredictionDto> =
        client.apiGet("analytics/ovulation-prediction")

    suspend fun getSleepPredictions(): ApiResult<SleepPredictionsDto> = client.apiGet("analytics/sleep-predictions")

    // ── Preferences ──────────────────────────────────────────────────────────
    suspend fun getPreferences(): ApiResult<PreferencesDto> = client.apiGet("preferences")

    /** Replace the dashboard category order with [categoryOrder] (every slug exactly once). */
    suspend fun putPreferences(categoryOrder: List<String>): ApiResult<PreferencesResponse> =
        client.apiSendReceiving(HttpMethod.Put, "preferences", UpdatePreferencesRequest(categoryOrder))

    // ── Reference data (fetched once, cached by the repository) ──────────────
    suspend fun getSymptomCategories(): ApiResult<SymptomCategoriesResponse> =
        client.apiGet("ref-data/symptom-categories")

    suspend fun getPainRegions(): ApiResult<PainRegionsResponse> = client.apiGet("ref-data/pain-regions")
}
