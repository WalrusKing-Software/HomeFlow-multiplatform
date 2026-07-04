package org.homeflow.app.shared.data

import kotlinx.datetime.LocalDate
import org.homeflow.core.ErrorCode
import org.homeflow.core.domain.CyclePhase
import org.homeflow.core.domain.DEFAULT_CYCLE_LENGTH
import org.homeflow.core.domain.DEFAULT_PERIOD_LENGTH
import org.homeflow.core.domain.cycleDayNumber
import org.homeflow.core.domain.predictPhase
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.dto.CycleStatsDto
import org.homeflow.core.dto.DailyLogDto
import org.homeflow.core.dto.OvulationPredictionDto
import org.homeflow.core.dto.PainDto
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.dto.PainRegionDto
import org.homeflow.core.dto.PainRegionsResponse
import org.homeflow.core.dto.PeriodLengthPoint
import org.homeflow.core.dto.SleepPredictionsDto
import org.homeflow.core.dto.SymptomCategoriesResponse
import org.homeflow.core.dto.SymptomCategoryDto
import org.homeflow.core.dto.SymptomOptionDto
import org.homeflow.core.validation.validateDailyLogWithinCycle

/**
 * The read/write surface the screens render off. It owns the **in-memory ref-data label
 * cache** (CLAUDE.md: no health data at rest, caches in memory only) and composes raw API
 * responses into screen-shaped data with option/location ids already resolved to labels.
 * The Phase 9 write surface (day editor save, cycle start/close, preference reorder) lives
 * alongside the reads and diffs edits before issuing the minimal set of calls.
 *
 * Routes that 404 on "nothing tracked yet" (the open cycle, a day with no log) are folded
 * into an absence (`null`) rather than an error — see [optional]. Everything else
 * propagates as an [ApiResult.Failure] so screens show a retryable error.
 *
 * Phase/label math delegates to `:core` (`predictPhase`, `cycleDayNumber`); this class adds
 * no domain rules of its own beyond shaping.
 */
