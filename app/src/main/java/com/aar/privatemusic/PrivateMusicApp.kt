package com.aar.privatemusic

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
import com.aar.privatemusic.downloader.TorrentDownloader
import com.aar.privatemusic.downloader.WatchWorker
import com.aar.privatemusic.downloader.YtDownloader
import com.aar.privatemusic.player.PlayerController
import com.aar.privatemusic.scrobble.ListenBrainz
import java.util.concurrent.TimeUnit
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
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
    lateinit var repository: MusicRepository
        private set
    lateinit var playerController: PlayerController
        private set

    override fun onCreate() {
        super.onCreate()
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
        } catch (e: Exception) {
            Log.e("PrivateMusicApp", "yt-dlp init failed", e)
        }
        val dao = MusicDatabase.get(this).musicDao()
        settings = AppSettings(this)
        downloader = YtDownloader(this, dao, appScope)
        torrents = TorrentDownloader(this, dao, appScope)
        repository = MusicRepository(dao, downloader)
        playerController = PlayerController(this) { songId ->
            appScope.launch {
                repository.recordPlay(songId)
                dao.getSong(songId)?.let {
                    ListenBrainz.submitListen(this@PrivateMusicApp, it.title, it.artist)
                }
            }
        }
        // Restore the last session's queue (paused) after a cold start.
        appScope.launch {
            playerController.savedQueue()?.let { saved ->
                val songs = saved.ids.mapNotNull { dao.getSong(it) }
                if (songs.isNotEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        playerController.restoreQueue(songs, saved.index, saved.positionMs)
                    }
                }
            }
        }
        appScope.launch {
            downloader.updateYtDlp() // YouTube breaks old extractors regularly
            downloader.resumePending() // finish downloads cut off by process death
            repository.backfillQuality()
            repository.backfillLoudness()
            repository.backfillAnalysis()
            // v2: threshold went from -24dB to -18dB; recompute existing rows.
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            if (prefs.getInt("tail_silence_v", 1) < 2) {
                repository.resetTailSilence()
                prefs.edit().putInt("tail_silence_v", 2).apply()
            }
            repository.backfillTailSilence()
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
}
