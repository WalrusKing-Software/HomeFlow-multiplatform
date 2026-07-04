package org.homeflow.app.shared.data

import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import org.homeflow.core.dto.CreateCycleRequest
import org.homeflow.core.dto.CreateDailyLogRequest
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.CycleStatsDto
import org.homeflow.core.dto.CyclesResponse
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.ImportResultDto
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
import org.homeflow.core.dto.SyncPullResponse
import org.homeflow.core.dto.SyncPushRequest
import org.homeflow.core.dto.SyncPushResponse
import org.homeflow.core.dto.UpdateCycleRequest
import org.homeflow.core.dto.UpdatePreferencesRequest
import org.homeflow.core.dto.UserDto
import org.homeflow.core.dto.VersionDto

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
class RemoteDataSource(
    private val client: HttpClient,
) : HomeFlowDataSource {
    override suspend fun getMe(): ApiResult<UserDto> = client.apiGet("users/me")

    /**
     * Unauthenticated check — called in [org.homeflow.app.shared.auth.AuthController.loadUser]
     * before [getMe] to verify the server accepts this client version. A 404 means the server
     * predates this endpoint; the caller treats that as compatible. Not on [HomeFlowDataSource]
     * — [org.homeflow.app.shared.data.local.LocalDataSource] has no server to query.
     */
    suspend fun getServerVersion(): ApiResult<VersionDto> = client.apiGet("version")

    /**
     * Permanently delete the account: all health data + the Keycloak identity (server-side).
     * The server replies `204 No Content`, so this is an [ApiResult]`<Unit>`.
     */
    override suspend fun deleteAccount(): ApiResult<Unit> = client.apiSendEmpty(HttpMethod.Delete, "users/me")

    // ── Cycles ───────────────────────────────────────────────────────────────
    override suspend fun getCycles(): ApiResult<CyclesResponse> = client.apiGet("cycles")

    /** `404 RESOURCE_NOT_FOUND` when no cycle is open. */
    override suspend fun getCurrentCycle(): ApiResult<CycleDto> = client.apiGet("cycles/current")

    /** Start a new cycle on [startDate]; the server auto-closes any open cycle. */
    override suspend fun createCycle(startDate: String): ApiResult<CycleDto> =
        client.apiSendReceiving(HttpMethod.Post, "cycles", CreateCycleRequest(startDate))

    /** Close [cycleId] by setting its end date. */
    override suspend fun closeCycle(
        cycleId: String,
        endDate: String,
    ): ApiResult<CycleDto> = client.apiSendReceiving(HttpMethod.Patch, "cycles/$cycleId", UpdateCycleRequest(endDate))

    /** Soft-delete [cycleId] and cascade to its daily logs. 204 on success. */
    override suspend fun deleteCycle(cycleId: String): ApiResult<Unit> =
        client.apiSendEmpty(HttpMethod.Delete, "cycles/$cycleId")

    // ── Daily logs ───────────────────────────────────────────────────────────

    /** [date] is an ISO `yyyy-MM-dd` string. `404` when no log exists for that day. */
    override suspend fun getDailyLog(date: String): ApiResult<DailyLogDto> = client.apiGet("daily-logs/$date")

    /** Create the anchor row for [date] in [cycleId]; `409 CONFLICT` if it already exists. */
    override suspend fun createDailyLog(
        date: String,
        cycleId: String,
    ): ApiResult<Unit> = client.apiSend(HttpMethod.Post, "daily-logs", CreateDailyLogRequest(date, cycleId))

    /** Soft-delete the daily log anchor for [date] and its sub-logs. 204 on success. */
    override suspend fun deleteDay(date: String): ApiResult<Unit> =
        client.apiSendEmpty(HttpMethod.Delete, "daily-logs/$date")

    /** Replace a multi-select sub-log ([endpoint] = `emotions`, `sleep`, …); empty list clears it. */
    override suspend fun putOptionIds(
        date: String,
        endpoint: String,
        optionIds: List<String>,
    ): ApiResult<Unit> = client.apiSend(HttpMethod.Put, "daily-logs/$date/$endpoint", OptionIdsRequest(optionIds))

    /** Replace a single-select sub-log ([endpoint] = `energy`, `flow`, `collection`); null clears it. */
    override suspend fun putOptionId(
        date: String,
        endpoint: String,
        optionId: String?,
    ): ApiResult<Unit> = client.apiSend(HttpMethod.Put, "daily-logs/$date/$endpoint", OptionIdRequest(optionId))

    /** Replace the free-text notes for [date]; null clears them. */
    override suspend fun patchNotes(
        date: String,
        notes: String?,
    ): ApiResult<Unit> = client.apiSend(HttpMethod.Patch, "daily-logs/$date/notes", NotesUpdateRequest(notes))

    /** Replace the whole pain log for [date]; an empty [locations] clears it. */
    override suspend fun putPain(
        date: String,
        locations: List<PainLocationDto>,
    ): ApiResult<Unit> = client.apiSend(HttpMethod.Put, "daily-logs/$date/pain", PainUpdateRequest(locations))

    // ── Analytics (always 200; null fields signal thin data) ─────────────────
    override suspend fun getCycleStats(): ApiResult<CycleStatsDto> = client.apiGet("analytics/cycle-stats")

    override suspend fun getPeriodLengthChart(): ApiResult<PeriodLengthChartDto> =
        client.apiGet("analytics/period-length-chart")

    override suspend fun getOvulationPrediction(): ApiResult<OvulationPredictionDto> =
        client.apiGet("analytics/ovulation-prediction")

    override suspend fun getSleepPredictions(): ApiResult<SleepPredictionsDto> =
        client.apiGet("analytics/sleep-predictions")

    // ── Preferences ──────────────────────────────────────────────────────────
    override suspend fun getPreferences(): ApiResult<PreferencesDto> = client.apiGet("preferences")

    /** Replace the dashboard category order with [categoryOrder] (every slug exactly once). */
    override suspend fun putPreferences(categoryOrder: List<String>): ApiResult<PreferencesResponse> =
        client.apiSendReceiving(HttpMethod.Put, "preferences", UpdatePreferencesRequest(categoryOrder))

    // ── Reference data (fetched once, cached by the repository) ──────────────
    override suspend fun getSymptomCategories(): ApiResult<SymptomCategoriesResponse> =
        client.apiGet("ref-data/symptom-categories")

    override suspend fun getPainRegions(): ApiResult<PainRegionsResponse> = client.apiGet("ref-data/pain-regions")

    // ── Sync (Phase 16b — not on the HomeFlowDataSource seam) ───────────────────

    /** Push pending local changes to the server; returns the server's view of each entity + cursor. */
    suspend fun pushSync(request: SyncPushRequest): ApiResult<SyncPushResponse> =
        client.apiSendReceiving(HttpMethod.Post, "sync/changes", request)

    /** Pull server changes since [cursor]; returns changed entities + new cursor. */
    suspend fun pullSync(cursor: Long): ApiResult<SyncPullResponse> = client.apiGet("sync/changes?cursor=$cursor")

    // ── Migration (not on the seam — LocalDataSource must not implement this) ──

    /**
     * POST [json] to `POST /import?source=homeflow` as multipart form data. Used
     * exclusively by the "adopt a server" migration path (D-15.6). The JSON is
     * health data — never log it.
     */
    suspend fun uploadHomeflowImport(json: String): ApiResult<ImportResultDto> =
        client.apiUploadImport("import?source=homeflow", json)
}
