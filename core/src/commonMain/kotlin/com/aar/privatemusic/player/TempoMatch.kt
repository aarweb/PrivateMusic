package com.aar.privatemusic.player

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

const val TEMPO_MATCH_MAX_RESIDUAL_ERROR_PCT = 3f

/** Tolerancia explícita del 2 % en escala logarítmica alrededor de sqrt(2). */
val TEMPO_MATCH_AMBIGUITY_LOG_TOLERANCE: Double = ln(1.02)

data class TempoMatchDecision(
    val requiredRatio: Float,
    val limitedRatio: Float,
    val appliedRatio: Float,
    val comparableTargetBpm: Float?,
    val effectiveBpm: Float?,
    val residualErrorPct: Float?,
    val discardedReason: String?,
)

private data class TempoCandidate(val ratio: Float, val targetBpm: Float) {
    val distanceFromUnity: Double = abs(ln(ratio.toDouble()))
}

fun decideTempoMatch(bpmOut: Float?, bpmIn: Float?, maxStretch: Float): TempoMatchDecision {
    if (bpmOut == null || bpmIn == null ||
        !bpmOut.isFinite() || !bpmIn.isFinite() || bpmOut <= 0f || bpmIn <= 0f ||
        !maxStretch.isFinite() || maxStretch < 0f
    ) {
        return TempoMatchDecision(
            1f, 1f, 1f, null,
            bpmOut?.takeIf { it.isFinite() && it > 0f }, null,
            "datos inválidos",
        )
    }

    val raw = bpmIn / bpmOut
    if (!raw.isFinite() || raw <= 0f) {
        return TempoMatchDecision(1f, 1f, 1f, null, bpmOut, null, "datos inválidos")
    }

    val candidates = listOf(
        TempoCandidate(raw, bpmIn),
        TempoCandidate(raw * 2f, bpmIn * 2f),
        TempoCandidate(raw / 2f, bpmIn / 2f),
    ).filter { it.ratio.isFinite() && it.ratio > 0f && it.targetBpm.isFinite() }
        .sortedBy { it.distanceFromUnity }

    val best = candidates.first()
    val alternative = candidates.drop(1).firstOrNull {
        (it.ratio - 1f) * (best.ratio - 1f) < 0f
    }
    val oppositeLimits = alternative != null &&
        best.ratio !in (1f - maxStretch)..(1f + maxStretch) &&
        alternative.ratio !in (1f - maxStretch)..(1f + maxStretch)
    val ambiguous = oppositeLimits &&
        abs(best.distanceFromUnity - alternative!!.distanceFromUnity) <=
        TEMPO_MATCH_AMBIGUITY_LOG_TOLERANCE

    val limited = best.ratio.coerceIn(1f - maxStretch, 1f + maxStretch)
    if (ambiguous) {
        return decisionWithApplied(
            best.ratio, limited, 1f, bpmOut, best.targetBpm,
            "interpretación de tempo ambigua",
        )
    }

    val limitedError = errorPct(bpmOut * limited, best.targetBpm)
    val applied = if (limitedError > TEMPO_MATCH_MAX_RESIDUAL_ERROR_PCT) 1f else limited
    return decisionWithApplied(
        best.ratio, limited, applied, bpmOut, best.targetBpm,
        if (applied == 1f && limited != 1f) "error tras limitar superior al 3 %" else null,
    )
}

private fun decisionWithApplied(
    requiredRatio: Float,
    limitedRatio: Float,
    appliedRatio: Float,
    bpmOut: Float,
    targetBpm: Float,
    discardedReason: String?,
): TempoMatchDecision {
    val effective = bpmOut * appliedRatio
    return TempoMatchDecision(
        requiredRatio,
        limitedRatio,
        appliedRatio,
        targetBpm,
        effective,
        errorPct(effective, targetBpm),
        discardedReason,
    )
}

private fun errorPct(actual: Float, target: Float): Float = abs(actual - target) / target * 100f

/** Conserva la API usada por los clientes antiguos. */
fun chooseTempoRatio(bpmOut: Float?, bpmIn: Float?, maxStretch: Float): Float =
    decideTempoMatch(bpmOut, bpmIn, maxStretch).appliedRatio

/** Convierte duración de contenido en duración real al reproducir a [ratio]. */
fun contentDurationToWallMs(contentMs: Long, ratio: Float): Long {
    if (contentMs <= 0L) return 0L
    if (!ratio.isFinite() || ratio <= 0f) return contentMs
    val wallMs = contentMs.toDouble() / ratio.toDouble()
    return if (wallMs >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else wallMs.roundToLong()
}
