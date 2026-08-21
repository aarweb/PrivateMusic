package com.aar.privatemusic.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Tipos de filtro paramétrico soportados (los que usa AutoEQ). */
enum class EqFilterType { PK, LS, HS }

/** Un filtro paramétrico: tipo, frecuencia central (Hz), ganancia (dB) y Q. */
data class EqFilter(
    val type: EqFilterType,
    val freqHz: Double,
    val gainDb: Double,
    val q: Double,
)

/**
 * Biquad IIR (recetario de audio de Robert Bristow-Johnson), forma directa II
 * transpuesta. Coeficientes ya normalizados por a0. Cada instancia guarda su
 * propio estado, así que hace falta una por canal.
 */
class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun reset() { z1 = 0.0; z2 = 0.0 }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        /** Diseña un biquad para [filter] al ritmo de muestreo [sampleRate]. */
        fun design(filter: EqFilter, sampleRate: Int): Biquad {
            val a = 10.0.pow(filter.gainDb / 40.0)
            val w0 = 2.0 * PI * filter.freqHz.coerceIn(1.0, sampleRate / 2.0 - 1.0) / sampleRate
            val cw = cos(w0)
            val sw = sin(w0)
            val q = filter.q.coerceAtLeast(1e-4)
            val alpha = sw / (2.0 * q)
            val b0: Double; val b1: Double; val b2: Double
            val a0: Double; val a1: Double; val a2: Double
            when (filter.type) {
                EqFilterType.PK -> {
                    b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
                    a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
                }
                EqFilterType.LS -> {
                    val s = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cw + s)
                    b1 = 2 * a * ((a - 1) - (a + 1) * cw)
                    b2 = a * ((a + 1) - (a - 1) * cw - s)
                    a0 = (a + 1) + (a - 1) * cw + s
                    a1 = -2 * ((a - 1) + (a + 1) * cw)
                    a2 = (a + 1) + (a - 1) * cw - s
                }
                EqFilterType.HS -> {
                    val s = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cw + s)
                    b1 = -2 * a * ((a - 1) + (a + 1) * cw)
                    b2 = a * ((a + 1) + (a - 1) * cw - s)
                    a0 = (a + 1) - (a - 1) * cw + s
                    a1 = 2 * ((a - 1) - (a + 1) * cw)
                    a2 = (a + 1) - (a - 1) * cw - s
                }
            }
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}
