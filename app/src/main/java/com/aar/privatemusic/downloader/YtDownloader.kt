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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class SearchResult(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val thumbnailUrl: String,
    /** Resultado de torrent (1337x): sin preescucha, la acción copia el magnet. */
    val isTorrent: Boolean = false,
    val magnetUri: String? = null,
)

sealed interface DownloadState {
    data object Queued : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}

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
        setState(result.id, DownloadState.Queued)
        scope.launch(Dispatchers.IO) {
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
                try {
                    if (!dao.songExists(result.id)) download(result)
                    targetPlaylistId?.let { playlistId ->
                        val position = dao.playlistSize(playlistId)
                        dao.addToPlaylist(
                            com.aar.privatemusic.data.db.PlaylistSongCrossRef(playlistId, result.id, position)
                        )
                    }
                    dao.deletePending(result.id)
                    setState(result.id, DownloadState.Done)
                } catch (e: Exception) {
                    Log.e("YtDownloader", "download failed for ${result.id}", e)
                    runCatching { dao.bumpPendingAttempts(result.id) }
                    setState(result.id, DownloadState.Failed(e.message ?: "error"))
                }
            }
        }
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
            // SponsorBlock: strip non-music segments (intros, outros, talking).
            if (AppSettings.readSponsorBlock(context)) {
                addOption("--sponsorblock-remove", "music_offtopic")
            }
        }
        ytdl.execute(request, result.id) { progress, _, _ ->
            setState(result.id, DownloadState.Downloading(progress.coerceIn(0f, 100f)))
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
