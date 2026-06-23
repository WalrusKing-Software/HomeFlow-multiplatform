package org.homeflow.app.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.homeflow.app.shared.data.DayContent
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.ui.components.EmptyHint
import org.homeflow.app.shared.ui.components.Loadable
import org.homeflow.app.shared.ui.components.toLoadable

/** Day view: a date stepper over the resolved log for the chosen day. */
@Composable
fun DayScreen(repository: HomeFlowRepository) {
    var date by remember { mutableStateOf(systemToday()) }
    var reloadKey by remember { mutableStateOf(0) }
    val state by produceState<Loadable<DayContent?>>(Loadable.Loading, repository, date, reloadKey) {
        value = repository.loadDay(date).toLoadable()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DateStepper(
            date = date,
            onPrevious = { date = date.minus(1, DateTimeUnit.DAY) },
            onNext = { date = date.plus(1, DateTimeUnit.DAY) },
        )
        Loadable(state, onRetry = { reloadKey++ }) { content: DayContent? ->
            if (content == null) {
                EmptyHint("Nothing logged on this day.")
            } else {
                DayDetails(content)
            }
        }
    }
}

@Composable
private fun DateStepper(
    date: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onPrevious) { Text("‹ Prev") }
        Text(
            date.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onNext) { Text("Next ›") }
    }
}
