package com.aar.privatemusic.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun `rechaza palabras y patrones no aptos completos`() {
        val rejectedTitles = listOf(
            "Song lyric",
            "Song lyrics",
            "Canción letra",
            "Canción letras",
            "Song official audio",
            "Song audio only",
            "Song karaoke",
            "Song visualizer",
            "Song visualiser",
            "Song static image",
            "Song still image",
            "Song cover audio",
            "Artist - Topic",
            "Song Official Music Video Lyrics",
        )

        rejectedTitles.forEach { title ->
            assertEquals(
                CanvasClipSelector.TitleKind.REJECTED,
                CanvasClipSelector.classifyTitle(title),
                title,
            )
        }
    }

    @Test
    fun `el rechazo ignora mayusculas y tolera separadores comunes`() {
        listOf(
            "SONG [OFFICIAL-AUDIO]",
            "Song_Audio.Only",
            "Song (STATIC_IMAGE)",
            "Song STILL-IMAGE",
            "Song COVER_AUDIO",
        ).forEach { title ->
            assertEquals(
                CanvasClipSelector.TitleKind.REJECTED,
                CanvasClipSelector.classifyTitle(title),
                title,
            )
        }
    }

    @Test
    fun `audio como subcadena incidental no se rechaza`() {
        assertEquals(
            CanvasClipSelector.TitleKind.NORMAL,
            CanvasClipSelector.classifyTitle("Claudio Baglioni - Questo piccolo grande amore"),
        )
        assertEquals(
            CanvasClipSelector.TitleKind.NORMAL,
            CanvasClipSelector.classifyTitle("Audiovisual Dreams"),
        )
    }

    @Test
    fun `detecta las variantes de video oficial como preferidas`() {
        listOf(
            "Song (Official Music Video)",
            "Song OFFICIAL-VIDEO",
            "Song - Vídeo Oficial",
            "Song Videoclip",
            "Song VIDEO_CLIP",
        ).forEach { title ->
            assertEquals(
                CanvasClipSelector.TitleKind.PREFERRED_OFFICIAL,
                CanvasClipSelector.classifyTitle(title),
                title,
            )
        }
    }

    @Test
    fun `prioriza el primer oficial y conserva el orden normal como respaldo`() {
        assertEquals(
            2,
            CanvasClipSelector.selectTitleIndex(
                listOf(
                    "Primera versión normal",
                    "Song Official Audio",
                    "Song Official Video",
                    "Song Official Music Video",
                )
            ),
        )
        assertEquals(
            1,
            CanvasClipSelector.selectTitleIndex(
                listOf("Song Lyrics", "Primera normal", "Segunda normal")
            ),
        )
        assertNull(CanvasClipSelector.selectTitleIndex(listOf("Song Lyrics", "Artist - Topic")))
    }
}
