package org.homeflow.lib

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Application-layer column encryption (AES-256-GCM). Used by service functions only —
 * never repositories, routes, `:core`, or any client (see `__docs/ARCHITECTURE-server.md`
 * "Encryption" and `__docs/threat-model.md`). The key comes from `APP_ENCRYPTION_KEY`
 * (32 raw bytes, base64) and is never hardcoded or logged.
 *
 * Stored form is `base64(iv):base64(ciphertext):base64(tag)` with a fresh 12-byte IV
 * per call and a 128-bit auth tag. Decryption failure (tamper, truncation, wrong key)
 * throws — it is never silently swallowed.
 */
class Encryption(
    keyBase64: String,
) {
    private val key: SecretKeySpec
    private val secureRandom = SecureRandom()

    init {
        val keyBytes =
            runCatching { Base64.getDecoder().decode(keyBase64.trim()) }
                .getOrElse { error("APP_ENCRYPTION_KEY is not valid base64") }
        require(keyBytes.size == KEY_LENGTH_BYTES) {
            "APP_ENCRYPTION_KEY must decode to $KEY_LENGTH_BYTES bytes (got ${keyBytes.size})"
        }
        key = SecretKeySpec(keyBytes, "AES")
    }

    /** Encrypts [plaintext], returning `base64(iv):base64(ciphertext):base64(tag)`. */
    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also(secureRandom::nextBytes)
        // A fresh random IV is generated per call (above), so GCM nonce reuse cannot
        // occur; the gcm-detection warning does not apply (suppressed inline below).
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv)) // nosemgrep
            }
        // GCM appends the auth tag to the ciphertext; split it back out for the stored form.
        val combined = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val tagStart = combined.size - TAG_LENGTH_BYTES
        val ciphertext = combined.copyOfRange(0, tagStart)
        val tag = combined.copyOfRange(tagStart, combined.size)
        return listOf(iv, ciphertext, tag).joinToString(SEPARATOR) { encode(it) }
    }

    /** Decrypts a [stored] `iv:ciphertext:tag` string. Throws if it is malformed or fails authentication. */
    fun decrypt(stored: String): String {
        val parts = stored.split(SEPARATOR)
        require(parts.size == PART_COUNT) { "Encrypted payload is malformed." }
        val iv = decode(parts[0])
        val ciphertext = decode(parts[1])
        val tag = decode(parts[2])
        // The IV is read from the stored payload for decryption, not generated here;
        // the gcm-detection warning does not apply (suppressed inline below).
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv)) // nosemgrep
            }
        return String(cipher.doFinal(ciphertext + tag), Charsets.UTF_8)
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_LENGTH_BYTES = 32
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
        const val SEPARATOR = ":"
        const val PART_COUNT = 3
    }
}
