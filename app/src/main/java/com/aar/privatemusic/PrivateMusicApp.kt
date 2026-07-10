package com.aar.privatemusic

import com.aar.privatemusic.data.db.openMusicDatabase
import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aar.privatemusic.data.AppSettings
import com.aar.privatemusic.data.MusicRepository
import com.aar.privatemusic.data.db.MusicDatabase
import com.aar.privatemusic.downloader.DeezerDownloader
import com.aar.privatemusic.downloader.InternetArchiveDownloader
import com.aar.privatemusic.downloader.TorrentDownloader
import com.aar.privatemusic.downloader.WatchWorker
import com.aar.privatemusic.downloader.YtDownloader
import com.aar.privatemusic.player.PlayerController
import com.aar.privatemusic.scrobble.ListenBrainz
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PrivateMusicApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: AppSettings
        private set
    lateinit var downloader: YtDownloader
        private set
    lateinit var torrents: TorrentDownloader
        private set
    lateinit var deezerDownloader: DeezerDownloader
        private set
    lateinit var archive: InternetArchiveDownloader
        private set
    lateinit var repository: MusicRepository
        private set
    lateinit var playerController: PlayerController
        private set
    lateinit var metadataService: com.aar.privatemusic.metadata.MetadataService
        private set
    lateinit var libraryShare: com.aar.privatemusic.sync.LibraryShare
        private set
    lateinit var musicDao: com.aar.privatemusic.data.db.MusicDao
        private set

    private val restoringQueue = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        val dao = openMusicDatabase(this).musicDao()
        musicDao = dao
        settings = AppSettings(this)
        downloader = YtDownloader(this, dao, appScope)
        // Desempaquetar yt-dlp y ffmpeg del APK es disco, y aquí estaríamos en el
        // hilo principal retrasando el primer fotograma. Va en segundo plano; quien
        // los use espera dentro de YtDownloader.
        downloader.initEngine()
        torrents = TorrentDownloader(this, dao, appScope)
        val downloaderEnv = com.aar.privatemusic.downloader.AndroidDownloaderEnv(this)
        deezerDownloader = DeezerDownloader(
            downloaderEnv,
            com.aar.privatemusic.downloader.AndroidDeezerAccount(this),
            dao,
            appScope,
        )
        archive = InternetArchiveDownloader(downloaderEnv, dao, appScope)
        repository = MusicRepository(dao, downloader)
        metadataService = com.aar.privatemusic.metadata.MetadataService(this, dao, downloader.musicDir)
        libraryShare = com.aar.privatemusic.sync.LibraryShare(this, dao)
        // El interruptor de Ajustes es la única verdad: sobrevive a reinicios.
        appScope.launch {
            settings.shareWithPc.collect { enabled ->
                if (enabled) libraryShare.start() else libraryShare.stop()
            }
        }
        // Auto-resolve canonical metadata (name/artist/album/cover/lyrics) after
        // each YouTube download, applying only when the match is confident.
        downloader.onDownloadComplete = { id ->
            appScope.launch { dao.getSong(id)?.let { metadataService.autoIdentify(it) } }
        }
        playerController = PlayerController(this) { songId ->
            appScope.launch {
                repository.recordPlay(songId)
                dao.getSong(songId)?.let {
                    ListenBrainz.submitListen(this@PrivateMusicApp, it.title, it.artist)
                }
            }
        }
        // 80/20: downloads throttle themselves while audio is actually playing.
        downloader.isPlayingProvider = { playerController.isPlaying.value }
        // Restore the last session's queue (paused) after a cold start.
        restoreSavedQueue()
        appScope.launch {
            downloader.updateYtDlp() // YouTube breaks old extractors regularly
            downloader.resumePending() // finish downloads cut off by process death
            maintenance()
        }

        // Check watched playlists/channels for new songs every 6 hours.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WatchWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                // Result.retry() re-runs at 10, 20, 40 min instead of waiting 6h.
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    10, TimeUnit.MINUTES,
                )
                .build(),
        )
    }

    /**
     * Devuelve al reproductor la cola de la última sesión, en pausa y donde la
     * dejaste, como hace Spotify.
     *
     * No basta con hacerlo en `onCreate`. Al cerrar la app desde recientes,
     * `PlaybackService.onTaskRemoved` vacía el reproductor para que la música no
     * siga sonando, pero **el proceso sobrevive**: `Application.onCreate` no se
     * repite, así que al volver a entrar no había ninguna canción aunque en
     * disco siguiera guardada. Por eso lo llama también `MainActivity`, en cada
     * arranque de la pantalla.
     *
     * Repetirlo es inofensivo: `restoreQueue` no toca nada si el reproductor ya
     * tiene cola, y el cerrojo evita que dos llamadas se pisen.
     */
    fun restoreSavedQueue() {
        if (!restoringQueue.compareAndSet(false, true)) return
        appScope.launch {
            try {
                val saved = playerController.savedQueue() ?: return@launch
                // Una canción borrada desde la última sesión desaparece de la
                // lista, y con ella se corren todos los índices siguientes: con
                // el índice guardado a secas, al arrancar sonaría otra canción.
                val found = saved.ids.map { musicDao.getSong(it) }
                val songs = found.filterNotNull()
                if (songs.isEmpty()) return@launch
                val present = found.map { it != null }
                val index = com.aar.privatemusic.player.QueueLogic
                    .restoreIndex(present, saved.index)
                val position = com.aar.privatemusic.player.QueueLogic
                    .restorePositionMs(present, saved.index, saved.positionMs)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    playerController.restoreQueue(songs, index, position)
                }
            } finally {
                restoringQueue.set(false)
            }
        }
    }

    /**
     * Tareas de mantenimiento de la biblioteca. Ninguna es urgente y todas
     * compiten con el arranque: `AudioAnalyzer` decodifica con MediaCodec y
     * satura los núcleos justo mientras la interfaz mide y dibuja la primera
     * pantalla, que es donde se veían los saltos.
     *
     * Así que esperan a que la pantalla esté puesta, y en el caso normal —una
     * biblioteca ya analizada— esto es una cuenta en SQLite y nada más.
     */
    private suspend fun maintenance() {
        kotlinx.coroutines.delay(SETTLE_MS)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // Recorre la biblioteca entera comprobando ficheros: una vez al día basta.
        // Las carátulas no desaparecen solas entre dos arranques.
        if (now - prefs.getLong("art_repaired_at", 0L) > DAY_MS) {
            runCatching { repository.repairMissingArt() }
                .onSuccess { prefs.edit().putLong("art_repaired_at", now).apply() }
                .onFailure { Log.w("PrivateMusicApp", "repairMissingArt failed", it) }
        }

        // v2: threshold went from -24dB to -18dB; recompute existing rows.
        if (prefs.getInt("tail_silence_v", 1) < 2) {
            repository.resetTailSilence()
            prefs.edit().putInt("tail_silence_v", 2).apply()
        }

        if (repository.pendingBackfills() == 0) return
        repository.backfillQuality()
        repository.backfillLoudness()
        repository.backfillAnalysis()
        repository.backfillTailSilence()
    }

    private companion object {
        /** Lo que tarda la primera pantalla en estar quieta. */
        const val SETTLE_MS = 3_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
