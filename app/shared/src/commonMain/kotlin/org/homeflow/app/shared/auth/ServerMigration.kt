package org.homeflow.app.shared.auth

import kotlinx.serialization.json.Json
import org.homeflow.app.shared.config.ServerConfigStore
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.local.LocalBootstrap
import org.homeflow.app.shared.data.local.LocalCyclesStore
import org.homeflow.app.shared.data.local.LocalDailyLogsStore
import org.homeflow.app.shared.data.local.LocalDatabaseFactory
import org.homeflow.app.shared.data.local.LocalExporter
import org.homeflow.app.shared.data.local.LocalRefData
import org.homeflow.app.shared.data.local.LocalSubsStore
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.dto.HomeFlowExport
import org.homeflow.core.dto.ImportResultDto

/**
 * Orchestrates the one-time "adopt a server" migration: opens the local encrypted DB,
 * builds a slug-keyed [HomeFlowExport], serializes it, and uploads it via the caller-
 * supplied [upload] lambda (bound to [AuthController.uploadLocalData]).
 *
 * Decoupled from [AuthController] via the upload lambda so it is unit-testable with fakes.
 * The [openDb] function type (injected via the public [LocalDatabaseFactory] constructor)
 * mirrors [LocalSessionController]'s pattern so tests can supply an in-memory DB.
 * All exceptions from the local DB or upload are swallowed and treated as "nothing to do."
 */
class ServerMigration
    @Suppress("LongParameterList")
    internal constructor(
        private val keyStore: LocalKeyStore,
        private val openDb: (ByteArray) -> HomeFlowDb,
        private val serverConfigStore: ServerConfigStore,
    ) {
        constructor(
            keyStore: LocalKeyStore,
            dbFactory: LocalDatabaseFactory,
            serverConfigStore: ServerConfigStore,
        ) : this(keyStore, { dek -> dbFactory.create(dek) }, serverConfigStore)

        /**
         * True only when all of:
         * - not already migrated ([ServerConfigStore.isMigrated] == false)
         * - a local DEK exists ([LocalKeyStore.loadDek] != null — a pure Mode-B install never
         *   created one, so this short-circuits correctly without calling [loadOrCreateDek])
         * - the local DB contains at least one cycle
         */
        fun hasUnmigratedLocalData(): Boolean {
            if (serverConfigStore.isMigrated()) return false
            val dek = keyStore.loadDek() ?: return false
            return runCatching {
                val db = openDb(dek)
                LocalBootstrap.seed(db)
                db.cyclesQueries
                    .selectAll(LocalBootstrap.LOCAL_USER_ID)
                    .executeAsList()
                    .isNotEmpty()
            }.getOrElse { false }
        }

        /**
         * Builds the local [HomeFlowExport], serializes it to JSON, and calls [upload].
         * On a successful upload calls [ServerConfigStore.setMigrated] so this is not offered
         * again. Returns the server's [ImportResultDto] summary, or null when there is nothing
         * to migrate (no DEK, no cycles, or the upload fails). Never throws.
         */
        suspend fun migrate(upload: suspend (String) -> ApiResult<ImportResultDto>): ImportResultDto? {
            if (serverConfigStore.isMigrated()) return null
            val dek = keyStore.loadDek() ?: return null
            return runCatching {
                val db = openDb(dek)
                LocalBootstrap.seed(db)
                val userId = LocalBootstrap.LOCAL_USER_ID
                val refData = LocalRefData(db)
                val cyclesStore = LocalCyclesStore(db, userId)
                val subsStore = LocalSubsStore(db, refData)
                val logsStore = LocalDailyLogsStore(db, userId, cyclesStore, subsStore, refData)

                val isEmpty =
                    db.cyclesQueries
                        .selectAll(userId)
                        .executeAsList()
                        .isEmpty()
                if (isEmpty) return null

                val export = LocalExporter(db, refData, logsStore).export()
                val json = Json.encodeToString(HomeFlowExport.serializer(), export)
                when (val result = upload(json)) {
                    is ApiResult.Success -> {
                        serverConfigStore.setMigrated()
                        result.value
                    }
                    is ApiResult.Failure -> null
                }
            }.getOrElse { null }
        }
    }
