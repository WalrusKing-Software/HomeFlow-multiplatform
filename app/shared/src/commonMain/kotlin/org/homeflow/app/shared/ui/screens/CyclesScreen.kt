package org.homeflow.app.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.ui.components.EmptyHint
import org.homeflow.app.shared.ui.components.KeyValueRow
import org.homeflow.app.shared.ui.components.Loadable
import org.homeflow.app.shared.ui.components.SectionCard
import org.homeflow.app.shared.ui.components.toLoadable
import org.homeflow.core.domain.cycleLength
import org.homeflow.core.dto.CycleDto

/** Cycles list: every cycle newest-first, with derived length and open/closed status. */
@Composable
fun CyclesScreen(repository: HomeFlowRepository) {
    var reloadKey by remember { mutableStateOf(0) }
    val state by produceState<Loadable<List<CycleDto>>>(Loadable.Loading, repository, reloadKey) {
        value = repository.loadCycles().toLoadable()
    }

    Loadable(state, onRetry = { reloadKey++ }) { cycles ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (cycles.isEmpty()) {
                EmptyHint("No cycles yet.")
            } else {
                cycles.forEach { CycleCard(it) }
            }
        }
    }
}

@Composable
private fun CycleCard(cycle: CycleDto) {
    val title = if (cycle.endDate == null) "${cycle.startDate} → open" else "${cycle.startDate} → ${cycle.endDate}"
    SectionCard(title) {
        val length = cycle.endDate?.let { cycleLength(LocalDate.parse(cycle.startDate), LocalDate.parse(it)) }
        KeyValueRow("Status", if (cycle.endDate == null) "Open" else "Closed")
        KeyValueRow("Length", length?.let { "$it days" } ?: "In progress")
    }
}
