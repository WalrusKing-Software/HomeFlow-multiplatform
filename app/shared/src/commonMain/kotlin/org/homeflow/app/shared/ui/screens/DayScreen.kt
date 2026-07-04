package org.homeflow.app.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.DayContent
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.ui.components.DateStepperField
import org.homeflow.app.shared.ui.components.EmptyHint
import org.homeflow.app.shared.ui.components.Loadable
import org.homeflow.app.shared.ui.components.kmp.feedback.ConfirmDialog
import org.homeflow.app.shared.ui.components.toLoadable

/** Day view: a date stepper over the resolved log for the chosen day, with edit and delete affordances. */
@Composable
fun DayScreen(repository: HomeFlowRepository) {
    var date by remember { mutableStateOf(systemToday()) }
    var editing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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

    ConfirmDialog(
        visible = showDeleteDialog,
        title = "Delete this day?",
        body = "This will permanently delete the log for $date. This cannot be undone.",
        confirmLabel = "Delete",
        destructive = true,
        onConfirm = {
            showDeleteDialog = false
            scope.launch {
                when (val result = repository.deleteDay(date.toString())) {
                    is ApiResult.Success -> reloadKey++
                    is ApiResult.Failure -> deleteError = result.message
                }
            }
        },
        onDismiss = { showDeleteDialog = false },
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DateStepperField(date = date, onDateChange = {
            date = it
            deleteError = null
        })
        deleteError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Loadable(state, onRetry = { reloadKey++ }) { content: DayContent? ->
            if (content == null) {
                EmptyHint("Nothing logged on this day.")
                // Entry point to log a day that has no entry yet (e.g. right after starting a
                // cycle). The editor resolves whether a cycle covers this date and either shows
                // the logging form or a "no cycle covers this date" message.
                Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text("Log this day") }
            } else {
                Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit this day") }
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete this day") }
                DayDetails(content)
            }
        }
    }
}
