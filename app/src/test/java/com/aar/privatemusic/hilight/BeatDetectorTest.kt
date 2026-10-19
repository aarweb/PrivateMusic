package com.aar.privatemusic.hilight

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeatDetectorTest {
    private fun detect(rate: Int = 48_000, channels: Int = 2, seconds: Int = 4,
        signal: (Int, Int) -> Double): List<Long> {
        val detector = BeatDetector(rate, channels)
        val beats = mutableListOf<Long>()
        for (frame in 0 until rate * seconds) for (channel in 0 until channels) {
            val time = frame * 1_000_000L / rate
            if (detector.sample(signal(frame, channel), channel, time)) beats += time
        }
        return beats
    }

    @Test fun silenceDoesNotProduceBeats() {
        assertTrue(detect { _, _ -> 0.0 }.isEmpty())
    }

    @Test fun steadyToneDoesNotBecomeAMetronome() {
        val beats = detect { frame, _ -> 0.6 * sin(2 * PI * 100 * frame / 48_000) }
        assertEquals(1, beats.size) // only the initial attack
    }

    @Test fun followsDifferentTemposAndSampleRates() {
        for (rate in listOf(44_100, 48_000)) for (bpm in listOf(60, 120, 180)) {
            val period = rate * 60 / bpm
            val beats = detect(rate = rate, seconds = 4) { frame, _ ->
                if (frame % period < rate / 20) 0.8 * sin(2 * PI * 80 * frame / rate) else 0.0
            }
            assertEquals(4 * bpm / 60, beats.size, "rate=$rate bpm=$bpm: $beats")
            beats.forEachIndexed { index, time ->
                assertTrue(time - index * 60_000_000L / bpm in 0..30_000, "$bpm: $time")
            }
        }
    }

    @Test fun oppositeStereoChannelsDoNotCancelTheDetector() {
        val beats = detect { frame, channel ->
            if (frame % 24_000 < 2400) (if (channel == 0) 0.8 else -0.8) *
                sin(2 * PI * 80 * frame / 48_000) else 0.0
        }
        assertEquals(8, beats.size)
    }

    @Test fun veryQuietNoiseDoesNotFlash() {
        assertTrue(detect { frame, _ -> 0.001 * sin(frame.toDouble()) }.isEmpty())
    }

    @Test fun corruptFloatSamplesCannotPoisonFollowingAudio() {
        val beats = detect { frame, _ ->
            when {
                frame < 48_000 -> Double.NaN
                frame % 24_000 < 2400 -> 0.8 * sin(2 * PI * 80 * frame / 48_000)
                else -> 0.0
            }
        }
        assertEquals(6, beats.size)
    }
}
