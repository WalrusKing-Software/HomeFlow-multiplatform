package org.homeflow

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.homeflow.app.shared.ui.AppRoot

fun main() {
    // Publish the data directory name so jvmMain actuals in :app:shared resolve the right path.
    // Must be set before application{} initialises any store that reads the property.
    System.setProperty("homeflow.dataDir", DESKTOP_DATA_DIR)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = if (DESKTOP_DATA_DIR == ".homeflow-dev") "HomeFlow Dev" else "HomeFlow",
            // Runtime window / taskbar icon (the installer icon is set separately in
            // build.gradle.kts nativeDistributions). Bundled at resources/homeflow-icon.png.
            icon = painterResource("homeflow-icon.png"),
        ) {
            AppRoot(clientVersion = DESKTOP_VERSION)
        }
    }
}
