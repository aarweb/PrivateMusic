package com.aar.privatemusic.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Amplía una señal estéreo en M/S sin alterar el centro. Los graves del
 * canal lateral se conservan para mantener una imagen estable.
 */
class StereoWidener {
    private var sampleRate = 0
    private var channels = 0
    private var level = 0
    private var width = 1.0
    private var lowPassCoef = 0.0
    private var sideLow = 0.0

    fun configure(sampleRate: Int, channels: Int, level: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        this.level = level
        width = when (level) {
            1 -> 1.15
            2 -> 1.30
            3 -> 1.45
            else -> 1.0
        }
        lowPassCoef = if (sampleRate > 0) {
            val dt = 1.0 / sampleRate
            val rc = 1.0 / (2.0 * PI * 180.0)
            dt / (rc + dt)
        } else {
            0.0
        }
        reset()
    }

    fun reset() {
        sideLow = 0.0
    }

    fun isActive(): Boolean = sampleRate > 0 && channels == 2 && level in 1..3

    fun process(buf: ShortArray, size: Int) {
        if (!isActive()) return
        var i = 0
        while (i + 1 < size) {
            val left = buf[i].toDouble()
            val right = buf[i + 1].toDouble()
            val mid = (left + right) * 0.5
            val side = (left - right) * 0.5
            sideLow += lowPassCoef * (side - sideLow)
            val widenedSide = sideLow + width * (side - sideLow)
            var outLeft = mid + widenedSide
            var outRight = mid - widenedSide

            // Un factor común protege el pico sin desplazar la imagen estéreo.
            val peak = maxOf(abs(outLeft), abs(outRight))
            if (peak > Short.MAX_VALUE.toDouble()) {
                val scale = Short.MAX_VALUE.toDouble() / peak
                outLeft *= scale
                outRight *= scale
            }
            buf[i] = outLeft.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            buf[i + 1] = outRight.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            i += 2
        }
    }
}
