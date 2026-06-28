package org.homeflow.app.shared.auth

/**
 * Clears the app-lock enrollment from secure storage, called on Mode-A account deletion
 * so a fresh Mode-A session asks the user to set a new passphrase.
 *
 * Desktop: removes the PBKDF2 passphrase record from the OS keychain.
 * Android: no-op — biometric enrollment is OS-managed and not per-app.
 */
expect fun resetAppLockGateEnrollment()
