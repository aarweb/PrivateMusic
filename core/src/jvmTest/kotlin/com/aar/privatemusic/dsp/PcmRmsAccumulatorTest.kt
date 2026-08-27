package com.aar.privatemusic.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PcmRmsAccumulatorTest {
    @Test
    fun `silence and no samples have no rms`() {
        assertNull(PcmRmsAccumulator().rmsDb())
        val accumulator = PcmRmsAccumulator()
        accumulator.add(ShortArray(32), 32, channelCount = 2)
        assertNull(accumulator.rmsDb())
    }

    @Test
    fun `known mono signal is measured in dbfs`() {
        val accumulator = PcmRmsAccumulator()
        accumulator.add(shortArrayOf(16384, 1, 2, 3), 4, channelCount = 1)
        assertEquals(-6.0206f, accumulator.rmsDb()!!, absoluteTolerance = 0.001f)
    }

    @Test
    fun `stereo sampling includes both channels`() {
        val accumulator = PcmRmsAccumulator()
        accumulator.add(shortArrayOf(0, Short.MIN_VALUE), 2, channelCount = 2)
        assertEquals(-3.0103f, accumulator.rmsDb()!!, absoluteTolerance = 0.001f)
    }

    @Test
    fun `split chunks including split frames equal a single chunk`() {
        val samples = shortArrayOf(
            0, Short.MIN_VALUE,
            100, 200,
            300, 400,
            500, 600,
            16384, -16384,
            700, 800,
            900, 1000,
            1100, 1200,
            Short.MAX_VALUE, Short.MIN_VALUE,
        )
        val whole = PcmRmsAccumulator().apply { add(samples, samples.size, 2) }
        val split = PcmRmsAccumulator().apply {
            add(samples.copyOfRange(0, 1), 1, 2)
            add(samples.copyOfRange(1, 8), 7, 2)
            add(samples.copyOfRange(8, 13), 5, 2)
            add(samples.copyOfRange(13, samples.size), samples.size - 13, 2)
        }
        assertEquals(whole.rmsDb()!!, split.rmsDb()!!, absoluteTolerance = 1e-6f)
    }

    @Test
    fun `invalid channel count and length are ignored safely`() {
        val accumulator = PcmRmsAccumulator()
        accumulator.add(shortArrayOf(Short.MIN_VALUE), 1, channelCount = 0)
        accumulator.add(shortArrayOf(Short.MIN_VALUE), -1, channelCount = 1)
        assertNull(accumulator.rmsDb())

        accumulator.add(shortArrayOf(16384, Short.MIN_VALUE), 1, channelCount = 1, frameStride = 1)
        assertEquals(-6.0206f, accumulator.rmsDb()!!, absoluteTolerance = 0.001f)
    }
}
