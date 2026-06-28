package org.homeflow.app.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.homeflow.app.shared.auth.AuthState
import org.homeflow.app.shared.auth.SessionController
import org.homeflow.app.shared.ui.shell.AppShell

/**
 * Auth gate composable: renders off [SessionController.state] — login → app-lock →
 * signed-in shell. Once authenticated it reads the repository from
 * [AuthState.Authenticated.repository] and passes it to [AppShell].
 *
 * The [onExport] action is non-null in Mode A (local-only) and null in Mode B so the
 * export button in settings is visible only when data is stored locally.
 */
@Composable
fun App(
    controller: SessionController,
    onExport: (suspend () -> Unit)? = null,
) {
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
                    repository = current.repository,
                    onLogout = { scope.launch { controller.logout() } },
                    onDeleteAccount = { controller.deleteAccount() },
                    onExport = onExport,
                )

            is AuthState.Error ->
                ErrorScreen(message = current.message, onRetry = { controller.start() })
        }
    }
}
