package com.aar.privatemusic.hilight

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HiLightPolicyTest {
    @Test fun localPlaybackHandlesCrossfadePauseBufferingCastAndDestruction() {
        assertTrue(HiLightPolicy.localPlayback(true, true, false, false, false))
        assertTrue(HiLightPolicy.localPlayback(true, false, true, false, false))
        assertFalse(HiLightPolicy.localPlayback(false, true, true, false, false))
        assertFalse(HiLightPolicy.localPlayback(true, false, false, false, false))
        assertFalse(HiLightPolicy.localPlayback(true, true, true, true, false))
        assertFalse(HiLightPolicy.localPlayback(true, true, true, false, true))
    }
    @Test fun onlyKnownHiLightPhonesOnAndroid17AreCandidates() {
        for (model in listOf("Pixel 11 Pro", "Pixel 11 Pro XL", "Pixel 11 Pro Fold")) {
            assertTrue(HiLightPolicy.isCandidate(37, "Google", model))
            assertFalse(HiLightPolicy.isCandidate(36, "Google", model))
        }
        for (model in listOf("Pixel 11", "Pixel 10 Pro", "Pixel 9", "Pixel 11 Pro Fake", "")) {
            assertFalse(HiLightPolicy.isCandidate(37, "Google", model))
        }
        assertFalse(HiLightPolicy.isCandidate(37, "Other", "Pixel 11 Pro"))
    }

    @Test fun playbackAloneCannotBypassOptInOrAccess() {
        assertFalse(HiLightPolicy.shouldIlluminate(false, true, true))
        assertFalse(HiLightPolicy.shouldIlluminate(true, true, false))
        assertFalse(HiLightPolicy.shouldIlluminate(true, false, true))
        assertTrue(HiLightPolicy.shouldIlluminate(true, true, true))
    }

    @Test fun lostHeartbeatReleasesLightsAndRenewalExtendsLease() {
        val output = FakeOutput()
        val lease = HiLightLease(output)
        lease.renew(0)
        lease.renew(1_000)
        lease.expire(2_500)
        assertEquals(0, output.stops)
        lease.expire(3_500)
        assertEquals(1, output.stops)
        lease.expire(9_000)
        assertEquals(1, output.stops)
    }

    @Test fun pauseReleasesImmediatelyAndDoesNotRequireAnotherPulse() {
        val output = FakeOutput()
        val lease = HiLightLease(output)
        lease.stop()
        assertEquals(0, output.stops)
        lease.renew(0)
        lease.stop()
        assertEquals(1, output.stops)
        lease.renew(1_000)
        lease.stop()
        assertEquals(2, output.stops)
    }

    @Test fun failedPulseStillAttemptsCleanup() {
        val output = FakeOutput(failPulse = true)
        val lease = HiLightLease(output)
        assertFailsWith<IllegalStateException> { lease.renew(0) }
        assertEquals(1, output.stops)
    }

    @Test fun failedCleanupRemainsArmedForWatchdogRetry() {
        val output = FakeOutput(failStop = true)
        val lease = HiLightLease(output)
        lease.renew(0)
        assertFailsWith<IllegalStateException> { lease.stop() }
        output.failStop = false
        lease.expire(3_000)
        assertEquals(2, output.stops)
    }

    private class FakeOutput(val failPulse: Boolean = false, var failStop: Boolean = false) : HiLightOutput {
        var stops = 0
        override fun pulse() { check(!failPulse) }
        override fun stop() { stops++; check(!failStop) }
    }
}
