package org.homeflow.app.shared.ui.theme

/**
 * Whether to show a quick light/dark toggle in the app's top bar. True on desktop (a
 * top-bar toggle is the idiomatic place there); false on Android, where the Appearance
 * setting lives in Settings only. The Appearance selector in Settings is shown on both.
 */
expect val showTopBarThemeToggle: Boolean
