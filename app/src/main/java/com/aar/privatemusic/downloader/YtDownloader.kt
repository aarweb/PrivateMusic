package com.aar.privatemusic.downloader

import android.content.Context
import android.util.Log
import com.aar.privatemusic.data.AppSettings
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.lyrics.LyricsFetcher
import com.aar.privatemusic.util.AudioAnalyzer
import com.aar.privatemusic.util.LoudnessScanner
import com.aar.privatemusic.util.readAudioQuality
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
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
    private val ytdl get() = YoutubeDL.getInstance()

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
                addOption("--dump-json")
                addOption("--flat-playlist")
                addOption("--no-warnings")
            }
            val out = ytdl.execute(request).out
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
                addOption("--dump-json")
                addOption("--flat-playlist")
                addOption("--no-warnings")
            }
            val out = ytdl.execute(request).out
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
                addOption("-f", "bestaudio/best")
                addOption("--no-playlist")
            }
            ytdl.getInfo(request).url?.takeIf { it.startsWith("http") }
        }.onFailure { Log.e("YtDownloader", "streamUrl failed for $id", it) }.getOrNull()
    }

    /** Resolve a raw YouTube URL (e.g. shared from another app) and queue it. */
    fun enqueueUrl(url: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val info = ytdl.getInfo(url)
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

    private suspend fun download(result: SearchResult) {
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=${result.id}").apply {
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
        ytdl.execute(request, result.id) { progress, _, _ ->
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
            }
        }
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

    /** Keep yt-dlp current — YouTube breaks old extractor versions regularly. */
    fun updateYtDlp() {
        scope.launch(Dispatchers.IO) {
            runCatching { YoutubeDL.getInstance().updateYoutubeDL(context) }
                .onFailure { Log.w("YtDownloader", "yt-dlp update failed", it) }
        }
    }

    private fun setState(id: String, state: DownloadState) {
        _downloads.update { it + (id to state) }
    }
}
