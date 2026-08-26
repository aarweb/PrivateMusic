package com.aar.privatemusic.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasClipSelectorTest {
    @Test
    fun `elige tres ventanas distintas dentro de la zona central`() {
        val starts = CanvasClipSelector.candidateStarts(240.0, "song-42")

        assertEquals(3, starts.distinct().size)
        assertTrue(starts.all { it in 60..172 })
    }

    @Test
    fun `la seleccion es estable para una cancion`() {
        assertEquals(
            CanvasClipSelector.candidateStarts(180.0, "same-song"),
            CanvasClipSelector.candidateStarts(180.0, "same-song"),
        )
    }

    @Test
    fun `un video corto usa el principio una sola vez`() {
        assertEquals(listOf(0), CanvasClipSelector.candidateStarts(9.0, "short"))
    }
}
