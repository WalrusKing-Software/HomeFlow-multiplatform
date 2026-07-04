package org.homeflow.app.shared.crypto

/** SHA-256 of [bytes]. Backed by the platform crypto provider (JDK `MessageDigest`). */
expect fun sha256(bytes: ByteArray): ByteArray
