package com.aar.privatemusic.util

/** Display data kept free of Android dependencies for deterministic policy tests. */
data class RefreshMode(
    val width: Int,
    val height: Int,
    val refreshRate: Float,
    val alternativeRefreshRates: List<Float> = emptyList(),
)

object RefreshRatePolicy {
    fun fastestAtCurrentResolution(current: RefreshMode?, supported: List<RefreshMode>): Float? {
        if (current == null || current.width <= 0 || current.height <= 0) return null
        return supported.asSequence()
            .filter { it.width == current.width && it.height == current.height }
            .flatMap { sequenceOf(it.refreshRate) + it.alternativeRefreshRates.asSequence() }
            .filter { it.isFinite() && it > 0f }
            .maxOrNull()
    }
}

/** Owns independent list leases and restores the window's pre-existing preference. */
class RefreshRateRequests(
    private val original: Float,
    private val write: (Float) -> Unit,
) {
    private val leases = mutableSetOf<Any>()
    private var resumed = false
    private var powerSaver = false
    private var maximum: Float? = null
    private var applied = original
    private var closed = false

    fun acquire(token: Any) { if (!closed && leases.add(token)) update() }
    fun release(token: Any) { if (leases.remove(token)) update() }
    fun setResumed(value: Boolean) { resumed = value; update() }
    fun setPowerSaver(value: Boolean) { powerSaver = value; update() }
    fun setMaximum(value: Float?) { maximum = value?.takeIf { it.isFinite() && it > 0f }; update() }
    fun close() {
        if (closed) return
        closed = true
        leases.clear()
        update()
    }

    private fun update() {
        val desired = maximum?.takeIf { !closed && resumed && !powerSaver && leases.isNotEmpty() }
            ?: original
        if (desired != applied) {
            write(desired)
            applied = desired
        }
    }
}
