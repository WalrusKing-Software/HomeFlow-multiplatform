package org.homeflow.app.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.homeflow.app.shared.data.DayContent
import org.homeflow.app.shared.data.ResolvedPain
import org.homeflow.app.shared.ui.components.EmptyHint
import org.homeflow.app.shared.ui.components.SectionCard

/**
 * Renders one day's resolved log — tracked categories, pain, and notes — shared by the
 * Dashboard "today" card and the Day screen. Shows an empty hint when nothing was logged.
 */
@Composable
fun DayDetails(
    content: DayContent,
    modifier: Modifier = Modifier,
) {
    if (content.isEmpty) {
        EmptyHint("Nothing logged on this day.", modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (content.categories.isNotEmpty()) {
            SectionCard("Tracked") {
                content.categories.forEach { category ->
                    LabeledValues(category.label, category.values.joinToString(", "))
                }
            }
        }
        if (content.pain.isNotEmpty()) {
            SectionCard("Pain") {
                content.pain.forEach { LabeledValues(it.label, severityText(it)) }
            }
        }
        if (!content.notes.isNullOrBlank()) {
            SectionCard("Notes") {
                Text(content.notes, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** A muted category/label above its value(s). */
@Composable
private fun LabeledValues(
    label: String,
    value: String,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun severityText(pain: ResolvedPain): String = pain.severity?.let { "$it/10" } ?: "Selected (unrated)"
