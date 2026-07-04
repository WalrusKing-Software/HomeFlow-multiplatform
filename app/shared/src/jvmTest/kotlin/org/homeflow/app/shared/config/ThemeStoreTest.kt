package org.homeflow.app.shared.config

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trip tests for [DesktopThemeStore]. Uses a temporary directory so it doesn't touch
 * `~/.homeflow`.
 */
class ThemeStoreTest {
    private lateinit var tmpDir: File
    private lateinit var store: ThemeStore

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("homeflow-test-theme").toFile()
        store = DesktopThemeStore(tmpDir)
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `load defaults to SYSTEM before any save`() {
        assertEquals(ThemePreference.SYSTEM, store.load())
    }

    @Test
    fun `save then load round-trips every value`() {
        for (pref in ThemePreference.entries) {
            store.save(pref)
            assertEquals(pref, store.load())
        }
    }

    @Test
    fun `save overwrites previous value`() {
        store.save(ThemePreference.DARK)
        store.save(ThemePreference.LIGHT)
        assertEquals(ThemePreference.LIGHT, store.load())
    }

    @Test
    fun `an unknown persisted value falls back to SYSTEM`() {
        File(tmpDir, "theme.properties").writeText("theme=NEON")
        assertEquals(ThemePreference.SYSTEM, store.load())
    }
}
