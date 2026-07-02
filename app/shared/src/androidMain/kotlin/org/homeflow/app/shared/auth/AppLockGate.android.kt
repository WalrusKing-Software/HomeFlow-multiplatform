package org.homeflow.app.shared.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.homeflow.app.shared.platform.AndroidAppContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `BiometricPrompt` gate (strong biometric, with device-credential fallback). The OS
 * owns enrollment, so [enroll]/[needsEnrollment] are no-ops; [authenticate] shows the
 * prompt. Requires the host to be a `FragmentActivity` (see `MainActivity`).
 */
class AndroidAppLockGate : AppLockGate {
    override val usesPassphrase: Boolean = false

    override fun needsEnrollment(): Boolean = false

    override suspend fun enroll(secret: String) = Unit

    override suspend fun authenticate(secret: String?): Boolean {
        val activity =
            AndroidAppContext.activity
                ?: error("Unlock failed: no active screen to show the biometric prompt on.")
        val allowed =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        // If the device has no usable strong factor — no biometric enrolled AND no device
        // credential (PIN/pattern/password) set — there is nothing to gate with. Rather than
        // bricking local-only mode with a hard error, proceed without the app-open lock: the DEK
        // stays encrypted at rest by the hardware-backed Keystore master key, and no OS factor
        // exists to enforce a prompt on a device with no lock screen. See ARCHITECTURE-client.md
        // (app-lock gate). BiometricPrompt is only shown when a factor is actually available.
        val canAuthenticate = BiometricManager.from(activity).canAuthenticate(allowed)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            return true
        }
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val prompt =
                    BiometricPrompt(
                        activity,
                        ContextCompat.getMainExecutor(activity),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onAuthenticationError(
                                errorCode: Int,
                                errString: CharSequence,
                            ) {
                                if (!cont.isActive) return
                                // User dismissed the prompt themselves — bounce back to the lock
                                // screen quietly rather than showing an "error".
                                val userDismissed =
                                    errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                                        errorCode == BiometricPrompt.ERROR_CANCELED
                                if (userDismissed) {
                                    cont.resume(false)
                                } else {
                                    cont.resumeWithException(IllegalStateException("Unlock failed: $errString"))
                                }
                            }

                            override fun onAuthenticationFailed() = Unit // keep prompting until error/success
                        },
                    )
                val info =
                    BiometricPrompt.PromptInfo
                        .Builder()
                        .setTitle("Unlock HomeFlow")
                        .setSubtitle("Authenticate to access your data")
                        .setAllowedAuthenticators(allowed)
                        .build()
                prompt.authenticate(info)
            }
        }
    }
}

actual fun createAppLockGate(): AppLockGate = AndroidAppLockGate()
