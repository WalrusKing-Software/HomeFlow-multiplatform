package org.homeflow.app.shared.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.homeflow.app.shared.db.HomeFlowDb
import java.io.File

/**
 * Desktop [LocalDatabaseFactory]: opens the SQLite database using the willena
 * JDBC driver (which bundles SQLite3MultipleCiphers / SQLCipher). The DEK is
 * applied via `PRAGMA key = "x'<hex>'"` immediately after opening the connection,
 * before any schema operations — as specified in D-13.2.
 *
 * Database location: `{user.home}/.homeflow/homeflow_local.db`
 *
 * If `io.github.willena:sqlite-jdbc` is not on the classpath or `PRAGMA key` is
 * rejected, this will throw — STOP and report (do not fall back to unencrypted).
 */
actual class LocalDatabaseFactory actual constructor() {

    actual fun create(dek: ByteArray): HomeFlowDb {
        val dbDir = File(System.getProperty("user.home"), ".homeflow")
        dbDir.mkdirs()
        val dbFile = File(dbDir, DB_NAME)
        val isNew = !dbFile.exists() || dbFile.length() == 0L

        val hexKey = dek.joinToString("") { "%02x".format(it) }

        val driver = JdbcSqliteDriver(url = "jdbc:sqlite:${dbFile.absolutePath}")

        // PRAGMA key must come before any schema operations — D-13.2 spec.
        driver.execute(null, """PRAGMA key = "x'${hexKey}'" """, 0, null)

        if (isNew) {
            HomeFlowDb.Schema.create(driver)
        } else {
            val currentVersion = runCatching {
                driver.executeQuery(
                    identifier = null,
                    sql = "PRAGMA user_version",
                    mapper = { cursor ->
                        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
                    },
                    parameters = 0,
                    binders = null,
                ).value
            }.getOrElse { 0L }

            val schemaVersion = HomeFlowDb.Schema.version
            if (currentVersion < schemaVersion) {
                HomeFlowDb.Schema.migrate(driver, currentVersion, schemaVersion)
            }
        }

        return HomeFlowDb(driver)
    }

    private companion object {
        const val DB_NAME = "homeflow_local.db"
    }
}
