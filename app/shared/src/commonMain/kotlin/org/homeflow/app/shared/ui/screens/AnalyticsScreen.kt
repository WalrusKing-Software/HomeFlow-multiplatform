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
import org.homeflow.app.shared.data.AnalyticsData
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.data.ResolvedSleepPhase
import org.homeflow.app.shared.ui.components.EmptyHint
import org.homeflow.app.shared.ui.components.KeyValueRow
import org.homeflow.app.shared.ui.components.Loadable
import org.homeflow.app.shared.ui.components.SectionCard
import org.homeflow.app.shared.ui.components.toLoadable
import org.homeflow.core.dto.CycleStatsDto
import org.homeflow.core.dto.OvulationPredictionDto
import org.homeflow.core.dto.PeriodLengthPoint

/** Analytics: cycle stats, period-length chart, ovulation predictions, and sleep by phase. */
@Composable
fun AnalyticsScreen(repository: HomeFlowRepository) {
    var reloadKey by remember { mutableStateOf(0) }
    val state by produceState<Loadable<AnalyticsData>>(Loadable.Loading, repository, reloadKey) {
        value = repository.loadAnalytics().toLoadable()
    }

    Loadable(state, onRetry = { reloadKey++ }) { data ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Cycle stats") { CycleStatsBody(data.stats) }
            SectionCard("Period length") { PeriodLengthBody(data.periodChart) }
            SectionCard("Ovulation prediction") { OvulationBody(data.ovulation) }
            SectionCard("Sleep by phase") { SleepBody(data.sleep) }
        }
    }
}

@Composable
private fun CycleStatsBody(stats: CycleStatsDto) {
    if (stats.averageCycleLength == null && stats.cycleVariation == null && stats.averagePeriodLength == null) {
        EmptyHint("Not enough data yet — track at least two cycles.")
        return
    }
    KeyValueRow("Average cycle length", stats.averageCycleLength?.let { "$it days" } ?: "—")
    KeyValueRow("Cycle variation", stats.cycleVariation?.let { "± $it days" } ?: "—")
    KeyValueRow("Average period length", stats.averagePeriodLength?.let { "$it days" } ?: "—")
}

@Composable
private fun PeriodLengthBody(points: List<PeriodLengthPoint>) {
    if (points.isEmpty()) {
        EmptyHint("No closed cycles to chart yet.")
        return
    }
    points.forEach { KeyValueRow(it.cycleStartDate, "${it.bleedingDays} bleeding days") }
}

@Composable
private fun OvulationBody(prediction: OvulationPredictionDto) {
    val predictions = prediction.predictions
    if (predictions.isNullOrEmpty()) {
        EmptyHint("Not enough data yet — track at least two cycles.")
        return
    }
    prediction.averageCycleLength?.let { KeyValueRow("Average cycle length", "$it days") }
    predictions.forEach {
        KeyValueRow(
            "Period ~ ${it.predictedPeriodStart}",
            "ovulation ~ ${it.predictedOvulationDate}",
        )
    }
}

@Composable
private fun SleepBody(phases: List<ResolvedSleepPhase>) {
    if (phases.isEmpty()) {
        EmptyHint("Not enough sleep data logged yet.")
        return
    }
    phases.forEach { phase ->
        Text(
            "${phase.phase.label} (${phase.sampleSize} days)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(phase.options.joinToString(", "), style = MaterialTheme.typography.bodyLarge)
    }
}
