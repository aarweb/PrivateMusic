package com.aar.privatemusic.desktop.update

import java.io.File

/**
 * La actualización ya descargada, esperando a que cierres la app.
 *
 * Aplicarla en cuanto se baja significaría matar el proceso a mitad de una
 * canción, así que se guarda aquí y `Main` la aplica al cerrar la ventana: el
 * script de instalación espera a que este proceso muera de todas formas, o sea
 * que el cierre es exactamente el momento bueno.
 */
object PendingUpdate {

    @Volatile
    private var downloaded: File? = null

    @Volatile
    var version: String? = null
        private set

    val isReady: Boolean get() = downloaded?.exists() == true

    fun offer(file: File, version: String) {
        this.downloaded = file
        this.version = version
    }

    /**
     * Se llama al cerrar. Si falla la instalación no se puede hacer nada útil —
     * la app se está yendo — pero tampoco debe impedir que se cierre.
     */
    fun applyOnExit() {
        val file = downloaded ?: return
        if (!file.exists()) return
        // Sin relanzar: el usuario ha cerrado la app porque quería cerrarla.
        runCatching { DesktopUpdater.install(file, relaunch = false) }
    }
}
