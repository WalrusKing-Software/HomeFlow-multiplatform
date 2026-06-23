package org.homeflow.app.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.homeflow.app.shared.auth.AuthController
import org.homeflow.app.shared.auth.AuthState
import org.homeflow.app.shared.auth.buildAuthController
import org.homeflow.app.shared.ui.shell.AppShell

/**
 * Root composable and the auth gate: it renders off [AuthController.state] — login →
 * app-lock gate → signed-in shell. Once authenticated it hands the controller's read
 * [AuthController.repository] to the Phase 8 [AppShell] (Dashboard/Day/Cycles/Analytics).
 */
@Composable
fun App(controller: AuthController = remember { buildAuthController() }) {
    MaterialTheme {
        val scope = rememberCoroutineScope()
        val state by controller.state.collectAsState()

        LaunchedEffect(controller) { controller.start() }

        when (val current = state) {
            is AuthState.LoggedOut ->
                LoginScreen(onLogin = { scope.launch { controller.login() } })

            is AuthState.Authenticating ->
                LoadingScreen(message = "Working…")

            is AuthState.Locked ->
                LockScreen(
                    usesPassphrase = controller.usesPassphraseGate,
                    needsEnrollment = current.needsEnrollment,
                    onSubmitPassphrase = { secret ->
                        scope.launch {
                            if (current.needsEnrollment) controller.enroll(secret) else controller.unlock(secret)
                        }
                    },
                    onBiometric = { scope.launch { controller.unlock(null) } },
                    onLogout = { scope.launch { controller.logout() } },
                )

            is AuthState.Authenticated ->
                AppShell(
                    repository = controller.repository,
                    onLogout = { scope.launch { controller.logout() } },
                )

            is AuthState.Error ->
                ErrorScreen(message = current.message, onRetry = { controller.start() })
        }
    }
}
