package org.homeflow.app.shared.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.homeflow.app.shared.config.AuthConfig
import org.homeflow.app.shared.data.buildHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthControllerTest {
    private val meBody = """{"id":"user-1","createdAt":"2024-01-15T10:00:00Z"}"""

    private fun meEngine() =
        MockEngine {
            respond(content = meBody, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }

    private fun controller(
        oidc: FakeOidcClient = FakeOidcClient(),
        store: FakeTokenStore = FakeTokenStore(),
        gate: FakeAppLockGate = FakeAppLockGate(),
        engine: MockEngine = meEngine(),
    ): AuthController =
        AuthController(
            config = AuthConfig(host = "example.test"),
            oidc = oidc,
            tokenStore = store,
            gate = gate,
            httpClientFactory = { c, h, onRefresh -> buildHttpClient(c, h, onRefresh, engine = engine) },
        )

    @Test
    fun login_persists_refresh_token_and_loads_user() =
        runTest {
            val oidc = FakeOidcClient()
            val store = FakeTokenStore()
            val controller = controller(oidc = oidc, store = store)

            controller.login()

            val state = assertIs<AuthState.Authenticated>(controller.state.value)
            assertEquals("user-1", state.user.id)
            assertEquals("login-RT", store.token)
            assertEquals(1, oidc.loginCount)
        }

    @Test
    fun start_logged_out_when_no_stored_token() {
        val controller = controller(store = FakeTokenStore(token = null))
        controller.start()
        assertIs<AuthState.LoggedOut>(controller.state.value)
    }

    @Test
    fun start_locked_when_token_present() {
        val controller = controller(store = FakeTokenStore(token = "rt"), gate = FakeAppLockGate(enrolled = true))
        controller.start()
        val locked = assertIs<AuthState.Locked>(controller.state.value)
        assertEquals(false, locked.needsEnrollment)
    }

    @Test
    fun unlock_refreshes_and_authenticates() =
        runTest {
            val oidc = FakeOidcClient()
            val store = FakeTokenStore(token = "stored-rt")
            val controller = controller(oidc = oidc, store = store, gate = FakeAppLockGate(authResult = true))

            controller.unlock("passphrase")

            assertIs<AuthState.Authenticated>(controller.state.value)
            assertEquals(1, oidc.refreshCount)
            assertEquals("refresh-RT", store.token)
        }

    @Test
    fun unlock_stays_locked_when_gate_fails() =
        runTest {
            val controller =
                controller(store = FakeTokenStore(token = "stored-rt"), gate = FakeAppLockGate(authResult = false))

            controller.unlock("wrong")

            assertIs<AuthState.Locked>(controller.state.value)
        }

    @Test
    fun enroll_then_remains_locked_without_enrollment_flag() =
        runTest {
            val gate = FakeAppLockGate(enrolled = false)
            val controller = controller(store = FakeTokenStore(token = "rt"), gate = gate)

            controller.enroll("new-passphrase")

            val locked = assertIs<AuthState.Locked>(controller.state.value)
            assertEquals(false, locked.needsEnrollment)
            assertEquals(1, gate.enrollCount)
        }

    @Test
    fun logout_revokes_and_clears() =
        runTest {
            val oidc = FakeOidcClient()
            val store = FakeTokenStore(token = "stored-rt")
            val controller = controller(oidc = oidc, store = store)

            controller.logout()

            assertIs<AuthState.LoggedOut>(controller.state.value)
            assertNull(store.token)
            assertEquals(1, oidc.logoutCount)
            assertTrue(oidc.lastLoggedOutToken == "stored-rt")
        }
}
