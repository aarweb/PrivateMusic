package com.aar.privatemusic.player

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aar.privatemusic.PrivateMusicApp
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Poweramp-style convenience: when a whitelisted Bluetooth audio device
 * connects (car, headphones), resume the queue — or start the daily mix
 * if there is nothing to resume.
 */
class BluetoothResumeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            intent.action != ACTION_TEST
        ) return
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("bt_autoplay", false)) return

        // Whitelist by MAC address; empty set = any device.
        val allowed = prefs.getStringSet("bt_autoplay_devices", emptySet()) ?: emptySet()
        if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED && allowed.isNotEmpty()) {
            @Suppress("DEPRECATION")
            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val address = device?.address ?: return
            if (address !in allowed) return
        }

        val pending = goAsync()
        // A receiver's context cannot bindService (ReceiverCallNotAllowedException);
        // the application context can.
        val appCtx = context.applicationContext
        val token = SessionToken(appCtx, ComponentName(appCtx, PlaybackService::class.java))
        val future = MediaController.Builder(appCtx, token).buildAsync()
        future.addListener({
            runCatching {
                val controller = future.get()
                if (controller.mediaItemCount > 0) {
                    controller.play()
                    controller.release()
                    pending.finish()
                } else {
                    // Nothing queued: fire up the daily mix.
                    val app = appCtx as? PrivateMusicApp
                    if (app == null) {
                        controller.release()
                        pending.finish()
                        return@runCatching
                    }
                    MainScope().launch(Dispatchers.Main) {
                        runCatching {
                            val mix = app.repository.buildDailyMix()
                            if (mix.isNotEmpty()) app.playerController.playQueue(mix, 0)
                        }
                        controller.release()
                        pending.finish()
                    }
                }
            }.onFailure { pending.finish() }
        }, MoreExecutors.directExecutor())
    }

    companion object {
        /** Manual trigger for testing without a physical BT connect. */
        const val ACTION_TEST = "com.aar.privatemusic.TEST_BT_RESUME"
    }
}
