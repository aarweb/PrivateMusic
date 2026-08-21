package com.aar.privatemusic.dsp

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Ecualizador paramétrico: un preamplificador (dB) y una cascada de filtros
 * [EqFilter], aplicados por canal sobre audio PCM 16-bit entrelazado. Guarda el
 * estado de los biquads, así que una instancia sirve para un único flujo de
 * audio; reconstruye la cascada cuando cambian la config o el formato.
 */
class ParametricEq {
    private var sampleRate = 0
    private var channels = 0
    private var preampGain = 1.0
    // [canal][filtro]
    private var chains: Array<Array<Biquad>> = emptyArray()
    private var active = false

    /** Recoloca la cascada. [filters] vacío o preamp 0 con lista vacía => passthrough. */
    fun configure(sampleRate: Int, channels: Int, preampDb: Double, filters: List<EqFilter>) {
        this.sampleRate = sampleRate
        this.channels = channels
        this.preampGain = 10.0.pow(preampDb / 20.0)
        chains = Array(channels) { Array(filters.size) { i -> Biquad.design(filters[i], sampleRate) } }
        active = filters.isNotEmpty() || preampDb != 0.0
    }

    fun reset() = chains.forEach { chain -> chain.forEach { it.reset() } }

    fun isActive(): Boolean = active

    /** Filtra [buf] (primeros [size] shorts, entrelazado por canal) en el sitio. */
    fun process(buf: ShortArray, size: Int) {
        if (!active || channels == 0) return
        var i = 0
        while (i < size) {
            for (c in 0 until channels) {
                if (i + c >= size) break
                var x = buf[i + c] * preampGain
                val chain = chains[c]
                for (f in chain.indices) x = chain[f].process(x)
                buf[i + c] = x.roundToInt().coerceIn(-32768, 32767).toShort()
            }
            i += channels
        }
    }
}
