package com.aar.privatemusic.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

object MixInfoHolder {
    private val _info = MutableStateFlow<MixInfo?>(null)
    val info: StateFlow<MixInfo?> = _info

    fun publish(value: MixInfo?) {
        _info.value = value
    }
}

data class MixInfo(
    val bpmOut: Float?,
    val bpmIn: Float?,
    val decision: TempoMatchDecision,
    val enabled: Boolean,
) {
    val requiredRatio: Float get() = decision.requiredRatio
    val appliedRatio: Float get() = if (enabled) decision.appliedRatio else 1f

    fun describe(): String? {
        if (!enabled) return null
        if (bpmOut == null || bpmIn == null || decision.comparableTargetBpm == null) {
            return "AutoMix: sin BPM válido"
        }
        val result = decision.effectiveBpm?.let { "${it.roundToInt()} BPM" } ?: "sin resultado"
        val error = decision.residualErrorPct?.let { "${formatOneDecimal(it)}% error" } ?: "error desconocido"
        val discarded = decision.discardedReason?.let { " · descartado: $it" }.orEmpty()
        return "AutoMix: ${bpmOut.roundToInt()} → ${bpmIn.roundToInt()} BPM · " +
            "requerido ${signedPct(requiredRatio)} · aplicado ${signedPct(appliedRatio)} · " +
            "resultado $result ($error)$discarded"
    }

    private fun signedPct(ratio: Float): String {
        val pct = (ratio - 1f) * 100f
        if (abs(pct) < 0.05f) return "0%"
        val sign = if (pct > 0f) "+" else "−"
        return "$sign${formatOneDecimal(abs(pct))}%"
    }

    private fun formatOneDecimal(value: Float): String =
        ((value * 10f).roundToInt() / 10f).toString()
}
