package com.aar.privatemusic.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Qué hizo el AutoMix en la última transición preparada. Sin esto no había
 * forma de saber por qué una mezcla no cuadraba: el ajuste ocurre en un
 * servicio, dura unos segundos y sólo dejaba rastro en el logcat.
 *
 * Singleton por lo mismo que [SleepFade]: el servicio corre en el proceso de
 * la app.
 */
object MixInfoHolder {
    private val _info = MutableStateFlow<MixInfo?>(null)
    val info: StateFlow<MixInfo?> = _info

    fun publish(value: MixInfo?) {
        _info.value = value
    }
}

/**
 * @param bpmOut BPM de la canción que se va (null = sin analizar)
 * @param bpmIn BPM de la que entra
 * @param rawRatio el ajuste que haría falta para igualarlas
 * @param appliedRatio el que de verdad se aplicó (1 = ninguno)
 * @param enabled si el AutoMix estaba activado
 */
data class MixInfo(
    val bpmOut: Float?,
    val bpmIn: Float?,
    val rawRatio: Float,
    val appliedRatio: Float,
    val enabled: Boolean,
) {
    /** true si hubo que recortar el ajuste: los tempos no llegan a cuadrar. */
    val clamped: Boolean
        get() = enabled && bpmOut != null && bpmIn != null &&
            kotlin.math.abs(rawRatio - appliedRatio) > 0.001f

    /** Texto para la ficha técnica del reproductor; null si no hay nada que contar. */
    fun describe(): String? {
        if (!enabled) return null
        if (bpmOut == null || bpmIn == null) return "AutoMix: sin BPM todavía (analizando)"
        val out = bpmOut.toInt()
        val into = bpmIn.toInt()
        val pct = ((appliedRatio - 1f) * 100f)
        val adjust = if (kotlin.math.abs(pct) < 0.5f) {
            "sin ajuste"
        } else {
            val sign = if (pct > 0) "+" else "−"
            "$sign${kotlin.math.abs(pct).toInt()}%"
        }
        return if (clamped) {
            "AutoMix: $out → $into BPM · $adjust (máximo)"
        } else {
            "AutoMix: $out → $into BPM · $adjust"
        }
    }
}
