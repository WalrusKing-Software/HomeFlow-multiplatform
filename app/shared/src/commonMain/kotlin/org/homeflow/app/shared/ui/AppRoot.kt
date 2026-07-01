package org.homeflow.app.shared.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.homeflow.app.shared.auth.AuthState
import org.homeflow.app.shared.auth.LocalSessionController
import org.homeflow.app.shared.auth.ServerMigration
import org.homeflow.app.shared.auth.buildAuthController
import org.homeflow.app.shared.auth.createLocalKeyStore
import org.homeflow.app.shared.auth.localSessionController
import org.homeflow.app.shared.config.AppMode
import org.homeflow.app.shared.config.authConfigForHost
import org.homeflow.app.shared.config.createAppModeStore
import org.homeflow.app.shared.config.createServerConfigStore
import org.homeflow.app.shared.crypto.loadOrCreateDek
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.data.local.LocalBootstrap
import org.homeflow.app.shared.data.local.LocalDataSource
import org.homeflow.app.shared.data.local.LocalDatabaseFactory
import org.homeflow.app.shared.data.sync.SyncEngine
import org.homeflow.core.dto.ImportResultDto

/**
 * Composition root: reads the persisted [AppMode] and dispatches to the correct session
 * controller and UI path. null mode → first-run chooser.
 *
 * - [AppMode.LOCAL_ONLY] → [LocalSessionController] + export wired.
 * - [AppMode.SERVER]     → runtime host entry gate → [buildAuthController] (Keycloak OIDC)
 *   + one-time "adopt a server" migration prompt.
 *
 * [serverHostOverride] seeds the SERVER host when none is stored yet, letting a platform
 * skip the host-entry gate (Android debug builds pass `localhost:8443`). A stored host
 * always wins; release builds pass null so the gate is shown (D-15.2).
 *
 * Desktop `main.kt` and Android `MainActivity` both call this instead of [App] directly.
 */
