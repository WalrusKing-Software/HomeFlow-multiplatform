package org.homeflow.app.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.homeflow.app.shared.auth.AuthState
import org.homeflow.app.shared.auth.SessionController
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.data.sync.SyncEngine
import org.homeflow.app.shared.ui.shell.AppShell
import org.homeflow.core.dto.ImportResultDto

/**
 * Auth gate composable: renders off [SessionController.state] — login → app-lock →
 * signed-in shell. Once authenticated it reads the repository from
 * [AuthState.Authenticated.repository] and passes it to [AppShell].
 *
 * Optional callbacks thread through to the Settings tab:
 * - [onExport]: non-null in Mode A — shows "Export my data".
 * - [onConnectServer]: non-null in Mode A — shows "Connect to a server".
 * - [onUploadToServer]: non-null in Mode B while the local data has not been migrated yet.
 * - [connectedHost]: non-null in Mode B — displayed in the Server section of Settings.
 * - [onSwitchToLocal]: non-null in Mode B — "Switch to local-only mode" in Settings.
 * - [syncEngine] + [syncRepository]: non-null in Mode C — local-first repository with background sync.
 * - [onCancelSetup]: non-null in Mode B — lets the user back out of server setup from the
 *   sign-in screen or while a login is in flight, instead of being stuck once a host is
 *   committed. Cancelling relies on Compose disposing this composable's [rememberCoroutineScope]
 *   (and any in-flight `controller.login()` job with it) once the caller flips back to the host
 *   gate — see [org.homeflow.app.shared.ui.AppRoot].
 */
@Composable
fun App(
    controller: SessionController,
    onExport: (suspend () -> Unit)? = null,
    onConnectServer: (() -> Unit)? = null,
    onUploadToServer: (suspend () -> ImportResultDto?)? = null,
    connectedHost: String? = null,
    onSwitchToLocal: (() -> Unit)? = null,
    syncEngine: SyncEngine? = null,
    syncRepository: HomeFlowRepository? = null,
    onCancelSetup: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()

    LaunchedEffect(controller) { controller.start() }

    when (val current = state) {
        is AuthState.LoggedOut ->
            LoginScreen(onLogin = { scope.launch { controller.login() } }, onCancel = onCancelSetup)

        is AuthState.Authenticating ->
            LoadingScreen(message = "Working…", onCancel = onCancelSetup)

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

        is AuthState.Authenticated -> {
            // Mode C: use local-first repository + background sync triggers.
            if (syncEngine != null && syncRepository != null) {
                // Initial sync on foreground + periodic timer (~15 min).
                LaunchedEffect(current) {
                    syncEngine.syncNow()
                    while (true) {
                        kotlinx.coroutines.delay(15 * 60 * 1_000L)
                        syncEngine.syncNow()
                    }
                }
                AppShell(
                    repository = syncRepository,
                    onLogout = { scope.launch { controller.logout() } },
                    onDeleteAccount = { controller.deleteAccount() },
                    onExport = onExport,
                    onConnectServer = onConnectServer,
                    onUploadToServer = onUploadToServer,
                    connectedHost = connectedHost,
                    onSwitchToLocal = onSwitchToLocal,
                    syncStatusFlow = syncEngine.status,
                )
            } else {
                AppShell(
                    repository = current.repository,
                    onLogout = { scope.launch { controller.logout() } },
                    onDeleteAccount = { controller.deleteAccount() },
                    onExport = onExport,
                    onConnectServer = onConnectServer,
                    onUploadToServer = onUploadToServer,
                    connectedHost = connectedHost,
                    onSwitchToLocal = onSwitchToLocal,
                )
            }
        }

        is AuthState.Error ->
            ErrorScreen(message = current.message, onRetry = { controller.start() })
    }
}
