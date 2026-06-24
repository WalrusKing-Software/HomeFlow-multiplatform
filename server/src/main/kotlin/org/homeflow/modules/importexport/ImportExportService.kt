package org.homeflow.modules.importexport

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.homeflow.core.dto.CreateDailyLogRequest
import org.homeflow.core.dto.ExportCycle
import org.homeflow.core.dto.ExportDay
import org.homeflow.core.dto.ExportPain
import org.homeflow.core.dto.HomeFlowExport
import org.homeflow.core.dto.ImportResultDto
import org.homeflow.core.dto.NotesUpdateRequest
import org.homeflow.core.dto.OptionIdRequest
import org.homeflow.core.dto.OptionIdsRequest
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.dto.PainUpdateRequest
import org.homeflow.lib.AppException
import org.homeflow.lib.ValidationException
import org.homeflow.lib.parseIsoDate
import org.homeflow.modules.cycles.CycleRow
import org.homeflow.modules.cycles.CyclesRepository
import org.homeflow.modules.dailylogs.DailyLogSubsService
import org.homeflow.modules.dailylogs.DailyLogsRepository
import org.homeflow.modules.dailylogs.DailyLogsService
import org.homeflow.modules.refdata.RefDataRepository
import org.homeflow.modules.users.UserPrincipal
import java.util.UUID

/**
 * The native `homeflow` export/import format (`__docs/API.md` "Import & Export",
 * `__docs/IMPLEMENTATION-PHASES-modular-offline.md` Phase 12). The wire shape is
 * slug-and-date-keyed and UUID-free (D4); this service is the only place that maps
 * slugs to this database's option/location UUIDs and back. Both directions go
 * through the existing [DailyLogsService]/[DailyLogSubsService] so decryption (on
 * export) and validation + encryption (on import) stay identical to a normal
 * read/write (D-12.6) — never decrypted or written here directly.
 */