@Composable
fun AppRoot(
    serverHostOverride: String? = null,
    clientVersion: String = "unknown",
) {
    MaterialTheme {
        val modeStore = remember { createAppModeStore() }
        var mode by remember { mutableStateOf(modeStore.load()) }
        // True when the SERVER flow was entered from Settings in an existing local install
        // (via onConnectServer below). It changes what "back" means on the host-entry gate:
        // an existing local user cancels back into the app (Settings), rather than being
        // reset to the first-run mode chooser as a genuine first-run SERVER user would be.
        var connectFromLocal by remember { mutableStateOf(false) }

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
                    onConnectServer = {
                        // Switch to SERVER mode without wiping the local DB — local data
                        // stays and will be offered for upload after login (D-15.10).
                        connectFromLocal = true
                        modeStore.save(AppMode.SERVER)
                        mode = AppMode.SERVER
                    },
                    startOnSettings = connectFromLocal,
                )
            }

            AppMode.SERVER -> {
                val serverConfigStore = remember { createServerConfigStore() }
                // Stored host wins; otherwise use the platform override (Android debug =
                // localhost:8443) so dev builds skip the gate. null → show the host gate.
                var host by remember { mutableStateOf(serverConfigStore.loadHost() ?: serverHostOverride) }
                val serverMigration =
                    remember {
                        ServerMigration(createLocalKeyStore(), LocalDatabaseFactory(), serverConfigStore)
                    }

                if (host == null) {
                    // No stored host yet — collect it from the user. "Back" means one of two
                    // things depending on how we got here:
                    //  - connectFromLocal: an existing local user tapped Settings → "Connect to a
                    //    server". Cancelling returns them to LOCAL_ONLY (and lands on Settings),
                    //    preserving their install — not the first-run chooser.
                    //  - otherwise: a genuine first-run SERVER user who has no server; let them
                    //    escape back to the mode chooser instead of being trapped here.
                    ServerConnectScreen(
                        onConnected = { newHost ->
                            serverConfigStore.saveHost(newHost)
                            host = newHost
                        },
                        onBack = {
                            serverConfigStore.clear()
                            if (connectFromLocal) {
                                modeStore.save(AppMode.LOCAL_ONLY)
                                mode = AppMode.LOCAL_ONLY
                            } else {
                                modeStore.clear()
                                mode = null
                            }
                        },
                        backLabel = if (connectFromLocal) "Back to settings" else "Back to setup",
                        clientVersion = clientVersion,
                    )
                } else {
                    val localKeyStore = remember { createLocalKeyStore() }
                    val dbFactory = remember { LocalDatabaseFactory() }
                    // Mode C: open (or create) the local encrypted DB for offline-first storage.
                    // authController, syncEngine, and syncRepository are all created together so
                    // the engine shares the same authenticated HttpClient as the controller.
                    val authController =
                        remember(host) { buildAuthController(authConfigForHost(host!!), clientVersion) }
                    val (syncEngine, syncRepository) =
                        remember(host) {
                            val dek = localKeyStore.loadOrCreateDek()
                            val db = dbFactory.create(dek)
                            LocalBootstrap.seed(db)
                            val engine = SyncEngine(db, authController.remote)
                            val localDs = LocalDataSource(db)
                            val repo = HomeFlowRepository(localDs)
                            Pair(engine, repo)
                        }
                    val controllerState by authController.state.collectAsState()
                    val scope = rememberCoroutineScope()

                    // Auto-migration prompt: once per session on first Authenticated state.
                    var migrationPromptDone by remember { mutableStateOf(false) }
                    var showMigrationDialog by remember { mutableStateOf(false) }
                    var migrationUploading by remember { mutableStateOf(false) }
                    var migrationResult by remember { mutableStateOf<ImportResultDto?>(null) }
                    var migrationFailed by remember { mutableStateOf(false) }

                    LaunchedEffect(controllerState) {
                        if (controllerState is AuthState.Authenticated && !migrationPromptDone) {
                            migrationPromptDone = true
                            if (serverMigration.hasUnmigratedLocalData()) {
                                showMigrationDialog = true
                            }
                        }
                    }

                    // Settings upload callback — only offered while not yet migrated.
                    val onUploadToServer: (suspend () -> ImportResultDto?)? =
                        if (!serverConfigStore.isMigrated()) {
                            { serverMigration.migrate(authController::uploadLocalData) }
                        } else {
                            null
                        }

                    App(
                        controller = authController,
                        connectedHost = host,
                        onUploadToServer = onUploadToServer,
                        onSwitchToLocal = {
                            // Reversible: keep the server config + local DB (which already holds the
                            // synced data) so the user can reconnect later. Just flip the mode.
                            modeStore.save(AppMode.LOCAL_ONLY)
                            mode = AppMode.LOCAL_ONLY
                        },
                        syncEngine = syncEngine,
                        syncRepository = syncRepository,
                    )

                    // Auto-prompt overlay — rendered on top of the signed-in App content. Confirm
                    // runs the upload with progress + a result/error summary (D-15.8).
                    when {
                        showMigrationDialog ->
                            MigrationPromptDialog(
                                onConfirm = {
                                    showMigrationDialog = false
                                    scope.launch {
                                        migrationUploading = true
                                        val result = serverMigration.migrate(authController::uploadLocalData)
                                        migrationUploading = false
                                        if (result != null) migrationResult = result else migrationFailed = true
                                    }
                                },
                                onSkip = { showMigrationDialog = false },
                            )

                        migrationUploading -> MigrationProgressDialog()

                        migrationResult != null ->
                            MigrationResultDialog(
                                result = migrationResult!!,
                                onDismiss = { migrationResult = null },
                            )

                        migrationFailed -> MigrationFailedDialog(onDismiss = { migrationFailed = false })
                    }
                }
            }
        }
    }
}

/**
 * Auto-migration prompt dialog. Confirm starts the upload (the caller shows progress then a
 * result/error summary). Skip dismisses without marking as migrated so the Settings "Upload
 * local data to server" button remains available (D-15.8).
 */
@Composable
private fun MigrationPromptDialog(
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Upload this device's data to the server?") },
        text = {
            Text(
                "Your locally stored cycles and daily logs will be uploaded to your server. " +
                    "This makes them available on all your devices. The upload can be run again " +
                    "from Settings.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Upload") }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("Skip") }
        },
    )
}

/** Non-dismissable progress dialog shown while the adoption upload is in flight. */
@Composable
private fun MigrationProgressDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Uploading…") },
        text = { CircularProgressIndicator() },
        confirmButton = {},
    )
}

/** Result summary shown after a successful adoption upload. */
@Composable
private fun MigrationResultDialog(
    result: ImportResultDto,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload complete") },
        text = {
            Text(
                "Uploaded ${result.cyclesCreated} cycle(s) and ${result.dailyLogsCreated} day(s) " +
                    "(${result.dailyLogsSkipped} already on the server).",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

/** Failure dialog; the upload can be retried from Settings (it was not marked migrated). */
@Composable
private fun MigrationFailedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload failed") },
        text = { Text("Your data could not be uploaded. You can try again from Settings.") },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}
