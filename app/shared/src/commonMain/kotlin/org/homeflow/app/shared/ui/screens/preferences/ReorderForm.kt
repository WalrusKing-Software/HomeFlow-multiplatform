package org.homeflow.app.shared.ui.screens.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.CategoryLabel
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.data.PreferencesEditor
import org.homeflow.app.shared.data.userMessage
import org.homeflow.app.shared.ui.components.SectionCard

@Composable
internal fun ReorderForm(
    repository: HomeFlowRepository,
    editor: PreferencesEditor,
) {
    var order by remember(editor) { mutableStateOf(editor.order) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SectionCard("Category order") {
        Text(
            "Arrange the order your tracking categories appear in when logging a day.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        order.forEachIndexed { index, category ->
            ReorderRow(
                category = category,
                canMoveUp = index > 0,
                canMoveDown = index < order.lastIndex,
                onMoveUp = {
                    order = order.swapped(index, index - 1)
                    status = null
                },
                onMoveDown = {
                    order = order.swapped(index, index + 1)
                    status = null
                },
            )
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    status = null
                    when (val result = repository.savePreferences(order.map { it.slug })) {
                        is ApiResult.Success -> status = "Saved."
                        is ApiResult.Failure -> status = result.userMessage()
                    }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
            Text("Save order")
        }
    }
}

@Composable
private fun ReorderRow(
    category: CategoryLabel,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(category.label, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMoveUp, enabled = canMoveUp) { Text("↑") }
            OutlinedButton(onClick = onMoveDown, enabled = canMoveDown) { Text("↓") }
        }
    }
}

private fun <T> List<T>.swapped(
    a: Int,
    b: Int,
): List<T> =
    toMutableList().apply {
        val tmp = this[a]
        this[a] = this[b]
        this[b] = tmp
    }
