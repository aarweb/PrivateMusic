package com.aar.privatemusic.hilight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeatQueueTest {
    @Test fun bufferedBeatWaitsForAudiblePositionAndOnlyFiresOnce() {
        val queue = BeatQueue()
        queue.add(500_000)
        assertFalse(queue.takeDue(250_000))
        assertTrue(queue.takeDue(510_000))
        assertFalse(queue.takeDue(520_000))
    }
    @Test fun stalledPlaybackDoesNotReplayOldBeats() {
        val queue = BeatQueue()
        queue.add(0)
        queue.add(200_000)
        assertFalse(queue.takeDue(1_000_000))
        assertEquals(0, queue.size)
    }
    @Test fun seekAndFlushDiscardPreviousTimeline() {
        val queue = BeatQueue()
        queue.add(500_000)
        queue.clear()
        assertFalse(queue.takeDue(500_000))
    }
    @Test fun boundedQueueCoalescesDueEvents() {
        val queue = BeatQueue()
        repeat(100) { queue.add(it * 1_000L) }
        assertEquals(64, queue.size)
        assertTrue(queue.takeDue(100_000))
        assertFalse(queue.isNotEmpty)
    }
}
