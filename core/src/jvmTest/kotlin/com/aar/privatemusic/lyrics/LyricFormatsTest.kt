package com.aar.privatemusic.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Los dos formatos con tiempos por palabra que lee la app: Enhanced LRC (el que
 * también genera la alineación automática) y TTML. Un LRC de toda la vida tiene
 * que seguir leyéndose igual que antes, sin palabras.
 */
class LyricFormatsTest {

    @Test
    fun `enhanced lrc da tiempo a cada palabra`() {
        val lrc = """
            [00:12.00] <00:12.00>Never <00:12.40>gonna <00:12.90>give <00:13.50>you <00:14.10>up
            [00:15.00] <00:15.00>Never <00:15.60>gonna <00:16.20>let <00:16.80>you <00:17.40>down
        """.trimIndent()

        val lyrics = assertNotNull(LyricsFetcher.parseLrc(lrc))
        assertTrue(lyrics.wordLevel)
        assertEquals(2, lyrics.lines.size)

        val first = lyrics.lines[0]
        // El texto queda limpio de marcas: es lo que se lee y lo que se romaniza.
        assertEquals("Never gonna give you up", first.text)
        assertEquals(12_000L, first.timeMs)
        assertEquals(listOf("Never", "gonna", "give", "you", "up"), first.words.map { it.text })
        assertEquals(listOf(12_000L, 12_400L, 12_900L, 13_500L, 14_100L), first.words.map { it.startMs })
        // Cada palabra acaba donde empieza la siguiente...
        assertEquals(12_400L, first.words[0].endMs)
        assertEquals(13_500L, first.words[2].endMs)
        // ...y la última, donde acaba la línea (el arranque de la siguiente).
        assertEquals(15_000L, first.words.last().endMs)
        assertEquals(15_000L, first.endMs)
    }

    @Test
    fun `las marcas desordenadas se reordenan sin tramos negativos`() {
        val lrc = "[00:10.00] <00:11.00>mundo <00:10.00>Hola"

        val line = assertNotNull(LyricsFetcher.parseLrc(lrc)).lines.single()
        assertEquals(listOf("Hola", "mundo"), line.words.map { it.text })
        assertTrue(line.words.all { it.endMs >= it.startMs }, "ningún tramo puede ser negativo")
    }

    @Test
    fun `un lrc normal sigue siendo por linea`() {
        val lrc = """
            [00:05.00]Primera linea
            [00:09.50]Segunda linea
        """.trimIndent()

        val lyrics = assertNotNull(LyricsFetcher.parseLrc(lrc))
        assertTrue(lyrics.synced)
        assertTrue(!lyrics.wordLevel, "sin marcas <> no hay palabras")
        assertEquals(listOf("Primera linea", "Segunda linea"), lyrics.lines.map { it.text })
        assertEquals(5_000L, lyrics.lines[0].timeMs)
        // Aun sin palabras, se sabe cuándo acaba cada línea: sirve para el barrido.
        assertEquals(9_500L, lyrics.lines[0].endMs)
        assertEquals(null, lyrics.lines[1].endMs)
    }

    @Test
    fun `una linea con varias marcas de tiempo se repite`() {
        // Estribillo marcado dos veces, como es costumbre en los LRC.
        val lyrics = assertNotNull(LyricsFetcher.parseLrc("[00:10.00][01:20.00]Estribillo"))
        assertEquals(2, lyrics.lines.size)
        assertEquals(listOf(10_000L, 80_000L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun `ttml lee begin y end en sus dos formatos`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="00:00:12.500" end="00:00:15.000">
                  <span begin="00:00:12.500" end="00:00:13.000">Hola</span>
                  <span begin="00:00:13.000" end="00:00:15.000">mundo</span>
                </p>
                <p begin="15.5s" end="18s">Sin palabras &amp; con entidad</p>
              </div></body>
            </tt>
        """.trimIndent()

        val lyrics = assertNotNull(LyricsFetcher.parseTtml(ttml))
        assertEquals(2, lyrics.lines.size)

        val first = lyrics.lines[0]
        assertEquals(12_500L, first.timeMs)
        assertEquals(15_000L, first.endMs)
        assertEquals("Hola mundo", first.text)
        assertEquals(listOf(12_500L, 13_000L), first.words.map { it.startMs })
        assertEquals(13_000L, first.words[0].endMs)

        val second = lyrics.lines[1]
        assertEquals(15_500L, second.timeMs) // "15.5s"
        assertEquals(18_000L, second.endMs) // "18s"
        assertEquals("Sin palabras & con entidad", second.text)
        assertTrue(second.words.isEmpty())
    }

    @Test
    fun `tiempos ttml sueltos`() {
        assertEquals(1_500L, LyricFormats.ttmlTime("00:00:01.500"))
        assertEquals(62_000L, LyricFormats.ttmlTime("01:02"))
        assertEquals(500L, LyricFormats.ttmlTime("500ms"))
        assertEquals(90_000L, LyricFormats.ttmlTime("1.5m"))
        assertEquals(null, LyricFormats.ttmlTime("no es un tiempo"))
    }

    @Test
    fun `escribir y volver a leer enhanced lrc no pierde nada`() {
        val original = Lyrics(
            synced = true,
            lines = listOf(
                LyricLine(
                    timeMs = 1_230L,
                    text = "Hola mundo",
                    endMs = 3_000L,
                    words = listOf(LyricWord(1_230L, 2_000L, "Hola"), LyricWord(2_000L, 3_000L, "mundo")),
                ),
                LyricLine(timeMs = 3_000L, text = "Segunda", endMs = null),
            ),
        )

        val text = LyricFormats.toEnhancedLrc(original)
        val reparsed = assertNotNull(LyricsFetcher.parseLrc(text))

        assertEquals(original.lines.map { it.text }, reparsed.lines.map { it.text })
        assertEquals(listOf(1_230L, 3_000L), reparsed.lines.map { it.timeMs })
        assertEquals(
            original.lines[0].words.map { it.startMs to it.text },
            reparsed.lines[0].words.map { it.startMs to it.text },
        )
    }
}
