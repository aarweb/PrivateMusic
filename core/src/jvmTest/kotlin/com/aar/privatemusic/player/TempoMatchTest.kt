package com.aar.privatemusic.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TempoMatchTest {
    private val max = 0.20f

    private fun assertClose(expected: Float, actual: Float, tol: Float = 0.001f) =
        assertTrue(abs(expected - actual) <= tol, "esperado ~$expected, obtenido $actual")

    @Test
    fun `normal doble y mitad producen un objetivo comparable`() {
        assertClose(1f, decideTempoMatch(150f, 75f, max).requiredRatio)
        assertClose(1f, decideTempoMatch(75f, 150f, max).requiredRatio)
        val half = decideTempoMatch(160f, 78f, max)
        assertClose(0.975f, half.requiredRatio)
        assertClose(156f, assertNotNull(half.comparableTargetBpm))
        assertClose(156f, assertNotNull(half.effectiveBpm))
        assertClose(0f, assertNotNull(half.residualErrorPct))
    }

    @Test
    fun `un limite que deja mas de tres por ciento se descarta`() {
        val d = decideTempoMatch(140f, 95f, max)
        assertClose(1.35714f, d.requiredRatio, 0.001f)
        assertClose(1.20f, d.limitedRatio)
        assertClose(1f, d.appliedRatio)
        assertNotNull(d.discardedReason)
        assertTrue(assertNotNull(d.residualErrorPct) > 3f)
        assertEquals(d.appliedRatio, chooseTempoRatio(140f, 95f, max))
    }

    @Test
    fun `el limite se aplica si deja como maximo tres por ciento`() {
        val d = decideTempoMatch(100f, 123f, max)
        assertClose(1.23f, d.requiredRatio)
        assertClose(1.20f, d.limitedRatio)
        assertClose(1.20f, d.appliedRatio)
        assertTrue(assertNotNull(d.residualErrorPct) <= 3f)
        assertNull(d.discardedReason)

        val oldLimit = decideTempoMatch(76f, 86f, 0.10f)
        assertClose(1.10f, oldLimit.appliedRatio)
        assertTrue(assertNotNull(oldLimit.residualErrorPct) <= 3f)
    }

    @Test
    fun `ajustes que caben se aplican enteros`() {
        assertClose(1.13158f, chooseTempoRatio(76f, 86f, max), 0.002f)
        assertClose(1.09748f, chooseTempoRatio(154.9f, 85f, max), 0.002f)
        assertClose(1.025f, chooseTempoRatio(120f, 123f, max))
        assertClose(0.975f, chooseTempoRatio(120f, 117f, max))
    }

    @Test
    fun `zona sqrt dos ambigua se descarta en ambos sentidos`() {
        listOf(99f to 140f, 99f to 141f, 140f to 99f, 141f to 99f).forEach { (out, into) ->
            val d = decideTempoMatch(out, into, max)
            assertClose(1f, d.appliedRatio)
            assertTrue(d.discardedReason?.contains("ambigua") == true, "$out -> $into: $d")
            assertTrue(abs(d.requiredRatio - 1f) > max)
        }
        assertTrue(TEMPO_MATCH_AMBIGUITY_LOG_TOLERANCE > 0.0)
    }

    @Test
    fun `datos invalidos son seguros`() {
        val bad = listOf(
            null to 120f, 120f to null, null to null, 0f to 120f,
            120f to 0f, -100f to 120f, Float.NaN to 120f,
            Float.POSITIVE_INFINITY to 120f,
        )
        bad.forEach { (out, into) ->
            val d = decideTempoMatch(out, into, max)
            assertEquals(1f, d.appliedRatio)
            assertNotNull(d.discardedReason)
        }
        assertEquals(1f, decideTempoMatch(120f, 130f, -0.1f).appliedRatio)
        assertEquals(1f, decideTempoMatch(120f, 130f, Float.NaN).appliedRatio)
    }

    @Test
    fun `barrido exhaustivo mantiene invariantes`() {
        for (out in 70..180) for (into in 70..180) {
            val d = decideTempoMatch(out.toFloat(), into.toFloat(), max)
            assertTrue(d.requiredRatio > 0f && d.requiredRatio.isFinite(), "$out -> $into")
            assertTrue(d.limitedRatio in 0.8f..1.2f, "$out -> $into: $d")
            assertTrue(d.appliedRatio == 1f || d.appliedRatio == d.limitedRatio, "$out -> $into: $d")
            assertTrue(assertNotNull(d.comparableTargetBpm) > 0f)
            assertClose(out * d.appliedRatio, assertNotNull(d.effectiveBpm), 0.002f)
            val expectedError = abs(assertNotNull(d.effectiveBpm) - d.comparableTargetBpm) /
                d.comparableTargetBpm * 100f
            assertClose(expectedError, assertNotNull(d.residualErrorPct), 0.002f)
            if (d.appliedRatio != 1f) {
                assertTrue(d.residualErrorPct <= TEMPO_MATCH_MAX_RESIDUAL_ERROR_PCT + 0.001f)
                assertNull(d.discardedReason)
            }
            assertEquals(d.appliedRatio, chooseTempoRatio(out.toFloat(), into.toFloat(), max))
        }
    }

    @Test
    fun `duracion de contenido se convierte a tiempo de reloj`() {
        assertEquals(5_000L, contentDurationToWallMs(6_000L, 1.2f))
        assertEquals(7_500L, contentDurationToWallMs(6_000L, 0.8f))
        assertEquals(6_000L, contentDurationToWallMs(6_000L, 0f))
        assertEquals(6_000L, contentDurationToWallMs(6_000L, Float.NaN))
        assertEquals(0L, contentDurationToWallMs(-1L, 1.2f))
    }
}
