package com.aar.privatemusic.lyrics

import com.aar.privatemusic.lyrics.ForcedAligner.TranscriptWord
import com.aar.privatemusic.lyrics.ForcedAligner.VoiceSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * La alineación automática: convertir una letra sin tiempos en una cantable.
 * Aquí se prueba el algoritmo puro, que es lo que decide si la letra cae en su
 * sitio; separar la voz y decodificar el audio es fontanería de la plataforma.
 */
class ForcedAlignerTest {

    // ------------------------------------------------------ contra el motor

    @Test
    fun `una transcripcion perfecta da los tiempos tal cual`() {
        val lines = listOf("Hola mundo", "Adios mundo")
        val transcript = listOf(
            TranscriptWord(1_000, 1_400, "hola"),
            TranscriptWord(1_400, 2_000, "mundo"),
            TranscriptWord(3_000, 3_500, "adios"),
            TranscriptWord(3_500, 4_000, "mundo"),
        )

        val lyrics = assertNotNull(ForcedAligner.alignToTranscript(lines, transcript))
        assertEquals(2, lyrics.lines.size)
        assertTrue(lyrics.wordLevel)
        assertEquals(1_000L, lyrics.lines[0].timeMs)
        assertEquals(2_000L, lyrics.lines[0].endMs)
        assertEquals(listOf(1_000L, 1_400L), lyrics.lines[0].words.map { it.startMs })
        assertEquals(3_000L, lyrics.lines[1].timeMs)
        assertEquals("Adios mundo", lyrics.lines[1].text)
    }

    @Test
    fun `aguanta mayusculas, tildes y puntuacion`() {
        val lines = listOf("¡Hola, Mundo!")
        val transcript = listOf(
            TranscriptWord(500, 900, "hola"),
            TranscriptWord(900, 1_500, "mundo"),
        )

        val line = assertNotNull(ForcedAligner.alignToTranscript(lines, transcript)).lines.single()
        // El texto que se ve es el original, con su puntuación.
        assertEquals("¡Hola, Mundo!", line.text)
        assertEquals(listOf("¡Hola,", "Mundo!"), line.words.map { it.text })
        assertEquals(listOf(500L, 900L), line.words.map { it.startMs })
    }

    @Test
    fun `una palabra que el motor no oyo se interpola entre sus vecinas`() {
        val lines = listOf("uno dos tres")
        // El motor se comió "dos".
        val transcript = listOf(
            TranscriptWord(1_000, 2_000, "uno"),
            TranscriptWord(4_000, 5_000, "tres"),
        )

        val line = assertNotNull(ForcedAligner.alignToTranscript(lines, transcript)).lines.single()
        assertEquals(3, line.words.size)
        assertEquals(1_000L, line.words[0].startMs)
        assertEquals(4_000L, line.words[2].startMs)
        val dos = line.words[1]
        assertTrue(dos.startMs in 2_000L..4_000L, "la palabra perdida cae entre las dos que sí se oyeron")
        assertTrue(dos.endMs >= dos.startMs)
    }

    @Test
    fun `sobrevive a que el motor invente palabras`() {
        val lines = listOf("cielo azul")
        val transcript = listOf(
            TranscriptWord(0, 500, "eh"),          // ruido
            TranscriptWord(1_000, 1_600, "cielo"),
            TranscriptWord(1_600, 1_800, "mmm"),   // ruido
            TranscriptWord(2_000, 2_800, "azul"),
        )

        val line = assertNotNull(ForcedAligner.alignToTranscript(lines, transcript)).lines.single()
        assertEquals(listOf("cielo", "azul"), line.words.map { it.text })
        assertEquals(listOf(1_000L, 2_000L), line.words.map { it.startMs })
    }

    @Test
    fun `una errata del motor sigue emparejando`() {
        val lines = listOf("corazon partido")
        val transcript = listOf(
            TranscriptWord(1_000, 1_800, "corason"),  // errata
            TranscriptWord(1_800, 2_600, "partido"),
        )

        val line = assertNotNull(ForcedAligner.alignToTranscript(lines, transcript)).lines.single()
        assertEquals(1_000L, line.words[0].startMs)
        assertEquals(1_800L, line.words[1].startMs)
    }

    @Test
    fun `sin transcripcion no se inventa nada`() {
        assertEquals(null, ForcedAligner.alignToTranscript(listOf("hola"), emptyList()))
        assertEquals(null, ForcedAligner.alignToTranscript(emptyList(), listOf(TranscriptWord(0, 1, "x"))))
    }

    // --------------------------------------------------------- por la voz

    @Test
    fun `los tramos con voz salen de la curva de energia`() {
        // Ventanas de 10 ms: un verso de 1 s (20-119) y otro de 1,5 s
        // (200-349), con 800 ms de instrumental en medio: más que una
        // respiración, así que son dos tramos.
        val energy = FloatArray(400) { i -> if (i in 20..119 || i in 200..349) 1f else 0.01f }

        val segments = ForcedAligner.segmentsFromEnergy(energy, frameMs = 10.0)
        assertEquals(2, segments.size)
        assertEquals(200L, segments[0].startMs)
        assertEquals(1_200L, segments[0].endMs)
        assertEquals(2_000L, segments[1].startMs)
        assertEquals(3_500L, segments[1].endMs)
    }

