package com.aar.privatemusic.player

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossfadeAlignmentTest {

    @Test
    fun `la divergencia intencionada de todos los tempos nunca provoca reseek`() {
        val stretches = listOf(0f, 0.05f, 0.10f, 0.20f)

        for (bpmOut in 40..240) {
            for (bpmIn in 40..240) {
                for (maxStretch in stretches) {
                    val ratio = decideTempoMatch(
                        bpmOut.toFloat(),
                        bpmIn.toFloat(),
                        maxStretch,
                    ).appliedRatio
                    assertTrue(ratio.isFinite() && ratio > 0f)

                    for (wallAdvanceMs in 100L..5_000L step 100L) {
                        val tailAdvanceMs =
                            (wallAdvanceMs.toDouble() * ratio.toDouble()).roundToLong()
                        val rawDriftMs = wallAdvanceMs - tailAdvanceMs
                        val realDriftMs = CrossfadeAlignment.realDriftMs(
                            rawDriftMs = rawDriftMs,
                            tailPositionMs = 10_000L + tailAdvanceMs,
                            referenceTailPositionMs = 10_000L,
                            appliedTempoRatio = ratio,
                        )

                        assertTrue(abs(realDriftMs) <= 1L)
                        assertFalse(CrossfadeAlignment.shouldReseek(realDriftMs))
                    }
                }
            }
        }
    }

    @Test
    fun `bpm ausente o invalido degrada a ratio neutro`() {
        val invalidCases = listOf(
            null to 120f,
            120f to null,
            null to null,
            0f to 120f,
            -1f to 120f,
            Float.NaN to 120f,
            Float.POSITIVE_INFINITY to 120f,
            120f to 0f,
            120f to Float.NEGATIVE_INFINITY,
        )

        invalidCases.forEach { (bpmOut, bpmIn) ->
            val ratio = decideTempoMatch(bpmOut, bpmIn, 0.20f).appliedRatio
            assertEquals(1f, ratio)
            assertEquals(
                37L,
                CrossfadeAlignment.realDriftMs(37L, 2_000L, 1_000L, ratio),
            )
        }
    }

    @Test
    fun `half time double time y extremos de stretch conservan ratios seguros`() {
        val cases = listOf(
            Triple(160f, 78f, 0.20f),
            Triple(78f, 160f, 0.20f),
            Triple(120f, 117f, 0.05f),
            Triple(120f, 123f, 0.05f),
            Triple(100f, 123f, 0.20f),
            Triple(76f, 86f, 0.10f),
            Triple(40f, 240f, 0f),
            Triple(240f, 40f, 0.20f),
        )

        val ratios = cases.map { (out, into, stretch) ->
            decideTempoMatch(out, into, stretch).appliedRatio
        }
        assertTrue(ratios.any { it < 1f })
        assertTrue(ratios.any { it > 1f })
        assertTrue(ratios.all { it.isFinite() && it > 0f })
    }

    @Test
    fun `ratio invalido equivale a uno`() {
        listOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            0f,
            -1f,
        ).forEach { ratio ->
            assertEquals(
                -73L,
                CrossfadeAlignment.realDriftMs(-73L, 5_000L, 1_000L, ratio),
            )
        }
    }

    @Test
    fun `la decision usa solo deriva real alrededor de sesenta ms`() {
        listOf(-60L, -1L, 0L, 1L, 60L).forEach {
            assertFalse(CrossfadeAlignment.shouldReseek(it))
        }
        assertTrue(CrossfadeAlignment.shouldReseek(-61L))
        assertTrue(CrossfadeAlignment.shouldReseek(61L))

        assertEquals(120L, CrossfadeAlignment.reseekAdvanceMs(-61L))
        assertEquals(120L, CrossfadeAlignment.reseekAdvanceMs(0L))
        assertEquals(101L, CrossfadeAlignment.reseekAdvanceMs(61L))
        assertEquals(1_101L, CrossfadeAlignment.reseekTargetMs(1_000L, 61L))
    }

    @Test
    fun `la comprobacion final admite exactamente doscientos cincuenta ms`() {
        assertTrue(CrossfadeAlignment.isFinalAlignmentAcceptable(-250L))
        assertTrue(CrossfadeAlignment.isFinalAlignmentAcceptable(250L))
        assertFalse(CrossfadeAlignment.isFinalAlignmentAcceptable(-251L))
        assertFalse(CrossfadeAlignment.isFinalAlignmentAcceptable(251L))
    }

    @Test
    fun `timeouts distinguen Sonic del camino normal`() {
        assertEquals(1_200L, CrossfadeAlignment.postReseekTimeoutMs(autoMix = true))
        assertEquals(600L, CrossfadeAlignment.postReseekTimeoutMs(autoMix = false))
    }

    @Test
    fun `aritmetica extrema satura sin cambiar de signo`() {
        assertEquals(
            Long.MAX_VALUE,
            CrossfadeAlignment.realDriftMs(
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                0L,
                2f,
            ),
        )
        assertEquals(
            Long.MIN_VALUE,
            CrossfadeAlignment.realDriftMs(
                Long.MIN_VALUE,
                Long.MAX_VALUE,
                0L,
                0.5f,
            ),
        )
        assertEquals(
            4_611_686_018_427_387_904L,
            CrossfadeAlignment.realDriftMs(
                0L,
                Long.MAX_VALUE,
                Long.MIN_VALUE,
                2f,
            ),
        )
        assertEquals(Long.MAX_VALUE, CrossfadeAlignment.reseekAdvanceMs(Long.MAX_VALUE))
        assertEquals(
            Long.MAX_VALUE,
            CrossfadeAlignment.reseekTargetMs(Long.MAX_VALUE, Long.MAX_VALUE),
        )
        assertTrue(CrossfadeAlignment.shouldReseek(Long.MIN_VALUE))
        assertTrue(CrossfadeAlignment.shouldReseek(Long.MAX_VALUE))

        val tinyRatioResult = CrossfadeAlignment.realDriftMs(
            0L,
            Long.MAX_VALUE,
            0L,
            Float.MIN_VALUE,
        )
        assertEquals(Long.MIN_VALUE, tinyRatioResult)
    }
}