class HomeFlowRepository(
    private val api: HomeFlowDataSource,
) {
    private var cachedLabels: Labels? = null

    /** Fetch ref data once and cache it; later calls reuse the in-memory [Labels]. */
    suspend fun ensureRefData(): ApiResult<Labels> {
        cachedLabels?.let { return ApiResult.Success(it) }
        val categories = api.getSymptomCategories().valueOr { return it }
        val regions = api.getPainRegions().valueOr { return it }
        return ApiResult.Success(buildLabels(categories, regions).also { cachedLabels = it })
    }

    /** Dashboard: the open cycle (+ derived day/phase), at-a-glance stats, and today's log. */
    suspend fun loadDashboard(today: LocalDate): ApiResult<DashboardData> {
        val labels = ensureRefData().valueOr { return it }
        val current = api.getCurrentCycle().optional().valueOr { return it }
        val stats = api.getCycleStats().valueOr { return it }
        val todayLog = api.getDailyLog(today.toString()).optional().valueOr { return it }
        val dayPhase = current?.let { cycleDayPhase(it.startDate, today, stats) }
        return ApiResult.Success(
            DashboardData(
                currentCycle = current,
                cycleDay = dayPhase?.day,
                phase = dayPhase?.phase,
                stats = stats,
                today = todayLog?.let { resolveDay(it, labels) },
            ),
        )
    }

    /** Day view: the resolved log for [date], or null when nothing is logged that day. */
    suspend fun loadDay(date: LocalDate): ApiResult<DayContent?> {
        val labels = ensureRefData().valueOr { return it }
        val log = api.getDailyLog(date.toString()).optional().valueOr { return it }
        return ApiResult.Success(log?.let { resolveDay(it, labels) })
    }

    /** All cycles, newest first (as returned by the server). */
    suspend fun loadCycles(): ApiResult<List<CycleDto>> =
        when (val result = api.getCycles()) {
            is ApiResult.Success -> ApiResult.Success(result.value.cycles)
            is ApiResult.Failure -> result
        }

    /** Analytics: stats, period-length chart, ovulation projection, sleep-by-phase (labels resolved). */
    suspend fun loadAnalytics(): ApiResult<AnalyticsData> {
        val labels = ensureRefData().valueOr { return it }
        val stats = api.getCycleStats().valueOr { return it }
        val chart = api.getPeriodLengthChart().valueOr { return it }
        val ovulation = api.getOvulationPrediction().valueOr { return it }
        val sleep = api.getSleepPredictions().valueOr { return it }
        return ApiResult.Success(
            AnalyticsData(
                stats = stats,
                periodChart = chart.dataPoints,
                ovulation = ovulation,
                sleep = resolveSleep(sleep, labels),
            ),
        )
    }

    // ── Write surface (Phase 9) ──────────────────────────────────────────────

    /**
     * Everything the day editor needs: the editable categories (in the user's saved
     * dashboard order, with options), pain regions, the cycle covering [date] (null when
     * none does — the day can't be logged until a cycle exists), and the day's current
     * selections pre-populated for editing.
     */
    suspend fun loadDayEditor(date: LocalDate): ApiResult<DayEditor> {
        val labels = ensureRefData().valueOr { return it }
        val order = api.getPreferences().valueOr { return it }.categoryOrder
        val cycles = api.getCycles().valueOr { return it }.cycles
        val log = api.getDailyLog(date.toString()).optional().valueOr { return it }
        val cycleId =
            cycles
                .firstOrNull { cycle ->
                    validateDailyLogWithinCycle(
                        date = date,
                        cycleStart = LocalDate.parse(cycle.startDate),
                        cycleEnd = cycle.endDate?.let { LocalDate.parse(it) },
                    ).isValid
                }?.id
        val rank = order.withIndex().associate { (index, slug) -> slug to index }
        val categories =
            labels.categories
                .sortedBy { rank[it.slug] ?: Int.MAX_VALUE }
                .map { EditableCategory(it.slug, it.label, selectionTypeOf(it.selectionType), it.phase, it.options) }
        val initial =
            DayEdits(
                selections = idsBySlug(log),
                notes = log?.notes,
                pain = log?.pain?.locations.orEmpty(),
            )
        return ApiResult.Success(DayEditor(cycleId, categories, labels.painRegions, initial))
    }

    /**
     * Persist [edits] for [date]: ensure the anchor row exists, then push only the
     * categories/notes/pain that changed from [editor].initial. Sub-log PUTs fully replace,
     * so an emptied selection clears it. A no-op edit is a success without any request.
     */
    suspend fun saveDay(
        date: LocalDate,
        editor: DayEditor,
        edits: DayEdits,
    ): ApiResult<Unit> {
        val cycleId =
            editor.cycleId
                ?: return ApiResult.Failure(ErrorCode.VALIDATION_ERROR, "No cycle covers this date.", 0)
        val iso = date.toString()
        val writes = changedWrites(iso, editor, edits)
        if (writes.isEmpty()) return ApiResult.Success(Unit)

        ensureAnchor(iso, cycleId).valueOr { return it }
        writes.forEach { write -> write().valueOr { return it } }
        return ApiResult.Success(Unit)
    }

    /** The list of sub-log calls needed to turn [editor].initial into [edits] (only the diffs). */
    private fun changedWrites(
        iso: String,
        editor: DayEditor,
        edits: DayEdits,
    ): List<suspend () -> ApiResult<Unit>> =
        buildList {
            editor.categories.forEach { category ->
                val now = edits.selections[category.slug].orEmpty()
                val before = editor.initial.selections[category.slug].orEmpty()
                if (now == before) return@forEach
                val endpoint = SUB_LOG_ENDPOINTS.getValue(category.slug)
                if (category.selectionType == SelectionType.SINGLE) {
                    add { api.putOptionId(iso, endpoint, now.firstOrNull()) }
                } else {
                    add { api.putOptionIds(iso, endpoint, now) }
                }
            }
            val notesNow = edits.notes?.takeUnless { it.isBlank() }
            if (notesNow != editor.initial.notes) add { api.patchNotes(iso, notesNow) }
            if (edits.pain != editor.initial.pain) add { api.putPain(iso, edits.pain) }
        }

    /** Create the anchor, treating a `409 CONFLICT` (already exists) as success. */
    private suspend fun ensureAnchor(
        iso: String,
        cycleId: String,
    ): ApiResult<Unit> =
        when (val result = api.createDailyLog(iso, cycleId)) {
            is ApiResult.Success -> result
            is ApiResult.Failure -> if (result.code == ErrorCode.CONFLICT) ApiResult.Success(Unit) else result
        }

    /** Start a new cycle on [start]; the server auto-closes any open cycle. */
    suspend fun startCycle(start: LocalDate): ApiResult<CycleDto> = api.createCycle(start.toString())

    /** Close [cycleId] by setting its end date to [end]. */
    suspend fun closeCycle(
        cycleId: String,
        end: LocalDate,
    ): ApiResult<CycleDto> = api.closeCycle(cycleId, end.toString())

    /** Soft-delete [cycleId] and cascade to its daily logs. */
    suspend fun deleteCycle(cycleId: String): ApiResult<Unit> = api.deleteCycle(cycleId)

    /** Soft-delete the daily log anchor for [date] and its sub-logs. */
    suspend fun deleteDay(date: String): ApiResult<Unit> = api.deleteDay(date)

    /** Permanently delete the account and all its health data (server-side cascade + Keycloak). */
    suspend fun deleteAccount(): ApiResult<Unit> = api.deleteAccount()

    /** The dashboard categories in their saved order, paired with labels, for the reorder UI. */
    suspend fun loadPreferences(): ApiResult<PreferencesEditor> {
        val labels = ensureRefData().valueOr { return it }
        val order = api.getPreferences().valueOr { return it }.categoryOrder
        val bySlug = labels.categories.associateBy { it.slug }
        val ordered = order.mapNotNull { slug -> bySlug[slug]?.let { CategoryLabel(slug, it.label) } }
        return ApiResult.Success(PreferencesEditor(ordered))
    }

    /** Persist a new dashboard category [order]; returns the order the server stored. */
    suspend fun savePreferences(order: List<String>): ApiResult<List<String>> =
        when (val result = api.putPreferences(order)) {
            is ApiResult.Success -> ApiResult.Success(result.value.categoryOrder)
            is ApiResult.Failure -> result
        }
}