    @Test
    fun `una respiracion no parte un verso en dos`() {
        // Bache de 100 ms en mitad de un tramo largo: es una respiración.
        val energy = FloatArray(200) { i -> if (i in 20..119 && i !in 60..69) 1f else 0f }

        val segments = ForcedAligner.segmentsFromEnergy(energy, frameMs = 10.0)
        assertEquals(1, segments.size, "el hueco corto no debe partir el tramo")
        assertEquals(200L, segments[0].startMs)
        assertEquals(1_200L, segments[0].endMs)
    }

    @Test
    fun `un chasquido suelto no cuenta como verso`() {
        val energy = FloatArray(200) { i -> if (i in 10..12) 1f else 0f }
        assertTrue(ForcedAligner.segmentsFromEnergy(energy, frameMs = 10.0).isEmpty())
    }

    @Test
    fun `tantos versos como tramos, cada uno al suyo`() {
        val lines = listOf("primer verso", "segundo verso")
        val segments = listOf(VoiceSegment(1_000, 3_000), VoiceSegment(5_000, 7_000))

        val lyrics = assertNotNull(ForcedAligner.alignToVoice(lines, segments, totalMs = 10_000))
        assertEquals(1_000L, lyrics.lines[0].timeMs)
        assertEquals(5_000L, lyrics.lines[1].timeMs)
        assertEquals(7_000L, lyrics.lines[1].endMs)
        // Y dentro de cada verso, las palabras repartidas y en orden.
        val words = lyrics.lines[0].words
        assertEquals(listOf("primer", "verso"), words.map { it.text })
        assertTrue(words[0].startMs < words[1].startMs)
        assertTrue(words.last().endMs <= 3_000L)
    }

    @Test
    fun `si no cuadran, se reparte por silabas dentro de lo cantado`() {
        // Un solo tramo de voz para tres versos de distinta longitud.
        val lines = listOf("sol", "amanecer bonito", "no")
        val segments = listOf(VoiceSegment(0, 6_000))

        val lyrics = assertNotNull(ForcedAligner.alignToVoice(lines, segments, totalMs = 6_000))
        assertEquals(3, lyrics.lines.size)
        // En orden y sin solaparse.
        val starts = lyrics.lines.map { it.timeMs }
        assertEquals(starts.sorted(), starts)
        // El verso largo se lleva más tiempo que los cortos.
        val d0 = lyrics.lines[1].timeMs - lyrics.lines[0].timeMs
        val d1 = lyrics.lines[2].timeMs - lyrics.lines[1].timeMs
        assertTrue(d1 > d0, "«amanecer bonito» debe durar más que «sol» ($d1 vs $d0)")
        assertTrue(lyrics.lines.all { it.words.isNotEmpty() })
    }

    @Test
    fun `los huecos instrumentales no se cuentan como cantados`() {
        // Dos tramos con un instrumental larguísimo en medio.
        val lines = listOf("uno", "dos")
        val segments = listOf(VoiceSegment(0, 2_000), VoiceSegment(60_000, 62_000))

        val lyrics = assertNotNull(ForcedAligner.alignToVoice(lines, segments, totalMs = 70_000))
        assertEquals(0L, lyrics.lines[0].timeMs)
        assertEquals(60_000L, lyrics.lines[1].timeMs, "el segundo verso empieza cuando vuelve la voz")
    }

    @Test
    fun `sin voz no hay letra sincronizada`() {
        assertEquals(null, ForcedAligner.alignToVoice(listOf("hola"), emptyList(), 1_000))
        assertEquals(null, ForcedAligner.alignToVoice(emptyList(), listOf(VoiceSegment(0, 1_000)), 1_000))
    }

    @Test
    fun `el resultado se puede guardar y volver a leer`() {
        val lyrics = assertNotNull(
            ForcedAligner.alignToVoice(
                listOf("hola mundo", "adios"),
                listOf(VoiceSegment(1_000, 3_000), VoiceSegment(4_000, 5_000)),
                totalMs = 6_000,
            )
        )
        val reparsed = assertNotNull(LyricsFetcher.parseLrc(LyricFormats.toEnhancedLrc(lyrics)))
        assertTrue(reparsed.wordLevel)
        assertEquals(lyrics.lines.map { it.text }, reparsed.lines.map { it.text })
    }

    // ------------------------------------------------------------- utilería

    @Test
    fun `contar silabas a ojo`() {
        assertEquals(1, ForcedAligner.syllables("sol"))
        assertEquals(2, ForcedAligner.syllables("cielo"))
        assertEquals(4, ForcedAligner.syllables("amanecer"))
        assertEquals(1, ForcedAligner.syllables("!!!"), "algo que no son letras cuenta como una")
    }
}
