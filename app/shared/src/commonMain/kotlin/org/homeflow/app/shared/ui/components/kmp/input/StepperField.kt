package org.homeflow.app.shared.ui.components.kmp.input

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// StepperField
//
// An integer increment/decrement input bounded by [min, max].
// Fully hoisted — the caller owns `value` and `onValueChange`.
// Decrement is disabled at min; increment is disabled at max.
//
// Usage:
//   var quantity by remember { mutableStateOf(1) }
//   StepperField(
//       value = quantity,
//       onValueChange = { quantity = it },
//       min = 1,
//       max = 99,
//       label = "Quantity",
//   )
// ---------------------------------------------------------------------------

@Composable
fun StepperField(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 0,
    max: Int = Int.MAX_VALUE,
    step: Int = 1,
    label: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }

        Row(
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(50.dp),
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilledIconButton(
                onClick = { if (value - step >= min) onValueChange(value - step) },
                enabled = enabled && value > min,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 36.dp),
            ) {
                Text("−", style = MaterialTheme.typography.titleMedium)
            }

            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                modifier = Modifier.widthIn(min = 36.dp),
            )

            FilledIconButton(
                onClick = { if (value + step <= max) onValueChange(value + step) },
                enabled = enabled && value < max,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 36.dp),
            ) {
                Text("+", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
