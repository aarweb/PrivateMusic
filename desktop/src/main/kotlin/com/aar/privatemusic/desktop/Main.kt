package com.aar.privatemusic.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.aar.privatemusic.desktop.ui.App
import com.aar.privatemusic.desktop.ui.PrivateMusicTheme

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "PrivateMusic",
        state = rememberWindowState(width = 1100.dp, height = 720.dp),
    ) {
        PrivateMusicTheme {
            Surface(Modifier.fillMaxSize()) { App() }
        }
    }
}
