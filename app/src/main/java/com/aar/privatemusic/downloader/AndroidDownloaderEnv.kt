package com.aar.privatemusic.downloader

import android.content.Context
import android.util.Log
import com.aar.privatemusic.data.AppSettings
import com.aar.privatemusic.util.Feedback
import java.io.File

/**
 * Ata los descargadores de `:core` a Android. Todo lo que antes recibían como
 * `Context` cabe aquí: la carpeta de música, el log y los avisos al usuario.
 */
class AndroidDownloaderEnv(private val context: Context) : DownloaderEnv {

    override val musicDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "music")

    override fun log(tag: String, message: String, error: Throwable?) {
        if (error != null) Log.e(tag, message, error) else Log.w(tag, message)
    }

    override fun notify(message: String) = Feedback.show(message)
}

/** El ARL y el plan viven en las preferencias de la app. */
class AndroidDeezerAccount(private val context: Context) : DeezerAccount {

    override fun readArl(): String = AppSettings.readDeezerArl(context)

    override fun saveSession(arl: String, info: DeezerUserInfo) {
        AppSettings(context).setDeezerSession(arl, info.name, info.country, info.hasFlac, info.hasHq)
    }
}
