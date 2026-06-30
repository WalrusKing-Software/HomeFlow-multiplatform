package org.homeflow.app.shared.config

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip tests for [DesktopServerConfigStore] against a temporary directory.
 * Mirrors [AppModeStoreTest] structure.
 */
class ServerConfigStoreTest {
    private lateinit var tmpDir: File
    private lateinit var store: ServerConfigStore

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("homeflow-test-server").toFile()
        store = DesktopServerConfigStore(tmpDir)
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `loadHost returns null before any save`() {
        assertNull(store.loadHost())
    }

    @Test
    fun `saveHost then loadHost round-trips`() {
        store.saveHost("myhost.ts.net")
        assertEquals("myhost.ts.net", store.loadHost())
    }

    @Test
    fun `saveHost overwrites previous value`() {
        store.saveHost("first.ts.net")
        store.saveHost("second.ts.net")
        assertEquals("second.ts.net", store.loadHost())
    }

    @Test
    fun `isMigrated defaults to false`() {
        assertFalse(store.isMigrated())
    }

    @Test
    fun `setMigrated makes isMigrated return true`() {
        store.setMigrated()
        assertTrue(store.isMigrated())
    }

    @Test
    fun `clear resets host to null`() {
        store.saveHost("myhost.ts.net")
        store.clear()
        assertNull(store.loadHost())
    }

    @Test
    fun `clear resets migrated to false`() {
        store.setMigrated()
        store.clear()
        assertFalse(store.isMigrated())
    }

    @Test
    fun `clear is safe when nothing was saved`() {
        store.clear() // must not throw
        assertNull(store.loadHost())
        assertFalse(store.isMigrated())
    }

    @Test
    fun `host and migrated persist independently`() {
        store.saveHost("myhost.ts.net")
        store.setMigrated()
        assertEquals("myhost.ts.net", store.loadHost())
        assertTrue(store.isMigrated())
    }
}
