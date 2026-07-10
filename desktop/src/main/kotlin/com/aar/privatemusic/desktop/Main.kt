package com.aar.privatemusic.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.aar.privatemusic.desktop.update.PendingUpdate
import com.aar.privatemusic.desktop.ui.App
import com.aar.privatemusic.desktop.ui.KeyShortcuts
import com.aar.privatemusic.desktop.ui.PrivateMusicTheme

fun main() = application {
    // Que la app aparezca en el buscador de aplicaciones a partir del primer arranque.
    remember { LauncherEntry.ensure() }
    val shortcuts = remember { KeyShortcuts() }
    Window(
        // Si hay una actualización esperando, este es su momento: el script de
        // instalación aguarda a que el proceso muera, y el proceso se está muriendo.
        onCloseRequest = { PendingUpdate.applyOnExit(); exitApplication() },
        title = "PrivateMusic",
        state = rememberWindowState(width = 1100.dp, height = 720.dp),
        onKeyEvent = shortcuts::handle,
    ) {
        PrivateMusicTheme {
            Surface(Modifier.fillMaxSize()) { App(shortcuts) }
        }
    }
}
