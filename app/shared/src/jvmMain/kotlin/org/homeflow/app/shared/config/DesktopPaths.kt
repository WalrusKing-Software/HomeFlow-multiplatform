package org.homeflow.app.shared.config

import java.io.File

/**
 * Root data directory for the desktop app. Reads the "homeflow.dataDir" system property
 * set by main() in :app:desktopApp so dev builds (~/.homeflow-dev) and production builds
 * (~/.homeflow) stay separate. Defaults to ".homeflow" if the property is absent (e.g.
 * in unit tests).
 */
internal val desktopDataDir: File
    get() = File(System.getProperty("user.home"), System.getProperty("homeflow.dataDir", ".homeflow"))
