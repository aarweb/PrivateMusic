package com.aar.privatemusic.desktop

import java.io.File

/**
 * Registra la app en el menú de aplicaciones de Linux.
 *
 * El paquete que produce `createDistributable` es una carpeta con un binario
 * dentro: no instala nada en el sistema, así que el lanzador del escritorio no
 * la encuentra por mucho que esté descomprimida en `~/.local`. Y el `.deb`
 * tampoco sirve en distros que no son Debian.
 *
 * Escribirlo desde la propia app resuelve las dos cosas y además sobrevive a
 * las actualizaciones, que reemplazan la carpeta entera: en cada arranque el
 * `.desktop` vuelve a apuntar al binario que se está ejecutando ahora.
 */
object LauncherEntry {

    /** Ruta del lanzador nativo, o null si corremos desde Gradle (no hay nada que registrar). */
    private val appPath: File?
        get() = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.canExecute() }

    fun ensure() {
        if ("linux" !in System.getProperty("os.name").orEmpty().lowercase()) return
        val binary = appPath ?: return
        runCatching { write(binary) }
    }

    private fun write(binary: File) {
        val home = File(System.getProperty("user.home"))
        val dir = System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
        val target = File(dir, "applications/privatemusic.desktop")
        target.parentFile.mkdirs()

        // El icono lo deja jpackage junto al runtime: <app>/lib/PrivateMusic.png,
        // y el binario está en <app>/bin/PrivateMusic.
        val icon = File(binary.parentFile.parentFile, "lib/PrivateMusic.png")
        val entry = buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Name=PrivateMusic")
            appendLine("Comment=Tu música, sin nadie mirando")
            appendLine("Exec=${binary.absolutePath}")
            if (icon.exists()) appendLine("Icon=${icon.absolutePath}")
            appendLine("Categories=AudioVideo;Audio;Player;")
            appendLine("Terminal=false")
            appendLine("StartupWMClass=PrivateMusic")
        }
        // Sólo se reescribe si cambió: en cada arranque, si no, se toca el fichero
        // y algunos menús reconstruyen su caché sin necesidad.
        if (target.exists() && target.readText() == entry) return
        target.writeText(entry)

        // Los menús que cachean (GNOME, KDE) necesitan el aviso; los que escanean
        // al vuelo (wofi, rofi) no. Si el comando no está, no pasa nada.
        runCatching {
            ProcessBuilder("update-desktop-database", target.parentFile.absolutePath)
                .redirectErrorStream(true).start().waitFor()
        }
    }
}
