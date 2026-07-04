package org.homeflow.app.shared.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ThemePreference.isDark] resolution across all modes and both system states. */
class ThemePreferenceTest {
    @Test
    fun `SYSTEM follows the OS dark setting`() {
        assertTrue(ThemePreference.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemePreference.SYSTEM.isDark(systemDark = false))
    }

    @Test
    fun `LIGHT is never dark regardless of OS`() {
        assertFalse(ThemePreference.LIGHT.isDark(systemDark = true))
        assertFalse(ThemePreference.LIGHT.isDark(systemDark = false))
    }

    @Test
    fun `DARK is always dark regardless of OS`() {
        assertTrue(ThemePreference.DARK.isDark(systemDark = true))
        assertTrue(ThemePreference.DARK.isDark(systemDark = false))
    }

    @Test
    fun `CLASSIC_DARK is always dark regardless of OS`() {
        assertTrue(ThemePreference.CLASSIC_DARK.isDark(systemDark = true))
        assertTrue(ThemePreference.CLASSIC_DARK.isDark(systemDark = false))
    }

    @Test
    fun `default preference is SYSTEM`() {
        assertEquals(ThemePreference.SYSTEM, ThemePreference.entries.first())
    }
}
