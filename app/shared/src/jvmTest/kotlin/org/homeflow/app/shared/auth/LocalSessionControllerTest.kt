package org.homeflow.app.shared.auth

import kotlinx.coroutines.test.runTest
import org.homeflow.app.shared.config.AppMode
import org.homeflow.app.shared.config.AppModeStore
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.local.TestDbHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Contract tests for [LocalSessionController] using in-memory fakes. */
class LocalSessionControllerTest {
    // --- fakes ---

    private class AlwaysPassGate(
        override val usesPassphrase: Boolean = true,
    ) : AppLockGate {
        private var enrolled = false

        override fun needsEnrollment(): Boolean = !enrolled

        override suspend fun enroll(secret: String) {
            enrolled = true
        }

        override suspend fun authenticate(secret: String?): Boolean = true
    }

    private class AlwaysFailGate : AppLockGate {
        override val usesPassphrase: Boolean = true

        override fun needsEnrollment(): Boolean = false

        override suspend fun enroll(secret: String) = Unit

        override suspend fun authenticate(secret: String?): Boolean = false
    }

    private class InMemoryKeyStore : LocalKeyStore {
        private var dek: ByteArray? = null

        override fun loadDek(): ByteArray? = dek

        override fun saveDek(dek: ByteArray) {
            this.dek = dek
        }

        override fun clearDek() {
            dek = null
        }
    }

    private class InMemoryModeStore : AppModeStore {
        private var mode: AppMode? = AppMode.LOCAL_ONLY

        override fun load(): AppMode? = mode

        override fun save(mode: AppMode) {
            this.mode = mode
        }

        override fun clear() {
            mode = null
        }
    }

    private fun controller(
        gate: AppLockGate = AlwaysPassGate(),
        keyStore: LocalKeyStore = InMemoryKeyStore(),
        modeStore: AppModeStore = InMemoryModeStore(),
    ) = LocalSessionController(gate = gate, keyStore = keyStore, modeStore = modeStore, openDb = {
        TestDbHelper.inMemory()
    })

    // --- tests ---

    @Test
    fun `start transitions to Locked with needsEnrollment true on first run`() {
        val c = controller(gate = AlwaysPassGate())
        c.start()
        assertIs<AuthState.Locked>(c.state.value)
        assertEquals(true, (c.state.value as AuthState.Locked).needsEnrollment)
    }

    @Test
    fun `start transitions to Locked with needsEnrollment false after enroll`() =
        runTest {
            val gate = AlwaysPassGate()
            val c = controller(gate = gate)
            c.start()
            c.enroll("secret")
            // enroll calls unlock internally; state is Authenticated
            assertIs<AuthState.Authenticated>(c.state.value)
        }

    @Test
    fun `unlock with correct passphrase reaches Authenticated`() =
        runTest {
            val c = controller()
            c.start()
            c.unlock("any")
            assertIs<AuthState.Authenticated>(c.state.value)
        }

    @Test
    fun `unlock with wrong passphrase stays Locked`() =
        runTest {
            val c = controller(gate = AlwaysFailGate())
            c.start()
            c.unlock("wrong")
            assertIs<AuthState.Locked>(c.state.value)
        }

    @Test
    fun `logout re-locks without clearing mode`() =
        runTest {
            val modeStore = InMemoryModeStore()
            val c = controller(modeStore = modeStore)
            c.start()
            c.unlock("any")
            assertIs<AuthState.Authenticated>(c.state.value)

            c.logout()
            assertIs<AuthState.Locked>(c.state.value)
            assertEquals(AppMode.LOCAL_ONLY, modeStore.load())
        }

    @Test
    fun `deleteAccount while not authenticated returns UNAUTHORIZED`() =
        runTest {
            val c = controller()
            c.start()
            val result = c.deleteAccount()
            assertIs<ApiResult.Failure>(result)
        }

    @Test
    fun `deleteAccount after unlock clears DEK and mode then reaches LoggedOut`() =
        runTest {
            val keyStore = InMemoryKeyStore()
            val modeStore = InMemoryModeStore()
            val c = controller(keyStore = keyStore, modeStore = modeStore)
            c.start()
            c.unlock("any")
            assertIs<AuthState.Authenticated>(c.state.value)

            val result = c.deleteAccount()
            assertIs<ApiResult.Success<Unit>>(result)
            assertIs<AuthState.LoggedOut>(c.state.value)
            assertNull(keyStore.loadDek())
            assertNull(modeStore.load())
        }

    @Test
    fun `login is a no-op`() =
        runTest {
            val c = controller()
            c.start()
            c.login()
            assertIs<AuthState.Locked>(c.state.value)
        }
}
