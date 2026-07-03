package com.aar.privatemusic.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.R
import com.aar.privatemusic.data.db.MusicDatabase

/**
 * Periodically checks watched playlists/channels and auto-downloads
 * anything new (YTDLnis-style "observe sources").
 */
class WatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? PrivateMusicApp ?: return Result.failure()
        val dao = MusicDatabase.get(applicationContext).musicDao()
        val sources = dao.watchedSourcesOnce()
        if (sources.isEmpty()) return Result.success()

        var newSongs = 0
        sources.forEach { source ->
            runCatching {
                if (SpotifyResolver.isSpotifyUrl(source.url)) {
                    newSongs += SpotifySync.sync(applicationContext, app.downloader, source)
                } else {
                    val (_, entries) = app.downloader.resolvePlaylist(source.url)
                    entries.forEach { entry ->
                        if (!dao.songExists(entry.id)) {
                            app.downloader.enqueue(entry, source.targetPlaylistId)
                            newSongs++
                        }
                    }
                }
                dao.touchWatchedSource(source.id, System.currentTimeMillis())
            }
        }

        if (newSongs > 0) notify(newSongs)
        return Result.success()
    }

    private fun notify(count: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Fuentes observadas", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("PrivateMusic")
            .setContentText("$count canciones nuevas descargándose de tus fuentes observadas")
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "watched_sources"
        const val NOTIFICATION_ID = 4210
        const val WORK_NAME = "watch_sources"
    }
}
