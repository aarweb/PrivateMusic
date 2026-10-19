package com.aar.privatemusic.hilight

import kotlin.math.PI
import kotlin.math.exp

/** Adaptive energy onsets, in 10 ms PCM windows. No microphone or fixed BPM timer. */
internal class BeatDetector(private val sampleRate: Int, private val channels: Int) {
    private val windowFrames = (sampleRate / 100).coerceAtLeast(1)
    private val bass = DoubleArray(channels)
    private val alpha = 1.0 - exp(-2.0 * PI * 180.0 / sampleRate)
    private var frames = 0
    private var energy = 0.0
    private var average = 0.0
    private var previous = 0.0
    private var lastBeatUs = Long.MIN_VALUE / 2

    fun sample(value: Double, channel: Int, timeUs: Long): Boolean {
        val v = if (value.isFinite()) value.coerceIn(-1.0, 1.0) else 0.0
        bass[channel] += alpha * (v - bass[channel])
        energy += 0.75 * bass[channel] * bass[channel] + 0.25 * v * v
        if (channel != channels - 1 || ++frames < windowFrames) return false
        val current = energy / (frames * channels)
        val onset = current > 0.0002 && current > average * 1.65 &&
            current > previous * 1.15 && timeUs - lastBeatUs >= 180_000
        average = average * 0.95 + current * 0.05
        previous = current
        energy = 0.0
        frames = 0
        if (onset) lastBeatUs = timeUs
        return onset
    }
}
