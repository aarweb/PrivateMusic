package com.aar.privatemusic.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aar.privatemusic.MainActivity
import com.aar.privatemusic.PrivateMusicApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that shows an ongoing "downloading" notification while
 * YtDownloader has work: current title, how many are left, live progress and a
 * "Cancelar" action that stops the whole queue. Stops itself when the queue
 * empties. Started (best-effort) from [YtDownloader.enqueue].
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Must post the FGS notification promptly after startForegroundService.
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(null, 0f, 1, indeterminate = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val downloader = (applicationContext as PrivateMusicApp).downloader
        scope.launch {
            downloader.downloads.collectLatest {
                val active = downloader.activeCount()
                if (active == 0) {
                    stopSelf()
                } else {
                    val cur = downloader.currentDownloading()
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(
                        NOTIF_ID,
                        buildNotification(cur?.first, cur?.second ?: 0f, active, indeterminate = cur == null),
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            (applicationContext as PrivateMusicApp).downloader.cancelAll()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(
        title: String?,
        progress: Float,
        active: Int,
        indeterminate: Boolean,
    ): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (active > 1) "Quedan $active en la cola" else "1 descarga"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title ?: "Descargando música")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, progress.toInt(), indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancel)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Descargas", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIF_ID = 4211
        const val ACTION_CANCEL_ALL = "com.aar.privatemusic.action.CANCEL_DOWNLOADS"

        /** Starts the foreground service; safe to call repeatedly and off the main thread. */
        fun ensureRunning(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, DownloadService::class.java),
                )
            }
        }
    }
}
