package org.homeflow.app.shared.config

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round-trip tests for [DesktopAppModeStore]: save→load and clear→null.
 * Uses a temporary directory so it doesn't touch `~/.homeflow`.
 */
class AppModeStoreTest {
    private lateinit var tmpDir: File
    private lateinit var store: AppModeStore

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("homeflow-test-mode").toFile()
        store = DesktopAppModeStore(tmpDir)
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `load returns null before any save`() {
        assertNull(store.load())
    }

    @Test
    fun `save then load round-trips LOCAL_ONLY`() {
        store.save(AppMode.LOCAL_ONLY)
        assertEquals(AppMode.LOCAL_ONLY, store.load())
    }

    @Test
    fun `save then load round-trips SERVER`() {
        store.save(AppMode.SERVER)
        assertEquals(AppMode.SERVER, store.load())
    }

    @Test
    fun `save overwrites previous value`() {
        store.save(AppMode.LOCAL_ONLY)
        store.save(AppMode.SERVER)
        assertEquals(AppMode.SERVER, store.load())
    }

    @Test
    fun `clear makes load return null`() {
        store.save(AppMode.LOCAL_ONLY)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `clear is safe when nothing was saved`() {
        store.clear() // must not throw
        assertNull(store.load())
    }
}
