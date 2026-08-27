package com.aar.privatemusic.downloader

/** Política temporal pura para recuperar vídeos que faltan en la biblioteca. */
object VideoRetryPolicy {
    const val ENTRY_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
    const val MARKER_RETRY_INTERVAL_MS = 7L * 24L * 60L * 60L * 1_000L
    const val MAX_BATCH_SIZE = 3

    fun isEntryCheckDue(lastCheckedAt: Long, now: Long): Boolean =
        isDue(lastCheckedAt, now, ENTRY_CHECK_INTERVAL_MS)

    fun canRetryMarker(markerLastModified: Long, now: Long): Boolean =
        isDue(markerLastModified, now, MARKER_RETRY_INTERVAL_MS)

    private fun isDue(timestamp: Long, now: Long, interval: Long): Boolean {
        if (timestamp <= 0L || now <= 0L || now < timestamp) return true
        // Con ambos valores positivos y now >= timestamp, la resta no desborda.
        return now - timestamp >= interval
    }
}
