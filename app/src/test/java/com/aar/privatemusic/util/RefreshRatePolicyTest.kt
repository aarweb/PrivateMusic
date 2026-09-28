package com.aar.privatemusic.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RefreshRatePolicyTest {
    @Test fun `selects fastest valid rate only at current resolution`() {
        val current = RefreshMode(1080, 2400, 60f)
        assertEquals(120f, RefreshRatePolicy.fastestAtCurrentResolution(current, listOf(
            RefreshMode(1080, 2400, 60f, listOf(90f, 120f)),
            RefreshMode(1440, 3200, 144f),
            RefreshMode(1080, 2400, Float.NaN, listOf(-1f, Float.POSITIVE_INFINITY)),
        )))
    }

    @Test fun `invalid modes leave preference unchanged`() {
        assertNull(RefreshRatePolicy.fastestAtCurrentResolution(null, emptyList()))
        assertNull(RefreshRatePolicy.fastestAtCurrentResolution(
            RefreshMode(1080, 2400, 60f),
            listOf(RefreshMode(1080, 2400, Float.NaN, listOf(0f, Float.NEGATIVE_INFINITY))),
        ))
    }

    @Test fun `leases lifecycle power saving and close restore original`() {
        val writes = mutableListOf<Float>()
        val requests = RefreshRateRequests(0f, writes::add)
        val a = Any(); val b = Any()
        requests.setMaximum(120f)
        requests.acquire(a)
        assertEquals(emptyList(), writes)
        requests.setResumed(true)
        assertEquals(listOf(120f), writes)
        requests.acquire(b)
        requests.release(a)
        assertEquals(listOf(120f), writes)
        requests.setPowerSaver(true)
        requests.setPowerSaver(false)
        requests.setResumed(false)
        requests.setResumed(true)
        requests.setMaximum(Float.NaN)
        requests.setMaximum(90f)
        requests.close()
        requests.release(b)
        assertEquals(listOf(120f, 0f, 120f, 0f, 120f, 0f, 90f, 0f), writes)
    }

    @Test fun `nonzero original is restored once after last lease and close`() {
        val writes = mutableListOf<Float>()
        val requests = RefreshRateRequests(90f, writes::add)
        val first = Any(); val second = Any()
        requests.setMaximum(120f)
        requests.setResumed(true)
        requests.acquire(first)
        requests.acquire(first)
        requests.acquire(second)
        requests.release(first)
        assertEquals(listOf(120f), writes)
        requests.release(second)
        requests.release(second)
        assertEquals(listOf(120f, 90f), writes)
        requests.acquire(first)
        requests.close()
        requests.close()
        requests.setResumed(true)
        requests.acquire(second)
        assertEquals(listOf(120f, 90f, 120f, 90f), writes)
    }
}
