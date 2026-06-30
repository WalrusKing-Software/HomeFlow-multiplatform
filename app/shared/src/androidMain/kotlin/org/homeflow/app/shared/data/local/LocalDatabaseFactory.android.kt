package org.homeflow.app.shared.data.local

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.homeflow.app.shared.db.HomeFlowDb
import org.homeflow.app.shared.platform.AndroidAppContext

actual class LocalDatabaseFactory actual constructor() {
    actual fun create(dek: ByteArray): HomeFlowDb {
        val context: Context = AndroidAppContext.application
        val factory = SupportOpenHelperFactory(dek)
        val driver =
            AndroidSqliteDriver(
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
