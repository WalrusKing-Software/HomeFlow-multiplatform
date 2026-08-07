package org.homeflow.app.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.data.PreferencesEditor
import org.homeflow.app.shared.data.sync.SyncStatus
import org.homeflow.app.shared.data.userFacingMessage
import org.homeflow.app.shared.ui.components.Loadable
import org.homeflow.app.shared.ui.components.SectionCard
import org.homeflow.app.shared.ui.components.toLoadable
import org.homeflow.app.shared.ui.screens.preferences.AppearanceSection
import org.homeflow.app.shared.ui.screens.preferences.DangerZone
import org.homeflow.app.shared.ui.screens.preferences.ExportSection
import org.homeflow.app.shared.ui.screens.preferences.ReorderForm
import org.homeflow.app.shared.ui.screens.preferences.ServerSection
import org.homeflow.core.dto.ImportResultDto

/**
 * Settings: reorder the dashboard tracking categories and persist them, a danger zone for
 * permanent account deletion, and mode-specific actions:
 * - Mode A: "Export my data" and "Connect to a server".
 * - Mode B: connected host, and (while not yet migrated) "Upload local data to server".
 */
@Composable
fun PreferencesScreen(
    repository: HomeFlowRepository,
    onDeleteAccount: suspend () -> ApiResult<Unit>,
    onExport: (suspend () -> Unit)? = null,
    onConnectServer: (() -> Unit)? = null,
    onUploadToServer: (suspend () -> ImportResultDto?)? = null,
    connectedHost: String? = null,
    onSwitchToLocal: (() -> Unit)? = null,
    onSyncNow: (suspend () -> Unit)? = null,
    syncStatus: SyncStatus? = null,
) {
    var reloadKey by remember { mutableStateOf(0) }
    val state by produceState<Loadable<PreferencesEditor>>(Loadable.Loading, repository, reloadKey) {
        value = repository.loadPreferences().toLoadable()
    }

    // One scrolling column so the danger zone is reachable regardless of the preferences
    // load state. The shared `Loadable` fills max size and can't live inside a scroll, so the
    // three states are rendered inline here with bounded heights.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppearanceSection()
        when (val current = state) {
            is Loadable.Loading ->
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

            is Loadable.Error ->
                SectionCard("Category order") {
                    Text(
                        userFacingMessage(current.code, current.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { reloadKey++ }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }

            is Loadable.Loaded -> ReorderForm(repository, current.value)
        }
        if (onExport != null) ExportSection(onExport)
        ServerSection(
            connectedHost = connectedHost,
            onConnectServer = onConnectServer,
            onUploadToServer = onUploadToServer,
            onSwitchToLocal = onSwitchToLocal,
            onSyncNow = onSyncNow,
            syncStatus = syncStatus,
        )
        DangerZone(onDeleteAccount)
    }
}
