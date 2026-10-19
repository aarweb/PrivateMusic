package com.aar.privatemusic.hilight

import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.Keep
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs as shell only after the user's Shizuku grant. Never exported as an Android service. */
@Keep
class HiLightUserService : IHiLightService.Stub() {
    private var lease: HiLightLease? = null
    private var owner: IBinder? = null
    private val death = IBinder.DeathRecipient { synchronized(this) { release() } }
    private val watchdog = Executors.newSingleThreadScheduledExecutor().apply {
        scheduleWithFixedDelay({ synchronized(this@HiLightUserService) {
            runCatching { lease?.expire(SystemClock.elapsedRealtime()) }
        } }, 1, 1, TimeUnit.SECONDS)
    }

    @Synchronized
    override fun isSupported(): Boolean {
        if (Build.VERSION.SDK_INT < 37) return false
        if (!HiLightPolicy.isCandidate(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL)) return false
        if (lease != null) return true
        return runCatching {
            val output = AndroidHiLightOutput.privileged()
            if (!output.supported) false else { lease = HiLightLease(output); true }
        }.getOrDefault(false)
    }

    @Synchronized
    override fun pulse(client: IBinder) {
        check(isSupported())
        if (owner != client) {
            release()
            client.linkToDeath(death, 0)
            owner = client
        }
        check(client.isBinderAlive)
        requireNotNull(lease).renew(SystemClock.elapsedRealtime())
    }

    @Synchronized
    override fun stop() = release()

    private fun release() {
        try { lease?.stop() } finally {
            owner?.let { runCatching { it.unlinkToDeath(death, 0) } }
            owner = null
        }
    }

    @Synchronized
    override fun destroy() {
        runCatching { release() }
        watchdog.shutdownNow()
        kotlin.system.exitProcess(0)
    }
}
