package com.aar.privatemusic.hilight

internal object HiLightPolicy {
    const val API = 37
    const val LEASE_MS = 2_500L

    fun isCandidate(sdk: Int, manufacturer: String, model: String): Boolean =
        sdk >= API && manufacturer.equals("Google", ignoreCase = true) &&
            model in setOf("Pixel 11 Pro", "Pixel 11 Pro XL", "Pixel 11 Pro Fold")

    fun shouldIlluminate(enabled: Boolean, localPlaying: Boolean, ready: Boolean): Boolean =
        enabled && localPlaying && ready

    fun localPlayback(wantsPlay: Boolean, mainPlaying: Boolean, tailPlaying: Boolean, cast: Boolean, destroyed: Boolean): Boolean =
        !destroyed && !cast && wantsPlay && (mainPlaying || tailPlaying)
}

/** A finite effect is renewed only while local audio is playing. Never loop in hardware. */
internal interface HiLightOutput {
    fun pulse()
    fun stop()
}

internal class HiLightLease(private val output: HiLightOutput) {
    private var lastRenewal: Long? = null

    fun renew(now: Long) {
        // Arm cleanup before the transaction: a failed reply may still have changed hardware.
        lastRenewal = now
        try {
            output.pulse()
        } catch (error: Exception) {
            runCatching { stop() }
            throw error
        }
    }

    fun expire(now: Long) {
        if (lastRenewal?.let { now - it >= HiLightPolicy.LEASE_MS } == true) stop()
    }

    fun stop() {
        if (lastRenewal == null) return
        output.stop()
        lastRenewal = null
    }
}
