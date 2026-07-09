package com.aar.privatemusic.desktop.downloader

import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.PlaylistSongCrossRef
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.downloader.DownloadState
import com.aar.privatemusic.downloader.DownloaderEnv
import com.aar.privatemusic.downloader.SearchResult
import com.aar.privatemusic.util.readAudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube en el escritorio. El móvil embebe yt-dlp con `youtubedl-android`; aquí
 * no existe ese envoltorio, así que se llama al binario oficial como un proceso
 * más y se lee su progreso de la salida estándar.
 *
 * Si el sistema ya trae `yt-dlp` se usa ese. Si no, se descarga el binario
 * autocontenido de las releases de yt-dlp (no el zipapp: ése necesitaría Python)
 * y se guarda junto a la biblioteca. Es un ejecutable, así que sólo se baja de
 * github.com por HTTPS y se marca ejecutable para el usuario, nadie más.
 */
class YtDlpDownloader(
    private val env: DownloaderEnv,
    private val dao: MusicDao,
    private val scope: CoroutineScope,
    private val binDir: File,
) {
    val musicDir: File = env.musicDir.apply { mkdirs() }

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    /** Progreso de instalación del binario, para que la interfaz no parezca colgada. */
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing

    private val slots = Semaphore(2)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    // --- Binario ---------------------------------------------------------

    @Volatile private var resolvedBinary: String? = null

    private fun systemBinary(): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, if (isWindows) "yt-dlp.exe" else "yt-dlp") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath

    private val isWindows: Boolean
        get() = "win" in System.getProperty("os.name").orEmpty().lowercase()

    private val assetName: String
        get() {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                "win" in os -> "yt-dlp.exe"
                "mac" in os -> "yt-dlp_macos"
                else -> "yt-dlp_linux"
            }
        }

    /** Devuelve la ruta del binario, descargándolo la primera vez. */
    private suspend fun binary(): String = withContext(Dispatchers.IO) {
        resolvedBinary?.let { return@withContext it }
        systemBinary()?.let { resolvedBinary = it; return@withContext it }

        val local = File(binDir.apply { mkdirs() }, assetName)
        if (!local.canExecute()) {
            _preparing.value = true
            try {
                val url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/$assetName"
                val temp = File(binDir, "$assetName.part")
                downloadTo(url, temp)
                // Se renombra al final: un binario a medias que ya sea ejecutable
                // se intentaría lanzar en el siguiente arranque.
                if (!temp.renameTo(local)) throw IllegalStateException("No se pudo instalar yt-dlp")
                local.setExecutable(true, true)
            } finally {
                _preparing.value = false
            }
        }
        local.absolutePath.also { resolvedBinary = it }
    }

    // --- Búsqueda --------------------------------------------------------

    suspend fun search(query: String, limit: Int = 20): List<SearchResult> = withContext(Dispatchers.IO) {
        val out = run(listOf("ytsearch$limit:$query", "--dump-json", "--flat-playlist", "--no-warnings"))
        out.lineSequence().filter { it.trim().startsWith("{") }.mapNotNull(::parseEntry).toList()
    }

    /** Busca y se queda con el resultado cuya duración más se acerca. */
    suspend fun searchBestMatch(query: String, durationSec: Int): SearchResult? {
        val results = runCatching { search(query, limit = 3) }.getOrNull().orEmpty()
        if (results.isEmpty()) return null
        if (durationSec <= 0) return results.first()
        return results.firstOrNull { kotlin.math.abs(it.durationSec - durationSec) <= 15 } ?: results.first()
    }

    /** Convierte una URL de playlist/canal en sus entradas, sin descargar nada. */
    suspend fun resolvePlaylist(url: String): Pair<String, List<SearchResult>> = withContext(Dispatchers.IO) {
        val out = run(listOf(url, "--dump-json", "--flat-playlist", "--no-warnings"))
        var title = "Playlist"
        val entries = out.lineSequence().filter { it.trim().startsWith("{") }.mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()?.let { json ->
                json.optString("playlist_title").takeIf { it.isNotBlank() && it != "null" }?.let { title = it }
                parseEntry(line)
            }
        }.toList()
        title to entries
    }

    private fun parseEntry(line: String): SearchResult? = runCatching {
        val json = JSONObject(line)
        val id = json.getString("id")
        SearchResult(
            id = id,
            title = json.optString("title", "(sin título)"),
            artist = json.optString("uploader").ifBlank { json.optString("channel") }.ifBlank { "Desconocido" },
            durationSec = json.optDouble("duration", 0.0).toInt(),
            thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        )
    }.getOrNull()

    /** URL directa del audio, para preescuchar sin descargar. YouTube la firma y caduca. */
    suspend fun streamUrl(id: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            run(listOf("-f", "bestaudio/best", "-g", "--no-warnings", watchUrl(id)))
                .lineSequence().firstOrNull { it.startsWith("http") }
        }.getOrNull()
    }

    // --- Descarga --------------------------------------------------------

    fun enqueue(result: SearchResult, targetPlaylistId: Long? = null) {
        val current = _downloads.value[result.id]
        if (current is DownloadState.Queued || current is DownloadState.Downloading) return
        cancelled.remove(result.id)
        setState(result.id, DownloadState.Queued)
        val job = scope.launch(Dispatchers.IO) {
            try {
                // Dedup: si ya tienes esta canción (mismo título+artista, venga de
                // donde venga) no se descarga otra vez; la copia existente va al destino.
                val dup = dao.findByTitleArtist(result.title, result.artist)
                if (dup != null && dup.id != result.id) {
                    targetPlaylistId?.let { dao.addToPlaylist(PlaylistSongCrossRef(it, dup.id, dao.playlistSize(it))) }
                    setState(result.id, DownloadState.Done)
                    env.notify("Ya tienes \"${result.title}\", no se descarga otra vez")
                    return@launch
                }
                slots.withPermit {
                    if (result.id in cancelled) return@withPermit
                    if (!dao.songExists(result.id)) download(result)
                    targetPlaylistId?.let { dao.addToPlaylist(PlaylistSongCrossRef(it, result.id, dao.playlistSize(it))) }
                    setState(result.id, DownloadState.Done)
                }
            } catch (e: CancellationException) {
                purge(result.id)
                throw e
            } catch (e: Exception) {
                if (result.id in cancelled) {
                    purge(result.id)
                } else {
                    env.log("YtDlpDownloader", "download failed for ${result.id}", e)
                    setState(result.id, DownloadState.Failed(e.message ?: "error"))
                }
            } finally {
                jobs.remove(result.id)
                processes.remove(result.id)
                cancelled.remove(result.id)
            }
        }
        jobs[result.id] = job
    }

    fun cancel(id: String) {
        cancelled.add(id)
        processes.remove(id)?.destroy()
        jobs.remove(id)?.cancel()
        purgeFiles(id)
        _downloads.update { it - id }
    }

    private fun purge(id: String) {
        purgeFiles(id)
        _downloads.update { it - id }
    }

    private fun purgeFiles(id: String) {
        musicDir.listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { runCatching { it.delete() } }
    }

    private suspend fun download(result: SearchResult) {
        // m4a antes que webm: es el mismo audio de YouTube, pero con cabecera que
        // se puede leer sin ffmpeg (bitrate, frecuencia) y que VLC abre sin dudar.
        val args = listOf(
            "-f", "bestaudio[ext=m4a]/bestaudio/best",
            "--no-playlist",
            "--no-mtime",
            "--newline",
            "--no-warnings",
            "-o", "${musicDir.absolutePath}/%(id)s.%(ext)s",
            watchUrl(result.id),
        )
        run(args, id = result.id) { progress ->
            if (result.id !in cancelled) setState(result.id, DownloadState.Downloading(progress))
        }

        val audioFile = musicDir.listFiles()
            ?.firstOrNull { it.nameWithoutExtension == result.id && it.extension != "jpg" }
            ?: throw IllegalStateException("Archivo no encontrado tras la descarga")

        // La carátula se baja aparte: `--write-thumbnail --convert-thumbnails jpg`
        // necesitaría ffmpeg, y YouTube ya sirve el JPEG directamente.
        val artFile = File(musicDir, "${result.id}.jpg")
        runCatching { downloadTo(result.thumbnailUrl, artFile) }

        // La duración viene de la búsqueda: un .webm de YouTube no la declara en
        // ninguna cabecera que se pueda leer sin abrir el códec.
        val quality = runCatching { readAudioQuality(audioFile.absolutePath, result.durationSec) }.getOrNull()
        dao.insertSong(
            Song(
                id = result.id,
                title = result.title,
                artist = result.artist,
                durationSec = result.durationSec,
                filePath = audioFile.absolutePath,
                artPath = artFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath,
                thumbnailUrl = result.thumbnailUrl,
                addedAt = System.currentTimeMillis(),
                codec = quality?.codec,
                bitrateKbps = quality?.bitrateKbps,
                sampleRateHz = quality?.sampleRateHz,
            )
        )
    }

    // --- Proceso ---------------------------------------------------------

    private fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    /** `[download]  12.3% of ...` — el único formato de progreso que emite `--newline`. */
    private val progressRegex = Regex("""\[download]\s+([\d.]+)%""")

    private suspend fun run(
        args: List<String>,
        id: String? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): String {
        val command = listOf(binary()) + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        id?.let { processes[it] = process }
        val output = StringBuilder()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                output.appendLine(line)
                if (onProgress != null) {
                    progressRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull()
                        ?.let { onProgress(it.coerceIn(0f, 100f)) }
                }
            }
        }
        val code = process.waitFor()
        if (code != 0 && (id == null || id !in cancelled)) {
            // La última línea suele ser el `ERROR:` de yt-dlp; lo demás es ruido.
            val reason = output.lines().lastOrNull { it.startsWith("ERROR") } ?: "yt-dlp devolvió $code"
            throw IllegalStateException(reason.removePrefix("ERROR: ").take(120))
        }
        return output.toString()
    }

    private fun downloadTo(url: String, out: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
        conn.disconnect()
    }

    private fun setState(id: String, state: DownloadState) {
        _downloads.update { it + (id to state) }
    }
}
