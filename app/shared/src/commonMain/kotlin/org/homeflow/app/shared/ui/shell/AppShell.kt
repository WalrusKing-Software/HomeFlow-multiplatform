package org.homeflow.app.shared.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.ui.screens.AnalyticsScreen
import org.homeflow.app.shared.ui.screens.CyclesScreen
import org.homeflow.app.shared.ui.screens.DashboardScreen
import org.homeflow.app.shared.ui.screens.DayScreen

/** The four read destinations of the signed-in shell, in tab order. */
private enum class Tab(
    val label: String,
) {
    DASHBOARD("Dashboard"),
    DAY("Day"),
    CYCLES("Cycles"),
    ANALYTICS("Analytics"),
}

/**
 * The signed-in shell: a top bar with logout, a tab row over the four Phase 8 read screens,
 * and the selected screen below. Text-only tabs work identically on desktop and Android and
 * keep the module free of an icon-pack dependency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    repository: HomeFlowRepository,
    onLogout: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("HomeFlow") },
                    actions = { TextButton(onClick = onLogout) { Text("Log out") } },
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
            }
        }
    }
}