/** Maps each symptom category slug to its daily-log sub-log endpoint segment. */
internal val SUB_LOG_ENDPOINTS: Map<String, String> =
    mapOf(
        "emotions" to "emotions",
        "sleep_quality" to "sleep",
        "energy" to "energy",
        "sex" to "sex",
        "discharge" to "discharge",
        "skin" to "skin",
        "digestion" to "digestion",
        "blood_flow" to "flow",
        "collection_method" to "collection",
        "mind" to "mind",
    )

internal fun selectionTypeOf(raw: String): SelectionType =
    if (raw == "single") SelectionType.SINGLE else SelectionType.MULTI

/** Resolves option/location ids to display labels and keeps categories in canonical order. */
class Labels(
    val categories: List<SymptomCategoryDto>,
    val painRegions: List<PainRegionDto>,
    private val optionLabels: Map<String, String>,
    private val locationLabels: Map<String, String>,
) {
    fun option(id: String): String = optionLabels[id] ?: id

    fun location(id: String): String = locationLabels[id] ?: id
}

// ── Screen-shaped data ───────────────────────────────────────────────────────

data class DashboardData(
    val currentCycle: CycleDto?,
    val cycleDay: Int?,
    val phase: CyclePhase?,
    val stats: CycleStatsDto,
    val today: DayContent?,
)

/** A day's logged tracking, with every id already resolved to a label. */
data class DayContent(
    val categories: List<ResolvedCategory>,
    val pain: List<ResolvedPain>,
    val notes: String?,
) {
    val isEmpty: Boolean get() = categories.isEmpty() && pain.isEmpty() && notes.isNullOrBlank()
}

data class ResolvedCategory(
    val label: String,
    val values: List<String>,
)

data class ResolvedPain(
    val label: String,
    val severity: Int?,
)

data class AnalyticsData(
    val stats: CycleStatsDto,
    val periodChart: List<PeriodLengthPoint>,
    val ovulation: OvulationPredictionDto,
    val sleep: List<ResolvedSleepPhase>,
)

data class ResolvedSleepPhase(
    val phase: CyclePhase,
    val options: List<String>,
    val sampleSize: Int,
)

// ── Editor-shaped data (Phase 9 write surface) ───────────────────────────────

enum class SelectionType { SINGLE, MULTI }

/** A symptom category as the editor renders it: its options plus how they're chosen and gated. */
data class EditableCategory(
    val slug: String,
    val label: String,
    val selectionType: SelectionType,
    /** `always` or `menstruation` — the editor groups menstruation-only categories separately. */
    val phase: String,
    val options: List<SymptomOptionDto>,
)

/** The mutable state of a day being edited: selected option ids per category, notes, and pain. */
data class DayEdits(
    val selections: Map<String, List<String>>,
    val notes: String?,
    val pain: List<PainLocationDto>,
)

/** Everything the day editor renders off, with [initial] pre-populated from the existing log. */
data class DayEditor(
    val cycleId: String?,
    val categories: List<EditableCategory>,
    val painRegions: List<PainRegionDto>,
    val initial: DayEdits,
) {
    val canLog: Boolean get() = cycleId != null
}

