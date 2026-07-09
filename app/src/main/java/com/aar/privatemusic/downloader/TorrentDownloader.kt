package com.aar.privatemusic.downloader

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.PlaylistSongCrossRef
import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.util.readAudioQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Descarga torrents de música con un motor BitTorrent embebido (libtorrent4j) e
 * importa los audios del álbum a la biblioteca, igual que una descarga de
 * YouTube. El usuario solo pulsa descargar: la app resuelve los metadatos del
 * magnet, baja el contenido, guarda cada pista como Song en una playlist con el
 * nombre del torrent y deja de compartir (unseed) al terminar.
 *
 * Comparte el modelo de estado de [YtDownloader] ([DownloadState]) para que las
 * filas del buscador muestren el progreso sin lógica de UI adicional.
 */
class TorrentDownloader(
    private val context: Context,
    private val dao: MusicDao,
    private val scope: CoroutineScope,
) {
    // Almacenamiento INTERNO a propósito: el externo (/storage/emulated/0) pasa
    // por FUSE de MediaProvider, cuyo NodeTracker aborta (SIGABRT) cuando
    // libtorrent pre-asigna y escribe muchos ficheros del álbum a la vez. El
    // interno no usa FUSE y evita ese crash; el reproductor lee la ruta directa.
    private val downloadDir: File =
        File(context.filesDir, "torrents").apply { mkdirs() }

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    // Un solo torrent a la vez: son grandes y compiten por ancho de banda.
    private val slots = Semaphore(1)

    private val jobs = ConcurrentHashMap<String, Job>()
    private val handles = ConcurrentHashMap<String, TorrentHandle>()

    // El motor es caro de arrancar (carga .so nativos), así que se comparte y se
    // inicia perezosamente la primera vez que se descarga algo.
    @Volatile private var session: SessionManager? = null

    private fun ensureSession(): SessionManager =
        session ?: synchronized(this) {
            session ?: SessionManager().also { it.start(); session = it }
        }

    private val audioExtensions = setOf(
        "flac", "mp3", "m4a", "aac", "opus", "ogg", "wav", "aiff", "aif", "wma", "alac",
    )

    fun enqueue(result: SearchResult) {
        val magnet = result.magnetUri
        val id = result.id
        if (magnet.isNullOrBlank()) {
            setState(id, DownloadState.Failed("Sin magnet"))
            return
        }
        val current = _downloads.value[id]
        if (current is DownloadState.Queued || current is DownloadState.Downloading) return
        setState(id, DownloadState.Queued)
        val job = scope.launch(Dispatchers.IO) {
            slots.withPermit {
                try {
                    val imported = downloadAndImport(result, magnet)
                    if (imported == 0) throw IllegalStateException("Sin audios en el torrent")
                    setState(id, DownloadState.Done)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    runCatching { handles.remove(id)?.let { ensureSession().remove(it) } }
                    _downloads.update { it - id }
                    throw e
                } catch (e: Exception) {
                    Log.e("TorrentDownloader", "torrent failed for $id", e)
                    setState(id, DownloadState.Failed(e.message ?: "error"))
                } finally {
                    jobs.remove(id)
                    handles.remove(id)
                }
            }
        }
        jobs[id] = job
    }

    /** Cancels a torrent download: stops the transfer and forgets it. */
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        runCatching { handles.remove(id)?.let { ensureSession().remove(it) } }
        _downloads.update { it - id }
    }

    /** Descarga el torrent y devuelve cuántas pistas de audio se importaron. */
    private suspend fun downloadAndImport(result: SearchResult, magnet: String): Int =
        withContext(Dispatchers.IO) {
            val s = ensureSession()

            // fetchMagnet bloquea hasta resolver los metadatos del magnet (fase
            // "resolviendo"); devuelve el .torrent como bytes o vacío si no hay peers.
            val hashHex = magnetHash(magnet)
                ?: throw IllegalStateException("Magnet sin btih")
            val hash = Sha1Hash.parseHex(hashHex)

            // Descarga directa del magnet en un ÚNICO handle: conserva los trackers
            // de forma nativa. (El patrón fetchMagnet + download(info) los perdía
            // porque bdecode sólo devuelve el info-dict, dejando el torrent con
            // sólo DHT: se estancaba tras la ráfaga inicial.) Flags de descarga
            // gestionada; este overload los combina con los suyos (no acepta null).
            val flags = TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPDATE_SUBSCRIBE)
            s.download(magnet, downloadDir, flags)
            val handle = awaitHandle(s, hash)
                ?: throw IllegalStateException("El torrent no arrancó")
            handles[result.id] = handle

            // Espera a resolver los metadatos (lista de ficheros) vía trackers+DHT.
            val info = awaitMetadata(handle)
                ?: throw IllegalStateException("No se pudieron obtener los metadatos (sin peers)")

            pollUntilFinished(handle, result.id)

            val rootName = info.name()
            // Cada torrent puede ser una carpeta (álbum) o un único fichero suelto;
            // filePath(i) ya incluye la carpeta raíz cuando la hay.
            val files = (0 until info.numFiles()).map { i ->
                File(downloadDir, info.files().filePath(i))
            }
            val cover = findCover(files, File(downloadDir, rootName))

            // Deja de compartir en cuanto tenemos los ficheros: el usuario no debe
            // seguir subiendo indefinidamente. Los archivos se conservan.
            runCatching { s.remove(handle) }

            importAudioFiles(result, files, cover)
        }

    private suspend fun importAudioFiles(
        result: SearchResult,
        files: List<File>,
        cover: File?,
    ): Int {
        val audios = files
            .filter { it.exists() && it.extension.lowercase() in audioExtensions }
            .sortedBy { it.absolutePath }
        if (audios.isEmpty()) return 0

        // Un torrent de varias pistas se agrupa en una playlist con su nombre.
        val playlistId: Long? = if (audios.size > 1) {
            dao.insertPlaylist(
                Playlist(name = result.title.take(60), createdAt = System.currentTimeMillis())
            )
        } else null

        var imported = 0
        audios.forEach { file ->
            val songId = "tor_" + sha1Hex(file.absolutePath).take(24)
            if (dao.songExists(songId)) {
                playlistId?.let { addToPlaylistEnd(it, songId) }
                imported++
                return@forEach
            }
            val tags = readTags(file)
            val title = tags.title ?: file.nameWithoutExtension
            val artist = tags.artist ?: result.title.substringBefore(" - ").trim()
                .ifBlank { "Desconocido" }
            // Dedup: si ya tienes esta pista (mismo título+artista), usa la existente.
            val dup = dao.findByTitleArtist(title, artist)
            if (dup != null && dup.id != songId) {
                playlistId?.let { addToPlaylistEnd(it, dup.id) }
                imported++
                return@forEach
            }
            val quality = runCatching { readAudioQuality(file.absolutePath, tags.durationSec) }.getOrNull()
            dao.insertSong(
                Song(
                    id = songId,
                    title = title,
                    artist = artist,
                    durationSec = tags.durationSec,
                    filePath = file.absolutePath,
                    artPath = cover?.absolutePath,
                    thumbnailUrl = null,
                    addedAt = System.currentTimeMillis(),
                    codec = quality?.codec,
                    bitrateKbps = quality?.bitrateKbps,
                    sampleRateHz = quality?.sampleRateHz,
                )
            )
            playlistId?.let { addToPlaylistEnd(it, songId) }
            imported++
        }
        return imported
    }

    private suspend fun addToPlaylistEnd(playlistId: Long, songId: String) {
        val position = dao.playlistSize(playlistId)
        dao.addToPlaylist(PlaylistSongCrossRef(playlistId, songId, position))
    }

    private data class Tags(val title: String?, val artist: String?, val durationSec: Int)

    private fun readTags(file: File): Tags {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            Tags(
                title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() },
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() },
                durationSec = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.let { (it / 1000).toInt() } ?: 0,
            )
        } catch (e: Exception) {
            Tags(null, null, 0)
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun findCover(files: List<File>, root: File): File? =
        com.aar.privatemusic.util.CoverFinder.pick(files, root)

    /** Espera a que el motor registre el handle tras añadir el magnet. */
    private suspend fun awaitHandle(s: SessionManager, hash: Sha1Hash): TorrentHandle? {
        repeat(20) {
            val h = s.find(hash)
            if (h != null && h.isValid) return h
            delay(500)
        }
        return null
    }

    /** Espera a que se descarguen los metadatos del magnet (lista de ficheros). */
    private suspend fun awaitMetadata(handle: TorrentHandle): TorrentInfo? {
        repeat(90) { // ~90s para encontrar peers y bajar el .torrent
            if (!handle.isValid) return null
            handle.torrentFile()?.takeIf { it.isValid }?.let { return it }
            delay(1_000)
        }
        return null
    }

    /**
     * Sondea el progreso hasta completar. Aborta si no avanza en 2 minutos
     * (torrent muerto sin seeders) para no dejar la descarga colgada.
     */
    private suspend fun pollUntilFinished(handle: TorrentHandle, id: String) {
        var lastProgress = -1f
        var stalledSeconds = 0
        while (true) {
            if (!handle.isValid) throw IllegalStateException("Torrent cancelado")
            val status = handle.status()
            val progress = (status.progress() * 100f).coerceIn(0f, 100f)
            setState(id, DownloadState.Downloading(progress))
            if (status.isFinished || status.isSeeding || progress >= 100f) return

            if (progress > lastProgress + 0.01f) {
                lastProgress = progress
                stalledSeconds = 0
            } else {
                stalledSeconds += 2
                if (stalledSeconds >= 180 && progress < 100f) {
                    throw IllegalStateException("Descarga estancada (sin seeders)")
                }
            }
            delay(2_000)
        }
    }

    private fun magnetHash(magnet: String): String? =
        Regex("""urn:btih:([a-fA-F0-9]{40})""").find(magnet)?.groupValues?.get(1)

    private fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun setState(id: String, state: DownloadState) {
        _downloads.update { it + (id to state) }
    }
}
