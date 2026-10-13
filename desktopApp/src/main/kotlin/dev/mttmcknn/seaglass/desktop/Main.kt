package dev.mttmcknn.seaglass.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mttmcknn.seaglass.sample.SeaGlassPlayground

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Sea Glass Playground",
        state = rememberWindowState(width = 1180.dp, height = 860.dp),
    ) {
        SeaGlassPlayground()
    }
}
