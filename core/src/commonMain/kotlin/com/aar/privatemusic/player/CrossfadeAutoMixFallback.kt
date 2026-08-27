package com.aar.privatemusic.player

/** Decide si un fallo previo al handover merece un único reintento sin cambio de tempo. */
object CrossfadeAutoMixFallback {

    fun shouldRetryNeutral(
        phase: CrossfadeLifecycle.Phase,
        reason: CrossfadeLifecycle.CancelReason,
        autoMixApplied: Boolean,
        ratio: Float,
        retryAlreadyAttempted: Boolean,
    ): Boolean =
        phase in ELIGIBLE_PHASES &&
            reason in ELIGIBLE_REASONS &&
            autoMixApplied &&
            ratio.isFinite() &&
            ratio > 0f &&
            ratio != 1f &&
            !retryAlreadyAttempted

    private val ELIGIBLE_PHASES = setOf(
        CrossfadeLifecycle.Phase.PREARMING,
        CrossfadeLifecycle.Phase.ARMED,
        CrossfadeLifecycle.Phase.WARMING,
    )

    private val ELIGIBLE_REASONS = setOf(
        CrossfadeLifecycle.CancelReason.PREPARATION_FAILED,
        CrossfadeLifecycle.CancelReason.TAIL_NO_AUDIO,
        CrossfadeLifecycle.CancelReason.EXCESSIVE_DRIFT,
        CrossfadeLifecycle.CancelReason.TAIL_ERROR,
    )
}
