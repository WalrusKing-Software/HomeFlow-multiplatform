package org.homeflow.app.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.homeflow.app.shared.data.DayContent
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.ui.components.DateStepperField
import org.homeflow.app.shared.ui.components.EmptyHint
import org.homeflow.app.shared.ui.components.Loadable
import org.homeflow.app.shared.ui.components.toLoadable

/** Day view: a date stepper over the resolved log for the chosen day, with an edit affordance. */
@Composable
fun DayScreen(repository: HomeFlowRepository) {
    var date by remember { mutableStateOf(systemToday()) }
    var editing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    if (editing) {
        DayEditorScreen(
            repository = repository,
            date = date,
            onSaved = {
                editing = false
                reloadKey++
            },
            onCancel = { editing = false },
        )
        return
    }

    val state by produceState<Loadable<DayContent?>>(Loadable.Loading, repository, date, reloadKey) {
        value = repository.loadDay(date).toLoadable()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DateStepperField(date = date, onDateChange = { date = it })
        Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit this day") }
        Loadable(state, onRetry = { reloadKey++ }) { content: DayContent? ->
            if (content == null) {
                EmptyHint("Nothing logged on this day.")
            } else {
                DayDetails(content)
            }
        }
    }
}
