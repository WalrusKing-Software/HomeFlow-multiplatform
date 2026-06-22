package org.homeflow

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.homeflow.app.shared.ui.App

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "HomeFlow",
        ) {
            App()
        }
    }
