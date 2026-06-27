package org.homeflow.app.shared.data.local

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportFactory
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.app.shared.platform.AndroidAppContext

/**
 * Android [LocalDatabaseFactory]: opens the SQLite database via SQLCipher's
 * [SupportFactory], passing the DEK as the database passphrase.
 * The database file is stored in the app's private data directory.
 */
actual class LocalDatabaseFactory actual constructor() {

    actual fun create(dek: ByteArray): HomeFlowDb {
        val context: Context = AndroidAppContext.application
        SQLiteDatabase.loadLibs(context)
        val factory = SupportFactory(dek)
        val driver = AndroidSqliteDriver(
            schema = HomeFlowDb.Schema,
            context = context,
            name = DB_NAME,
            factory = factory,
        )
        return HomeFlowDb(driver)
    }

    private companion object {
        const val DB_NAME = "homeflow_local.db"
    }
}
