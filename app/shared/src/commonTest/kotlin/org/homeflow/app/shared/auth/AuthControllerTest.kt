package org.homeflow.app.shared.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.homeflow.app.shared.config.AuthConfig
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.TokenHolder
import org.homeflow.app.shared.data.buildHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthControllerTest {
    private val meBody = """{"id":"user-1","createdAt":"2024-01-15T10:00:00Z"}"""

    private fun meEngine() =
        MockEngine { request ->
            if (request.url.encodedPath.endsWith("/version")) {
                respond(content = "", status = HttpStatusCode.NotFound)
            } else {
                respond(content = meBody, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

    private fun controller(
        oidc: FakeOidcClient = FakeOidcClient(),
        store: FakeTokenStore = FakeTokenStore(),
        gate: FakeAppLockGate = FakeAppLockGate(),
        engine: MockEngine = meEngine(),
        allowOfflineUnlock: Boolean = false,
        tokenHolder: TokenHolder = TokenHolder(),
    ): AuthController =
        AuthController(
            config = AuthConfig(host = "example.test"),
            oidc = oidc,
            tokenStore = store,
            gate = gate,
            tokenHolder = tokenHolder,
            allowOfflineUnlock = allowOfflineUnlock,
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
    fun unlock_offline_authenticates_from_local_when_allowed() =
        runTest {
            val oidc = FakeOidcClient().apply { refreshError = RuntimeException("network unreachable") }
            val store = FakeTokenStore(token = "stored-rt")
            val holder = TokenHolder()
            val controller =
                controller(
                    oidc = oidc,
                    store = store,
                    gate = FakeAppLockGate(authResult = true),
                    allowOfflineUnlock = true,
                    tokenHolder = holder,
                )

            controller.unlock("passphrase")

            // Offline unlock reaches Authenticated (local store is authoritative in Mode C)...
            assertIs<AuthState.Authenticated>(controller.state.value)
            // ...and seeds the refresh token so sync can self-heal once the network returns.
            assertEquals("stored-rt", holder.refreshToken)
            // The stored token is left intact (not cleared) — the session is not rejected.
            assertEquals("stored-rt", store.token)
        }

    @Test
    fun unlock_offline_grant_rejected_still_logs_out() =
        runTest {
            val oidc =
                FakeOidcClient().apply {
                    refreshError = OidcException(statusCode = 400, oauthError = "invalid_grant", description = null)
                }
            val store = FakeTokenStore(token = "stored-rt")
            val controller =
                controller(
                    oidc = oidc,
                    store = store,
                    gate = FakeAppLockGate(authResult = true),
                    allowOfflineUnlock = true,
                )

            controller.unlock("passphrase")

            // A rejected grant comes from a REACHABLE server — never masked as "offline".
            assertIs<AuthState.LoggedOut>(controller.state.value)
            assertNull(store.token)
        }

    @Test
    fun unlock_offline_errors_when_not_allowed() =
        runTest {
            val oidc = FakeOidcClient().apply { refreshError = RuntimeException("network unreachable") }
            val controller =
                controller(
                    oidc = oidc,
                    store = FakeTokenStore(token = "stored-rt"),
                    gate = FakeAppLockGate(authResult = true),
                )

            controller.unlock("passphrase")

            // Default (Mode B, no local store): unreachable server still surfaces an error.
            assertIs<AuthState.Error>(controller.state.value)
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
    fun delete_account_issues_delete_then_clears_storage_and_logs_out() =
        runTest {
            val store = FakeTokenStore(token = "stored-rt")
            var deleteCall: Pair<HttpMethod, String>? = null
            val engine =
                MockEngine { request ->
                    deleteCall = request.method to request.url.encodedPath
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
            val controller = controller(store = store, engine = engine)

            val result = controller.deleteAccount()

            assertIs<ApiResult.Success<Unit>>(result)
            assertEquals(HttpMethod.Delete, deleteCall?.first)
            assertEquals("/api/v1/users/me", deleteCall?.second)
            assertIs<AuthState.LoggedOut>(controller.state.value)
            assertNull(store.token)
        }

    @Test
    fun delete_account_failure_leaves_session_intact() =
        runTest {
            val store = FakeTokenStore(token = "stored-rt")
            val engine =
                MockEngine {
                    respond(
                        content = """{"error":{"code":"INTERNAL_ERROR","message":"boom"}}""",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val controller = controller(store = store, engine = engine)

            val result = controller.deleteAccount()

            assertIs<ApiResult.Failure>(result)
            assertEquals("stored-rt", store.token)
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
