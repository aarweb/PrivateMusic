package com.aar.privatemusic.desktop

import java.io.File

/**
 * Dónde vive la biblioteca en el PC. Sigue la convención de cada sistema:
 * `~/.local/share/PrivateMusic` en Linux, `%APPDATA%\PrivateMusic` en Windows.
 */
object DesktopStorage {

    val dataDir: File by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val home = File(System.getProperty("user.home"))
        val root = when {
            "win" in os -> System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
            "mac" in os -> File(home, "Library/Application Support")
            else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
        }
        File(root, "PrivateMusic").apply { mkdirs() }
    }

    val musicDir: File by lazy { File(dataDir, "music").apply { mkdirs() } }

    val artDir: File by lazy { File(dataDir, "art").apply { mkdirs() } }

    /** Herramientas que la app se descarga sola (yt-dlp). Fuera de la instalación:
     *  sobreviven a una actualización, que reemplaza la carpeta entera. */
    val binDir: File by lazy { File(dataDir, "bin").apply { mkdirs() } }
}
