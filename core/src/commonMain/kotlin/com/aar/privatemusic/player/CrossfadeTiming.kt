package com.aar.privatemusic.player

/** Temporización pura de la preparación técnica y la ventana audible. */
object CrossfadeTiming {

    data class MusicalEndPlan(
        val effectiveDurationMs: Long,
        val appliedTailSilenceMs: Long,
    )

    sealed interface AudibleStartDecision {
        data object WAIT : AudibleStartDecision
        data class START(val durationMs: Long) : AudibleStartDecision
        data object ABORT : AudibleStartDecision
    }

    fun planMusicalEnd(
        durationMs: Long,
        tailSilenceMs: Long,
        configuredCrossfadeMs: Long,
    ): MusicalEndPlan {
        val duration = durationMs.positiveOrZero()
        if (duration == 0L) return MusicalEndPlan(0L, 0L)

        val removableDurationMs = duration - 1L
        val appliedTailSilenceMs = minOf(
            tailSilenceMs.positiveOrZero(),
            configuredCrossfadeMs.positiveOrZero() / 2L,
            removableDurationMs,
        )
        return MusicalEndPlan(
            effectiveDurationMs = duration - appliedTailSilenceMs,
            appliedTailSilenceMs = appliedTailSilenceMs,
        )
    }

    fun decideAudibleStart(
        configuredCrossfadeMs: Long,
        realRemainingMs: Long,
    ): AudibleStartDecision {
        val configuredMs = configuredCrossfadeMs.positiveOrZero()
        if (configuredMs == 0L || realRemainingMs <= 0L) {
            return AudibleStartDecision.ABORT
        }
        if (realRemainingMs > configuredMs) return AudibleStartDecision.WAIT

        // Si la latencia agota la reserva, sólo se acorta al restante real;
        // nunca se prolonga el fundido más allá del final musical.
        return AudibleStartDecision.START(minOf(configuredMs, realRemainingMs))
    }

    fun startupReserveMs(configuredCrossfadeMs: Long, autoMix: Boolean): Long {
        val configuredMs = configuredCrossfadeMs.positiveOrZero()
        val warmupMs = maxOf(if (autoMix) 2_600L else 1_500L, configuredMs / 2L)
        val requiredMs = saturatedPositiveSum(
            1_200L,
            500L,
            warmupMs,
            120L,
            CrossfadeAlignment.postReseekTimeoutMs(autoMix),
            120L,
            80L,
            120L,
            2_500L,
        )
        val reserveLimitMs = minOf(MAX_STARTUP_RESERVE_MS, saturatedDouble(configuredMs))
        return minOf(requiredMs, reserveLimitMs)
    }

    fun technicalTriggerMs(configuredCrossfadeMs: Long, autoMix: Boolean): Long {
        val configuredMs = configuredCrossfadeMs.positiveOrZero()
        return saturatedAdd(configuredMs, startupReserveMs(configuredMs, autoMix))
    }

    private fun Long.positiveOrZero(): Long = if (this > 0L) this else 0L

    private fun saturatedDouble(value: Long): Long =
        if (value > Long.MAX_VALUE / 2L) Long.MAX_VALUE else value * 2L

    private fun saturatedPositiveSum(vararg values: Long): Long =
        values.fold(0L, ::saturatedAdd)

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private const val MAX_STARTUP_RESERVE_MS = 12_000L
}
