package com.aar.privatemusic.player

/**
 * Deterministic, owner-thread policy for recovering playback errors.
 *
 * State deliberately survives successful playback transitions: clearing it there would allow a
 * bad item (or one of its duplicates) to create an endless repeat loop.
 */
class PlaybackErrorRecovery {
    enum class ErrorType {
        TRANSIENT,
        PERMANENT,
    }

    data class Event(
        val trackId: String?,
        val type: ErrorType,
        val queueIds: List<String>,
        val currentIndex: Int,
        val allowWrap: Boolean,
    )

    sealed interface Action {
        data object RETRY_CURRENT : Action
        data class SKIP_TO(val index: Int) : Action
        data object STOP : Action
    }

    private val retriedTrackIds = mutableSetOf<String>()
    private val failedTrackIds = mutableSetOf<String>()

    fun onError(event: Event): Action {
        val trackId = event.trackId
        if (event.type == ErrorType.PERMANENT && trackId != null) {
            failedTrackIds += trackId
        }
        if (event.queueIds.isEmpty() || event.currentIndex !in event.queueIds.indices) {
            return Action.STOP
        }

        if (
            event.type == ErrorType.TRANSIENT &&
            trackId != null &&
            trackId !in retriedTrackIds &&
            trackId !in failedTrackIds
        ) {
            retriedTrackIds += trackId
            return Action.RETRY_CURRENT
        }

        if (event.type == ErrorType.TRANSIENT && trackId != null) {
            failedTrackIds += trackId
        }

        val candidateCount = if (event.allowWrap) {
            event.queueIds.size - 1
        } else {
            event.queueIds.lastIndex - event.currentIndex
        }
        for (offset in 1..candidateCount) {
            val index = if (event.allowWrap) {
                (event.currentIndex + offset) % event.queueIds.size
            } else {
                event.currentIndex + offset
            }
            if (event.queueIds[index] !in failedTrackIds) {
                return Action.SKIP_TO(index)
            }
        }
        return Action.STOP
    }

    /** Intended for owner teardown only. */
    fun reset() {
        retriedTrackIds.clear()
        failedTrackIds.clear()
    }
}
