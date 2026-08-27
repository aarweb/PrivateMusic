package com.aar.privatemusic.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackHandoffTest {
    @Test
    fun successiveTicketsInvalidatePreviousOnes() {
        val handoff = PlaybackHandoff()
        val first = handoff.begin()
        assertTrue(handoff.isCurrent(first))

        val second = handoff.begin()
        assertFalse(handoff.isCurrent(first))
        assertTrue(handoff.isCurrent(second))
    }

    @Test
    fun manyBeginsLeaveOnlyTheLastTicketCurrent() {
        val handoff = PlaybackHandoff()
        val tickets = List(100_000) { handoff.begin() }

        tickets.dropLast(1).forEach { assertFalse(handoff.isCurrent(it)) }
        assertTrue(handoff.isCurrent(tickets.last()))
    }

    @Test
    fun overflowNeverRevivesAnOldTicket() {
        val handoff = PlaybackHandoff(Long.MAX_VALUE - 1L)
        val beforeOverflow = handoff.begin()
        val afterOverflow = handoff.begin()

        assertFalse(handoff.isCurrent(beforeOverflow))
        assertTrue(handoff.isCurrent(afterOverflow))
        assertEquals(Long.MIN_VALUE, afterOverflow.generation)
    }

    @Test
    fun completeQueueKeepsItemsAndIndex() {
        assertEquals(
            ResolvedHandoffQueue(listOf("a", "b", "c"), currentIndex = 1),
            resolveHandoffQueue(listOf("a", "b", "c"), requestedIndex = 1),
        )
    }

    @Test
    fun missingEntriesBeforeCurrentAdjustIndex() {
        assertEquals(
            ResolvedHandoffQueue(listOf("a", "current", "d"), currentIndex = 1),
            resolveHandoffQueue(listOf("a", null, "current", null, "d"), requestedIndex = 2),
        )
    }

    @Test
    fun missingEntriesAfterCurrentDoNotAdjustIndex() {
        assertEquals(
            ResolvedHandoffQueue(listOf("a", "current", "d"), currentIndex = 1),
            resolveHandoffQueue(listOf("a", "current", null, null, "d"), requestedIndex = 1),
        )
    }

    @Test
    fun missingCurrentFails() {
        assertNull(resolveHandoffQueue(listOf("a", null, "c"), requestedIndex = 1))
    }

    @Test
    fun invalidIndexAndEmptyQueueFail() {
        assertNull(resolveHandoffQueue<String>(emptyList(), requestedIndex = 0))
        assertNull(resolveHandoffQueue(listOf("a"), requestedIndex = -1))
        assertNull(resolveHandoffQueue(listOf("a"), requestedIndex = 1))
    }
}
