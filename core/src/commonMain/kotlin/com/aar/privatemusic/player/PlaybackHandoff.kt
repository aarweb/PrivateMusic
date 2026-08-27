package com.aar.privatemusic.player

/** Owner-thread generation gate for asynchronous player handoffs. */
class PlaybackHandoff internal constructor(initialGeneration: Long) {
    constructor() : this(0L)

    class Ticket internal constructor(internal val generation: Long)

    private var generation = initialGeneration
    private var current: Ticket? = null

    fun begin(): Ticket {
        generation = when (generation) {
            Long.MAX_VALUE -> Long.MIN_VALUE
            -1L -> 1L // Zero is reserved for the initial state.
            else -> generation + 1L
        }
        return Ticket(generation).also { current = it }
    }

    /** Ticket identity also prevents an ancient ticket reviving after overflow. */
    fun isCurrent(ticket: Ticket): Boolean = current === ticket
}

data class ResolvedHandoffQueue<T>(
    val items: List<T>,
    val currentIndex: Int,
)

/** Drops unresolved entries without losing which item was current. */
fun <T : Any> resolveHandoffQueue(
    entries: List<T?>,
    requestedIndex: Int,
): ResolvedHandoffQueue<T>? {
    if (requestedIndex !in entries.indices || entries[requestedIndex] == null) return null
    return ResolvedHandoffQueue(
        items = entries.filterNotNull().toList(),
        currentIndex = entries.take(requestedIndex).count { it != null },
    )
}
