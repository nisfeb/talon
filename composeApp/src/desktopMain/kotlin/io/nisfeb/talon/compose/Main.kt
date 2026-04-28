package io.nisfeb.talon.compose

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Talon (port)") {
        PlaceholderApp()
    }
}
