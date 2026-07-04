package org.homeflow

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.homeflow.app.shared.ui.AppRoot

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "HomeFlow",
            // Runtime window / taskbar icon (the installer icon is set separately in
            // build.gradle.kts nativeDistributions). Bundled at resources/homeflow-icon.png.
            icon = painterResource("homeflow-icon.png"),
        ) {
            AppRoot(clientVersion = DESKTOP_VERSION)
        }
    }
