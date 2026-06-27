package org.homeflow.app.shared.auth

/**
 * Stores and retrieves the local database data-encryption key (DEK).
 *
 * The DEK is a 32-byte AES-256 key that encrypts the whole SQLite database (SQLCipher).
 * It is wrapped by the OS-level secure store:
 *   - Android: Keystore-backed [EncryptedSharedPreferences]
 *   - Desktop: OS keychain via java-keyring
 *
 * The DEK never appears in logs or plaintext files. [clearDek] is called on account deletion.
 */
interface LocalKeyStore {
    /** Retrieves the DEK, or null if it has not been generated yet. */
    fun loadDek(): ByteArray?

    /** Persists the DEK (Base64-encoded). */
    fun saveDek(dek: ByteArray)

    /** Removes the DEK from secure storage (account deletion). */
    fun clearDek()
}

expect fun createLocalKeyStore(): LocalKeyStore
