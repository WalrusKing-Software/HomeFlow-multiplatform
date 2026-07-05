package org.homeflow.app.shared.config

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trip tests for [DesktopThemePreferenceStore]: default → SYSTEM, save→load, overwrite.
 * Uses a temporary directory so it doesn't touch `~/.homeflow`.
 */
class ThemePreferenceStoreTest {
    private lateinit var tmpDir: File
    private lateinit var store: ThemePreferenceStore

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("homeflow-test-theme").toFile()
        store = DesktopThemePreferenceStore(tmpDir)
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `load defaults to SYSTEM before any save`() {
        assertEquals(ThemeMode.SYSTEM, store.load())
    }

    @Test
    fun `save then load round-trips DARK`() {
        store.save(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, store.load())
    }

    @Test
    fun `save then load round-trips LIGHT`() {
        store.save(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, store.load())
    }

    @Test
    fun `save overwrites previous value`() {
        store.save(ThemeMode.DARK)
        store.save(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, store.load())
    }

    @Test
    fun `load falls back to SYSTEM on an unrecognised value`() {
        // Simulate a file written by a newer/older build with an unknown mode.
        File(tmpDir, "theme.properties").writeText("theme_mode=NEON")
        assertEquals(ThemeMode.SYSTEM, store.load())
    }
}
