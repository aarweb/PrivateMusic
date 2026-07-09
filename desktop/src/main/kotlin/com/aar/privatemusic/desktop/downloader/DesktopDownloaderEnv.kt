package com.aar.privatemusic.desktop.downloader

import com.aar.privatemusic.desktop.DesktopSettings
import com.aar.privatemusic.desktop.DesktopStorage
import com.aar.privatemusic.downloader.DeezerAccount
import com.aar.privatemusic.downloader.DeezerUserInfo
import com.aar.privatemusic.downloader.DownloaderEnv
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/**
 * Los descargadores de `:core` puestos a funcionar en el PC. El log va a la
 * consola (jpackage la redirige a un fichero) y los avisos a un flujo que la
 * interfaz muestra como snackbar, igual que `Feedback` en el móvil.
 */
object DesktopFeedback {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages
    fun show(message: String) { _messages.tryEmit(message) }
}

class DesktopDownloaderEnv(
    override val musicDir: File = DesktopStorage.musicDir,
) : DownloaderEnv {

    override fun log(tag: String, message: String, error: Throwable?) {
        System.err.println("[$tag] $message")
        error?.printStackTrace()
    }

    override fun notify(message: String) = DesktopFeedback.show(message)
}

class DesktopDeezerAccount(private val settings: DesktopSettings) : DeezerAccount {

    override fun readArl(): String = settings.deezerArl.value

    override fun saveSession(arl: String, info: DeezerUserInfo) {
        settings.setDeezerSession(arl, info.name, info.country, info.hasFlac, info.hasHq)
    }
}
