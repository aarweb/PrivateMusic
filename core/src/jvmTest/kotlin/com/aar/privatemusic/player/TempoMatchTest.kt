package com.aar.privatemusic.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El AutoMix decide aquí cuánto estirar la canción saliente. Los casos vienen
 * de transiciones reales que sonaban mal: doble tiempo (150→75), diferencia
 * grande de género (140→95) y el par que reportó el usuario (76→86).
 */
class TempoMatchTest {

    private val max = 0.20f

    private fun assertClose(expected: Float, actual: Float, tol: Float = 0.001f) =
        assertTrue(abs(expected - actual) < tol, "esperado ~$expected, obtenido $actual")

    @Test
    fun `doble tiempo no se estira, 150 contra 75 ya cuadra`() {
        assertClose(1f, chooseTempoRatio(150f, 75f, max))
        assertClose(1f, chooseTempoRatio(75f, 150f, max))
    }

    @Test
    fun `medio tiempo con desajuste pequeno se corrige por la octava`() {
        // 152 saliente contra 76 entrante-mal-detectada: la octava lo arregla.
        assertClose(1f, chooseTempoRatio(152f, 76f, max))
        // 160 contra 78: ratio crudo 0,4875; x2 = 0,975 (un 2,5% de ajuste).
        assertClose(0.975f, chooseTempoRatio(160f, 78f, max))
    }

    @Test
    fun `140 contra 95 elige la direccion correcta y se recorta`() {
        // Crudo 0,679; x2 = 1,357. En lineal ganaría 0,679 (0,321 < 0,357) y el
        // tempo acabaría en el extremo opuesto al bueno: en log gana 1,357.
        val r = chooseTempoRatio(140f, 95f, max)
        assertTrue(r > 1f, "debe acelerar, no frenar (obtenido $r)")
        assertClose(1.20f, r) // 1,357 recortado al máximo permitido
    }

    @Test
    fun `el caso del usuario 76 contra 86 cabe entero sin recorte`() {
        assertClose(1.1316f, chooseTempoRatio(76f, 86f, max), tol = 0.002f)
        // Con el limite viejo del 10% se quedaba corto.
        assertClose(1.10f, chooseTempoRatio(76f, 86f, 0.10f))
    }

    @Test
    fun `sin bpm o con bpm invalido no se toca el tempo`() {
        assertEquals(1f, chooseTempoRatio(null, 120f, max))
        assertEquals(1f, chooseTempoRatio(120f, null, max))
        assertEquals(1f, chooseTempoRatio(null, null, max))
        assertEquals(1f, chooseTempoRatio(0f, 120f, max))
        assertEquals(1f, chooseTempoRatio(120f, 0f, max))
        assertEquals(1f, chooseTempoRatio(-100f, 120f, max))
    }

    @Test
    fun `ratios ya cercanos a 1 pasan intactos`() {
        assertClose(1.025f, chooseTempoRatio(120f, 123f, max))
        assertClose(0.975f, chooseTempoRatio(120f, 117f, max))
        assertClose(1f, chooseTempoRatio(128f, 128f, max))
    }

    @Test
    fun `el maximo configurado manda`() {
        // Mismo par, tres limites: siempre recortado al que toque.
        assertClose(1.10f, chooseTempoRatio(100f, 140f, 0.10f))
        assertClose(1.15f, chooseTempoRatio(100f, 140f, 0.15f))
        assertClose(1.20f, chooseTempoRatio(100f, 140f, 0.20f))
        // Y hacia abajo.
        assertClose(0.80f, chooseTempoRatio(140f, 100f, 0.20f))
    }
}
