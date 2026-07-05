package org.homeflow.app.shared.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import org.homeflow.app.shared.config.ThemeMode
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.data.sync.SyncStatus
import org.homeflow.app.shared.ui.components.kmp.navigation.SideNavRail
import org.homeflow.app.shared.ui.screens.AnalyticsScreen
import org.homeflow.app.shared.ui.screens.CyclesScreen
import org.homeflow.app.shared.ui.screens.DashboardScreen
import org.homeflow.app.shared.ui.screens.DayScreen
import org.homeflow.app.shared.ui.screens.PreferencesScreen
import org.homeflow.core.dto.ImportResultDto

/** Below this window width the shell uses a top tab row; at or above it, a side rail. */
private val RAIL_BREAKPOINT = 600.dp

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

/**
 * Quick light/dark toggle for the wide-layout top bar. Reads the effective scheme (resolving
 * [ThemeMode.SYSTEM] against the OS) and flips to the opposite explicit mode — so one tap always
 * does the visually obvious thing. The full three-way choice (incl. "System") lives in Settings.
 */
@Composable
private fun ThemeToggleButton(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val dark =
        when (themeMode) {
            ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.CLASSIC_DARK -> true
        }
    IconButton(onClick = { onThemeModeChange(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) }) {
        Icon(
            imageVector = if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
            contentDescription = if (dark) "Switch to light theme" else "Switch to dark theme",
        )
    }
}

/** The destinations of the signed-in shell, in order, each with its rail icon. */
private enum class Tab(
    val label: String,
    val icon: ImageVector,
) {
    DASHBOARD("Dashboard", Icons.Filled.Home),
    DAY("Day", Icons.Filled.Today),
    CYCLES("Cycles", Icons.Filled.Autorenew),
    ANALYTICS("Analytics", Icons.Filled.Analytics),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/**
 * The signed-in shell. On narrow windows (phones) the destinations sit in a top tab row;
 * on wide windows (desktop, tablets) they move to a [SideNavRail] alongside the content.
 * Optional callbacks thread through to the Settings tab:
 * - [onExport]: non-null in Mode A — "Export my data".
 * - [onConnectServer]: non-null in Mode A — "Connect to a server".
 * - [onUploadToServer]: non-null in Mode B while unmigrated — "Upload local data to server".
 * - [connectedHost]: non-null in Mode B — displayed in the Server section.
 * - [onSwitchToLocal]: non-null in Mode B — "Switch to local-only mode".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    repository: HomeFlowRepository,
    onLogout: () -> Unit,
    onDeleteAccount: suspend () -> ApiResult<Unit>,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onExport: (suspend () -> Unit)? = null,
    onConnectServer: (() -> Unit)? = null,
    onUploadToServer: (suspend () -> ImportResultDto?)? = null,
    connectedHost: String? = null,
    onSwitchToLocal: (() -> Unit)? = null,
    syncStatusFlow: StateFlow<SyncStatus>? = null,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    val syncStatus by syncStatusFlow?.collectAsState()
        ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<SyncStatus>(SyncStatus.Idle) }

    // On wide (desktop/tablet) layouts the top bar carries a quick light/dark toggle; on narrow
    // (phone) layouts the top bar stays minimal and appearance lives only in Settings.
    val topBar: @Composable (showThemeToggle: Boolean) -> Unit = { showThemeToggle ->
        TopAppBar(
            title = { Text("HomeFlow") },
            actions = {
                if (syncStatusFlow != null) {
                    SyncStatusChip(syncStatus)
                }
                if (showThemeToggle) {
                    ThemeToggleButton(themeMode = themeMode, onThemeModeChange = onThemeModeChange)
                }
                TextButton(onClick = onLogout) { Text("Log out") }
            },
        )
    }

    val screen: @Composable () -> Unit = {
        when (tab) {
            Tab.DASHBOARD -> DashboardScreen(repository)
            Tab.DAY -> DayScreen(repository)
            Tab.CYCLES -> CyclesScreen(repository)
            Tab.ANALYTICS -> AnalyticsScreen(repository)
            Tab.SETTINGS ->
                PreferencesScreen(
                    repository = repository,
                    onDeleteAccount = onDeleteAccount,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onExport = onExport,
                    onConnectServer = onConnectServer,
                    onUploadToServer = onUploadToServer,
                    connectedHost = connectedHost,
                    onSwitchToLocal = onSwitchToLocal,
                )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= RAIL_BREAKPOINT) {
            // Wide: side navigation rail beside the content; top bar carries the quick theme toggle.
            Scaffold(topBar = { topBar(true) }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    SideNavRail(
                        items = Tab.entries,
                        selectedIndex = tab.ordinal,
                        onItemSelected = { tab = Tab.entries[it] },
                        icon = { it.icon },
                        label = { it.label },
                    )
                    Box(Modifier.weight(1f).fillMaxSize()) { screen() }
                }
            }
        } else {
            // Narrow: top tab row under the app bar.
            Scaffold(
                topBar = {
                    Column {
                        topBar(false)
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
                Box(Modifier.fillMaxSize().padding(padding)) { screen() }
            }
        }
    }
}
