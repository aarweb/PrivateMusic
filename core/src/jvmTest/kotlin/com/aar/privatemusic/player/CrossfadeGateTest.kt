package com.aar.privatemusic.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las guardas del fundido, fijadas caso por caso.
 *
 * Cada uno de estos casos ha sido un fallo real en el móvil alguna vez ("esta
 * canción nunca funde", "con el fundido corto no funde", "la preescucha se
 * llevó por delante la cola"), así que aquí quedan clavados.
 */
class CrossfadeGateTest {

    private val unset = Long.MIN_VALUE + 1 // hace de C.TIME_UNSET

    // ---- duración utilizable ----

    @Test
    fun `usa la duración del contenedor cuando la hay`() {
        assertEquals(240_000L, CrossfadeGate.effectiveDurationMs(240_000L, 999_000L, unset))
    }

    @Test
    fun `sin duración en el contenedor tira de la biblioteca`() {
        // El caso de los opus/webm de YouTube: antes no fundían nunca.
        assertEquals(180_000L, CrossfadeGate.effectiveDurationMs(unset, 180_000L, unset))
    }

    @Test
    fun `sin ninguna duración fiable se queda sin fundido`() {
        assertEquals(unset, CrossfadeGate.effectiveDurationMs(unset, null, unset))
        assertEquals(unset, CrossfadeGate.effectiveDurationMs(unset, 0L, unset))
    }

    // ---- ventana ----

    private fun window(
        crossfadeMs: Long = 6_000L,
        durationMs: Long = 200_000L,
        isPlaying: Boolean = true,
        hasNext: Boolean = true,
        repeatOne: Boolean = false,
    ) = CrossfadeGate.windowOpen(crossfadeMs, durationMs, isPlaying, hasNext, repeatOne, unset)

    @Test
    fun `caso normal funde`() {
        assertTrue(window())
    }

    @Test
    fun `con el fundido desactivado no funde`() {
        assertFalse(window(crossfadeMs = 0L))
    }

    @Test
    fun `una pista mas corta que el doble del fundido no funde`() {
        assertFalse(window(crossfadeMs = 6_000L, durationMs = 12_000L))
        assertFalse(window(crossfadeMs = 6_000L, durationMs = 11_000L))
        assertTrue(window(crossfadeMs = 6_000L, durationMs = 12_001L))
    }

    @Test
    fun `los fundidos cortos siguen fundiendo`() {
        // Regresión de v1.85: con 2 s el motor se iba a corte seco.
        assertTrue(window(crossfadeMs = 2_000L, durationMs = 40_000L))
    }

    @Test
    fun `en pausa, sin siguiente o repitiendo una no funde`() {
        assertFalse(window(isPlaying = false))
        assertFalse(window(hasNext = false))
        assertFalse(window(repeatOne = true))
    }

    @Test
    fun `sin duración conocida no funde`() {
        assertFalse(window(durationMs = unset))
    }

    // ---- pista candidata ----

    @Test
    fun `una canción normal es candidata`() {
        assertTrue(CrossfadeGate.trackEligible(hasUri = true, mediaId = "yt_abc", skipXfForId = null))
    }

    @Test
    fun `la preescucha nunca funde`() {
        assertFalse(CrossfadeGate.trackEligible(true, "preview:yt_abc", null))
    }

    @Test
    fun `sin uri o sin id no es candidata`() {
        assertFalse(CrossfadeGate.trackEligible(hasUri = false, mediaId = "yt_abc", skipXfForId = null))
        assertFalse(CrossfadeGate.trackEligible(hasUri = true, mediaId = null, skipXfForId = null))
    }

    @Test
    fun `la que ya falló el relevo se salta`() {
        assertFalse(CrossfadeGate.trackEligible(true, "yt_abc", skipXfForId = "yt_abc"))
        assertTrue(CrossfadeGate.trackEligible(true, "yt_otra", skipXfForId = "yt_abc"))
    }

    // ---- marca de fallo ----

    @Test
    fun `la marca de fallo se olvida al cambiar de canción`() {
        assertEquals("yt_abc", CrossfadeGate.keepSkipMark("yt_abc", "yt_abc"))
        assertNull(CrossfadeGate.keepSkipMark("yt_abc", "yt_otra"))
        assertNull(CrossfadeGate.keepSkipMark(null, "yt_abc"))
    }
}
