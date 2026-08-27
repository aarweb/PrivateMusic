package com.aar.privatemusic.dsp

import kotlin.math.log10
import kotlin.math.sqrt

/** Incremental RMS measurement for interleaved signed PCM16 frames. */
class PcmRmsAccumulator {
    private var sumSquares = 0.0
    private var sampleCount = 0L
    private var channelOffset = 0
    private var framePhase = 0
    private var configuredChannelCount: Int? = null
    private var configuredFrameStride: Int? = null

    /**
     * Adds at most [length] samples. One frame out of every [frameStride] is
     * sampled, starting with the first, and every channel in that frame is
     * included. Calls belonging to one stream must keep [channelCount] and
     * [frameStride] unchanged; mismatching calls are ignored.
     *
     * The position within both the interleaved frame and the sampling stride is
     * retained, so chunks may end at any sample, including in the middle of a
     * frame.
     */
    fun add(
        samples: ShortArray,
        length: Int,
        channelCount: Int,
        frameStride: Int = 4,
    ) {
        if (length <= 0 || channelCount <= 0 || frameStride <= 0 || samples.isEmpty()) return
        val limit = minOf(length, samples.size)
        if (limit <= 0) return

        val channels = configuredChannelCount
        val stride = configuredFrameStride
        if (channels != null && (channels != channelCount || stride != frameStride)) return
        configuredChannelCount = channelCount
        configuredFrameStride = frameStride

        for (index in 0 until limit) {
            if (framePhase == 0) {
                val normalized = samples[index].toDouble() / PCM16_SCALE
                sumSquares += normalized * normalized
                if (sampleCount < Long.MAX_VALUE) sampleCount++
            }

            channelOffset++
            if (channelOffset == channelCount) {
                channelOffset = 0
                framePhase++
                if (framePhase == frameStride) framePhase = 0
            }
        }
    }

    /** RMS in dBFS, or null when no sampled non-silent signal exists. */
    fun rmsDb(): Float? {
        if (sampleCount == 0L || sumSquares <= 0.0 || !sumSquares.isFinite()) return null
        val rms = sqrt(sumSquares / sampleCount.toDouble())
        if (rms <= 0.0 || !rms.isFinite()) return null
        return (20.0 * log10(rms)).toFloat().takeIf { it.isFinite() }
    }

    private companion object {
        const val PCM16_SCALE = 32768.0
    }
}
