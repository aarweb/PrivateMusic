package com.aar.privatemusic.player

import com.aar.privatemusic.player.PlaybackErrorRecovery.Action
import com.aar.privatemusic.player.PlaybackErrorRecovery.ErrorType
import com.aar.privatemusic.player.PlaybackErrorRecovery.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlaybackErrorRecoveryTest {
    private fun event(
        trackId: String?,
        type: ErrorType,
        queueIds: List<String> = listOf("a", "b", "c"),
        currentIndex: Int = 0,
        allowWrap: Boolean = false,
    ) = Event(trackId, type, queueIds, currentIndex, allowWrap)

    private fun assertDoesNotChooseCurrent(action: Action, currentIndex: Int) {
        if (action is Action.SKIP_TO) assertNotEquals(currentIndex, action.index)
    }

    @Test
    fun firstTransientRetriesCurrentAndSecondMarksItFailed() {
        val recovery = PlaybackErrorRecovery()

        assertEquals(Action.RETRY_CURRENT, recovery.onError(event("a", ErrorType.TRANSIENT)))
        val second = recovery.onError(event("a", ErrorType.TRANSIENT))

        assertEquals(Action.SKIP_TO(1), second)
        assertDoesNotChooseCurrent(second, 0)
    }

    @Test
    fun permanentErrorMarksTrackFailedImmediately() {
        val action = PlaybackErrorRecovery().onError(event("a", ErrorType.PERMANENT))

        assertEquals(Action.SKIP_TO(1), action)
        assertDoesNotChooseCurrent(action, 0)
    }

    @Test
    fun nullTrackIdIsNeverRetried() {
        val action = PlaybackErrorRecovery().onError(event(null, ErrorType.TRANSIENT))

        assertEquals(Action.SKIP_TO(1), action)
        assertDoesNotChooseCurrent(action, 0)
    }

    @Test
    fun skipsToImmediateNextHealthyTrack() {
        val action = PlaybackErrorRecovery().onError(
            event("b", ErrorType.PERMANENT, currentIndex = 1),
        )

        assertEquals(Action.SKIP_TO(2), action)
        assertDoesNotChooseCurrent(action, 1)
    }

    @Test
    fun skipsSeveralPreviouslyFailedTracks() {
        val recovery = PlaybackErrorRecovery()
        val queue = listOf("a", "b", "c", "d")
        recovery.onError(event("b", ErrorType.PERMANENT, queue, currentIndex = 1))
        recovery.onError(event("c", ErrorType.PERMANENT, queue, currentIndex = 2))

        val action = recovery.onError(event("a", ErrorType.PERMANENT, queue))

        assertEquals(Action.SKIP_TO(3), action)
        assertDoesNotChooseCurrent(action, 0)
    }

    @Test
    fun skipsEveryDuplicateOfFailedId() {
        val recovery = PlaybackErrorRecovery()
        val queue = listOf("bad", "bad", "good", "bad")

        val action = recovery.onError(event("bad", ErrorType.PERMANENT, queue))

        assertEquals(Action.SKIP_TO(2), action)
        assertDoesNotChooseCurrent(action, 0)
    }

    @Test
    fun wrapsForwardExactlyOnceWhenAllowed() {
        val recovery = PlaybackErrorRecovery()
        val queue = listOf("good", "failed", "current")
        recovery.onError(event("failed", ErrorType.PERMANENT, queue, currentIndex = 1))

        val action = recovery.onError(
            event("current", ErrorType.PERMANENT, queue, currentIndex = 2, allowWrap = true),
        )

        assertEquals(Action.SKIP_TO(0), action)
        assertDoesNotChooseCurrent(action, 2)
    }

    @Test
    fun doesNotWrapWhenDisabled() {
        val action = PlaybackErrorRecovery().onError(
            event("c", ErrorType.PERMANENT, currentIndex = 2, allowWrap = false),
        )

        assertEquals(Action.STOP, action)
    }

    @Test
    fun repeatOneIsEquivalentToNoWrap() {
        val action = PlaybackErrorRecovery().onError(
            event("c", ErrorType.PERMANENT, currentIndex = 2, allowWrap = false),
        )

        assertEquals(Action.STOP, action)
    }

    @Test
    fun stopsWhenAllTracksAreFailed() {
        val recovery = PlaybackErrorRecovery()
        val queue = listOf("a", "b", "c")
        recovery.onError(event("a", ErrorType.PERMANENT, queue, currentIndex = 0))
        recovery.onError(event("b", ErrorType.PERMANENT, queue, currentIndex = 1))

        val action = recovery.onError(
            event("c", ErrorType.PERMANENT, queue, currentIndex = 2, allowWrap = true),
        )

        assertEquals(Action.STOP, action)
    }

    @Test
    fun invalidIndicesStop() {
        val recovery = PlaybackErrorRecovery()

        assertEquals(Action.STOP, recovery.onError(event("a", ErrorType.TRANSIENT, currentIndex = -1)))
        assertEquals(Action.STOP, recovery.onError(event("a", ErrorType.TRANSIENT, currentIndex = 3)))
    }

    @Test
    fun permanentErrorRemainsFailedEvenWhenItsOriginalIndexWasInvalid() {
        val recovery = PlaybackErrorRecovery()
        recovery.onError(event("bad", ErrorType.PERMANENT, currentIndex = -1))

        val action = recovery.onError(
            event("current", ErrorType.PERMANENT, queueIds = listOf("current", "bad", "good")),
        )

        assertEquals(Action.SKIP_TO(2), action)
        assertDoesNotChooseCurrent(action, 0)
    }

    @Test
    fun emptyQueueStops() {
        assertEquals(
            Action.STOP,
            PlaybackErrorRecovery().onError(
                event("a", ErrorType.TRANSIENT, queueIds = emptyList(), currentIndex = 0),
            ),
        )
    }

    @Test
    fun singleTrackRetriesOnceThenStopsEvenWithWrap() {
        val recovery = PlaybackErrorRecovery()
        val only = event(
            "only",
            ErrorType.TRANSIENT,
            queueIds = listOf("only"),
            allowWrap = true,
        )

        assertEquals(Action.RETRY_CURRENT, recovery.onError(only))
        assertEquals(Action.STOP, recovery.onError(only))
    }

    @Test
    fun skipActionNeverReturnsCurrentIndex() {
        for (queueSize in 1..6) {
            val queue = List(queueSize) { "track-$it" }
            for (currentIndex in queue.indices) {
                for (allowWrap in listOf(false, true)) {
                    val action = PlaybackErrorRecovery().onError(
                        event(
                            trackId = queue[currentIndex],
                            type = ErrorType.PERMANENT,
                            queueIds = queue,
                            currentIndex = currentIndex,
                            allowWrap = allowWrap,
                        ),
                    )
                    assertDoesNotChooseCurrent(action, currentIndex)
                }
            }
        }
    }

    @Test
    fun resetIsOnlyExplicitWayToRestoreRetryBudget() {
        val recovery = PlaybackErrorRecovery()
        val failure = event("a", ErrorType.TRANSIENT)
        recovery.onError(failure)
        recovery.onError(failure)

        recovery.reset()

        assertEquals(Action.RETRY_CURRENT, recovery.onError(failure))
    }
}
