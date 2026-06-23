package org.homeflow.app.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.DayEditor
import org.homeflow.app.shared.data.DayEdits
import org.homeflow.app.shared.data.EditableCategory
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.data.SelectionType
import org.homeflow.app.shared.ui.components.EmptyHint
import org.homeflow.app.shared.ui.components.Loadable
import org.homeflow.app.shared.ui.components.SectionCard
import org.homeflow.app.shared.ui.components.toLoadable
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.dto.PainRegionDto
import org.homeflow.core.validation.MAX_SEVERITY
import org.homeflow.core.validation.MIN_SEVERITY

/**
 * The day-logging editor: editable cards for every symptom category (multi/single-select
 * chips), pain (locations + per-location severity), and notes. Saving pushes only what
 * changed (see [HomeFlowRepository.saveDay]) then calls [onSaved]; [onCancel] discards.
 */
@Composable
fun DayEditorScreen(
    repository: HomeFlowRepository,
    date: LocalDate,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    var reloadKey by remember { mutableStateOf(0) }
    val state by produceState<Loadable<DayEditor>>(Loadable.Loading, repository, date, reloadKey) {
        value = repository.loadDayEditor(date).toLoadable()
    }

    Loadable(state, onRetry = { reloadKey++ }) { editor ->
        if (!editor.canLog) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyHint("No cycle covers this date. Start a cycle on the Cycles tab to log here.")
                OutlinedButton(onClick = onCancel) { Text("Back") }
            }
        } else {
            EditorForm(repository, date, editor, onSaved, onCancel)
        }
    }
}

@Composable
private fun EditorForm(
    repository: HomeFlowRepository,
    date: LocalDate,
    editor: DayEditor,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    var selections by remember(editor) { mutableStateOf(editor.initial.selections) }
    var pain by remember(editor) { mutableStateOf(editor.initial.pain) }
    var notes by remember(editor) { mutableStateOf(editor.initial.notes.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val everyday = editor.categories.filter { it.phase != "menstruation" }
    val menstruation = editor.categories.filter { it.phase == "menstruation" }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        everyday.forEach { category ->
            CategoryEditor(category, selections[category.slug].orEmpty()) {
                selections = selections + (category.slug to it)
            }
        }
        if (menstruation.isNotEmpty()) {
            Text("During menstruation", style = MaterialTheme.typography.titleSmall)
            menstruation.forEach { category ->
                CategoryEditor(category, selections[category.slug].orEmpty()) {
                    selections = selections + (category.slug to it)
                }
            }
        }
        PainEditor(editor.painRegions, pain) { pain = it }
        SectionCard("Notes") {
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Anything worth remembering about today") },
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        SaveBar(
            saving = saving,
            onCancel = onCancel,
            onSave = {
                scope.launch {
                    saving = true
                    error = null
                    val edits = DayEdits(selections, notes, pain)
                    when (val result = repository.saveDay(date, editor, edits)) {
                        is ApiResult.Success -> onSaved()
                        is ApiResult.Failure -> {
                            error = result.message
                            saving = false
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SaveBar(
    saving: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel, enabled = !saving) { Text("Cancel") }
        Button(onClick = onSave, enabled = !saving) {
            if (saving) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
            }
            Text("Save")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditor(
    category: EditableCategory,
    selected: List<String>,
    onChange: (List<String>) -> Unit,
) {
    SectionCard(category.label) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            category.options.forEach { option ->
                val isSelected = option.id in selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onChange(toggleSelection(category.selectionType, selected, option.id)) },
                    label = { Text(option.label) },
                )
            }
        }
    }
}

/** Toggle [optionId]: multi-select adds/removes; single-select picks it (or clears if re-tapped). */
private fun toggleSelection(
    type: SelectionType,
    selected: List<String>,
    optionId: String,
): List<String> =
    when {
        type == SelectionType.SINGLE -> if (optionId in selected) emptyList() else listOf(optionId)
        optionId in selected -> selected - optionId
        else -> selected + optionId
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PainEditor(
    regions: List<PainRegionDto>,
    pain: List<PainLocationDto>,
    onChange: (List<PainLocationDto>) -> Unit,
) {
    SectionCard("Pain") {
        regions.forEach { region ->
            Text(
                region.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                region.locations.forEach { location ->
                    val current = pain.firstOrNull { it.locationId == location.id }
                    FilterChip(
                        selected = current != null,
                        onClick = { onChange(togglePain(pain, location.id)) },
                        label = { Text(location.label) },
                    )
                }
            }
            region.locations.forEach { location ->
                val current = pain.firstOrNull { it.locationId == location.id } ?: return@forEach
                SeverityRow(location.label, current.severity) { severity ->
                    onChange(pain.map { if (it.locationId == location.id) it.copy(severity = severity) else it })
                }
            }
        }
    }
}

private fun togglePain(
    pain: List<PainLocationDto>,
    locationId: String,
): List<PainLocationDto> =
    if (pain.any { it.locationId == locationId }) {
        pain.filterNot { it.locationId == locationId }
    } else {
        pain + PainLocationDto(locationId, null)
    }

@Composable
private fun SeverityRow(
    label: String,
    severity: Int?,
    onChange: (Int?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChange(decrementSeverity(severity)) }) { Text("−") }
            Text(severity?.let { "$it/$MAX_SEVERITY" } ?: "Unrated", style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = { onChange(incrementSeverity(severity)) }) { Text("+") }
        }
    }
}

/** Stepping down past the minimum lands on "unrated" (null). */
private fun decrementSeverity(severity: Int?): Int? =
    when {
        severity == null -> null
        severity <= MIN_SEVERITY -> null
        else -> severity - 1
    }

/** Stepping up from "unrated" (null) starts at the minimum severity. */
private fun incrementSeverity(severity: Int?): Int =
    when {
        severity == null -> MIN_SEVERITY
        severity >= MAX_SEVERITY -> MAX_SEVERITY
        else -> severity + 1
    }
