package org.homeflow.app.shared.auth

import kotlinx.coroutines.test.runTest
import org.homeflow.app.shared.config.AppMode
import org.homeflow.app.shared.config.AppModeStore
import org.homeflow.app.shared.config.ServerConfigStore
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.local.LocalBootstrap
import org.homeflow.app.shared.data.local.LocalDataSource
import org.homeflow.app.shared.data.local.TestDbHelper
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.core.ErrorCode
import org.homeflow.core.dto.ImportResultDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract tests for [ServerMigration] using in-memory fakes. */
class ServerMigrationTest {
    // --- fakes ---

    private class InMemoryKeyStore(
        private var dek: ByteArray? = null,
    ) : LocalKeyStore {
        override fun loadDek(): ByteArray? = dek

        override fun saveDek(dek: ByteArray) {
            this.dek = dek
        }

        override fun clearDek() {
            dek = null
        }
    }

    private class InMemoryServerConfigStore : ServerConfigStore {
        private var host: String? = null
        private var migrated = false

        override fun loadHost(): String? = host

        override fun saveHost(host: String) {
            this.host = host
        }

        override fun isMigrated(): Boolean = migrated

        override fun setMigrated() {
            migrated = true
        }

        override fun clear() {
            host = null
            migrated = false
        }
    }

    private class InMemoryAppModeStore : AppModeStore {
        private var mode: AppMode? = AppMode.SERVER

        override fun load(): AppMode? = mode

        override fun save(mode: AppMode) {
            this.mode = mode
        }

        override fun clear() {
            mode = null
        }
    }

    /** Uses the internal [ServerMigration] constructor to inject an in-memory DB directly. */
    private fun migration(
        keyStore: LocalKeyStore = InMemoryKeyStore(dek = ByteArray(32)),
        serverConfigStore: InMemoryServerConfigStore = InMemoryServerConfigStore(),
        db: HomeFlowDb = TestDbHelper.inMemory().also { LocalBootstrap.seed(it) },
    ): Triple<ServerMigration, InMemoryServerConfigStore, HomeFlowDb> {
        val migration =
            ServerMigration(
                keyStore = keyStore,
                openDb = { _ -> db },
                serverConfigStore = serverConfigStore,
            )
        return Triple(migration, serverConfigStore, db)
    }

    // --- tests ---

    @Test
    fun `hasUnmigratedLocalData returns false when no DEK`() {
        val (m, _, _) = migration(keyStore = InMemoryKeyStore(dek = null))
        assertFalse(m.hasUnmigratedLocalData())
    }

    @Test
    fun `hasUnmigratedLocalData returns false when already migrated`() {
        val (m, configStore, db) = migration()
        configStore.setMigrated()
        // seed a cycle so there is data
        val ds = LocalDataSource(db)
        runTest { ds.createCycle("2024-03-01") }
        assertFalse(m.hasUnmigratedLocalData())
    }

    @Test
    fun `hasUnmigratedLocalData returns false when no cycles`() {
        val (m, _, _) = migration()
        // no cycles seeded
        assertFalse(m.hasUnmigratedLocalData())
    }

    @Test
    fun `hasUnmigratedLocalData returns true when DEK and cycles exist`() =
        runTest {
            val (m, _, db) = migration()
            LocalDataSource(db).createCycle("2024-03-01")
            assertTrue(m.hasUnmigratedLocalData())
        }

    @Test
    fun `migrate returns null when no DEK`() =
        runTest {
            val (m, _, _) = migration(keyStore = InMemoryKeyStore(dek = null))
            var uploadCalled = false
            val result =
                m.migrate {
                    uploadCalled = true
                    ApiResult.Success(ImportResultDto(1, 1, 0))
                }
            assertNull(result)
            assertFalse(uploadCalled, "upload must not be called when there is no DEK")
        }

    @Test
    fun `migrate returns null when no cycles`() =
        runTest {
            val (m, _, _) = migration()
            var uploadCalled = false
            val result =
                m.migrate {
                    uploadCalled = true
                    ApiResult.Success(ImportResultDto(0, 0, 0))
                }
            assertNull(result)
            assertFalse(uploadCalled, "upload must not be called when there are no cycles")
        }

    @Test
    fun `migrate calls upload once with slug-keyed JSON and sets migrated on success`() =
        runTest {
            val (m, configStore, db) = migration()
            val ds = LocalDataSource(db)
            ds.createCycle("2024-03-01")

            val capturedJsons = mutableListOf<String>()
            val stubResult = ImportResultDto(cyclesCreated = 1, dailyLogsCreated = 0, dailyLogsSkipped = 0)
            val result =
                m.migrate { json ->
                    capturedJsons += json
                    ApiResult.Success(stubResult)
                }

            assertNotNull(result)
            assertEquals(stubResult.cyclesCreated, result.cyclesCreated)
            assertEquals(1, capturedJsons.size, "upload called exactly once")
            assertTrue(configStore.isMigrated(), "setMigrated() must be called on success")

            // The JSON must contain no bare UUIDs (D-15.7 references LocalExporterTest invariant).
            val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            assertFalse(
                uuidPattern.containsMatchIn(capturedJsons.first()),
                "Export JSON must be slug-keyed (no UUIDs): ${capturedJsons.first()}",
            )
        }

    @Test
    fun `migrate does not call upload when already migrated`() =
        runTest {
            val (m, configStore, db) = migration()
            val ds = LocalDataSource(db)
            ds.createCycle("2024-03-01")
            configStore.setMigrated()

            var uploadCalled = false
            val result =
                m.migrate {
                    uploadCalled = true
                    ApiResult.Success(ImportResultDto(1, 0, 0))
                }
            assertNull(result)
            assertFalse(uploadCalled)
        }

    @Test
    fun `migrate returns null when upload fails and does not set migrated`() =
        runTest {
            val (m, configStore, db) = migration()
            LocalDataSource(db).createCycle("2024-03-01")

            val result =
                m.migrate {
                    ApiResult.Failure(ErrorCode.INTERNAL_ERROR, "server error", 500)
                }

            assertNull(result)
            assertFalse(configStore.isMigrated(), "must not set migrated on upload failure")
        }
}
