package org.homeflow.lib

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for the AES-256-GCM column-encryption helper: round-tripping,
 * fresh-IV non-determinism, ciphertext-at-rest (no plaintext leakage), and hard
 * failure on tamper / wrong key / malformed key. No DB or Ktor needed.
 */
class EncryptionTest {
    private val key = base64Key(seed = 1)
    private val encryption = Encryption(key)

    @Test
    fun `round-trips plaintext`() {
        val plaintext = "Cramping started in the afternoon."
        assertEquals(plaintext, encryption.decrypt(encryption.encrypt(plaintext)))
    }

    @Test
    fun `round-trips empty and unicode`() {
        for (text in listOf("", "café ☕ 月経 🩸")) {
            assertEquals(text, encryption.decrypt(encryption.encrypt(text)))
        }
    }

    @Test
    fun `uses a fresh IV so the same plaintext encrypts differently`() {
        val plaintext = "same input"
        assertNotEquals(encryption.encrypt(plaintext), encryption.encrypt(plaintext))
    }

    @Test
    fun `ciphertext does not contain the plaintext`() {
        val plaintext = "secret note"
        assertTrue(plaintext !in encryption.encrypt(plaintext), "plaintext must not appear in the stored form")
    }

    @Test
    fun `decrypting a tampered payload fails`() {
        val stored = encryption.encrypt("untouched")
        val parts = stored.split(":")
        val flippedCipher = parts[1].dropLast(1) + if (parts[1].last() == 'A') 'B' else 'A'
        val tampered = "${parts[0]}:$flippedCipher:${parts[2]}"
        assertFails { encryption.decrypt(tampered) }
    }

    @Test
    fun `decrypting with a different key fails`() {
        val stored = encryption.encrypt("for key one")
        assertFails { Encryption(base64Key(seed = 2)).decrypt(stored) }
    }

    @Test
    fun `a malformed payload is rejected`() {
        assertFails { encryption.decrypt("not-a-valid-payload") }
    }

    @Test
    fun `a key of the wrong length is rejected at construction`() {
        assertFails { Encryption(Base64.getEncoder().encodeToString(ByteArray(16))) }
    }

    private fun base64Key(seed: Byte): String = Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES) { seed })

    private companion object {
        const val KEY_BYTES = 32
    }
}
