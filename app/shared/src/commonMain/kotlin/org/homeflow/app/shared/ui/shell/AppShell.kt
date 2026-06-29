package org.homeflow.app.shared.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.data.sync.SyncStatus
import org.homeflow.app.shared.ui.screens.AnalyticsScreen
import org.homeflow.app.shared.ui.screens.CyclesScreen
import org.homeflow.app.shared.ui.screens.DashboardScreen
import org.homeflow.app.shared.ui.screens.DayScreen
import org.homeflow.app.shared.ui.screens.PreferencesScreen
import org.homeflow.core.dto.ImportResultDto

/** Small sync-status label for the top bar; only shown in Mode C. */
@Composable
private fun SyncStatusChip(status: SyncStatus) {
    val (label, color) =
        when (status) {
            is SyncStatus.Idle -> "Synced" to MaterialTheme.colorScheme.onSurfaceVariant
            is SyncStatus.Syncing -> "Syncing…" to MaterialTheme.colorScheme.primary
            is SyncStatus.Success -> "Synced" to MaterialTheme.colorScheme.onSurfaceVariant
            is SyncStatus.Error -> "Sync error" to MaterialTheme.colorScheme.error
        }
    Text(label, fontSize = 12.sp, color = color)
}

/** The destinations of the signed-in shell, in tab order. */
private enum class Tab(
    val label: String,
) {
    DASHBOARD("Dashboard"),
    DAY("Day"),
    CYCLES("Cycles"),
    ANALYTICS("Analytics"),
    SETTINGS("Settings"),
}

/**
 * The signed-in shell: a top bar with logout, a tab row over the read/write screens, and
 * the selected screen below. Optional callbacks thread through to the Settings tab:
 * - [onExport]: non-null in Mode A — "Export my data".
 * - [onConnectServer]: non-null in Mode A — "Connect to a server".
 * - [onUploadToServer]: non-null in Mode B while unmigrated — "Upload local data to server".
 * - [connectedHost]: non-null in Mode B — displayed in the Server section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    repository: HomeFlowRepository,
    onLogout: () -> Unit,
    onDeleteAccount: suspend () -> ApiResult<Unit>,
    onExport: (suspend () -> Unit)? = null,
    onConnectServer: (() -> Unit)? = null,
    onUploadToServer: (suspend () -> ImportResultDto?)? = null,
    connectedHost: String? = null,
    syncStatusFlow: StateFlow<SyncStatus>? = null,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    val syncStatus by syncStatusFlow?.collectAsState() ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<SyncStatus>(SyncStatus.Idle) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("HomeFlow") },
                    actions = {
                        if (syncStatusFlow != null) {
                            SyncStatusChip(syncStatus)
                        }
                        TextButton(onClick = onLogout) { Text("Log out") }
                    },
                )
                PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    Tab.entries.forEach { entry ->
                        Tab(
                            selected = entry == tab,
                            onClick = { tab = entry },
                            text = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.DASHBOARD -> DashboardScreen(repository)
                Tab.DAY -> DayScreen(repository)
                Tab.CYCLES -> CyclesScreen(repository)
                Tab.ANALYTICS -> AnalyticsScreen(repository)
                Tab.SETTINGS ->
                    PreferencesScreen(
                        repository = repository,
                        onDeleteAccount = onDeleteAccount,
                        onExport = onExport,
                        onConnectServer = onConnectServer,
                        onUploadToServer = onUploadToServer,
                        connectedHost = connectedHost,
                    )
            }
        }
    }
}
