package org.homeflow.app.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.datetime.LocalDate
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.ui.components.DateStepperField
import org.homeflow.app.shared.ui.components.EmptyHint
import org.homeflow.app.shared.ui.components.KeyValueRow
import org.homeflow.app.shared.ui.components.Loadable
import org.homeflow.app.shared.ui.components.SectionCard
import org.homeflow.app.shared.ui.components.toLoadable
import org.homeflow.core.domain.cycleLength
import org.homeflow.core.dto.CycleDto
import org.homeflow.core.validation.ValidationResult
import org.homeflow.core.validation.validateCycleEnd
import org.homeflow.core.validation.validateCycleStart

/** Cycles list with start/close controls: every cycle newest-first, plus a "start new cycle" form. */
@Composable
fun CyclesScreen(repository: HomeFlowRepository) {
    var reloadKey by remember { mutableStateOf(0) }
    val state by produceState<Loadable<List<CycleDto>>>(Loadable.Loading, repository, reloadKey) {
        value = repository.loadCycles().toLoadable()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StartCycleCard(repository, onStarted = { reloadKey++ })
        Loadable(state, onRetry = { reloadKey++ }) { cycles ->
            if (cycles.isEmpty()) {
                EmptyHint("No cycles yet.")
            } else {
                cycles.forEach { CycleCard(repository, it, onClosed = { reloadKey++ }, onDeleted = { reloadKey++ }) }
            }
        }
    }
}

@Composable
private fun StartCycleCard(
    repository: HomeFlowRepository,
    onStarted: () -> Unit,
) {
    val today = remember { systemToday() }
    var start by remember { mutableStateOf(today) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val validation = validateCycleStart(start, today)

    SectionCard("Start a new cycle") {
        Text(
            "Starting a cycle automatically closes the current open one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DateStepperField(date = start, onDateChange = { start = it }, maxDate = today)
        InvalidHint(validation)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    error = null
                    when (val result = repository.startCycle(start)) {
                        is ApiResult.Success -> {
                            busy = false
                            onStarted()
                        }
                        is ApiResult.Failure -> {
                            error = result.message
                            busy = false
                        }
                    }
                }
            },
            enabled = !busy && validation.isValid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
            Text("Start cycle")
        }
    }
}

@Composable
private fun CycleCard(
    repository: HomeFlowRepository,
    cycle: CycleDto,
    onClosed: () -> Unit,
    onDeleted: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val title = if (cycle.endDate == null) "${cycle.startDate} → open" else "${cycle.startDate} → ${cycle.endDate}"

    if (showDeleteDialog) {
        DeleteCycleDialog(
            cycleTitle = title,
            onConfirm = {
                showDeleteDialog = false
                scope.launch {
                    when (val result = repository.deleteCycle(cycle.id)) {
                        is ApiResult.Success -> onDeleted()
                        is ApiResult.Failure -> deleteError = result.message
                    }
                }
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    SectionCard(title) {
        val length = cycle.endDate?.let { cycleLength(LocalDate.parse(cycle.startDate), LocalDate.parse(it)) }
        KeyValueRow("Status", if (cycle.endDate == null) "Open" else "Closed")
        KeyValueRow("Length", length?.let { "$it days" } ?: "In progress")
        deleteError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (cycle.endDate == null) {
            CloseCycleControls(repository, cycle, onClosed)
        }
        OutlinedButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Delete cycle") }
    }
}

@Composable
private fun DeleteCycleDialog(
    cycleTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete cycle?") },
        text = {
            Text(
                "This will permanently delete \"$cycleTitle\" and all its daily logs. " +
                    "This cannot be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CloseCycleControls(
    repository: HomeFlowRepository,
    cycle: CycleDto,
    onClosed: () -> Unit,
) {
    val today = remember { systemToday() }
    val start = remember(cycle) { LocalDate.parse(cycle.startDate) }
    var end by remember(cycle) { mutableStateOf(today) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val validation = validateCycleEnd(start, end, today)

    Text("Close this cycle", style = MaterialTheme.typography.labelMedium)
    DateStepperField(date = end, onDateChange = { end = it }, minDate = start, maxDate = today)
    InvalidHint(validation)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    OutlinedButton(
        onClick = {
            scope.launch {
                busy = true
                error = null
                when (val result = repository.closeCycle(cycle.id, end)) {
                    is ApiResult.Success -> {
                        busy = false
                        onClosed()
                    }
                    is ApiResult.Failure -> {
                        error = result.message
                        busy = false
                    }
                }
            }
        },
        enabled = !busy && validation.isValid,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
        Text("Close cycle")
    }
}

@Composable
private fun InvalidHint(result: ValidationResult) {
    if (result is ValidationResult.Invalid) {
        Text(result.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
