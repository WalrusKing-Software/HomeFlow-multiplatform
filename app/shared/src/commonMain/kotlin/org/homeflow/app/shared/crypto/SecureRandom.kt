package org.homeflow.app.shared.crypto

import org.homeflow.app.shared.auth.LocalKeyStore

/** Generates [size] cryptographically random bytes. Both platforms use `java.security.SecureRandom`. */
expect fun secureRandomBytes(size: Int): ByteArray

/**
 * Returns the existing DEK from secure storage, or generates and persists a fresh 32-byte
 * DEK if none exists yet. The DEK never leaves the device and is never logged.
 */
fun LocalKeyStore.loadOrCreateDek(): ByteArray = loadDek() ?: secureRandomBytes(32).also { saveDek(it) }
