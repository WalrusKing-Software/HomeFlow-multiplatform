package org.homeflow.app.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import org.homeflow.app.shared.ui.components.kmp.accordion.EditingState
import org.homeflow.app.shared.ui.components.kmp.accordion.PainLocation
import org.homeflow.app.shared.ui.components.kmp.accordion.PainLocationRating
import org.homeflow.app.shared.ui.components.kmp.accordion.PainRegion
import org.homeflow.app.shared.ui.components.kmp.accordion.PainRegionAccordion
import org.homeflow.app.shared.ui.components.kmp.feedback.BannerStatus
import org.homeflow.app.shared.ui.components.kmp.feedback.LoadingOverlay
import org.homeflow.app.shared.ui.components.kmp.feedback.StatusBanner
import org.homeflow.app.shared.ui.components.kmp.input.ToggleChipGroup
import org.homeflow.app.shared.ui.components.toLoadable
import org.homeflow.core.dto.PainLocationDto
import org.homeflow.core.dto.PainRegionDto

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

    Box(Modifier.fillMaxSize()) {
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
            error?.let { StatusBanner(status = BannerStatus.Error, message = it) }
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
        LoadingOverlay(visible = saving, message = "Saving…")
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

@Composable
private fun CategoryEditor(
    category: EditableCategory,
    selected: List<String>,
    onChange: (List<String>) -> Unit,
) {
    SectionCard(category.label) {
        ToggleChipGroup(
            options = category.options,
            selected = category.options.filter { it.id in selected }.toSet(),
            onSelectionChange = { chosen ->
                // Emit ids in the category's display order, not selection order.
                onChange(category.options.filter { it in chosen }.map { it.id })
            },
            label = { it.label },
            multiSelect = category.selectionType == SelectionType.MULTI,
        )
    }
}

/**
 * Pain tracking: collapsible region rows (Head & Neck, Back, Abdomen, …), each
 * expanding to multi-select location chips with an inline 1–10 severity editor.
 * See [PainRegionAccordion]. [editing] holds the transient open-editor draft;
 * only committed selections land in [pain] via [onChange].
 */
@Composable
private fun PainEditor(
    regions: List<PainRegionDto>,
    pain: List<PainLocationDto>,
    onChange: (List<PainLocationDto>) -> Unit,
) {
    var editing by remember { mutableStateOf<EditingState?>(null) }

    val accordionRegions =
        remember(regions) {
            regions.map { region ->
                PainRegion(
                    id = region.id,
                    label = region.label,
                    locations = region.locations.map { PainLocation(it.id, it.label) },
                )
            }
        }
    val selected = pain.map { PainLocationRating(it.locationId, it.severity) }

    SectionCard("Pain") {
        PainRegionAccordion(
            regions = accordionRegions,
            selected = selected,
            editing = editing,
            severityOf = { id -> pain.firstOrNull { it.locationId == id }?.severity },
            onOpenEditor = { id ->
                editing = EditingState(id, pain.firstOrNull { it.locationId == id }?.severity)
            },
            onSetDraftSeverity = { severity -> editing = editing?.copy(severity = severity) },
            onCommit = {
                editing?.let { draft ->
                    onChange(upsertPain(pain, draft.locationId, draft.severity))
                }
                editing = null
            },
            onCancel = { editing = null },
            onRemove = { id ->
                onChange(pain.filterNot { it.locationId == id })
                if (editing?.locationId == id) editing = null
            },
        )
    }
}

/** Add [locationId] with [severity], or update its severity if already present. */
private fun upsertPain(
    pain: List<PainLocationDto>,
    locationId: String,
    severity: Int?,
): List<PainLocationDto> =
    if (pain.any { it.locationId == locationId }) {
        pain.map { if (it.locationId == locationId) it.copy(severity = severity) else it }
    } else {
        pain + PainLocationDto(locationId, severity)
    }
