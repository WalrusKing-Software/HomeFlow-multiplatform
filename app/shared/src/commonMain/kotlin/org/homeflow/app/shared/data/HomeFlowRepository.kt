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
import org.homeflow.core.dto.PainRegionsResponse
import org.homeflow.core.dto.PeriodLengthPoint
import org.homeflow.core.dto.SleepPredictionsDto
import org.homeflow.core.dto.SymptomCategoriesResponse
import org.homeflow.core.dto.SymptomCategoryDto

/**
 * The read surface the four Phase 8 screens render off. It owns the **in-memory ref-data
 * label cache** (CLAUDE.md: no health data at rest, caches in memory only) and composes
 * raw API responses into screen-shaped data with option/location ids already resolved to
 * labels.
 *
 * Routes that 404 on "nothing tracked yet" (the open cycle, a day with no log) are folded
 * into an absence (`null`) rather than an error — see [optional]. Everything else
 * propagates as an [ApiResult.Failure] so screens show a retryable error.
 *
 * Phase/label math delegates to `:core` (`predictPhase`, `cycleDayNumber`); this class adds
 * no domain rules of its own beyond shaping.
 */
class HomeFlowRepository(
    private val api: HomeFlowApi,
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
}

/** Resolves option/location ids to display labels and keeps categories in canonical order. */
class Labels(
    val categories: List<SymptomCategoryDto>,
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

internal data class CycleDayPhase(
    val day: Int,
    val phase: CyclePhase,
)

// ── Pure shaping helpers (unit-tested directly) ──────────────────────────────

/**
 * Map [HomeFlowApi]'s 404-on-absence into a success-with-null, leaving every other
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

/** Resolve a raw day log into labelled categories (canonical order), pain, and notes. */
internal fun resolveDay(
    log: DailyLogDto,
    labels: Labels,
): DayContent {
    val idsBySlug: Map<String, List<String>> =
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
    val resolved =
        labels.categories.mapNotNull { category ->
            val ids = idsBySlug[category.slug].orEmpty()
            if (ids.isEmpty()) {
                null
            } else {
                ResolvedCategory(category.label, ids.map { labels.option(it) })
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
