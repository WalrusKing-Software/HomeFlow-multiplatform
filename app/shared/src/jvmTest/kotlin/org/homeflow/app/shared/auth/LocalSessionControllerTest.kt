package org.homeflow.app.shared.auth

import kotlinx.coroutines.test.runTest
import org.homeflow.app.shared.config.AppMode
import org.homeflow.app.shared.config.AppModeStore
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.local.TestDbHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    /** Mirrors the Android biometric gate: no passphrase, no enrollment step, always authenticates. */
    private class BiometricGate(
        override val usesPassphrase: Boolean = false,
    ) : AppLockGate {
        override fun needsEnrollment(): Boolean = false

        override suspend fun enroll(secret: String) = Unit

        override suspend fun authenticate(secret: String?): Boolean = true
    }

    private class AlwaysFailGate : AppLockGate {
        override val usesPassphrase: Boolean = true

        override fun needsEnrollment(): Boolean = false

        override suspend fun enroll(secret: String) = Unit

        override suspend fun authenticate(secret: String?): Boolean = false
    }

    private class InMemoryKeyStore(
        seeded: Boolean = false,
    ) : LocalKeyStore {
        private var dek: ByteArray? = if (seeded) ByteArray(32) { 7 } else null

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
    fun `enroll generates the DEK and reaches Authenticated`() =
        runTest {
            val gate = AlwaysPassGate()
            val keyStore = InMemoryKeyStore() // no DEK yet — enroll must create it
            val c = controller(gate = gate, keyStore = keyStore)
            c.start()
            c.enroll("secret")
            // enroll creates the DEK, then opens the session.
            assertIs<AuthState.Authenticated>(c.state.value)
            assertNotNull(keyStore.loadDek())
        }

    @Test
    fun `unlock with correct passphrase reaches Authenticated`() =
        runTest {
            // A returning user: the DEK already exists (created at enrollment).
            val c = controller(keyStore = InMemoryKeyStore(seeded = true))
            c.start()
            c.unlock("any")
            assertIs<AuthState.Authenticated>(c.state.value)
        }

    @Test
    fun `unlock fails closed when the DEK cannot be read`() =
        runTest {
            // Passphrase verifies, but the encryption key is missing/unreadable. We must NOT mint
            // a new key (which would corrupt the existing DB); fail closed with an error instead.
            val c = controller(gate = AlwaysPassGate(), keyStore = InMemoryKeyStore(seeded = false))
            c.start()
            c.unlock("any")
            assertIs<AuthState.Error>(c.state.value)
        }

    @Test
    fun `biometric first-run unlock creates the DEK and reaches Authenticated`() =
        runTest {
            // Android: no passphrase gate, no enrollment step, and no DEK yet. The first unlock is
            // first-run setup — it must create the DEK (not fail closed) and open the session.
            val keyStore = InMemoryKeyStore(seeded = false)
            val c = controller(gate = BiometricGate(), keyStore = keyStore)
            c.start()
            c.unlock(null)
            assertIs<AuthState.Authenticated>(c.state.value)
            assertNotNull(keyStore.loadDek())
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
            val c = controller(keyStore = InMemoryKeyStore(seeded = true), modeStore = modeStore)
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
            val keyStore = InMemoryKeyStore(seeded = true)
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
