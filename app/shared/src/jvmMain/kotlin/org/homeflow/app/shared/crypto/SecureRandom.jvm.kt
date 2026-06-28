package org.homeflow.app.shared.crypto

import java.security.SecureRandom

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
