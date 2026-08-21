package com.aar.privatemusic.downloader

import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.util.readAudioQuality
import com.aar.privatemusic.util.readAudioTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Descarga el MP3-128 público de una pista de Bandcamp a la biblioteca, con el
 * mismo modelo [DownloadState] que el resto de fuentes. El id local es
 * `bc_<sha1(pageUrl)>` para que la misma página no se baje dos veces.
 */
class BandcampDownloader(
    private val env: DownloaderEnv,
    private val dao: MusicDao,
    private val scope: CoroutineScope,
) {
    private val musicDir: File = env.musicDir.apply { mkdirs() }

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    private val slots = Semaphore(2)

    fun localId(pageUrl: String) = "bc_" + sha1Hex(pageUrl).take(24)

    /** Resuelve la página y devuelve el stream MP3-128 de la 1ª pista, para preescuchar. */
    suspend fun previewUrl(pageUrl: String): String? =
        BandcampSource.resolve(pageUrl)?.tracks?.firstOrNull()?.streamUrl

    fun enqueue(result: SearchResult) {
        val key = localId(result.id)
        val current = _downloads.value[key]
        if (current is DownloadState.Queued || current is DownloadState.Downloading) return
        setState(key, DownloadState.Queued)
        scope.launch(Dispatchers.IO) {
            slots.withPermit {
                try {
                    if (!dao.songExists(key)) download(result)
                    setState(key, DownloadState.Done)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _downloads.update { it - key }; throw e
                } catch (e: Exception) {
                    env.log("BandcampDownloader", "download failed for ${result.id}", e)
                    setState(key, DownloadState.Failed(e.message ?: "error"))
                }
            }
        }
    }

    private suspend fun download(result: SearchResult) {
        val key = localId(result.id)
        val item = BandcampSource.resolve(result.id)
            ?: throw IllegalStateException("No se pudo leer la página de Bandcamp")
        val track = item.tracks.firstOrNull()
            ?: throw IllegalStateException("Esta página no tiene audio para escuchar gratis")
        // El artista de la página a veces viene vacío; el band_name de la búsqueda es fiable.
        val fallbackArtist = result.artist.substringBefore(" · ").trim()
        val artist = track.artist.takeIf { it.isNotBlank() && it != "Bandcamp" }
            ?: fallbackArtist.ifBlank { "Bandcamp" }
        // Dedup por título+artista, como el resto de fuentes.
        dao.findByTitleArtist(track.title, artist)?.let { return }

        setState(key, DownloadState.Downloading(0f))
        val out = File(musicDir, "$key.mp3")
        downloadTo(track.streamUrl, out)
        val cover: File? = (item.artUrl ?: result.thumbnailUrl.takeIf { it.isNotBlank() })?.let { url ->
            File(musicDir, "$key.jpg").also { runCatching { downloadTo(url, it) } }
        }?.takeIf { it.exists() && it.length() > 0 }

        val tags = runCatching { readAudioTags(out.absolutePath) }.getOrNull()
        val duration = tags?.durationSec?.takeIf { it > 0 } ?: track.durationSec
        val quality = runCatching { readAudioQuality(out.absolutePath, duration) }.getOrNull()
        dao.insertSong(
            Song(
                id = key,
                title = track.title,
                artist = artist,
                durationSec = duration,
                filePath = out.absolutePath,
                artPath = cover?.absolutePath,
                thumbnailUrl = result.thumbnailUrl.ifBlank { null },
                addedAt = System.currentTimeMillis(),
                codec = quality?.codec,
                bitrateKbps = quality?.bitrateKbps,
                sampleRateHz = quality?.sampleRateHz,
            ),
        )
    }

    private fun downloadTo(url: String, out: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "PrivateMusic")
        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
        conn.disconnect()
    }

    private fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun setState(id: String, state: DownloadState) = _downloads.update { it + (id to state) }
}
