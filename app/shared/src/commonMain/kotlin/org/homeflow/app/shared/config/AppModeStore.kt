package org.homeflow.app.shared.config

/**
 * Persists the user's chosen [AppMode] in a **non-encrypted** store so it can be read
 * before the app-lock gate is satisfied (and before the DEK is available).
 *
 * [load] returns null on first run (unset) — [AppRoot] shows the chooser.
 * Android: plain SharedPreferences. Desktop: `~/.homeflow/mode.properties`.
 */
interface AppModeStore {
    fun load(): AppMode?

    fun save(mode: AppMode)

    fun clear()
}

expect fun createAppModeStore(): AppModeStore
