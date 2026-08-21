package com.aar.privatemusic.player

import kotlin.math.abs
import kotlin.math.ln

/**
 * Tempo del AutoMix: cuánto hay que estirar o encoger la canción SALIENTE para
 * que su pulso cuadre con el de la entrante.
 *
 * Dos temas a 150 y 75 BPM son perfectamente mezclables (uno va a doble tiempo
 * del otro), pero el cociente crudo da 0,5: antes se recortaba a 0,9 y salía un
 * frenazo absurdo que ni igualaba ni respetaba la relación 2:1. Aquí se prueban
 * también el doble y la mitad y gana el más suave.
 *
 * La comparación va en escala logarítmica a propósito: en lineal, "×0,679" (a
 * 0,321 de 1) parece más suave que "×1,357" (a 0,357), cuando musicalmente son
 * el mismo salto en direcciones opuestas — y con el clamp, elegir mal deja el
 * tempo en el extremo contrario del correcto.
 */
fun chooseTempoRatio(bpmOut: Float?, bpmIn: Float?, maxStretch: Float): Float {
    if (bpmOut == null || bpmIn == null || bpmOut <= 0f || bpmIn <= 0f) return 1f
    val raw = bpmIn / bpmOut
    if (!raw.isFinite() || raw <= 0f) return 1f
    val best = listOf(raw, raw * 2f, raw / 2f).minBy { abs(ln(it.toDouble())) }
    return best.coerceIn(1f - maxStretch, 1f + maxStretch)
}
