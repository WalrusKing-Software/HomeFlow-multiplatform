package org.homeflow.app.shared.auth

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.homeflow.app.shared.config.AuthConfig
import org.homeflow.app.shared.data.ApiResult
import org.homeflow.app.shared.data.HomeFlowDataSource
import org.homeflow.app.shared.data.HomeFlowRepository
import org.homeflow.app.shared.data.RemoteDataSource
import org.homeflow.app.shared.data.TokenHolder
import org.homeflow.app.shared.data.buildHttpClient
import org.homeflow.app.shared.data.userMessage
import org.homeflow.core.dto.UserDto

/** The auth-gate state the root composable renders off. */
sealed interface AuthState {
    /** No stored session — show the login screen. */
    data object LoggedOut : AuthState

    /** Interactive login / token exchange in flight. */
    data object Authenticating : AuthState

    /**
     * A refresh token exists but the app-open gate has not been satisfied this launch.
     * [needsEnrollment] is true on the very first run (desktop passphrase setup).
     */
    data class Locked(
        val needsEnrollment: Boolean,
    ) : AuthState

    /** Signed in; [user] is the `GET /users/me` record. */
    data class Authenticated(
        val user: UserDto,
    ) : AuthState

    data class Error(
        val message: String,
    ) : AuthState
}

/**
 * Owns the sequence from `ARCHITECTURE-client.md` "Auth flow": login → secure store →
 * app-lock gate → silent refresh → `GET /users/me`. Platform specifics
 * ([OidcClient]/[TokenStore]/[AppLockGate]) are injected; everything here is shared.
 */
class AuthController(
    private val config: AuthConfig,
    private val oidc: OidcClient,
    private val tokenStore: TokenStore,
    private val gate: AppLockGate,
    private val tokenHolder: TokenHolder = TokenHolder(),
    httpClientFactory: (AuthConfig, TokenHolder, suspend (String) -> OidcTokens?) -> HttpClient =
        { c, h, onRefresh -> buildHttpClient(c, h, onRefresh) },
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Whether the lock screen should collect a passphrase (desktop) or trigger biometrics (Android). */
    val usesPassphraseGate: Boolean = gate.usesPassphrase

    private val http: HttpClient = httpClientFactory(config, tokenHolder, ::refreshAndPersist)
    private val api: HomeFlowDataSource = RemoteDataSource(http)

    /** The Phase 8 read surface for the signed-in shell — backed by the same authenticated client. */
    val repository: HomeFlowRepository = HomeFlowRepository(api)

    /** App open: a stored refresh token sends us to the lock gate; otherwise log in. */
    fun start() {
        val hasRefresh = tokenStore.loadRefreshToken() != null
        authDebugLog("start: host=${config.host} apiBaseUrl=${config.apiBaseUrl} hasRefresh=$hasRefresh")
        _state.value =
            if (!hasRefresh) {
                AuthState.LoggedOut
            } else {
                AuthState.Locked(needsEnrollment = gate.needsEnrollment())
            }
    }

    /** Fresh interactive login (one-time browser flow). */
    suspend fun login() {
        _state.value = AuthState.Authenticating
        authDebugLog("login: starting OIDC flow")
        runCatching {
            val tokens = oidc.login()
            authDebugLog("login: token exchange OK; loading user from ${config.apiBaseUrl}users/me")
            tokens.refreshToken?.let { tokenStore.saveRefreshToken(it) }
            tokenHolder.set(tokens)
            loadUser()
        }.onFailure {
            authDebugLog("login FAILED: ${it.describe()}")
            _state.value = AuthState.Error(it.message ?: "Login failed")
        }
    }

    /** Desktop first-run: establish the passphrase, then drop back to the unlock prompt. */
    suspend fun enroll(secret: String) {
        runCatching {
            gate.enroll(secret)
            _state.value = AuthState.Locked(needsEnrollment = false)
        }.onFailure { _state.value = AuthState.Error(it.message ?: "Enrollment failed") }
    }

    /** Satisfy the gate, then silently refresh the stored token and load the user. */
    suspend fun unlock(secret: String? = null) {
        _state.value = AuthState.Authenticating
        runCatching {
            if (!gate.authenticate(secret)) {
                _state.value = AuthState.Locked(needsEnrollment = false)
                return
            }
            val refresh = tokenStore.loadRefreshToken()
            if (refresh == null) {
                _state.value = AuthState.LoggedOut
                return
            }
            val tokens = oidc.refresh(refresh)
            tokens.refreshToken?.let { tokenStore.saveRefreshToken(it) }
            tokenHolder.set(tokens)
            loadUser()
        }.onFailure { _state.value = AuthState.Error(it.message ?: "Unlock failed") }
    }

    /** Best-effort revocation, then clear secure storage and memory regardless. */
    suspend fun logout() {
        tokenStore.loadRefreshToken()?.let { rt -> runCatching { oidc.logout(rt) } }
        tokenStore.clear()
        tokenHolder.clear()
        _state.value = AuthState.LoggedOut
    }

    /**
     * Permanently delete the account. On success the server has already destroyed the data and
     * the Keycloak identity, so we just clear local secure storage + memory and drop to the login
     * screen (no revocation — the identity is gone). On failure the session is left intact and the
     * caller renders the error; the stored token stays valid.
     */
    suspend fun deleteAccount(): ApiResult<Unit> =
        repository.deleteAccount().also { result ->
            if (result is ApiResult.Success) {
                tokenStore.clear()
                tokenHolder.clear()
                _state.value = AuthState.LoggedOut
            }
        }

    private suspend fun loadUser() {
        _state.value =
            when (val result = api.getMe()) {
                is ApiResult.Success -> {
                    authDebugLog("getMe OK")
                    AuthState.Authenticated(result.value)
                }
                is ApiResult.Failure -> {
                    authDebugLog("getMe FAILED: code=${result.code} status=${result.httpStatus} msg=${result.message}")
                    AuthState.Error(result.userMessage())
                }
            }
    }

    /** Used by the Ktor bearer provider's reactive 401 refresh. */
    private suspend fun refreshAndPersist(refreshToken: String): OidcTokens? =
        runCatching {
            oidc.refresh(refreshToken).also { tokens ->
                tokens.refreshToken?.let { tokenStore.saveRefreshToken(it) }
                tokenHolder.set(tokens)
            }
        }.getOrNull()
}
