package org.homeflow.app.shared.config

/**
 * Persists the runtime server host and the one-time adoption-migration flag in a
 * **non-encrypted** store, readable before the app-lock gate is satisfied.
 *
 * The host is a public hostname (not a secret); the migrated flag is not sensitive.
 * Neither value should live in the SQLCipher DB — the host must be known before the
 * DEK is available (D-15.1).
 *
 * Android: plain SharedPreferences (`homeflow_server`).
 * Desktop: `~/.homeflow/server.properties` (keys `server_host`, `migrated`).
 */
interface ServerConfigStore {
    /** The last saved server host (e.g. `myhost.ts.net`), or null if none entered yet. */
    fun loadHost(): String?

    /** Persists the bare host. Normalisation (trim, strip scheme) is the caller's job. */
    fun saveHost(host: String)

    /** True once this device's local data has been uploaded to the server (Mode B adoption). */
    fun isMigrated(): Boolean

    /** Marks the local-to-server migration as completed so it is not offered again. */
    fun setMigrated()

    /** Clears both the host and the migrated flag (account deletion / mode reset). */
    fun clear()
}

expect fun createServerConfigStore(): ServerConfigStore
