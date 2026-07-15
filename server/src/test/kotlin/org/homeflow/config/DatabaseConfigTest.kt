package org.homeflow.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** SEC-04: the JDBC URL carries a validated `sslmode` (default `disable` for the Docker network). */
class DatabaseConfigTest {
    @Test
    fun `default sslmode is disable and appears in the JDBC URL`() {
        val config = DatabaseConfig("db.local", 5432, "period_tracker", "app", "pw")
        assertTrue(config.jdbcUrl.endsWith("?sslmode=disable"), "got ${config.jdbcUrl}")
    }

    @Test
    fun `an explicit verify-full sslmode is honored`() {
        val config = DatabaseConfig("db.local", 5432, "period_tracker", "app", "pw", sslMode = "verify-full")
        assertEquals("jdbc:postgresql://db.local:5432/period_tracker?sslmode=verify-full", config.jdbcUrl)
    }

    @Test
    fun `an invalid sslmode fails fast at construction`() {
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig("db.local", 5432, "period_tracker", "app", "pw", sslMode = "yes-please")
        }
    }
}
