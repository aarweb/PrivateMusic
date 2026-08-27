package com.aar.privatemusic.player

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaybackGainTest {
    @Test
    fun `disabled always returns unity`() {
        assertEquals(1f, PlaybackGain.gain(10f, -8f, false, "replaygain"))
        assertEquals(1f, PlaybackGain.gain(Float.NaN, Float.NEGATIVE_INFINITY, false, "rms"))
    }

    @Test
    fun `rms attenuates loud tracks and never boosts quiet tracks`() {
        assertGainDb(-6f, PlaybackGain.gain(-8f, null, true, "rms"))
        assertEquals(1f, PlaybackGain.gain(-20f, null, true, "rms"))
    }

    @Test
    fun `replaygain uses negative gain and clamps positive gain to unity`() {
        assertGainDb(-7.5f, PlaybackGain.gain(-3f, -7.5f, true, "replaygain"))
        assertEquals(1f, PlaybackGain.gain(-3f, 4f, true, "replaygain"))
    }

    @Test
    fun `replaygain without a finite tag falls back to rms`() {
        assertGainDb(-4f, PlaybackGain.gain(-10f, null, true, "replaygain"))
        assertGainDb(-4f, PlaybackGain.gain(-10f, Float.NaN, true, "replaygain"))
    }

    @Test
    fun `missing and non-finite measurements return finite unity`() {
        val values = listOf(
            PlaybackGain.gain(null, null, true, "rms"),
            PlaybackGain.gain(Float.NaN, null, true, "rms"),
            PlaybackGain.gain(Float.POSITIVE_INFINITY, null, true, "rms"),
            PlaybackGain.gain(null, Float.NEGATIVE_INFINITY, true, "replaygain"),
        )
        values.forEach { gain ->
            assertEquals(1f, gain)
            assertTrue(gain.isFinite())
            assertTrue(gain in 0f..1f)
        }
    }

    @Test
    fun `gain clamps to configured attenuation range`() {
        assertGainDb(-12f, PlaybackGain.gain(10f, null, true, "rms"))
        assertGainDb(-6f, PlaybackGain.gain(-4f, null, true, "rms", targetDb = -10f, minGainDb = -6f))
        assertEquals(1f, PlaybackGain.gain(-30f, null, true, "rms"))
    }

    @Test
    fun `cache key includes enabled state and normalized mode`() {
        val rms = PlaybackGain.configKey("track", true, "rms")
        assertNotEquals(rms, PlaybackGain.configKey("track", false, "rms"))
        assertNotEquals(rms, PlaybackGain.configKey("track", true, "replaygain"))
        assertEquals(rms, PlaybackGain.configKey("track", true, "unknown"))
        assertEquals(rms, PlaybackGain.configKey("track", true, null))
        assertEquals(
            PlaybackGain.Mode.REPLAY_GAIN,
            PlaybackGain.configKey("track", true, " ReplayGain ").mode,
        )
    }

    private fun assertGainDb(expectedDb: Float, actual: Float) {
        val expected = 10.0.pow(expectedDb / 20.0).toFloat()
        assertEquals(expected, actual, absoluteTolerance = 1e-6f)
        assertTrue(actual.isFinite())
        assertTrue(actual in 0f..1f)
    }
}
