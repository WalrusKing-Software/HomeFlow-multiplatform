package org.homeflow.app.shared.data.local

import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.dto.ExportCycle
import org.homeflow.core.dto.ExportDay
import org.homeflow.core.dto.ExportPain
import org.homeflow.core.dto.HomeFlowExport

/**
 * Reads the local SQLDelight database and emits a [HomeFlowExport] in the Phase 12
 * `homeflow` format — slug-keyed, UUID-free, plaintext notes/sex.
 *
 * This is the client-side counterpart to the server's `ImportExportService.exportAll`.
 * It does NOT call server code; all mapping is done locally using [LocalRefData]'s
 * reverse maps ([LocalRefData.optionSlugById] / [LocalRefData.locationSlugById]).
 */
class LocalExporter(
    private val db: HomeFlowDb,
    private val refData: LocalRefData,
    private val logsStore: LocalDailyLogsStore,
) {
    fun export(): HomeFlowExport {
        val userId = LocalBootstrap.LOCAL_USER_ID
        val cycleRows = db.cyclesQueries.selectAll(userId).executeAsList()

        val optionSlugMap = refData.optionSlugById()
        val locationSlugMap = refData.locationSlugById()

        val cycles = cycleRows.map { ExportCycle(startDate = it.start_date, endDate = it.end_date) }

        val days = mutableListOf<ExportDay>()
        for (cycle in cycleRows) {
            val logs = logsStore.getLogsByCycleId(cycle.id)
            for (logRow in logs) {
                val dto = logsStore.assembleDto(logRow)
                days +=
                    buildExportDay(
                        date = dto.logDate,
                        cycleStartDate = cycle.start_date,
                        flow = dto.flow?.let { optionSlugMap[it] },
                        collectionMethod = dto.collection?.let { optionSlugMap[it] },
                        energy = dto.energy?.let { optionSlugMap[it] },
                        emotions = dto.emotions?.mapNotNull { optionSlugMap[it] } ?: emptyList(),
                        sleep = dto.sleep?.mapNotNull { optionSlugMap[it] } ?: emptyList(),
                        discharge = dto.discharge?.mapNotNull { optionSlugMap[it] } ?: emptyList(),
                        skin = dto.skin?.mapNotNull { optionSlugMap[it] } ?: emptyList(),
                        digestion = dto.digestion?.mapNotNull { optionSlugMap[it] } ?: emptyList(),
                        mind = dto.mind?.mapNotNull { optionSlugMap[it] } ?: emptyList(),
                        sex = dto.sex?.mapNotNull { optionSlugMap[it] } ?: emptyList(),
                        pain =
                            dto.pain?.locations?.mapNotNull { loc ->
                                val slug = locationSlugMap[loc.locationId] ?: return@mapNotNull null
                                ExportPain(location = slug, severity = loc.severity)
                            } ?: emptyList(),
                        notes = dto.notes,
                    )
            }
        }

        return HomeFlowExport(cycles = cycles, days = days)
    }

    private fun buildExportDay(
        date: String,
        cycleStartDate: String,
        flow: String?,
        collectionMethod: String?,
        energy: String?,
        emotions: List<String>,
        sleep: List<String>,
        discharge: List<String>,
        skin: List<String>,
        digestion: List<String>,
        mind: List<String>,
        sex: List<String>,
        pain: List<ExportPain>,
        notes: String?,
    ) = ExportDay(
        date = date,
        cycleStartDate = cycleStartDate,
        flow = flow,
        collectionMethod = collectionMethod,
        energy = energy,
        emotions = emotions,
        sleep = sleep,
        discharge = discharge,
        skin = skin,
        digestion = digestion,
        mind = mind,
        sex = sex,
        pain = pain,
        notes = notes,
    )
}
