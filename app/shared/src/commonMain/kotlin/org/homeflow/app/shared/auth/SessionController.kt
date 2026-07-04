package org.homeflow.app.shared.auth

import kotlinx.coroutines.flow.StateFlow
import org.homeflow.app.shared.data.ApiResult

/**
 * The uniform session contract both Mode A (local-only) and Mode B (server-connected)
 * satisfy. [org.homeflow.app.shared.ui.App] renders off this interface; [AppRoot] picks
 * the implementation at startup from the persisted [org.homeflow.app.shared.config.AppMode].
 *
 * Implementations: [AuthController] (Mode B, OIDC) and [LocalSessionController] (Mode A,
 * app-lock + DEK only).
 */
interface SessionController {
    val state: StateFlow<AuthState>
    val usesPassphraseGate: Boolean

    /** Called once on app open: sets the initial state from persisted storage. */
    fun start()

    /** Interactive login (Mode B: OIDC browser flow; Mode A: no-op). */
    suspend fun login()

    /** First-run factor establishment (desktop passphrase; Android: no-op). */
    suspend fun enroll(secret: String)

    /** Satisfy the gate, then load the session. [secret] is the desktop passphrase; pass null for biometrics. */
    suspend fun unlock(secret: String? = null)

    /** End the session; returns the app to the lock/login screen. */
    suspend fun logout()

    /** Permanently destroy the account and all associated data. */
    suspend fun deleteAccount(): ApiResult<Unit>
}
