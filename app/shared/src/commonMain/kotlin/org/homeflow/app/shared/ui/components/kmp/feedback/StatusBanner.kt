package org.homeflow.app.shared.ui.components.kmp.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// StatusBanner
//
// An inline banner for success, warning, error, and info states.
// Uses tonal containers derived from Material 3's color scheme so it adapts
// to any theme without extra configuration.
//
// Optionally shows a dismiss or action button on the trailing edge.
//
// Usage:
//   StatusBanner(
//       status = BannerStatus.Success,
//       message = "Record saved successfully.",
//       actionLabel = "Undo",
//       onAction = { viewModel.undoSave() },
//   )
// ---------------------------------------------------------------------------

enum class BannerStatus { Success, Warning, Error, Info }

@Composable
fun StatusBanner(
    status: BannerStatus,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val (bg, contentColor) = when (status) {
        BannerStatus.Success -> colors.tertiaryContainer to colors.onTertiaryContainer
        BannerStatus.Warning -> Color(0xFFFFF3CD) to Color(0xFF664D03)
        BannerStatus.Error   -> colors.errorContainer to colors.onErrorContainer
        BannerStatus.Info    -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    val label = when (status) {
        BannerStatus.Success -> "✓ "
        BannerStatus.Warning -> "⚠ "
        BannerStatus.Error   -> "✕ "
        BannerStatus.Info    -> "ℹ "
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label + message,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(
                    text = actionLabel,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (onDismiss != null) {
            TextButton(onClick = onDismiss) {
                Text("✕", color = contentColor)
            }
        }
    }
}
