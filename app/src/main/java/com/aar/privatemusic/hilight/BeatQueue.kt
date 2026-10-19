package com.aar.privatemusic.hilight

import java.util.ArrayDeque

/** Renderer-thread timeline: bounded, no replay of expired attacks after seek/stall. */
internal class BeatQueue {
    private val events = ArrayDeque<Long>()
    val isNotEmpty: Boolean get() = events.isNotEmpty()
    val size: Int get() = events.size
    fun add(timeUs: Long) {
        if (events.size == 64) events.removeFirst()
        events.addLast(timeUs)
    }
    fun clear() = events.clear()
    fun takeDue(positionUs: Long): Boolean {
        var due = false
        while (events.isNotEmpty() && events.first <= positionUs) {
            if (positionUs - events.removeFirst() <= 150_000) due = true
        }
        return due
    }
}
