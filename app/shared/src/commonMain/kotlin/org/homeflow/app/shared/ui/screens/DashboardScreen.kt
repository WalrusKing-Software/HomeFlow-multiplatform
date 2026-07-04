package org.homeflow.app.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.homeflow.app.shared.data.DashboardData
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.ui.components.EmptyHint
import org.homeflow.app.shared.ui.components.KeyValueRow
import org.homeflow.app.shared.ui.components.Loadable
import org.homeflow.app.shared.ui.components.SectionCard
import org.homeflow.app.shared.ui.components.toLoadable
import org.homeflow.core.dto.CycleStatsDto

/** Dashboard: current cycle + phase, at-a-glance stats, and today's log. */
@Composable
fun DashboardScreen(repository: HomeFlowRepository) {
    val today = remember { systemToday() }
    var reloadKey by remember { mutableStateOf(0) }
    val state by produceState<Loadable<DashboardData>>(Loadable.Loading, repository, reloadKey, today) {
        value = repository.loadDashboard(today).toLoadable()
    }

    Loadable(state, onRetry = { reloadKey++ }) { data ->
        DashboardContent(data, today)
    }
}

@Composable
private fun DashboardContent(
    data: DashboardData,
    today: LocalDate,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard("Current cycle") {
            val cycle = data.currentCycle
            if (cycle == null) {
                EmptyHint("No open cycle. Start one to begin tracking.")
            } else {
                Text(
                    phaseHeadline(data),
                    style = MaterialTheme.typography.headlineSmall,
                )
                KeyValueRow("Started", cycle.startDate)
            }
        }

        SectionCard("At a glance") {
            StatsBody(data.stats)
        }

        SectionCard("Today · $today") {
            val todayContent = data.today
            if (todayContent == null) {
                EmptyHint("Nothing logged today yet.")
            } else {
                DayDetails(todayContent)
            }
        }
    }
}

/** "Day N · Phase" when an open cycle exists. */
private fun phaseHeadline(data: DashboardData): String {
    val day = data.cycleDay
    val phase = data.phase
    return if (day != null && phase != null) "Day $day · ${phase.label}" else "Open cycle"
}

@Composable
private fun StatsBody(stats: CycleStatsDto) {
    if (stats.averageCycleLength == null && stats.cycleVariation == null && stats.averagePeriodLength == null) {
        EmptyHint("Not enough data yet — track a couple of cycles to see your averages.")
        return
    }
    KeyValueRow("Average cycle length", stats.averageCycleLength?.let { "$it days" } ?: "—")
    KeyValueRow("Cycle variation", stats.cycleVariation?.let { "± $it days" } ?: "—")
    KeyValueRow("Average period length", stats.averagePeriodLength?.let { "$it days" } ?: "—")
}
