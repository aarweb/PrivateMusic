package com.aar.privatemusic.downloader

import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.PlaylistSongCrossRef
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.util.readAudioQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga canciones del servidor OpenSubsonic del usuario (Navidrome/Jellyfin…)
 * a la biblioteca local, con el mismo modelo [DownloadState] que el resto de
 * fuentes. La config vive en las preferencias de la plataforma; aquí llega por
 * [configProvider] para no depender de Android.
 */
class SubsonicDownloader(
    private val env: DownloaderEnv,
    private val dao: MusicDao,
    private val scope: CoroutineScope,
    private val configProvider: () -> SubsonicConfig?,
) {
    private val musicDir: File = env.musicDir.apply { mkdirs() }

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    private val slots = Semaphore(2)

    private fun client(): SubsonicSource? = configProvider()?.takeIf { it.isSet }?.let { SubsonicSource(it) }

    fun localId(serverId: String) = "sub_$serverId"

    suspend fun ping(config: SubsonicConfig): SubsonicPing = SubsonicSource(config).ping()

    suspend fun search(query: String, limit: Int = 30): List<SubsonicTrack> =
        client()?.search(query, limit) ?: emptyList()

    suspend fun playlists(): List<SubsonicPlaylist> = client()?.playlists() ?: emptyList()

    /** URL de streaming para preescuchar sin descargar (o null si no hay servidor). */
    fun streamUrl(serverId: String): String? = client()?.streamUrl(serverId)

    fun coverUrl(coverArtId: String?): String? = coverArtId?.let { client()?.coverArtUrl(it) }

    fun enqueue(track: SubsonicTrack) {
        val key = localId(track.id)
        val current = _downloads.value[key]
        if (current is DownloadState.Queued || current is DownloadState.Downloading) return
        setState(key, DownloadState.Queued)
        scope.launch(Dispatchers.IO) {
            slots.withPermit {
                try {
                    if (!dao.songExists(key)) downloadTrack(track, null, null)
                    setState(key, DownloadState.Done)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _downloads.update { it - key }; throw e
                } catch (e: Exception) {
                    env.log("SubsonicDownloader", "download failed for ${track.id}", e)
                    setState(key, DownloadState.Failed(e.message ?: "error"))
                }
            }
        }
    }

    /** Descarga una playlist entera del servidor a una playlist local nueva. */
    fun importPlaylist(playlist: SubsonicPlaylist) {
        val marker = "subpl_${playlist.id}"
        setState(marker, DownloadState.Queued)
        scope.launch(Dispatchers.IO) {
            try {
                val c = client() ?: throw IllegalStateException("Servidor no configurado")
                val tracks = c.playlistTracks(playlist.id)
                if (tracks.isEmpty()) throw IllegalStateException("La playlist está vacía")
                val localPlaylistId = dao.insertPlaylist(
                    Playlist(name = playlist.name.take(60), createdAt = System.currentTimeMillis()),
                )
                tracks.forEachIndexed { i, t ->
                    setState(marker, DownloadState.Downloading(i.toFloat() / tracks.size * 100f))
                    runCatching { downloadTrack(t, localPlaylistId, i) }
                        .onFailure { env.log("SubsonicDownloader", "skip ${t.title}: ${it.message}") }
                }
                setState(marker, DownloadState.Done)
            } catch (e: Exception) {
                env.log("SubsonicDownloader", "playlist import failed", e)
                setState(marker, DownloadState.Failed(e.message ?: "error"))
            }
        }
    }

    private suspend fun downloadTrack(track: SubsonicTrack, playlistId: Long?, position: Int?) {
        val c = client() ?: throw IllegalStateException("Servidor no configurado")
        val key = localId(track.id)
        // Dedup por título+artista, como el resto de fuentes.
        dao.findByTitleArtist(track.title, track.artist)?.let { dup ->
            playlistId?.let { addToPlaylist(it, dup.id, position) }
            return
        }
        if (dao.songExists(key)) {
            playlistId?.let { addToPlaylist(it, key, position) }
            return
        }
        val out = File(musicDir, "$key.${track.suffix}")
        downloadTo(c.streamUrl(track.id), out)
        val cover: File? = track.coverArtId?.let { artId ->
            File(musicDir, "$key.jpg").also { runCatching { downloadTo(c.coverArtUrl(artId), it) } }
        }?.takeIf { it.exists() && it.length() > 0 }
        val quality = runCatching { readAudioQuality(out.absolutePath, track.durationSec) }.getOrNull()
        dao.insertSong(
            Song(
                id = key,
                title = track.title,
                artist = track.artist,
                durationSec = track.durationSec,
                filePath = out.absolutePath,
                artPath = cover?.absolutePath,
                thumbnailUrl = track.coverArtId?.let { c.coverArtUrl(it) },
                addedAt = System.currentTimeMillis(),
                codec = quality?.codec,
                bitrateKbps = quality?.bitrateKbps,
                sampleRateHz = quality?.sampleRateHz,
            ),
        )
        playlistId?.let { addToPlaylist(it, key, position) }
    }

    private suspend fun addToPlaylist(playlistId: Long, songId: String, position: Int?) {
        runCatching {
            dao.addToPlaylist(PlaylistSongCrossRef(playlistId, songId, position ?: dao.playlistSize(playlistId)))
        }.onFailure { env.log("SubsonicDownloader", "no se pudo añadir a la playlist", it) }
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

    private fun setState(id: String, state: DownloadState) = _downloads.update { it + (id to state) }
}