class ImportExportService(
    private val cyclesRepository: CyclesRepository,
    private val dailyLogsRepository: DailyLogsRepository,
    private val dailyLogsService: DailyLogsService,
    private val dailyLogSubsService: DailyLogSubsService,
    private val refDataRepository: RefDataRepository,
) {
    /** Every cycle and logged day for the user, decrypted, slug-keyed (D4). */
    fun exportAll(principal: UserPrincipal): HomeFlowExport {
        val cycles = cyclesRepository.findAllByUser(principal.id).sortedBy { it.startDate }
        val cycleStartDateById = cycles.associate { it.id to it.startDate.toString() }

        val optionSlugById = refDataRepository.symptomOptions().associate { it.id.toString() to it.slug }
        val locationSlugById = refDataRepository.painLocations().associate { it.id.toString() to it.slug }

        val days =
            dailyLogsRepository.findAllByUser(principal.id).map { anchor ->
                val day = dailyLogsService.getDailyLog(principal, anchor.logDate.toString())
                ExportDay(
                    date = day.logDate,
                    cycleStartDate = cycleStartDateById[anchor.cycleId].orEmpty(),
                    flow = day.flow?.let(optionSlugById::get),
                    collectionMethod = day.collection?.let(optionSlugById::get),
                    energy = day.energy?.let(optionSlugById::get),
                    emotions = day.emotions.orEmpty().mapNotNull(optionSlugById::get),
                    sleep = day.sleep.orEmpty().mapNotNull(optionSlugById::get),
                    discharge = day.discharge.orEmpty().mapNotNull(optionSlugById::get),
                    skin = day.skin.orEmpty().mapNotNull(optionSlugById::get),
                    digestion = day.digestion.orEmpty().mapNotNull(optionSlugById::get),
                    mind = day.mind.orEmpty().mapNotNull(optionSlugById::get),
                    sex = day.sex.orEmpty().mapNotNull(optionSlugById::get),
                    pain =
                        day.pain?.locations?.mapNotNull { location ->
                            locationSlugById[location.locationId]?.let { ExportPain(it, location.severity) }
                        } ?: emptyList(),
                    notes = day.notes,
                )
            }
        return HomeFlowExport(
            cycles = cycles.map { ExportCycle(it.startDate.toString(), it.endDate?.toString()) },
            days = days,
        )
    }

    /** Imports a `homeflow` export; additive and idempotent (D-12.7/D-12.8). */
    fun importHomeflow(
        principal: UserPrincipal,
        json: String,
    ): ImportResultDto {
        @Suppress("SwallowedException")
        val export =
            try {
                JSON.decodeFromString<HomeFlowExport>(json)
            } catch (e: SerializationException) {
                throw ValidationException("The import file is not a valid homeflow export: ${e.message}")
            }

        val categoryIdToSlug = refDataRepository.symptomCategories().associate { it.id to it.slug }
        val optionIdByCategoryAndSlug =
            refDataRepository
                .symptomOptions()
                .associate { (categoryIdToSlug[it.categoryId] to it.slug) to it.id }
        val locationIdBySlug = refDataRepository.painLocations().associate { it.slug to it.id }
        val warnings = mutableListOf<String>()

        var cyclesCreated = 0
        val cyclesByStartDate =
            cyclesRepository.findAllByUser(principal.id).associateByTo(mutableMapOf()) { it.startDate }
        for (exportCycle in export.cycles) {
            val startDate = parseIsoDate(exportCycle.startDate, "cycles[].startDate")
            if (startDate in cyclesByStartDate) continue
            val endDate = exportCycle.endDate?.let { parseIsoDate(it, "cycles[].endDate") }
            cyclesByStartDate[startDate] = cyclesRepository.insertExplicit(principal.id, startDate, endDate)
            cyclesCreated++
        }

        var dailyLogsCreated = 0
        var dailyLogsSkipped = 0
        for (exportDay in export.days) {
            val outcome =
                importDay(
                    principal,
                    exportDay,
                    cyclesByStartDate,
                    optionIdByCategoryAndSlug,
                    locationIdBySlug,
                    warnings,
                )
            when (outcome) {
                DayOutcome.CREATED -> dailyLogsCreated++
                DayOutcome.SKIPPED -> dailyLogsSkipped++
            }
        }

        return ImportResultDto(cyclesCreated, dailyLogsCreated, dailyLogsSkipped, warnings)
    }

    private enum class DayOutcome { CREATED, SKIPPED }

    @Suppress("LongParameterList")
    private fun importDay(
        principal: UserPrincipal,
        exportDay: ExportDay,
        cyclesByStartDate: Map<LocalDate, CycleRow>,
        optionIdByCategoryAndSlug: Map<Pair<String?, String>, UUID>,
        locationIdBySlug: Map<String, UUID>,
        warnings: MutableList<String>,
    ): DayOutcome {
        val date = parseIsoDate(exportDay.date, "days[].date")
        if (dailyLogsRepository.findByDate(principal.id, date) != null) return DayOutcome.SKIPPED

        val cycleStartDate = parseIsoDate(exportDay.cycleStartDate, "days[].cycleStartDate")
        val cycle = cyclesByStartDate[cycleStartDate]
        if (cycle == null) {
            warnings += "Day ${exportDay.date} references unknown cycle ${exportDay.cycleStartDate}; skipped."
            return DayOutcome.SKIPPED
        }

        val createRequest = CreateDailyLogRequest(date = exportDay.date, cycleId = cycle.id.toString())
        val anchor =
            try {
                dailyLogsService.createAnchor(principal, createRequest)
            } catch (e: AppException) {
                warnings += "Day ${exportDay.date} could not be imported (${e.message}); skipped."
                return DayOutcome.SKIPPED
            }

        importSubLogs(principal, anchor.logDate, exportDay, optionIdByCategoryAndSlug, locationIdBySlug, warnings)
        return DayOutcome.CREATED
    }

    @Suppress("LongParameterList", "CyclomaticComplexMethod") // one category per symptom sub-log, by design
    private fun importSubLogs(
        principal: UserPrincipal,
        dateStr: String,
        exportDay: ExportDay,
        optionIdByCategoryAndSlug: Map<Pair<String?, String>, UUID>,
        locationIdBySlug: Map<String, UUID>,
        warnings: MutableList<String>,
    ) {
        fun resolveMulti(
            slugs: List<String>,
            categorySlug: String,
        ): List<String> =
            slugs.mapNotNull { slug ->
                val id = optionIdByCategoryAndSlug[categorySlug to slug]
                if (id == null) warnings += "Unknown $categorySlug option '$slug' on $dateStr was dropped."
                id?.toString()
            }

        fun resolveSingle(
            slug: String?,
            categorySlug: String,
        ): String? {
            if (slug == null) return null
            val id = optionIdByCategoryAndSlug[categorySlug to slug]
            if (id == null) warnings += "Unknown $categorySlug option '$slug' on $dateStr was dropped."
            return id?.toString()
        }

        fun multi(
            values: List<String>,
            categorySlug: String,
            apply: (List<String>) -> Unit,
        ) = Triple(values, categorySlug, apply)

        val multiCategories =
            listOf(
                multi(exportDay.emotions, "emotions") {
                    dailyLogSubsService.setEmotions(principal, dateStr, OptionIdsRequest(it))
                },
                multi(exportDay.sleep, "sleep_quality") {
                    dailyLogSubsService.setSleep(principal, dateStr, OptionIdsRequest(it))
                },
                multi(exportDay.discharge, "discharge") {
                    dailyLogSubsService.setDischarge(principal, dateStr, OptionIdsRequest(it))
                },
                multi(exportDay.skin, "skin") { dailyLogSubsService.setSkin(principal, dateStr, OptionIdsRequest(it)) },
                multi(exportDay.digestion, "digestion") {
                    dailyLogSubsService.setDigestion(principal, dateStr, OptionIdsRequest(it))
                },
                multi(exportDay.mind, "mind") { dailyLogSubsService.setMind(principal, dateStr, OptionIdsRequest(it)) },
                multi(exportDay.sex, "sex") { dailyLogSubsService.setSex(principal, dateStr, OptionIdsRequest(it)) },
            )
        for ((slugs, categorySlug, apply) in multiCategories) {
            if (slugs.isNotEmpty()) apply(resolveMulti(slugs, categorySlug))
        }

        fun single(
            value: String?,
            categorySlug: String,
            apply: (String) -> Unit,
        ) = Triple(value, categorySlug, apply)

        val singleCategories =
            listOf(
                single(exportDay.energy, "energy") {
                    dailyLogSubsService.setEnergy(principal, dateStr, OptionIdRequest(it))
                },
                single(exportDay.flow, "blood_flow") {
                    dailyLogSubsService.setFlow(principal, dateStr, OptionIdRequest(it))
                },
                single(exportDay.collectionMethod, "collection_method") {
                    dailyLogSubsService.setCollection(principal, dateStr, OptionIdRequest(it))
                },
            )
        for ((slug, categorySlug, apply) in singleCategories) {
            resolveSingle(slug, categorySlug)?.let(apply)
        }

        if (exportDay.pain.isNotEmpty()) {
            val locations =
                exportDay.pain.mapNotNull { pain ->
                    val id = locationIdBySlug[pain.location]
                    if (id == null) {
                        warnings += "Unknown pain location '${pain.location}' on $dateStr was dropped."
                        null
                    } else {
                        PainLocationDto(locationId = id.toString(), severity = pain.severity)
                    }
                }
            dailyLogSubsService.setPain(principal, dateStr, PainUpdateRequest(locations))
        }
        if (exportDay.notes != null) {
            try {
                dailyLogsService.updateNotes(principal, dateStr, NotesUpdateRequest(exportDay.notes))
            } catch (e: ValidationException) {
                warnings += "Notes on $dateStr were dropped (${e.message})."
            }
        }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
