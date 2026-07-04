package org.homeflow.app.shared.ui.components.kmp.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// ToggleChipGroup
//
// A wrapping row of FilterChips that supports single-select or multi-select.
// Fully hoisted — the caller owns the selected set and all callbacks.
//
// Type parameter T can be any type (String, enum, data class, etc.) as long
// as equality works correctly for it.
//
// Usage — single select:
//   var selected by remember { mutableStateOf(setOf("Option A")) }
//   ToggleChipGroup(
//       options = listOf("Option A", "Option B", "Option C"),
//       selected = selected,
//       onSelectionChange = { selected = it },
//       multiSelect = false,
//       label = { it },
//   )
//
// Usage — multi select:
//   var tags by remember { mutableStateOf(setOf<String>()) }
//   ToggleChipGroup(
//       options = listOf("Urgent", "Review", "Blocked", "Done"),
//       selected = tags,
//       onSelectionChange = { tags = it },
//       multiSelect = true,
//       label = { it },
//   )
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ToggleChipGroup(
    options: List<T>,
    selected: Set<T>,
    onSelectionChange: (Set<T>) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    multiSelect: Boolean = false,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option in selected
            FilterChip(
                selected = isSelected,
                onClick = {
                    onSelectionChange(
                        when {
                            multiSelect && isSelected -> selected - option
                            multiSelect -> selected + option
                            isSelected -> emptySet()
                            else -> setOf(option)
                        },
                    )
                },
                label = { Text(label(option)) },
                enabled = enabled,
            )
        }
    }
}
