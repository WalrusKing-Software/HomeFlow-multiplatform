package org.homeflow.app.shared.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.homeflow.app.shared.platform.AndroidAppContext
import kotlin.coroutines.resume

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
        val activity = AndroidAppContext.activity ?: return false
        val allowed =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        // No biometric/credential enrolled on the device → can't gate; fail closed.
        if (BiometricManager.from(activity).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            return false
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
                                if (cont.isActive) cont.resume(false)
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
