package com.aar.privatemusic.downloader

import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.aar.privatemusic.data.AppSettings
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private val workStateLock = Any()
    private var fillJob: Job? = null
    private var entryJob: Job? = null
    private val entryPrefs by lazy {
        context.getSharedPreferences(ENTRY_PREFS_NAME, Context.MODE_PRIVATE)
    }

    data class Progress(val done: Int, val total: Int)

    private val _fillProgress = MutableStateFlow<Progress?>(null)
    /** Progreso del relleno masivo; null si no hay ninguno en curso. */
    val fillProgress: StateFlow<Progress?> = _fillProgress

    private val _manualDownloadIds = MutableStateFlow<Set<String>>(emptySet())
    /** IDs cuyo Canvas se está descargando o reemplazando por petición del usuario. */
    val manualDownloadIds: StateFlow<Set<String>> = _manualDownloadIds

    private fun videoFile(id: String) = File(downloader.musicDir, "$id.mp4")
    private fun marker(id: String) = File(downloader.musicDir, "$id.novideo")

    /** La descarga manual reintenta siempre: borra la marca de "sin vídeo". */
    private fun clearNoVideoMarker(id: String) {
        runCatching { marker(id).delete() }
    }

    /**
     * Descarga o reemplaza el Canvas de una canción. Las peticiones manuales del
     * mismo ID no se apilan: si ya hay una activa, la segunda devuelve false.
     * Comparte el mismo [gate] que el relleno automático y nunca deja una marca
     * `.novideo`, porque una acción explícita siempre debe poder reintentarse.
     */
    suspend fun downloadOrReplace(song: Song): Boolean {
        val accepted = synchronized(_manualDownloadIds) {
            if (song.id in _manualDownloadIds.value) {
                false
            } else {
                _manualDownloadIds.value = _manualDownloadIds.value + song.id
                true
            }
        }
        if (!accepted) return false

        var ok = false
        try {
            clearNoVideoMarker(song.id)
            ok = gate.withLock { downloader.downloadVideoClip(song) }
            return ok
        } finally {
            if (!ok) clearNoVideoMarker(song.id)
            synchronized(_manualDownloadIds) {
                _manualDownloadIds.value = _manualDownloadIds.value - song.id
            }
        }
    }

    private fun isRealVideo(file: File): Boolean = runCatching {
        file.isFile && file.length() > 0L
    }.getOrDefault(false)

    private fun hasVideo(song: Song): Boolean =
        isRealVideo(videoFile(song.id)) ||
            song.videoPath?.takeUnless(String::isBlank)?.let { isRealVideo(File(it)) } == true

    /** Ya tiene un vídeo real, o ya se intentó y no había: no hay nada que hacer. */
    private fun settled(song: Song): Boolean {
        return hasVideo(song) || marker(song.id).exists()
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

    /** El intent sticky de batería permite consultar el estado sin registrar un receptor permanente. */
    fun chargingAllows(): Boolean = runCatching {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    private fun entryRestrictionsAllow(): Boolean =
        settings.autoDownloadVideo.value &&
            networkAllows() &&
            (!settings.videoOnlyWhileCharging.value || chargingAllows())

    /**
     * Revisa al entrar en la app una muestra pequeña de vídeos ausentes. No
     * bloquea al llamante y la ventana sólo se consume después de validar las
     * restricciones de red y carga.
     */
    fun checkMissingOnEntry() {
        synchronized(workStateLock) {
            if (entryJob?.isActive == true || fillJob?.isActive == true || _fillProgress.value != null) return

            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (!entryRestrictionsAllow()) return@launch

                    val now = System.currentTimeMillis()
                    val lastCheckedAt = entryPrefs.getLong(ENTRY_CHECKED_AT_KEY, 0L)
                    if (!VideoRetryPolicy.isEntryCheckDue(lastCheckedAt, now)) return@launch

                    val pending = withContext(Dispatchers.IO) {
                        entryPrefs.edit().putLong(ENTRY_CHECKED_AT_KEY, now).commit()

                        val unmarked = mutableListOf<Song>()
                        val retryable = mutableListOf<Pair<Song, Long>>()
                        dao.songsOnce().forEach { song ->
                            if (hasVideo(song)) return@forEach
                            val noVideo = marker(song.id)
                            if (!noVideo.exists()) {
                                unmarked += song
                            } else {
                                val markedAt = noVideo.lastModified()
                                if (VideoRetryPolicy.canRetryMarker(markedAt, now)) {
                                    retryable += song to markedAt
                                }
                            }
                        }

                        val orderedUnmarked = unmarked.sortedBy(Song::id)
                        val orderedRetryable = retryable
                            .sortedWith(compareBy<Pair<Song, Long>> { it.second }.thenBy { it.first.id })
                            .map { it.first }
                        (orderedUnmarked + orderedRetryable).take(VideoRetryPolicy.MAX_BATCH_SIZE)
                    }

                    Log.d("VideoAuto", "comprobación de entrada: ${pending.size} vídeos pendientes")
                    for (song in pending) {
                        if (!entryRestrictionsAllow()) {
                            Log.d("VideoAuto", "comprobación de entrada detenida por red o carga")
                            break
                        }
                        fetch(song)
                    }
                } finally {
                    synchronized(workStateLock) { entryJob = null }
                }
            }
            entryJob = job
            job.start()
        }
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
            if (settings.videoOnlyWhileCharging.value && !chargingAllows()) {
                Log.d("VideoAuto", "aplazado (el móvil no está cargando): ${song.title}")
                return@launch
            }
            fetch(song)
        }
    }

    /** Descarga una y deja marca si no había vídeo. Serializado para no saturar. */
    private suspend fun fetch(song: Song): Boolean = gate.withLock {
        val ok = downloader.downloadVideoClip(song)
        if (ok) {
            clearNoVideoMarker(song.id)
        } else {
            // No encontrado (o fallo): marca para no reintentar en cada relleno.
            runCatching {
                val noVideo = marker(song.id)
                noVideo.createNewFile()
                noVideo.setLastModified(System.currentTimeMillis())
            }
        }
        ok
    }

    /**
     * Baja el vídeo de todas las canciones que no lo tienen ni se han intentado.
     * Secuencial, en [scope]; publica progreso. Devuelve false si no arrancó.
     */
    fun fillMissing(onDone: (Int, Int) -> Unit) {
        synchronized(workStateLock) {
            if (_fillProgress.value != null || fillJob?.isActive == true) return
            val entryToCancel = entryJob
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    entryToCancel?.cancelAndJoin()
                    if (!networkAllows()) {
                        withContext(Dispatchers.Main) { onDone(-1, 0) }
                        return@launch
                    }
                    if (settings.videoOnlyWhileCharging.value && !chargingAllows()) {
                        withContext(Dispatchers.Main) { onDone(-2, 0) }
                        return@launch
                    }
                    val pending = withContext(Dispatchers.IO) { dao.songsOnce().filterNot { settled(it) } }
                    if (pending.isEmpty()) {
                        withContext(Dispatchers.Main) { onDone(0, 0) }
                        return@launch
                    }
                    var got = 0
                    for ((i, song) in pending.withIndex()) {
                        _fillProgress.value = Progress(i, pending.size)
                        if (!networkAllows()) break // se cortó la WiFi
                        if (settings.videoOnlyWhileCharging.value && !chargingAllows()) break
                        if (fetch(song)) got++
                    }
                    withContext(Dispatchers.Main) { onDone(got, pending.size) }
                } finally {
                    _fillProgress.value = null
                    synchronized(workStateLock) { fillJob = null }
                }
            }
            fillJob = job
            job.start()
        }
    }

    /** Borra Canvas, intentos y temporales para poder generarlos de nuevo desde cero. */
    suspend fun deleteAllVideos(): Int {
        entryJob?.cancelAndJoin()
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

    private companion object {
        const val ENTRY_PREFS_NAME = "video_auto"
        const val ENTRY_CHECKED_AT_KEY = "missing_video_entry_checked_at"
    }
}
