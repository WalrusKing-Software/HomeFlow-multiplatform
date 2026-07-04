package org.homeflow.app.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * First-run chooser shown when no [org.homeflow.app.shared.config.AppMode] is persisted.
 * Two buttons: "Use this device only" (Mode A — local store, no server) and "Connect to a
 * server" (Mode B — today's Keycloak OIDC flow). The choice is persisted by [AppRoot]
 * before navigating away.
 */
@Composable
fun ModeChooserScreen(
    onLocal: () -> Unit,
    onServer: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeContentPadding()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Welcome to HomeFlow", style = MaterialTheme.typography.headlineMedium)
        Text(
            "How would you like to use HomeFlow?",
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onLocal,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use this device only")
            }
            Text(
                "All data stays on this device. No server or account required.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            OutlinedButton(
                onClick = onServer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect to a server")
            }
            Text(
                "Sign in to a HomeFlow server you're already running, for backup and multi-device " +
                    "access. Requires a running server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                "You can change this later in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
