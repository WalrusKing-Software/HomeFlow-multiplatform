package org.homeflow.app.shared.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.homeflow.app.shared.config.AppModeStore
import org.homeflow.app.shared.crypto.loadOrCreateDek
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.data.local.LocalBootstrap
import org.homeflow.app.shared.data.local.LocalCyclesStore
import org.homeflow.app.shared.data.local.LocalDailyLogsStore
import org.homeflow.app.shared.data.local.LocalDataSource
import org.homeflow.app.shared.data.local.LocalDatabaseFactory
import org.homeflow.app.shared.data.local.LocalExporter
import org.homeflow.app.shared.data.local.LocalRefData
import org.homeflow.app.shared.data.local.LocalSubsStore
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.app.shared.platform.writeExportFile
import org.homeflow.core.ErrorCode
import org.homeflow.core.dto.HomeFlowExport

/**
 * Mode-A session controller: app-lock gate + DEK only, zero network.
 *
 * State machine:
 * - [start] → [AuthState.Locked] (Mode A never shows [AuthState.LoggedOut] during normal use)
 * - [enroll] → gate.enroll then [unlock]
 * - [unlock] → [AuthState.Authenticating] → gate.authenticate → DEK load/create → DB open →
 *   seed → [AuthState.Authenticated]
 * - [logout] → drop DB handle → [AuthState.Locked]
 * - [deleteAccount] → wipe data + clear DEK + clear enrollment + clear mode →
 *   [AuthState.LoggedOut] (terminal; [AppRoot] re-reads mode → chooser)
 *
 * No [io.ktor.client.HttpClient] is constructed here; this class must remain network-free.
 */
class LocalSessionController
    @Suppress("LongParameterList")
    internal constructor(
        private val gate: AppLockGate,
        private val keyStore: LocalKeyStore,
        private val modeStore: AppModeStore,
        private val openDb: (ByteArray) -> HomeFlowDb,
    ) : SessionController {
        constructor(
            gate: AppLockGate,
            keyStore: LocalKeyStore,
            modeStore: AppModeStore,
            dbFactory: LocalDatabaseFactory,
        ) : this(gate, keyStore, modeStore, { dek -> dbFactory.create(dek) })

        private val _state = MutableStateFlow<AuthState>(AuthState.LoggedOut)
        override val state: StateFlow<AuthState> = _state.asStateFlow()
        override val usesPassphraseGate: Boolean = gate.usesPassphrase

        // Cleared on logout/deleteAccount; non-null only while Authenticated.
        private var localDb: HomeFlowDb? = null
        private var localDataSource: LocalDataSource? = null

        override fun start() {
            _state.value = AuthState.Locked(needsEnrollment = gate.needsEnrollment())
        }

        /** No-op: Mode A has no remote login flow. */
        override suspend fun login() = Unit

        override suspend fun enroll(secret: String) {
            runCatching {
                gate.enroll(secret)
                unlock(secret)
            }.onFailure { _state.value = AuthState.Error(it.message ?: "Enrollment failed") }
        }

        override suspend fun unlock(secret: String?) {
            _state.value = AuthState.Authenticating
            runCatching {
                if (!gate.authenticate(secret)) {
                    _state.value = AuthState.Locked(needsEnrollment = false)
                    return
                }
                val dek = keyStore.loadOrCreateDek()
                val db = openDb(dek)
                LocalBootstrap.seed(db)
                localDb = db
                val ds = LocalDataSource(db)
                localDataSource = ds
                val user = (ds.getMe() as ApiResult.Success).value
                val repository = HomeFlowRepository(ds)
                _state.value = AuthState.Authenticated(user, repository)
            }.onFailure { _state.value = AuthState.Error(it.message ?: "Unlock failed") }
        }

        /** Re-locks without clearing the DEK (data remains; user can unlock again). */
        override suspend fun logout() {
            localDb = null
            localDataSource = null
            _state.value = AuthState.Locked(needsEnrollment = false)
        }

        /**
         * Wipes all local data, clears the DEK, resets the app-lock enrollment, and clears
         * the persisted mode. Transitions to [AuthState.LoggedOut] so [AppRoot] detects the
         * cleared mode and re-shows the first-run chooser.
         */
        override suspend fun deleteAccount(): ApiResult<Unit> {
            val ds =
                localDataSource
                    ?: return ApiResult.Failure(ErrorCode.UNAUTHORIZED, "Not authenticated.", 401)
            return ds.deleteAccount().also { result ->
                if (result is ApiResult.Success) {
                    keyStore.clearDek()
                    resetAppLockGateEnrollment()
                    modeStore.clear()
                    localDb = null
                    localDataSource = null
                    _state.value = AuthState.LoggedOut
                }
            }
        }

        /**
         * Builds and serializes a [HomeFlowExport] from the local store, then invokes the
         * platform save-file dialog. Returns silently if the user cancels the dialog.
         */
        suspend fun exportData() {
            val db = localDb ?: error("Cannot export: not authenticated.")
            val userId = LocalBootstrap.LOCAL_USER_ID
            val refData = LocalRefData(db)
            val cyclesStore = LocalCyclesStore(db, userId)
            val subsStore = LocalSubsStore(db, refData)
            val logsStore = LocalDailyLogsStore(db, userId, cyclesStore, subsStore, refData)
            val export = LocalExporter(db, refData, logsStore).export()
            val json = Json.encodeToString(HomeFlowExport.serializer(), export)
            writeExportFile("homeflow-export.json", json)
        }
    }
