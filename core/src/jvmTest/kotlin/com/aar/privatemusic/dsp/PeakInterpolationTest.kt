package com.aar.privatemusic.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeakInterpolationTest {

    private fun assertClose(expected: Float, actual: Float, tol: Float = 0.0005f) =
        assertTrue(abs(expected - actual) < tol, "esperado ~$expected, obtenido $actual")

    @Test
    fun `pico simetrico no se mueve`() {
        assertClose(0f, parabolicPeakOffset(1f, 3f, 1f))
    }

    @Test
    fun `el pico se inclina hacia el vecino mas alto`() {
        assertTrue(parabolicPeakOffset(1f, 3f, 2f) > 0f)
        assertTrue(parabolicPeakOffset(2f, 3f, 1f) < 0f)
    }

    @Test
    fun `recupera el vertice de una parabola conocida`() {
        // y = -(x - 0.25)^2 + 10 muestreada en -1, 0, 1.
        fun y(x: Float) = -(x - 0.25f) * (x - 0.25f) + 10f
        assertClose(0.25f, parabolicPeakOffset(y(-1f), y(0f), y(1f)))
    }

    @Test
    fun `nunca se sale de medio frame`() {
        val off = parabolicPeakOffset(0f, 1f, 0.999f)
        assertTrue(off in -0.5f..0.5f, "offset fuera de rango: $off")
    }

    @Test
    fun `meseta o curvatura invertida devuelve cero`() {
        assertEquals(0f, parabolicPeakOffset(1f, 1f, 1f)) // denom 0
        assertEquals(0f, parabolicPeakOffset(5f, 1f, 5f)) // valle, no pico
        assertEquals(0f, parabolicPeakOffset(Float.NaN, 1f, 2f))
    }
}
