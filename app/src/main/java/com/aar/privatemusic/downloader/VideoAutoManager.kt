package com.aar.privatemusic.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.aar.privatemusic.data.AppSettings
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Baja el vídeo de fondo (`downloadVideoClip`) sin que el usuario lo pida canción
 * por canción: automáticamente tras cada descarga si está activado, y en masa
 * con "rellenar los que faltan". La política (¿activado? ¿la red vale? ¿ya se
 * intentó?) vive aquí; el "cómo" es de [YtDownloader].
 *
 * Marca de "sin vídeo": un fichero vacío `<id>.novideo` junto al audio cuando la
 * búsqueda no encontró nada, para que rellenar no rebusque siempre las mismas.
 * Un error de red NO deja marca (se reintentará). La descarga manual ("Buscar
 * vídeo en YouTube") borra la marca y siempre lo intenta.
 */
class VideoAutoManager(
    private val context: Context,
    private val downloader: YtDownloader,
    private val dao: MusicDao,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val gate = Mutex()
    private var fillJob: Job? = null

    data class Progress(val done: Int, val total: Int)

    private val _fillProgress = MutableStateFlow<Progress?>(null)
    /** Progreso del relleno masivo; null si no hay ninguno en curso. */
    val fillProgress: StateFlow<Progress?> = _fillProgress

    private fun videoFile(id: String) = File(downloader.musicDir, "$id.mp4")
    private fun marker(id: String) = File(downloader.musicDir, "$id.novideo")

    /** La descarga manual reintenta siempre: borra la marca de "sin vídeo". */
    fun clearNoVideoMarker(id: String) {
        runCatching { marker(id).delete() }
    }

    /** Ya tiene vídeo, o ya se intentó y no había: no hay nada que hacer. */
    private fun settled(song: Song): Boolean {
        val hasFile = videoFile(song.id).let { it.exists() && it.length() > 0 }
        return hasFile || !song.videoPath.isNullOrBlank() || marker(song.id).exists()
    }

    /** Sin datos: WiFi (no medida) siempre; datos móviles solo si el usuario lo permitió. */
    fun networkAllows(): Boolean {
        if (settings.videoOnMetered.value) return true
        return runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }.getOrDefault(false)
    }

    /** Gancho tras una descarga: baja el vídeo si procede. No bloquea al llamante. */
    fun onSongDownloaded(id: String) {
        if (!settings.autoDownloadVideo.value) return
        scope.launch {
            val song = dao.getSong(id) ?: return@launch
            if (settled(song)) return@launch
            if (!networkAllows()) {
                Log.d("VideoAuto", "aplazado (red medida sin permiso): ${song.title}")
                return@launch
            }
            fetch(song)
        }
    }

    /** Descarga una y deja marca si no había vídeo. Serializado para no saturar. */
    private suspend fun fetch(song: Song): Boolean = gate.withLock {
        val ok = downloader.downloadVideoClip(song)
        if (!ok) {
            // No encontrado (o fallo): marca para no reintentar en cada relleno.
            runCatching { marker(song.id).createNewFile() }
        }
        ok
    }

    /**
     * Baja el vídeo de todas las canciones que no lo tienen ni se han intentado.
     * Secuencial, en [scope]; publica progreso. Devuelve false si no arrancó.
     */
    fun fillMissing(onDone: (Int, Int) -> Unit) {
        if (_fillProgress.value != null) return
        fillJob = scope.launch {
            try {
                if (!networkAllows()) {
                    withContext(Dispatchers.Main) { onDone(-1, 0) }
                    return@launch
                }
                val pending = withContext(Dispatchers.IO) { dao.songsOnce().filterNot { settled(it) } }
                if (pending.isEmpty()) {
                    withContext(Dispatchers.Main) { onDone(0, 0) }
                    return@launch
                }
                var got = 0
                pending.forEachIndexed { i, song ->
                    _fillProgress.value = Progress(i, pending.size)
                    if (!networkAllows()) return@forEachIndexed // se cortó la WiFi
                    if (fetch(song)) got++
                }
                withContext(Dispatchers.Main) { onDone(got, pending.size) }
            } finally {
                _fillProgress.value = null
                fillJob = null
            }
        }
    }

    /** Borra Canvas, intentos y temporales para poder generarlos de nuevo desde cero. */
    suspend fun deleteAllVideos(): Int {
        fillJob?.cancelAndJoin()
        return gate.withLock {
            withContext(Dispatchers.IO) {
                val songs = dao.songsOnce()
                var cleared = 0
                songs.forEach { song ->
                    val audio = File(song.filePath).canonicalFile
                    val candidates = buildSet {
                        song.videoPath?.let { add(File(it)) }
                        add(videoFile(song.id))
                        add(File(downloader.musicDir, "${song.id}.gif"))
                        add(marker(song.id))
                        downloader.musicDir.listFiles()
                            ?.filter {
                                it.name.startsWith("${song.id}.canvas") ||
                                    it.name.startsWith("${song.id}.video.")
                            }
                            ?.let(::addAll)
                    }
                    var hadVideo = !song.videoPath.isNullOrBlank()
                    candidates.forEach { file ->
                        val safe = runCatching { file.canonicalFile }.getOrNull()
                        if (safe != null && safe != audio && safe.parentFile == downloader.musicDir.canonicalFile) {
                            if (safe.exists() && safe.extension.lowercase() in setOf("mp4", "webm", "mkv", "gif")) {
                                hadVideo = true
                            }
                            safe.delete()
                        }
                    }
                    dao.updateSongVideo(song.id, null)
                    if (hadVideo) cleared++
                }
                cleared
            }
        }
    }
}
