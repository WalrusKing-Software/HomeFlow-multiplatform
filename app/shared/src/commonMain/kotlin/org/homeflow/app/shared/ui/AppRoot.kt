package org.homeflow.app.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.homeflow.app.shared.auth.AuthState
import org.homeflow.app.shared.auth.LocalSessionController
import org.homeflow.app.shared.auth.SessionController
import org.homeflow.app.shared.auth.keycloakSessionController
import org.homeflow.app.shared.auth.localSessionController
import org.homeflow.app.shared.config.AppMode
import org.homeflow.app.shared.config.AuthConfig
import org.homeflow.app.shared.config.createAppModeStore
import org.homeflow.app.shared.config.defaultAuthConfig

/**
 * Composition root: reads the persisted [AppMode] and dispatches to the correct session
 * controller and UI path. null mode → first-run chooser.
 *
 * - [AppMode.LOCAL_ONLY] → [LocalSessionController] + export wired.
 * - [AppMode.SERVER]     → [org.homeflow.app.shared.auth.AuthController] (Keycloak OIDC).
 *
 * Desktop `main.kt` and Android `MainActivity` both call this instead of [App] directly.
 */
@Composable
fun AppRoot(serverConfig: AuthConfig = defaultAuthConfig()) {
    MaterialTheme {
        val modeStore = remember { createAppModeStore() }
        var mode by remember { mutableStateOf(modeStore.load()) }

        when (mode) {
            null ->
                ModeChooserScreen(
                    onLocal = {
                        modeStore.save(AppMode.LOCAL_ONLY)
                        mode = AppMode.LOCAL_ONLY
                    },
                    onServer = {
                        modeStore.save(AppMode.SERVER)
                        mode = AppMode.SERVER
                    },
                )

            AppMode.LOCAL_ONLY -> {
                val controller = remember { localSessionController(modeStore) }
                val controllerState by controller.state.collectAsState()
                // After deleteAccount(), the controller transitions to LoggedOut and
                // modeStore is cleared; re-reading the store returns null → chooser.
                LaunchedEffect(controllerState) {
                    if (controllerState is AuthState.LoggedOut && modeStore.load() == null) {
                        mode = null
                    }
                }
                App(
                    controller = controller,
                    onExport = { controller.exportData() },
                )
            }

            AppMode.SERVER -> {
                val controller: SessionController = remember { keycloakSessionController(serverConfig) }
                App(controller = controller)
            }
        }
    }
}
