package com.aar.privatemusic.player

import kotlin.math.round

/** Política pura para alinear las dos copias de la pista saliente. */
object CrossfadeAlignment {

    const val RESEEK_THRESHOLD_MS = 60L
    const val FINAL_TOLERANCE_MS = 250L

    /**
     * Descuenta de [rawDriftMs] la separación de contenido causada a propósito
     * por reproducir el tail a [appliedTempoRatio].
     */
    fun realDriftMs(
        rawDriftMs: Long,
        tailPositionMs: Long,
        referenceTailPositionMs: Long,
        appliedTempoRatio: Float,
    ): Long {
        val ratio = appliedTempoRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        if (ratio == 1f) return rawDriftMs

        val advancedMs = saturatedPositiveDifference(tailPositionMs, referenceTailPositionMs)
        if (advancedMs == 0L) return rawDriftMs

        val intentionalDivergenceMs = saturatedRoundToLong(
            advancedMs.toDouble() * (1.0 - 1.0 / ratio.toDouble()),
        )
        return saturatedAdd(rawDriftMs, intentionalDivergenceMs)
    }

    fun shouldReseek(realDriftMs: Long): Boolean =
        exceedsTolerance(realDriftMs, RESEEK_THRESHOLD_MS)

    /** Adelanto con el que se compensa la latencia de reinicio del tail. */
    fun reseekAdvanceMs(realDriftMs: Long): Long =
        if (realDriftMs > 0L) saturatedAdd(realDriftMs, 40L) else 120L

    fun reseekTargetMs(mainPositionMs: Long, realDriftMs: Long): Long =
        saturatedAdd(mainPositionMs, reseekAdvanceMs(realDriftMs))

    fun isFinalAlignmentAcceptable(realDriftMs: Long): Boolean =
        !exceedsTolerance(realDriftMs, FINAL_TOLERANCE_MS)

    fun postReseekTimeoutMs(autoMix: Boolean): Long = if (autoMix) 1_200L else 600L

    private fun exceedsTolerance(value: Long, toleranceMs: Long): Boolean =
        value > toleranceMs || value < -toleranceMs

    private fun saturatedPositiveDifference(left: Long, right: Long): Long {
        if (left <= right) return 0L
        return if (right < 0L && left > Long.MAX_VALUE + right) {
            Long.MAX_VALUE
        } else {
            left - right
        }
    }

    private fun saturatedRoundToLong(value: Double): Long = when {
        value.isNaN() -> 0L
        value >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
        value <= Long.MIN_VALUE.toDouble() -> Long.MIN_VALUE
        else -> round(value).toLong()
    }

    private fun saturatedAdd(left: Long, right: Long): Long = when {
        right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
        right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
        else -> left + right
    }
}
