package com.aar.privatemusic.downloader

import android.content.Context
import android.util.Log
import com.aar.privatemusic.data.AppSettings
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.lyrics.LyricsFetcher
import com.aar.privatemusic.util.AudioAnalyzer
import com.aar.privatemusic.util.CanvasClipSelector
import com.aar.privatemusic.util.LoudnessScanner
import com.aar.privatemusic.util.readAudioQuality
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Search and download via the bundled yt-dlp. Audio is fetched with `-f bestaudio`
 * and kept in its original codec (Opus/M4A) — no re-encode, so nothing is lost.
 */
class YtDownloader(
    private val context: Context,
    private val dao: MusicDao,
    private val scope: CoroutineScope,
) {
    /**
     * `YoutubeDL.init` desempaqueta el intérprete de Python y las bibliotecas
     * nativas desde el APK: cientos de milisegundos de disco que, hechos en
     * `Application.onCreate`, retrasan el primer fotograma. Se hace en segundo
     * plano y todo lo que use yt-dlp espera aquí.
     */
    private val ready = kotlinx.coroutines.CompletableDeferred<Unit>()

    fun initEngine() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
            }.onFailure { Log.e("YtDownloader", "yt-dlp init failed", it) }
            // Se completa pase lo que pase: si el motor no arrancó, que las
            // llamadas fallen con su propia excepción y no se queden colgadas.
            ready.complete(Unit)
        }
    }

    private suspend fun ytdl(): YoutubeDL {
        ready.await()
        return YoutubeDL.getInstance()
    }

    /**
     * Aplica el cliente de YouTube elegido en Ajustes. Desde 2026 los clientes
     * por defecto (`web`, `android`, `ios`) exigen un PO token para el stream y
     * fallan sin él; una cadena como `default,android_vr,web_embedded,tv` prueba
     * varios y se queda con el primero que sirva. Vacío = por defecto de yt-dlp.
     */
    private fun YoutubeDLRequest.applyYoutubeClient(): YoutubeDLRequest {
        val client = AppSettings.readYoutubeClient(context)
        if (client.isNotBlank()) addOption("--extractor-args", "youtube:player_client=$client")
        return this
    }

    val musicDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "music")
        .apply { mkdirs() }

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    // Two simultaneous downloads at most; the rest wait in queue.
    private val slots = Semaphore(2)

    // 80/20 playback vs. download: downloads run on background-priority threads
    // and, WHILE music is playing, are held to a single concurrent job plus a
    // bandwidth cap so decoding/IO never steals cycles from the audio pipeline.
    private val downloadDispatcher = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "yt-download").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private val soloWhilePlaying = Mutex()

    /** Wired from the app: true while audio is actually playing. */
    var isPlayingProvider: () -> Boolean = { false }

    /** Wired from the app: called with a song id right after a successful
     *  download so it can auto-resolve online metadata (title/artist/cover/lyrics). */
    var onDownloadComplete: ((String) -> Unit)? = null

    // Per-id coroutine handles and titles, so downloads can be cancelled and the
    // notification can name what's downloading.
    private val jobs = ConcurrentHashMap<String, Job>()
    private val titles = ConcurrentHashMap<String, String>()
    // Ids the user cancelled: killing yt-dlp makes execute() throw a *normal*
    // exception (not CancellationException), so the download loop consults this
    // set to treat that as a cancel (clean up, drop pending) instead of a retry.
    private val cancelled: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    suspend fun search(query: String, limit: Int = 20): List<SearchResult> =
        kotlinx.coroutines.withTimeoutOrNull(45_000) {
            searchInner(query, limit)
        } ?: emptyList()

    private suspend fun searchInner(query: String, limit: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest("ytsearch$limit:$query").apply {
                applyYoutubeClient()
                addOption("--dump-json")
                addOption("--flat-playlist")
                addOption("--no-warnings")
            }
            val out = ytdl().execute(request).out
            out.lineSequence()
                .filter { it.trim().startsWith("{") }
                .mapNotNull { line ->
                    runCatching {
                        val json = JSONObject(line)
                        val id = json.getString("id")
                        SearchResult(
                            id = id,
                            title = json.optString("title", "(sin título)"),
                            artist = json.optString("uploader")
                                .ifBlank { json.optString("channel") }
                                .ifBlank { "Desconocido" },
                            durationSec = json.optDouble("duration", 0.0).toInt(),
                            thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                        )
                    }.getOrNull()
                }
                .toList()
        }

    fun enqueue(result: SearchResult, targetPlaylistId: Long? = null) {
        val current = _downloads.value[result.id]
        if (current is DownloadState.Queued || current is DownloadState.Downloading) return
        // Re-enqueuing clears any stale "cancelled" flag from a prior dismiss so
        // the new attempt isn't aborted on start.
        cancelled.remove(result.id)
        titles[result.id] = result.title
        setState(result.id, DownloadState.Queued)
        // Foreground notification (progress + pending count + cancel) while active.
        DownloadService.ensureRunning(context)
        val job = scope.launch(downloadDispatcher) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                // Dedup: si ya tienes esta canción (mismo título+artista, de cualquier
                // fuente) no se descarga otra vez; la copia existente va al destino.
                val dup = dao.findByTitleArtist(result.title, result.artist)
                if (dup != null && dup.id != result.id) {
                    targetPlaylistId?.let { pid ->
                        dao.addToPlaylist(
                            com.aar.privatemusic.data.db.PlaylistSongCrossRef(pid, dup.id, dao.playlistSize(pid))
                        )
                    }
                    setState(result.id, DownloadState.Done)
                    com.aar.privatemusic.util.Feedback.show("Ya tienes \"${result.title}\", no se descarga otra vez")
                    return@launch
                }
                if (result.id in cancelled) return@launch
                // Persist BEFORE downloading: if Android kills the process with a
                // full import queue, everything resumes on next app start.
                runCatching {
                    dao.upsertPending(
                        com.aar.privatemusic.data.db.PendingDownload(
                            id = result.id,
                            title = result.title,
                            artist = result.artist,
                            durationSec = result.durationSec,
                            thumbnailUrl = result.thumbnailUrl,
                            targetPlaylistId = targetPlaylistId,
                            addedAt = System.currentTimeMillis(),
                        )
                    )
                }
                slots.withPermit {
                    if (result.id in cancelled) return@withPermit
                    // While playing, only ONE download runs the heavy phase at a
                    // time (the other permit-holder waits here): playback first.
                    val playing = isPlayingProvider()
                    val runHeavy: suspend () -> Unit = {
                        if (!dao.songExists(result.id)) download(result)
                        targetPlaylistId?.let { playlistId ->
                            val position = dao.playlistSize(playlistId)
                            dao.addToPlaylist(
                                com.aar.privatemusic.data.db.PlaylistSongCrossRef(playlistId, result.id, position)
                            )
                        }
                    }
                    if (playing) soloWhilePlaying.withLock { runHeavy() } else runHeavy()
                    dao.deletePending(result.id)
                    setState(result.id, DownloadState.Done)
                    // Fire-and-forget: resolve canonical tags/cover/lyrics online.
                    onDownloadComplete?.invoke(result.id)
                }
            } catch (e: CancellationException) {
                purgeCancelled(result.id)
                throw e
            } catch (e: Exception) {
                if (result.id in cancelled) {
                    // The exception came from us killing the yt-dlp process.
                    purgeCancelled(result.id)
                } else {
                    Log.e("YtDownloader", "download failed for ${result.id}", e)
                    runCatching { dao.bumpPendingAttempts(result.id) }
                    setState(result.id, DownloadState.Failed(e.message ?: "error"))
                }
            } finally {
                jobs.remove(result.id)
                titles.remove(result.id)
                cancelled.remove(result.id)
            }
        }
        jobs[result.id] = job
    }

    /** Drops all trace of a cancelled download (pending row, partials, state). */
    private suspend fun purgeCancelled(id: String) {
        runCatching { dao.deletePending(id) }
        cleanupPartial(id)
        _downloads.update { it - id }
    }

    /** Cancels a single download: kills the process, forgets it, cleans partials. */
    fun cancel(id: String) {
        cancelled.add(id)
        jobs.remove(id)?.cancel()
        titles.remove(id)
        _downloads.update { it - id }
        scope.launch(Dispatchers.IO) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
            runCatching { dao.deletePending(id) }
            cleanupPartial(id)
        }
    }

    /** Manual retry from the library errors list: clears the failed state and
     *  re-enqueues. `enqueue` upserts the pending row (REPLACE) so attempts reset. */
    fun retry(p: com.aar.privatemusic.data.db.PendingDownload) {
        _downloads.update { it - p.id }
        enqueue(
            SearchResult(p.id, p.title, p.artist, p.durationSec, p.thumbnailUrl),
            p.targetPlaylistId,
        )
    }

    /** Cancels every queued/running download and wipes the pending queue. */
    fun cancelAll() {
        val ids = _downloads.value
            .filterValues { it is DownloadState.Queued || it is DownloadState.Downloading }
            .keys.toList()
        cancelled.addAll(ids)
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
        titles.clear()
        _downloads.update { m ->
            m.filterValues { it !is DownloadState.Queued && it !is DownloadState.Downloading }
        }
        scope.launch(Dispatchers.IO) {
            ids.forEach { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
            runCatching { dao.clearPending() }
            // A killed download may re-insert its pending row moments later; clear twice.
            kotlinx.coroutines.delay(300)
            runCatching { dao.clearPending() }
            ids.forEach { cleanupPartial(it) }
        }
    }

    /** How many downloads are queued or in progress. */
    fun activeCount(): Int =
        _downloads.value.count { it.value is DownloadState.Queued || it.value is DownloadState.Downloading }

    /** Title + progress (0-100) of the item currently downloading, for the notification. */
    fun currentDownloading(): Pair<String, Float>? =
        _downloads.value.entries.firstOrNull { it.value is DownloadState.Downloading }
            ?.let { (titles[it.key] ?: "…") to (it.value as DownloadState.Downloading).progress }

    /** Removes half-written files left by a cancelled/failed download. */
    private fun cleanupPartial(id: String) {
        musicDir.listFiles()
            ?.filter { it.name.startsWith("$id.") }
            ?.forEach { runCatching { it.delete() } }
    }

    /** Re-enqueues downloads that were pending when the process last died. */
    suspend fun resumePending() {
        dao.pendingDownloads().forEach { p ->
            if (dao.songExists(p.id)) {
                dao.deletePending(p.id)
            } else {
                enqueue(
                    SearchResult(p.id, p.title, p.artist, p.durationSec, p.thumbnailUrl),
                    p.targetPlaylistId,
                )
            }
        }
    }

    /** Searches YouTube and picks the result whose duration best matches. */
    suspend fun searchBestMatch(query: String, durationSec: Int): SearchResult? {
        val results = runCatching { search(query, limit = 3) }.getOrNull() ?: return null
        if (results.isEmpty()) return null
        if (durationSec <= 0) return results.first()
        return results.firstOrNull { kotlin.math.abs(it.durationSec - durationSec) <= 15 }
            ?: results.first()
    }

    /** Resolves a playlist/channel URL into its entries without downloading. */
    suspend fun resolvePlaylist(url: String): Pair<String, List<SearchResult>> =
        kotlinx.coroutines.withTimeoutOrNull(60_000) {
            resolvePlaylistInner(url)
        } ?: ("" to emptyList())

    private suspend fun resolvePlaylistInner(url: String): Pair<String, List<SearchResult>> =
        withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(url).apply {
                applyYoutubeClient()
                addOption("--dump-json")
                addOption("--flat-playlist")
                addOption("--no-warnings")
            }
            val out = ytdl().execute(request).out
            var playlistTitle = "Playlist"
            val entries = out.lineSequence()
                .filter { it.trim().startsWith("{") }
                .mapNotNull { line ->
                    runCatching {
                        val json = JSONObject(line)
                        val id = json.getString("id")
                        json.optString("playlist_title").takeIf { it.isNotBlank() && it != "null" }
                            ?.let { playlistTitle = it }
                        SearchResult(
                            id = id,
                            title = json.optString("title", "(sin título)"),
                            artist = json.optString("uploader")
                                .ifBlank { json.optString("channel") }
                                .ifBlank { "Desconocido" },
                            durationSec = json.optDouble("duration", 0.0).toInt(),
                            thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                        )
                    }.getOrNull()
                }
                .toList()
            playlistTitle to entries
        }

    /**
     * Imports a Spotify-style CSV (columns with track name and artist),
     * searching YouTube for each row and downloading the first match into a
     * new playlist. Returns the number of queued songs, -1 on failure.
     */
    suspend fun importCsvAndDownload(
        context: Context,
        uri: android.net.Uri,
        createPlaylist: suspend (String) -> Long,
    ): Int = withContext(Dispatchers.IO) {
        try {
            val lines = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readLines() ?: return@withContext -1
            if (lines.size < 2) return@withContext 0

            val header = splitCsvLine(lines.first()).map { it.lowercase() }
            val titleIdx = header.indexOfFirst { it.contains("track") || it.contains("title") || it.contains("song") }
            val artistIdx = header.indexOfFirst { it.contains("artist") }
            if (titleIdx < 0) return@withContext 0

            val name = uri.lastPathSegment?.substringAfterLast('/')
                ?.substringBeforeLast('.')?.take(40) ?: "Importada"
            val playlistId = createPlaylist(name)
            var queued = 0

            lines.drop(1).take(200).forEach { line ->
                val cols = splitCsvLine(line)
                val title = cols.getOrNull(titleIdx)?.trim().orEmpty()
                val artist = if (artistIdx >= 0) cols.getOrNull(artistIdx)?.trim().orEmpty() else ""
                if (title.isBlank()) return@forEach
                runCatching {
                    val results = search("$artist $title".trim(), limit = 1)
                    results.firstOrNull()?.let {
                        enqueue(it, playlistId)
                        queued++
                    }
                }
            }
            queued
        } catch (e: Exception) {
            -1
        }
    }

    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(c)
            }
        }
        result.add(current.toString())
        return result
    }

    /**
     * Direct URL of the best audio stream, for previewing a result
     * without downloading it. Expires after a while (YouTube signs it).
     */
    suspend fun streamUrl(id: String): String? = kotlinx.coroutines.withTimeoutOrNull(30_000) {
        streamUrlInner(id)
    }

    private suspend fun streamUrlInner(id: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$id").apply {
                applyYoutubeClient()
                addOption("-f", "bestaudio/best")
                addOption("--no-playlist")
            }
            ytdl().getInfo(request).url?.takeIf { it.startsWith("http") }
        }.onFailure { Log.e("YtDownloader", "streamUrl failed for $id", it) }.getOrNull()
    }

    /** Resolve a raw YouTube URL (e.g. shared from another app) and queue it. */
    fun enqueueUrl(url: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val infoReq = YoutubeDLRequest(url).apply { applyYoutubeClient() }
                val info = ytdl().getInfo(infoReq)
                enqueue(
                    SearchResult(
                        id = info.id ?: return@launch,
                        title = info.title ?: "(sin título)",
                        artist = info.uploader ?: "Desconocido",
                        durationSec = info.duration,
                        thumbnailUrl = "https://i.ytimg.com/vi/${info.id}/hqdefault.jpg",
                    )
                )
            } catch (e: Exception) {
                Log.e("YtDownloader", "cannot resolve $url", e)
            }
        }
    }

    /**
     * Vídeo de fondo estilo "Canvas": busca el clip oficial en YouTube y guarda
     * un bucle mudo corto como `<id>.mp4` junto al audio. Sólo vídeo (`bv*`), sin
     * pista de audio. Devuelve true si consiguió el fichero. La misma superficie
     * de riesgo que ya se asume para el audio de YouTube.
     */
    suspend fun downloadVideoClip(song: Song): Boolean = withContext(downloadDispatcher) {
        val staging = File(musicDir, "${song.id}.canvas.staging.mp4")
        val backup = File(musicDir, "${song.id}.canvas.backup.mp4")
        val candidatePrefix = "${song.id}.canvas"
        var preserveBackup = false
        fun cleanupCanvasTemps() {
            musicDir.listFiles()
                ?.filter {
                    it.name.startsWith(candidatePrefix) && !(preserveBackup && it == backup)
                }
                ?.forEach { runCatching { it.delete() } }
        }
        fun cleanupCandidatePrefix(prefix: String) {
            musicDir.listFiles()
                ?.filter { it.name.startsWith(prefix) }
                ?.forEach { runCatching { it.delete() } }
        }

        cleanupCanvasTemps()
        try {
            val results = runCatching {
                search("${song.artist} ${song.title} official video", limit = 10)
            }.getOrElse {
                Log.w("YtDownloader", "No se pudieron buscar candidatos para el Canvas", it)
                emptyList()
            }
            val selectedIndex = CanvasClipSelector.selectTitleIndex(results.map { it.title })
            val selected = selectedIndex?.let { results.getOrNull(it) }
            if (selected == null || selected.id.isBlank()) {
                Log.w("YtDownloader", "No hay resultados admisibles para el Canvas")
                return@withContext false
            }

            val starts = CanvasClipSelector.candidateStarts(
                selected.durationSec.toDouble(),
                song.id,
            )
            Log.d(
                "YtDownloader",
                "Canvas ${song.id}: dur=${selected.durationSec} candidatos=${starts.size} id=${selected.id}",
            )

            val target = "https://www.youtube.com/watch?v=${selected.id}"
            val canvasHeight = AppSettings.readCanvasQuality(context)
            val minimumHeight = when (canvasHeight) {
                1080 -> 720
                720 -> 480
                else -> 360
            }
            val candidates = coroutineScope {
                starts.mapIndexed { index, start ->
                    async {
                        val prefix = "${song.id}.canvas$index."
                        cleanupCandidatePrefix(prefix)
                        val request = YoutubeDLRequest(target).apply {
                            applyYoutubeClient()
                            addOption(
                                "-f",
                                "bv*[height<=$canvasHeight][height>=$minimumHeight]/" +
                                    "bv*[height<=$canvasHeight]/bv*",
                            )
                            addOption("--no-playlist")
                            addOption("--no-mtime")
                            addOption("--no-warnings")
                            addOption("--download-sections", "*$start-${start + 8}")
                            addOption("--force-keyframes-at-cuts")
                            addOption("-o", "${musicDir.absolutePath}/${song.id}.canvas$index.%(ext)s")
                            if (isPlayingProvider()) addOption("--limit-rate", "1M")
                        }
                        val run = suspend {
                            ytdl().execute(request, "${song.id}#canvas$index") { _, _, _ -> }
                        }
                        val response = try {
                            if (isPlayingProvider()) soloWhilePlaying.withLock { run() } else run()
                        } catch (e: CancellationException) {
                            cleanupCandidatePrefix(prefix)
                            throw e
                        } catch (e: Exception) {
                            cleanupCandidatePrefix(prefix)
                            Log.w(
                                "YtDownloader",
                                "No se pudo descargar la ventana Canvas $index; " +
                                    "se descartan sus restos",
                                e,
                            )
                            return@async null
                        }
                        if (response.exitCode != 0) {
                            cleanupCandidatePrefix(prefix)
                            Log.w(
                                "YtDownloader",
                                "yt-dlp rechazó la ventana Canvas $index " +
                                    "(código ${response.exitCode}); se descartan sus restos",
                            )
                            return@async null
                        }
                        val finalFile = musicDir.listFiles()
                            ?.filter {
                                it.name.startsWith(prefix) && !it.name.contains(".part") &&
                                    it.isFile && it.length() > 0L
                            }
                            ?.sortedBy { it.name }
                            ?.firstOrNull()
                        if (finalFile == null) {
                            cleanupCandidatePrefix(prefix)
                            Log.w(
                                "YtDownloader",
                                "La ventana Canvas $index no produjo un archivo final válido; " +
                                    "se descartan sus restos",
                            )
                            return@async null
                        }
                        finalFile
                    }
                }.awaitAll().filterNotNull()
            }
            val raw = CanvasClipSelector.choose(candidates)
                ?: return@withContext false
            val dest = File(musicDir, "${song.id}.mp4")
            // El Canvas vigente no se toca durante búsqueda, descarga ni procesado.
            // bv* no trae audio; aun así se normaliza en un staging independiente.
            val out = com.aar.privatemusic.util.VideoImport.stripAudioFromFile(raw, staging)
            if (out == null || !staging.isFile || staging.length() <= 0L) {
                return@withContext false
            }

            // Conserva el destino previo para poder restaurarlo si falla el cambio
            // de fichero o el registro en Room. El movimiento ocurre en el mismo
            // directorio y es atómico cuando el sistema de ficheros lo permite.
            if (dest.exists() && !dest.isFile) return@withContext false
            val hadDestination = dest.isFile
            val previousLength = if (hadDestination) dest.length() else null
            if (hadDestination) {
                runCatching { dest.copyTo(backup, overwrite = true) }
                    .getOrElse { return@withContext false }
            }

            fun rollbackDestination(): Boolean {
                val restored = if (hadDestination) {
                    val moved = runCatching {
                        Files.move(
                            backup.toPath(),
                            dest.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }.isSuccess
                    val copied = if (!moved && backup.isFile) {
                        runCatching { backup.copyTo(dest, overwrite = true) }.isSuccess
                    } else {
                        false
                    }
                    (moved || copied) && dest.isFile && dest.length() == previousLength
                } else {
                    runCatching { !dest.exists() || dest.delete() }.getOrDefault(false) &&
                        !dest.exists()
                }
                if (!restored) {
                    preserveBackup = hadDestination && backup.isFile
                    Log.e(
                        "YtDownloader",
                        "No se pudo restaurar de forma segura el Canvas anterior",
                    )
                }
                return restored
            }

            val installed = runCatching {
                Files.move(
                    staging.toPath(),
                    dest.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.recoverCatching {
                Files.move(staging.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.isSuccess && dest.isFile && dest.length() > 0L
            if (!installed) {
                rollbackDestination()
                return@withContext false
            }

            try {
                dao.updateSongVideo(song.id, dest.absolutePath)
            } catch (e: Exception) {
                rollbackDestination()
                throw e
            }

            // Sólo tras instalar y registrar el MP4 se retira el Canvas anterior.
            val root = runCatching { musicDir.canonicalFile }.getOrNull()
            val previous = song.videoPath?.let { runCatching { File(it).canonicalFile }.getOrNull() }
            val audio = runCatching { File(song.filePath).canonicalFile }.getOrNull()
            val finalFile = runCatching { dest.canonicalFile }.getOrNull()
            if (
                previous != null && previous != finalFile && previous != audio &&
                root != null && previous.parentFile == root &&
                previous.extension.lowercase() in setOf("mp4", "webm", "mkv", "gif")
            ) {
                runCatching { previous.delete() }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("YtDownloader", "downloadVideoClip falló", e)
            false
        } finally {
            cleanupCanvasTemps()
        }
    }

    private suspend fun download(result: SearchResult) {
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=${result.id}").apply {
            applyYoutubeClient()
            // bestaudio picks the highest-bitrate stream YouTube serves
            // (Opus 160k on normal videos, Opus/AAC 256k when available).
            addOption("-f", "bestaudio/best")
            addOption("--no-playlist")
            addOption("--no-mtime")
            // Cover art saved next to the audio for offline display.
            addOption("--write-thumbnail")
            addOption("--convert-thumbnails", "jpg")
            addOption("-o", "${musicDir.absolutePath}/%(id)s.%(ext)s")
            addOption("-o", "thumbnail:${musicDir.absolutePath}/%(id)s.%(ext)s")
            // 80/20: while music plays, cap the download rate so it never
            // saturates network/IO ahead of the audio pipeline.
            if (isPlayingProvider()) addOption("--limit-rate", "1M")
            // SponsorBlock: strip non-music segments (intros, outros, talking).
            if (AppSettings.readSponsorBlock(context)) {
                addOption("--sponsorblock-remove", "music_offtopic")
            }
        }
        ytdl().execute(request, result.id) { progress, _, _ ->
            // Ignore late progress ticks after the user cancelled (the native
            // process is being torn down): otherwise they'd re-add the id.
            if (result.id !in cancelled) {
                setState(result.id, DownloadState.Downloading(progress.coerceIn(0f, 100f)))
            }
        }

        val audioFile = musicDir.listFiles()
            ?.firstOrNull { it.nameWithoutExtension == result.id && it.extension != "jpg" }
            ?: throw IllegalStateException("Archivo no encontrado tras la descarga")
        val artFile = File(musicDir, "${result.id}.jpg").takeIf { it.exists() }
        val quality = readAudioQuality(audioFile.absolutePath, result.durationSec)

        dao.insertSong(
            Song(
                id = result.id,
                title = result.title,
                artist = result.artist,
                durationSec = result.durationSec,
                filePath = audioFile.absolutePath,
                artPath = artFile?.absolutePath,
                thumbnailUrl = result.thumbnailUrl,
                addedAt = System.currentTimeMillis(),
                codec = quality?.codec,
                bitrateKbps = quality?.bitrateKbps,
                sampleRateHz = quality?.sampleRateHz,
            )
        )

        // Best-effort post-processing: offline lyrics, loudness, sonic analysis.
        dao.getSong(result.id)?.let { saved ->
            scope.launch(Dispatchers.IO) {
                runCatching { LyricsFetcher.getOrFetch(saved, musicDir) }
                runCatching {
                    LoudnessScanner.measureRmsDb(saved.filePath)?.let {
                        dao.updateLoudness(saved.id, it)
                    }
                }
                runCatching {
                    AudioAnalyzer.analyze(saved.filePath, saved.durationSec)?.let {
                        dao.updateAnalysis(saved.id, it.bpm, it.camelot, it.featuresJson())
                    }
                }
                // Los modelos se bajan en el backfill del arranque; aquí sólo se usan si ya están.
                runCatching {
                    com.aar.privatemusic.util.MoodAnalyzer.analyze(context, saved.filePath, saved.durationSec)?.let {
                        dao.updateMood(saved.id, it.happy, it.sad, it.aggressive, it.relaxed, it.danceability, it.vocalness)
                    }
                }
            }
        }
    }

    // ---- Capítulos: un vídeo largo con marcadores → una pista por capítulo ----

    /**
     * Lee los capítulos de un vídeo (yt-dlp los expone en `chapters`). Lista
     * vacía si no tiene, o si falla la red. No descarga nada: sólo metadatos.
     */
    suspend fun fetchChapters(videoId: String): List<Chapter> = withContext(Dispatchers.IO) {
        runCatching {
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                applyYoutubeClient()
                addOption("--dump-single-json")
                addOption("--no-warnings")
                addOption("--skip-download")
            }
            val out = ytdl().execute(request).out
            val json = out.lineSequence().firstOrNull { it.trim().startsWith("{") } ?: return@runCatching emptyList()
            Chapter.parseFrom(json)
        }.getOrElse {
            Log.w("YtDownloader", "no se pudieron leer los capítulos de $videoId", it)
            emptyList()
        }
    }

    /**
     * Descarga un vídeo y lo parte en una pista por capítulo (`--split-chapters`,
     * sin recodificar). Cada trozo entra en la biblioteca como su propia canción,
     * con id `${videoId}_chNNN`, y todas van a una playlist con el título del
     * vídeo. La carátula del vídeo se reutiliza para todas.
     */
    fun enqueueChapters(result: SearchResult, chapters: List<Chapter>, albumTitle: String) {
        if (chapters.isEmpty()) { enqueue(result); return }
        val marker = "chapters:${result.id}"
        cancelled.remove(marker)
        titles[marker] = albumTitle
        setState(marker, DownloadState.Queued)
        DownloadService.ensureRunning(context)
        val job = scope.launch(downloadDispatcher) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                slots.withPermit {
                    val runHeavy: suspend () -> Unit = { downloadChapters(result, chapters, albumTitle) }
                    if (isPlayingProvider()) soloWhilePlaying.withLock { runHeavy() } else runHeavy()
                    setState(marker, DownloadState.Done)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("YtDownloader", "chapter split failed for ${result.id}", e)
                setState(marker, DownloadState.Failed(e.message ?: "Error"))
            } finally {
                jobs.remove(marker)
                titles.remove(marker)
                cancelled.remove(marker)
            }
        }
        jobs[marker] = job
    }

    private suspend fun downloadChapters(result: SearchResult, chapters: List<Chapter>, albumTitle: String) {
        setState("chapters:${result.id}", DownloadState.Downloading(0f))
        // Los trozos se nombran por número de capítulo (0-padded), así que el
        // título del capítulo —con barras u otros caracteres— nunca toca el disco.
        val chapterTemplate = "${musicDir.absolutePath}/${result.id}_ch%(section_number)03d.%(ext)s"
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=${result.id}").apply {
            applyYoutubeClient()
            addOption("-f", "bestaudio/best")
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--split-chapters")
            // El fichero completo iría a un id temporal; luego se borra.
            addOption("-o", "${musicDir.absolutePath}/${result.id}_full.%(ext)s")
            addOption("-o", "chapter:$chapterTemplate")
            addOption("--write-thumbnail")
            addOption("--convert-thumbnails", "jpg")
            addOption("-o", "thumbnail:${musicDir.absolutePath}/${result.id}.%(ext)s")
            if (isPlayingProvider()) addOption("--limit-rate", "1M")
        }
        ytdl().execute(request, "chapters:${result.id}") { progress, _, _ ->
            if ("chapters:${result.id}" !in cancelled) {
                setState("chapters:${result.id}", DownloadState.Downloading(progress.coerceIn(0f, 100f)))
            }
        }

        // El fichero completo ya no hace falta: sólo queríamos los trozos.
        musicDir.listFiles()?.filter { it.name.startsWith("${result.id}_full") }?.forEach { it.delete() }
        val artFile = File(musicDir, "${result.id}.jpg").takeIf { it.exists() }

        val playlistId = dao.insertPlaylist(
            com.aar.privatemusic.data.db.Playlist(
                name = albumTitle.take(60),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )
        var added = 0
        chapters.forEach { chapter ->
            val chId = "${result.id}_ch%03d".format(chapter.index)
            val audioFile = musicDir.listFiles()
                ?.firstOrNull { it.nameWithoutExtension == chId && it.extension != "jpg" }
                ?: return@forEach
            val quality = readAudioQuality(audioFile.absolutePath, chapter.durationSec)
            dao.insertSong(
                Song(
                    id = chId,
                    title = chapter.title,
                    artist = result.artist,
                    durationSec = chapter.durationSec,
                    filePath = audioFile.absolutePath,
                    artPath = artFile?.absolutePath,
                    thumbnailUrl = result.thumbnailUrl,
                    addedAt = System.currentTimeMillis(),
                    album = albumTitle,
                    trackNumber = chapter.index,
                    codec = quality?.codec,
                    bitrateKbps = quality?.bitrateKbps,
                    sampleRateHz = quality?.sampleRateHz,
                )
            )
            dao.addToPlaylist(
                com.aar.privatemusic.data.db.PlaylistSongCrossRef(playlistId, chId, added)
            )
            added++
            // Post-proceso barato por pista (letra no aplica; loudness/análisis sí).
            dao.getSong(chId)?.let { saved ->
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        LoudnessScanner.measureRmsDb(saved.filePath)?.let { dao.updateLoudness(saved.id, it) }
                    }
                    runCatching {
                        AudioAnalyzer.analyze(saved.filePath, saved.durationSec)?.let {
                            dao.updateAnalysis(saved.id, it.bpm, it.camelot, it.featuresJson())
                        }
                    }
                }
            }
        }
        com.aar.privatemusic.util.Feedback.show("\"$albumTitle\": $added pistas divididas por capítulos")
    }

    suspend fun deleteSongFiles(song: Song) = withContext(Dispatchers.IO) {
        // Local (MediaStore) songs are referenced in place: removing them from
        // the library must NOT delete the user's file.
        if (!song.id.startsWith("local_")) File(song.filePath).delete()
        song.artPath?.let { File(it).delete() }
        // Companion files: cached lyrics and karaoke instrumental (~40 MB).
        File(musicDir, "${song.id}.lrc").delete()
        File(musicDir, "${song.id}.karaoke.wav").delete()
        File(musicDir, "${song.id}.karaoke.part").delete()
        File(musicDir, "${song.id}.karaoke_hq.wav").delete()
        File(musicDir, "${song.id}.karaoke_hq.part").delete()
        _downloads.update { it - song.id }
    }

    /**
     * Keep yt-dlp current — YouTube breaks old extractor versions regularly.
     *
     * Una vez al día basta: los extractores no cambian cada vez que abres la app,
     * y comprobarlo en cada arranque en frío es una petición de red y una escritura
     * en disco compitiendo con el primer fotograma.
     */
    fun updateYtDlp() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val last = prefs.getLong("ytdlp_checked_at", 0L)
        val now = System.currentTimeMillis()
        if (now - last < 24 * 60 * 60 * 1000L) return
        scope.launch(Dispatchers.IO) {
            ready.await()
            runCatching { YoutubeDL.getInstance().updateYoutubeDL(context) }
                .onSuccess { prefs.edit().putLong("ytdlp_checked_at", now).apply() }
                .onFailure { Log.w("YtDownloader", "yt-dlp update failed", it) }
        }
    }

    private fun setState(id: String, state: DownloadState) {
        _downloads.update { it + (id to state) }
    }
}
