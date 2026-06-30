package org.homeflow.app.shared.data.local

import org.homeflow.app.shared.db.HomeFlowDb

/**
 * Platform-specific factory that opens (or creates) the encrypted local database.
 * The [dek] is the 32-byte data-encryption key held by [LocalKeyStore]; it is
 * consumed here and never leaves this layer.
 *
 * Android uses SQLCipher's `SupportFactory`; desktop (JVM) uses the willena
 * SQLite+SQLCipher JDBC driver with `PRAGMA key`.
 */
expect class LocalDatabaseFactory() {
    fun create(dek: ByteArray): HomeFlowDb
}
