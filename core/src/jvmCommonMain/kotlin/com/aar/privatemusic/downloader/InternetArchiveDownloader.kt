package com.aar.privatemusic.downloader

import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.PlaylistSongCrossRef
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.util.readAudioQuality
import com.aar.privatemusic.util.readAudioTags
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
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Descarga un ítem de Internet Archive (álbum/concierto) e importa sus pistas a
 * la biblioteca, igual que [TorrentDownloader] con un torrent. De cada ítem elige
 * UN formato (FLAC si lo hay, si no MP3, si no Ogg) para no duplicar la misma
 * pista, baja los ficheros por HTTP uno a uno (sin el problema de FUSE de los
 * torrents) y los agrupa en una playlist con el nombre del ítem.
 *
 * Comparte el modelo [DownloadState] con [YtDownloader] para reutilizar la UI.
 */
class InternetArchiveDownloader(
    private val env: DownloaderEnv,
    private val dao: MusicDao,
    private val scope: CoroutineScope,
) {
    // Mismo directorio que el resto de descargas. Se baja una pista a la vez, así
    // que no dispara el bug de FUSE que sí afectaba a los torrents.
    private val musicDir: File = env.musicDir.apply { mkdirs() }

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    // Un ítem a la vez: pueden ser conciertos enteros (muchas pistas grandes).
    private val slots = Semaphore(1)

    private val jobs = ConcurrentHashMap<String, Job>()

    private val audioExtensions = setOf(
        "flac", "mp3", "m4a", "aac", "opus", "ogg", "wav", "aiff", "aif", "wma", "alac",
    )

    /**
     * URL directa de una pista del ítem para preescuchar sin descargarlo. Prefiere
     * MP3/Ogg (ligeros para streaming) sobre FLAC. Los ficheros de archive.org son
     * HTTP abiertos, así que el reproductor los streamea directamente.
     */
    suspend fun previewUrl(identifier: String): String? = withContext(Dispatchers.IO) {
        val body = httpGet("https://archive.org/metadata/${enc(identifier)}") ?: return@withContext null
        val files = runCatching { JSONObject(body).optJSONArray("files") }.getOrNull()
            ?: return@withContext null
        // (nombre, rango de formato, nº de pista): elige el mejor para streaming.
        val cands = ArrayList<Triple<String, Int, Int>>()
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name").takeIf { it.isNotBlank() } ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in audioExtensions) continue
            val fmt = f.optString("format").lowercase()
            val rank = when {
                "mp3" in fmt || ext == "mp3" -> 0
                "ogg" in fmt || ext == "ogg" -> 1
                else -> 2 // FLAC u otros: pesados para preescuchar
            }
            val track = f.optString("track").substringBefore('/').trim().toIntOrNull() ?: 9999
            cands.add(Triple(name, rank, track))
        }
        val best = cands.minWithOrNull(compareBy({ it.second }, { it.third }, { it.first }))
            ?: return@withContext null
        "https://archive.org/download/${enc(identifier)}/${encPath(best.first)}"
    }

    fun enqueue(result: SearchResult) {
        val id = result.id
        val current = _downloads.value[id]
        if (current is DownloadState.Queued || current is DownloadState.Downloading) return
        setState(id, DownloadState.Queued)
        val job = scope.launch(Dispatchers.IO) {
            slots.withPermit {
                try {
                    val imported = downloadAndImport(result)
                    if (imported == 0) throw IllegalStateException("El ítem no tiene audio descargable")
                    setState(id, DownloadState.Done)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _downloads.update { it - id }
                    throw e
                } catch (e: Exception) {
                    env.log("ArchiveDownloader", "archive failed for $id", e)
                    setState(id, DownloadState.Failed(e.message ?: "error"))
                } finally {
                    jobs.remove(id)
                }
            }
        }
        jobs[id] = job
    }

    /** Cancels an Internet Archive item download. */
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        _downloads.update { it - id }
    }

    private data class ArchiveFile(val name: String, val ext: String, val format: String, val track: Int)

    /** Descarga las pistas del ítem y devuelve cuántas se importaron. */
    private suspend fun downloadAndImport(result: SearchResult): Int = withContext(Dispatchers.IO) {
        val id = result.id
        val meta = JSONObject(
            httpGet("https://archive.org/metadata/${enc(id)}")
                ?: throw IllegalStateException("No se pudo leer el ítem"),
        )
        val filesJson = meta.optJSONArray("files")
            ?: throw IllegalStateException("El ítem no tiene ficheros")

        // Recolecta todos los ficheros de audio con su formato exacto.
        val audio = ArrayList<ArchiveFile>()
        for (i in 0 until filesJson.length()) {
            val f = filesJson.optJSONObject(i) ?: continue
            val name = f.optString("name").takeIf { it.isNotBlank() } ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in audioExtensions) continue
            val fmt = f.optString("format").ifBlank { ext }
            val track = f.optString("track").substringBefore('/').trim().toIntOrNull() ?: 9999
            audio.add(ArchiveFile(name, ext, fmt, track))
        }
        if (audio.isEmpty()) return@withContext 0
        // Un ÚNICO formato exacto (el de mejor calidad; a igualdad, el más completo)
        // para no duplicar pistas cuando el ítem trae FLAC + MP3 + Ogg de lo mismo.
        val bestFormat = audio.groupBy { it.format }
            .minWithOrNull(
                compareBy({ InternetArchiveSource.audioFormatRank(it.key) }, { -it.value.size }),
            )?.key ?: return@withContext 0
        val chosen = audio.filter { it.format == bestFormat }
            .sortedWith(compareBy({ it.track }, { it.name }))
        if (chosen.isEmpty()) return@withContext 0

        // Portada compartida del ítem (miniatura de archive.org).
        val albumKey = sha1Hex(id).take(24)
        val cover = File(musicDir, "ia_$albumKey.jpg")
        runCatching { downloadTo("https://archive.org/services/img/${enc(id)}", cover) }

        val playlistId: Long? = if (chosen.size > 1) {
            dao.insertPlaylist(Playlist(name = result.title.take(60), createdAt = System.currentTimeMillis()))
        } else null

        val fallbackArtist = result.artist.substringBefore(" · ").trim().ifBlank { "Internet Archive" }
        var imported = 0
        chosen.forEachIndexed { index, af ->
            setState(id, DownloadState.Downloading(index.toFloat() / chosen.size * 100f))
            val songId = "ia_" + sha1Hex("$id/${af.name}").take(24)
            if (dao.songExists(songId)) {
                playlistId?.let { addToPlaylistEnd(it, songId) }
                imported++
                return@forEachIndexed
            }
            val out = File(musicDir, "$songId.${af.ext}")
            val url = "https://archive.org/download/${enc(id)}/${encPath(af.name)}"
            try {
                downloadTo(url, out)
            } catch (e: Exception) {
                env.log("ArchiveDownloader", "skip ${af.name}: ${e.message}")
                out.delete()
                return@forEachIndexed
            }
            val tags = readAudioTags(out.absolutePath)
            val title = tags.title ?: af.name.substringAfterLast('/').substringBeforeLast('.')
            val artist = tags.artist ?: fallbackArtist
            // Dedup: si ya tienes esta pista (mismo título+artista), usa la existente.
            val dup = dao.findByTitleArtist(title, artist)
            if (dup != null && dup.id != songId) {
                out.delete()
                playlistId?.let { addToPlaylistEnd(it, dup.id) }
                imported++
                return@forEachIndexed
            }
            val quality = runCatching { readAudioQuality(out.absolutePath, tags.durationSec) }.getOrNull()
            dao.insertSong(
                Song(
                    id = songId,
                    title = title,
                    artist = artist,
                    durationSec = tags.durationSec,
                    filePath = out.absolutePath,
                    artPath = cover.takeIf { it.exists() && it.length() > 0 }?.absolutePath,
                    thumbnailUrl = result.thumbnailUrl.ifBlank { null },
                    addedAt = System.currentTimeMillis(),
                    codec = quality?.codec,
                    bitrateKbps = quality?.bitrateKbps,
                    sampleRateHz = quality?.sampleRateHz,
                )
            )
            playlistId?.let { addToPlaylistEnd(it, songId) }
            imported++
        }
        imported
    }

    private suspend fun addToPlaylistEnd(playlistId: Long, songId: String) {
        val position = dao.playlistSize(playlistId)
        dao.addToPlaylist(PlaylistSongCrossRef(playlistId, songId, position))
    }

    private fun downloadTo(url: String, out: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", UA)
        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
        conn.disconnect()
    }

    private fun httpGet(spec: String): String? = try {
        val conn = URL(spec).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", UA)
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        } else {
            conn.disconnect()
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** URL-encode preservando las barras de subcarpetas del nombre. */
    private fun encPath(name: String): String =
        name.split("/").joinToString("/") { enc(it) }

    private fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun setState(id: String, state: DownloadState) {
        _downloads.update { it + (id to state) }
    }

    companion object {
        private const val UA = "PrivateMusic (https://github.com/aarweb/PrivateMusic)"
    }
}
