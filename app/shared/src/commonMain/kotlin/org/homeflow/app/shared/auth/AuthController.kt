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
import org.homeflow.core.dto.ImportResultDto
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

    /**
     * Signed in; [user] is the `GET /users/me` record (or local synthetic user in Mode A).
     * [repository] is the ready-to-use read/write surface — available here so the UI never
     * needs a separate "repository only valid after unlock" reference.
     */
    data class Authenticated(
        val user: UserDto,
        val repository: HomeFlowRepository,
    ) : AuthState

    data class Error(
        val message: String,
    ) : AuthState
}

/**
 * Owns the sequence from `ARCHITECTURE-client.md` "Auth flow": login → secure store →
 * app-lock gate → silent refresh → `GET /users/me`. Platform specifics
 * ([OidcClient]/[TokenStore]/[AppLockGate]) are injected; everything here is shared.
 * Implements [SessionController] so [AppRoot] can use it interchangeably with
 * [LocalSessionController].
 */
class AuthController(
    private val config: AuthConfig,
    private val oidc: OidcClient,
    private val tokenStore: TokenStore,
    private val gate: AppLockGate,
    private val tokenHolder: TokenHolder = TokenHolder(),
    httpClientFactory: (AuthConfig, TokenHolder, suspend (String) -> OidcTokens?) -> HttpClient =
        { c, h, onRefresh -> buildHttpClient(c, h, onRefresh) },
) : SessionController {
    private val _state = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Whether the lock screen should collect a passphrase (desktop) or trigger biometrics (Android). */
    override val usesPassphraseGate: Boolean = gate.usesPassphrase

    private val http: HttpClient = httpClientFactory(config, tokenHolder, ::refreshAndPersist)

    // Typed as RemoteDataSource so uploadLocalData and sync can call extra methods (D-15.9, D-16b).
    // Both fields point at the same instance; api uses the seam type for all other call sites.
    internal val remote: RemoteDataSource = RemoteDataSource(http)
    private val api: HomeFlowDataSource = remote

    // Kept private — the repository is now surfaced through AuthState.Authenticated.
    private val repository: HomeFlowRepository = HomeFlowRepository(api)

    /** App open: a stored refresh token sends us to the lock gate; otherwise log in. */
    override fun start() {
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
    override suspend fun login() {
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
    override suspend fun enroll(secret: String) {
        runCatching {
            gate.enroll(secret)
            _state.value = AuthState.Locked(needsEnrollment = false)
        }.onFailure { _state.value = AuthState.Error(it.message ?: "Enrollment failed") }
    }

    /** Satisfy the gate, then silently refresh the stored token and load the user. */
    override suspend fun unlock(secret: String?) {
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
    override suspend fun logout() {
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
    override suspend fun deleteAccount(): ApiResult<Unit> =
        repository.deleteAccount().also { result ->
            if (result is ApiResult.Success) {
                tokenStore.clear()
                tokenHolder.clear()
                _state.value = AuthState.LoggedOut
            }
        }

    /**
     * Upload [json] (a `HomeFlowExport` payload) to the server's import endpoint.
     * Valid only while [AuthState.Authenticated] (the bearer token is attached by the HTTP
     * client). Not on [SessionController] — this is a remote-only migration affordance
     * that [LocalDataSource] must not implement (D-15.6/D-15.9).
     */
    suspend fun uploadLocalData(json: String): ApiResult<ImportResultDto> = remote.uploadHomeflowImport(json)

    private suspend fun loadUser() {
        _state.value =
            when (val result = api.getMe()) {
                is ApiResult.Success -> {
                    authDebugLog("getMe OK")
                    AuthState.Authenticated(result.value, repository)
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
