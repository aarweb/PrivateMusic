package com.aar.privatemusic.player

/** Política pura que espera a que la pista entrante produzca audio real. */
class CrossfadeStartGuard(initialWallFadeMs: Long) {

    val waitBudgetMs: Long = if (initialWallFadeMs <= MIN_TAIL_WALL_MS) {
        0L
    } else {
        minOf(MAX_WAIT_MS, initialWallFadeMs - MIN_TAIL_WALL_MS)
    }

    data class Input(
        val expectedMainTrackId: String?,
        val observedMainTrackId: String?,
        val playWhenReady: Boolean,
        val incomingAudioTrackId: String?,
        val tailError: Boolean,
        val elapsedWaitingMs: Long,
        val remainingTailWallMs: Long,
    )

    sealed interface Decision {
        data object WAIT : Decision
        data class START(val fadeMs: Long) : Decision
        data object ABORT : Decision
    }

    fun evaluate(input: Input): Decision {
        val expectedId = input.expectedMainTrackId
        if (expectedId == null || input.observedMainTrackId != expectedId) return Decision.ABORT
        if (!input.playWhenReady || input.tailError) return Decision.ABORT

        if (input.incomingAudioTrackId == expectedId) {
            return if (input.remainingTailWallMs >= MIN_TAIL_WALL_MS) {
                Decision.START(input.remainingTailWallMs)
            } else {
                Decision.ABORT
            }
        }

        return if (
            input.elapsedWaitingMs >= 0L &&
            input.elapsedWaitingMs < waitBudgetMs &&
            input.remainingTailWallMs > MIN_TAIL_WALL_MS
        ) {
            Decision.WAIT
        } else {
            Decision.ABORT
        }
    }

    private companion object {
        const val MIN_TAIL_WALL_MS = 500L
        const val MAX_WAIT_MS = 2_500L
    }
}
