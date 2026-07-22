package org.homeflow.app.shared.auth

import com.github.javakeyring.Keyring
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Desktop app-lock = an **app passphrase**. On first run the user enrols a passphrase;
 * we store only its PBKDF2(HMAC-SHA256) hash + salt in the OS keychain. On each launch
 * the entered passphrase is verified before the stored refresh token is used.
 */
@OptIn(ExperimentalEncodingApi::class)
class DesktopAppLockGate(
    private val keyring: Keyring = Keyring.create(),
) : AppLockGate {
    override val usesPassphrase: Boolean = true

    override fun needsEnrollment(): Boolean = loadRecord() == null

    override suspend fun enroll(secret: String) {
        require(secret.isNotBlank()) { "Passphrase must not be blank" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(secret, salt)
        keyring.setPassword(SERVICE, ACCOUNT_PASSPHRASE, "${Base64.encode(salt)}:${Base64.encode(hash)}")
    }

    override suspend fun authenticate(secret: String?): Boolean {
        if (secret.isNullOrEmpty()) return false
        val (salt, expected) = loadRecord() ?: return false
        return constantTimeEquals(pbkdf2(secret, salt), expected)
    }

    private fun loadRecord(): Pair<ByteArray, ByteArray>? {
        val stored =
            try {
                keyring.getPassword(SERVICE, ACCOUNT_PASSPHRASE)
            } catch (e: Exception) {
                // SEC-12: never fail silently — a broken keychain reads as "wrong passphrase".
                authDebugLog("AppLockGate: keyring read failed: ${e.describe()}")
                null
            } ?: return null
        val parts = stored.split(":")
        if (parts.size != 2) return null
        return try {
            Base64.decode(parts[0]) to Base64.decode(parts[1])
        } catch (e: Exception) {
            authDebugLog("AppLockGate: stored enrollment record is corrupt: ${e.describe()}")
            null
        }
    }

    private fun pbkdf2(
        secret: String,
        salt: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private companion object {
        const val SERVICE = "org.homeflow.desktop"
        const val ACCOUNT_PASSPHRASE = "app_lock_passphrase"
        const val SALT_BYTES = 16
        const val ITERATIONS = 120_000
        const val KEY_BITS = 256
    }
}

actual fun createAppLockGate(): AppLockGate = DesktopAppLockGate()
