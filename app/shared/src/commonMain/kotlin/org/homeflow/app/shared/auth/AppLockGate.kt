package org.homeflow.app.shared.auth

/**
 * The strong factor required on app open **before the stored refresh token is used**
 * (`ARCHITECTURE-client.md` step 3). Android: `BiometricPrompt` (device-credential
 * fallback) — [authenticate] shows the prompt and [secret] is ignored. Desktop: an
 * app passphrase — [enroll] sets it on first run, [authenticate] verifies the
 * supplied [secret].
 */
interface AppLockGate {
    /** True when the gate is satisfied by a typed passphrase (desktop) vs. biometrics (Android). */
    val usesPassphrase: Boolean

    /** True on first run, before any factor has been established (desktop passphrase). */
    fun needsEnrollment(): Boolean

    /** Establish the factor. No-op where the OS owns it (Android biometric). */
    suspend fun enroll(secret: String)

    /**
     * Verify the strong factor. Returns true on success. [secret] carries the desktop
     * passphrase; Android ignores it and shows the biometric prompt.
     */
    suspend fun authenticate(secret: String?): Boolean
}

expect fun createAppLockGate(): AppLockGate
