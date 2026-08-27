package com.aar.privatemusic.dsp

/** Convierte las unidades del Equalizer de Android en un filtro para el DSP propio. */
fun graphicEqFilter(
    centerFrequencyMilliHz: Int,
    lowerFrequencyMilliHz: Int,
    upperFrequencyMilliHz: Int,
    levelMilliBel: Short,
): EqFilter {
    val frequencyHz = (centerFrequencyMilliHz / 1000.0).coerceIn(1.0, 200_000.0)
    val bandwidthMilliHz = upperFrequencyMilliHz.toLong() - lowerFrequencyMilliHz.toLong()
    val q = if (bandwidthMilliHz > 0L) {
        centerFrequencyMilliHz.toDouble() / bandwidthMilliHz.toDouble()
    } else {
        DEFAULT_GRAPHIC_EQ_Q
    }.takeIf { it.isFinite() }?.coerceIn(MIN_GRAPHIC_EQ_Q, MAX_GRAPHIC_EQ_Q)
        ?: DEFAULT_GRAPHIC_EQ_Q

    return EqFilter(
        type = EqFilterType.PK,
        freqHz = frequencyHz,
        gainDb = levelMilliBel / 100.0,
        q = q,
    )
}

private const val MIN_GRAPHIC_EQ_Q = 0.1
private const val MAX_GRAPHIC_EQ_Q = 10.0
private const val DEFAULT_GRAPHIC_EQ_Q = 1.0
