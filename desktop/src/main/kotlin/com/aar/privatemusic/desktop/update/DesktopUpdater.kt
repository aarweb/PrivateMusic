package com.aar.privatemusic.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualización desde GitHub Releases, igual que `AppUpdater` en Android:
 * mira la última release, busca el asset de este sistema y lo instala.
 *
 * La diferencia está en el final. Android le pasa el APK al instalador del
 * sistema; en Linux no existe tal cosa para una app portable, así que la app se
 * sustituye a sí misma: descomprime al lado, y un script que espera a que este
 * proceso muera hace el cambiazo y la vuelve a lanzar.
 */
object DesktopUpdater {

    private const val LATEST_URL = "https://api.github.com/repos/aarweb/PrivateMusic/releases/latest"

    /**
     * `jpackage` inyecta `-Djpackage.app-version` en el lanzador nativo. Si no
     * está, esto corre desde Gradle: una compilación de trabajo, que nunca debe
     * intentar reemplazarse a sí misma.
     */
    val currentVersion: String
        get() = System.getProperty("jpackage.app-version") ?: "dev"

    val isDevBuild: Boolean get() = currentVersion == "dev" || currentVersion == "1.0.0"

    data class UpdateInfo(val version: String, val assetUrl: String, val notes: String)

    private val assetSuffix: String
        get() = if (isWindows) ".msi" else "-linux-x64.tar.gz"

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** La release nueva, o null si no hay, no se puede mirar, o es una compilación de trabajo. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (isDevBuild) return@withContext null
        runCatching {
            val json = JSONObject(httpGet(LATEST_URL))
            val version = json.optString("tag_name").removePrefix("v")
            if (version.isBlank() || !isNewer(version, currentVersion)) return@withContext null

            val assets = json.optJSONArray("assets") ?: return@withContext null
            val url = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(assetSuffix) }
                ?.optString("browser_download_url")
                ?: return@withContext null

            UpdateInfo(version, url, json.optString("body").take(500))
        }.getOrNull()
    }

    /**
     * Descarga e instala. En Windows abre el `.msi` y devuelve el control al
     * sistema; en Linux devuelve el comando que hay que ejecutar al salir.
     * Nunca toca la instalación actual mientras la app corre.
     */
    private fun targetFor(info: UpdateInfo) =
        File(System.getProperty("java.io.tmpdir"), "privatemusic-${info.version}${assetSuffix}")

    /**
     * El paquete de esta versión, si ya se bajó entero. Una descarga cortada se
     * queda en `.part` y nunca llega a este nombre, así que si el fichero existe
     * está completo. Sin esto, la actualización automática se bajaría cien megas
     * en cada arranque hasta que el usuario cerrara la app.
     */
    fun cached(info: UpdateInfo): File? = targetFor(info).takeIf { it.length() > 1_000_000 }

    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val target = targetFor(info)
        val tmp = File(target.parentFile, "${target.name}.part")

        val conn = URL(info.assetUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        val total = conn.contentLengthLong
        var copied = 0L
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0) onProgress((copied * 100 / total).toInt())
                }
            }
        }
        conn.disconnect()

        // Una descarga cortada no puede parecer una actualización válida.
        check(total <= 0 || copied == total) { "descarga incompleta: $copied de $total" }
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "no se pudo renombrar ${tmp.name}" }
        target
    }

    /**
     * Dónde está instalada la app. `java.home` apunta a `<raíz>/lib/runtime`
     * dentro de la imagen de jpackage.
     */
    private val installRoot: File?
        get() = File(System.getProperty("java.home")).parentFile?.parentFile
            ?.takeIf { File(it, "bin/PrivateMusic").exists() || File(it, "PrivateMusic.exe").exists() }

    /**
     * Instala lo descargado. En Windows abre el `.msi` y deja que el sistema
     * pregunte, como hace Android con el APK. En Linux descomprime al lado y
     * deja un script que espera a que este proceso muera antes de cambiar los
     * ficheros: una app no puede borrarse a sí misma mientras corre.
     *
     * Devuelve false si no hay nada que hacer. Si tiene éxito, vuelve enseguida:
     * el trabajo lo hace el script cuando este proceso muera, así que el llamante
     * debe salir a continuación.
     *
     * [relaunch] arranca la versión nueva al terminar. Es lo que quieres al pulsar
     * "Actualizar" (la app se va y vuelve ya actualizada), y lo que **no** quieres
     * al aplicarla porque el usuario ha cerrado la ventana: cerrar una app y verla
     * reaparecer sola es de las cosas que hacen desinstalarla.
     */
    fun install(downloaded: File, relaunch: Boolean = true): Boolean {
        if (isWindows) {
            return runCatching {
                ProcessBuilder("msiexec", "/i", downloaded.absolutePath).start()
                true
            }.getOrDefault(false)
        }

        val root = installRoot ?: return false
        // Se desempaqueta al lado de la instalación, no en /tmp: así el `mv`
        // final es un renombrado dentro del mismo sistema de ficheros, y no una
        // copia que se pueda quedar a medias.
        val staging = File(root.parentFile, ".privatemusic-update")
        staging.deleteRecursively()
        staging.mkdirs()

        val untar = ProcessBuilder("tar", "-xzf", downloaded.absolutePath, "-C", staging.absolutePath)
            .start().waitFor()
        if (untar != 0) return false
        // El tar.gz lleva un único directorio dentro.
        val extracted = staging.listFiles()?.singleOrNull { it.isDirectory } ?: return false

        // Nunca se borra la instalación buena antes de tener la nueva en su
        // sitio: se aparta, y si algo falla se devuelve donde estaba. Una
        // actualización fallida deja la app anterior funcionando.
        val pid = ProcessHandle.current().pid()
        val old = "${root.absolutePath}.old"
        val script = """
            while kill -0 $pid 2>/dev/null; do sleep 0.3; done
            rm -rf '$old'
            mv '${root.absolutePath}' '$old' || exit 1
            if ! mv '${extracted.absolutePath}' '${root.absolutePath}'; then
                mv '$old' '${root.absolutePath}'
                exit 1
            fi
            rm -rf '$old' '${staging.absolutePath}' '${downloaded.absolutePath}'
            ${if (relaunch) "exec '${root.absolutePath}/bin/PrivateMusic'" else ""}
        """.trimIndent()

        return runCatching {
            ProcessBuilder("sh", "-c", script).start()
            true
        }.getOrDefault(false)
    }

    private fun httpGet(spec: String): String {
        val conn = URL(spec).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            require(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    internal fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.trim().toIntOrNull() }
        val l = local.split(".").mapNotNull { it.trim().toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