/** A category slug + display label, used by the preferences reorder screen. */
data class CategoryLabel(
    val slug: String,
    val label: String,
)

data class PreferencesEditor(
    val order: List<CategoryLabel>,
)

internal data class CycleDayPhase(
    val day: Int,
    val phase: CyclePhase,
)

// ── Pure shaping helpers (unit-tested directly) ──────────────────────────────

/**
 * Map [HomeFlowDataSource]'s 404-on-absence into a success-with-null, leaving every other
 * failure intact. Used for the open cycle and a day with no log.
 */
internal fun <T> ApiResult<T>.optional(): ApiResult<T?> =
    when (this) {
        is ApiResult.Success -> ApiResult.Success(value)
        is ApiResult.Failure -> if (code == ErrorCode.RESOURCE_NOT_FOUND) ApiResult.Success(null) else this
    }

/** Unwrap a success or short-circuit the calling suspend fun with the [ApiResult.Failure]. */
internal inline fun <T> ApiResult<T>.valueOr(onFailure: (ApiResult.Failure) -> Nothing): T =
    when (this) {
        is ApiResult.Success -> value
        is ApiResult.Failure -> onFailure(this)
    }

internal fun buildLabels(
    categories: SymptomCategoriesResponse,
    regions: PainRegionsResponse,
): Labels =
    Labels(
        categories = categories.categories,
        painRegions = regions.regions,
        optionLabels = categories.categories.flatMap { it.options }.associate { it.id to it.label },
        locationLabels = regions.regions.flatMap { it.locations }.associate { it.id to it.label },
    )

/** 1-based cycle day + predicted phase for [today], using the user's stats (defaults when thin). */
internal fun cycleDayPhase(
    cycleStartIso: String,
    today: LocalDate,
    stats: CycleStatsDto,
): CycleDayPhase {
    val day = cycleDayNumber(LocalDate.parse(cycleStartIso), today)
    val phase =
        predictPhase(
            cycleDay = day,
            avgCycleLength = stats.averageCycleLength ?: DEFAULT_CYCLE_LENGTH,
            avgPeriodLength = stats.averagePeriodLength ?: DEFAULT_PERIOD_LENGTH,
        )
    return CycleDayPhase(day, phase)
}

/**
 * The selected option ids of a day log, keyed by category slug (single-selects become a
 * 0/1-element list). Shared by the read resolver and the editor's initial selections; an
 * absent log is every category empty.
 */
internal fun idsBySlug(log: DailyLogDto?): Map<String, List<String>> =
    if (log == null) {
        emptyMap()
    } else {
        mapOf(
            "emotions" to log.emotions.orEmpty(),
            "sleep_quality" to log.sleep.orEmpty(),
            "energy" to listOfNotNull(log.energy),
            "sex" to log.sex.orEmpty(),
            "discharge" to log.discharge.orEmpty(),
            "skin" to log.skin.orEmpty(),
            "digestion" to log.digestion.orEmpty(),
            "blood_flow" to listOfNotNull(log.flow),
            "collection_method" to listOfNotNull(log.collection),
            "mind" to log.mind.orEmpty(),
        )
    }

/** Resolve a raw day log into labelled categories (canonical order), pain, and notes. */
internal fun resolveDay(
    log: DailyLogDto,
    labels: Labels,
): DayContent {
    val ids = idsBySlug(log)
    val resolved =
        labels.categories.mapNotNull { category ->
            val selected = ids[category.slug].orEmpty()
            if (selected.isEmpty()) {
                null
            } else {
                ResolvedCategory(category.label, selected.map { labels.option(it) })
            }
        }
    return DayContent(resolved, resolvePain(log.pain, labels), log.notes)
}

internal fun resolvePain(
    pain: PainDto?,
    labels: Labels,
): List<ResolvedPain> = pain?.locations?.map { ResolvedPain(labels.location(it.locationId), it.severity) }.orEmpty()

internal fun resolveSleep(
    dto: SleepPredictionsDto,
    labels: Labels,
): List<ResolvedSleepPhase> =
    listOf(
        CyclePhase.MENSTRUATION to dto.phases.menstruation,
        CyclePhase.FOLLICULAR to dto.phases.follicular,
        CyclePhase.OVULATION to dto.phases.ovulation,
        CyclePhase.LUTEAL to dto.phases.luteal,
    ).mapNotNull { (phase, prediction) ->
        prediction?.let { ResolvedSleepPhase(phase, it.mostCommon.map { id -> labels.option(id) }, it.sampleSize) }
    }
