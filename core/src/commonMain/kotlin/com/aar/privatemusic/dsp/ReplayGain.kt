package com.aar.privatemusic.dsp

/** Utilidades para los valores de ReplayGain / R128 de los tags. */
object ReplayGain {
    /** "-6.35 dB" / "+3.2" / "-6.35 dB (measured)" -> -6.35; null si no parsea. */
    fun parseGainDb(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("""[-+]?\d+(?:\.\d+)?""").find(raw.trim()) ?: return null
        return m.value.toFloatOrNull()
    }

    /** R128_TRACK_GAIN es Q7.8 (dB * 256, entero con signo). */
    fun r128ToDb(q78: Int): Float = q78 / 256f

    /** dB de ganancia -> texto de tag ReplayGain estándar ("-6.35 dB"). */
    fun formatGainDb(db: Float): String {
        val rounded = (kotlin.math.round(db * 100f) / 100f)
        return "$rounded dB"
    }
}
