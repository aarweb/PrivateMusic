package com.aar.privatemusic.metadata

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Límite global de MusicBrainz para toda la aplicación.
 *
 * El cerrojo también conserva el orden mientras se hace la petición. Es una
 * serialización deliberada: evita que una reanudación de corutina permita que
 * la siguiente llamada empiece antes que la que acababa de reservar turno.
 */
object MusicBrainzRateLimiter {
    private const val MIN_INTERVAL_MS = 1_100L
    private val mutex = Mutex()
    private var lastStartNanos = 0L

    suspend fun <T> run(block: () -> T): T = mutex.withLock {
        val now = System.nanoTime()
        if (lastStartNanos != 0L) {
            val elapsedMs = (now - lastStartNanos) / 1_000_000L
            if (elapsedMs < MIN_INTERVAL_MS) delay(MIN_INTERVAL_MS - elapsedMs)
        }
        lastStartNanos = System.nanoTime()
        block()
    }
}
