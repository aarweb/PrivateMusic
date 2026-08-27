package com.aar.privatemusic.player

import kotlin.math.pow

/** Pure volume-normalization policy shared by every playback implementation. */
object PlaybackGain {
    const val TARGET_LOUDNESS_DB = -14f
    const val MIN_GAIN_DB = -12f

    enum class Mode {
        RMS,
        REPLAY_GAIN,
    }

    /** Everything that can change the cached gain for the current track. */
    data class ConfigKey(
        val trackId: String?,
        val enabled: Boolean,
        val mode: Mode,
    )

    fun normalizeMode(mode: String?): Mode =
        if (mode?.trim()?.lowercase() == "replaygain") Mode.REPLAY_GAIN else Mode.RMS

    fun configKey(trackId: String?, enabled: Boolean, mode: String?): ConfigKey =
        ConfigKey(trackId, enabled, normalizeMode(mode))

    /**
     * Returns a linear gain in 0..1. ReplayGain values are already gain values
     * in dB; measured RMS values are converted relative to [targetDb].
     */
    fun gain(
        loudnessDb: Float?,
        replayGainDb: Float?,
        enabled: Boolean,
        mode: String?,
        targetDb: Float = TARGET_LOUDNESS_DB,
        minGainDb: Float = MIN_GAIN_DB,
    ): Float {
        if (!enabled) return 1f
        if (!targetDb.isFinite() || !minGainDb.isFinite()) return 1f

        val gainDb = when (normalizeMode(mode)) {
            Mode.REPLAY_GAIN -> replayGainDb?.takeIf { it.isFinite() }
                ?: loudnessDb?.takeIf { it.isFinite() }?.let { targetDb - it }
            Mode.RMS -> loudnessDb?.takeIf { it.isFinite() }?.let { targetDb - it }
        } ?: return 1f

        val lowerBound = minGainDb.coerceAtMost(0f)
        val linear = 10.0.pow(gainDb.coerceIn(lowerBound, 0f) / 20.0).toFloat()
        return linear.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    }
}
