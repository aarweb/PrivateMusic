package com.aar.privatemusic.dsp

/**
 * Afina la posición de un pico entre dos muestras vecinas ajustando una
 * parábola por los tres puntos. Devuelve el desplazamiento respecto al máximo
 * entero, en [-0.5, 0.5].
 *
 * La autocorrelación del tempo se evalúa en retardos enteros de frame, y a 43
 * frames/s eso cuantiza el BPM: entre dos retardos contiguos hay ~1,8% abajo
 * (76,0 → 78,3 BPM) y hasta un 6% arriba (152 → 161,5). Ese error se comía
 * medio presupuesto del ajuste de tempo del AutoMix; el vértice de la parábola
 * lo recupera casi entero y sale gratis.
 */
fun parabolicPeakOffset(prev: Float, peak: Float, next: Float): Float {
    val denom = prev - 2f * peak + next
    // Sin curvatura (meseta) o con curvatura hacia arriba: no hay vértice fiable.
    if (denom >= 0f || !denom.isFinite()) return 0f
    val offset = 0.5f * (prev - next) / denom
    return if (offset.isFinite()) offset.coerceIn(-0.5f, 0.5f) else 0f
}
