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
import org.homeflow.app.shared.data.local.LocalBootstrap
import org.homeflow.app.shared.data.userMessage
import org.homeflow.core.dto.ImportResultDto
import org.homeflow.core.dto.UserDto
import org.homeflow.core.validation.semverAtLeast

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
    private val clientVersion: String = "unknown",
    /**
     * When true, an unlock whose token refresh / `getMe` fails because the server is
     * **unreachable** (offline) still reaches [AuthState.Authenticated] using the local store,
     * rather than [AuthState.Error]. Set only for the Mode-C composition (server-connected +
     * local offline store); a genuine grant rejection still forces re-login. Default false
     * preserves the strict online behavior for any other caller.
     */
    private val allowOfflineUnlock: Boolean = false,
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
            val tokens =
                runCatching { oidc.refresh(refresh) }
                    .getOrElse { e ->
                        if (e is OidcException && e.isGrantRejected) {
                            // The stored session is no longer valid (expired, revoked, or the realm/
                            // Keycloak was recreated). Drop the dead token and send the user to a
                            // fresh login instead of surfacing a raw token-parse error. This is a
                            // definitive answer from a REACHABLE server, so it is never treated as
                            // "offline" — even in Mode C.
                            authDebugLog("unlock: stored refresh rejected (${e.statusCode}/${e.oauthError}); re-login")
                            tokenStore.clear()
                            tokenHolder.clear()
                            _state.value = AuthState.LoggedOut
                            return
                        }
                        if (allowOfflineUnlock) {
                            // The IdP is unreachable (offline / transient). In Mode C the app-lock
                            // gate has already passed and the local encrypted store is authoritative,
                            // so unlock offline and let sync resume when connectivity returns rather
                            // than blocking the user out of their own on-device data.
                            authDebugLog("unlock: IdP unreachable (${e.describe()}); using local data offline")
                            authenticateOffline(refresh)
                            return
                        }
                        throw e
                    }
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

    /**
     * Reach [AuthState.Authenticated] from the local store when the server is unreachable.
     * Only called under [allowOfflineUnlock] after the app-lock gate has passed. Seeds the
     * refresh token so the HTTP client can mint a fresh access token (and sync can resume)
     * once the network returns, then presents the synthetic local user (unused by the Mode-C
     * shell, which renders off the local repository).
     */
    private fun authenticateOffline(refreshToken: String) {
        tokenHolder.setRefreshToken(refreshToken)
        _state.value = AuthState.Authenticated(offlineUser(), repository)
    }

    /** The single local account identity used while offline (see [LocalBootstrap.LOCAL_USER_ID]). */
    private fun offlineUser(): UserDto = UserDto(id = LocalBootstrap.LOCAL_USER_ID, createdAt = "")

    private suspend fun loadUser() {
        // Check server compatibility before loading the user. A 404 or network error
        // means the server predates the /version endpoint — treat as compatible.
        when (val versionResult = remote.getServerVersion()) {
            is ApiResult.Success -> {
                val ver = versionResult.value
                if (!semverAtLeast(clientVersion, ver.minClientVersion)) {
                    authDebugLog(
                        "compatibility check FAILED: clientVersion=$clientVersion " +
                            "minRequired=${ver.minClientVersion}",
                    )
                    _state.value =
                        AuthState.Error(
                            "This app (version $clientVersion) is too old for your server. " +
                                "Please update to version ${ver.minClientVersion} or newer.",
                        )
                    return
                }
                authDebugLog(
                    "compatibility check OK: server=${ver.serverVersion} " +
                        "minClient=${ver.minClientVersion} client=$clientVersion",
                )
            }
            is ApiResult.Failure -> {
                // 404 = server predates /version endpoint; or a transient network error.
                // Do not block login in either case.
                authDebugLog("version check skipped: code=${versionResult.code}")
            }
        }
        _state.value =
            when (val result = api.getMe()) {
                is ApiResult.Success -> {
                    authDebugLog("getMe OK")
                    AuthState.Authenticated(result.value, repository)
                }
                is ApiResult.Failure -> {
                    authDebugLog("getMe FAILED: code=${result.code} status=${result.httpStatus} msg=${result.message}")
                    if (allowOfflineUnlock && result.httpStatus == 0) {
                        // httpStatus == 0 is the network-error marker (see ApiResult helpers): the
                        // API is unreachable, not rejecting us. In Mode C fall back to the local
                        // store rather than erroring the whole app.
                        authDebugLog("getMe offline (network unreachable); using local data")
                        AuthState.Authenticated(offlineUser(), repository)
                    } else {
                        AuthState.Error(result.userMessage())
                    }
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
