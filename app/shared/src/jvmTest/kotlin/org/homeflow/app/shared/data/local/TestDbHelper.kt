package org.homeflow.app.shared.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.homeflow.app.shared.db.HomeFlowDb

/**
 * Creates an in-memory [HomeFlowDb] for jvmTest tests that don't need encryption.
 * Uses the standard sqlite-driver (no SQLCipher dependency).
 */
object TestDbHelper {
    fun inMemory(): HomeFlowDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HomeFlowDb.Schema.create(driver)
        return HomeFlowDb(driver)
    }

    /** Convenience: build + seed. Returns a ready-to-use [LocalDataSource]. */
    fun seededDataSource(): LocalDataSource {
        val db = inMemory()
        LocalBootstrap.seed(db)
        return LocalDataSource(db)
    }
}
