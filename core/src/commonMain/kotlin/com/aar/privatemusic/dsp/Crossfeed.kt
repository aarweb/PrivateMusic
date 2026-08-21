package com.aar.privatemusic.dsp

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Crossfeed para auriculares (estilo Bauer/BS2B): sangra a cada canal una copia
 * retardada y filtrada del canal opuesto, para que la escena estéreo no suene
 * "dentro de la cabeza". Passthrough si es mono o el nivel es 0.
 *
 * Nivel: 1 suave, 2 medio, 3 fuerte (más sangrado y filtro más bajo).
 */
class Crossfeed {
    private var sampleRate = 44100
    private var channels = 2
    private var level = 0
    private var bleed = 0.0
    private var lpCoef = 0.0
    private var delaySamples = 0

    // Línea de retardo circular por canal para el opuesto, y estado del paso-bajo.
    private var delayL = DoubleArray(1)
    private var delayR = DoubleArray(1)
    private var pos = 0
    private var lpL = 0.0
    private var lpR = 0.0

    fun configure(sampleRate: Int, channels: Int, level: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        this.level = level
        this.bleed = when (level) { 1 -> 0.3; 2 -> 0.5; 3 -> 0.7; else -> 0.0 }
        val cutoff = when (level) { 1 -> 900.0; 2 -> 700.0; else -> 500.0 }
        // Paso-bajo de un polo: y += coef*(x - y).
        val dt = 1.0 / sampleRate
        val rc = 1.0 / (2.0 * PI * cutoff)
        this.lpCoef = dt / (rc + dt)
        this.delaySamples = (0.0003 * sampleRate).roundToInt().coerceAtLeast(1)
        val n = delaySamples + 1
        delayL = DoubleArray(n); delayR = DoubleArray(n); pos = 0
        lpL = 0.0; lpR = 0.0
    }

    fun reset() {
        delayL.fill(0.0); delayR.fill(0.0); pos = 0; lpL = 0.0; lpR = 0.0
    }

    fun isActive(): Boolean = level > 0 && channels == 2

    fun process(buf: ShortArray, size: Int) {
        if (!isActive()) return
        val n = delayL.size
        val norm = 1.0 / (1.0 + bleed)
        var i = 0
        while (i + 1 < size) {
            val l = buf[i].toDouble()
            val r = buf[i + 1].toDouble()
            // Retardo del canal opuesto.
            val dl = delayL[pos] // L retardado (irá a R)
            val dr = delayR[pos] // R retardado (irá a L)
            delayL[pos] = l
            delayR[pos] = r
            pos = (pos + 1) % n
            // Paso-bajo sobre la señal cruzada.
            lpR += lpCoef * (dl - lpR)
            lpL += lpCoef * (dr - lpL)
            val outL = (l + bleed * lpL) * norm
            val outR = (r + bleed * lpR) * norm
            buf[i] = outL.roundToInt().coerceIn(-32768, 32767).toShort()
            buf[i + 1] = outR.roundToInt().coerceIn(-32768, 32767).toShort()
            i += 2
        }
    }
}
