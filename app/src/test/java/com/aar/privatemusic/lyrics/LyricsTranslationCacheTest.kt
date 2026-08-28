package com.aar.privatemusic.lyrics

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsTranslationCacheTest {

    @Test
    fun `roundtrip conserva Unicode tiempos y elimina palabras`() = withCache { cache, _ ->
        val source = lyrics("Hello 🌍", "今日は")
        val translated = source.copy(
            lines = listOf(
                source.lines[0].copy(text = "Hola 🌍"),
                source.lines[1].copy(text = "Hoy"),
            ),
        )

        cache.write("canción/安全", "es", source, translated)
        val read = cache.read("canción/安全", "es", source)!!

        assertEquals(listOf("Hola 🌍", "Hoy"), read.lines.map { it.text })
        assertEquals(source.lines.map { it.timeMs }, read.lines.map { it.timeMs })
        assertEquals(source.lines.map { it.endMs }, read.lines.map { it.endMs })
        assertTrue(read.lines.all { it.words.isEmpty() })
    }

    @Test
    fun `cambio en texto fuente invalida la cache`() = withCache { cache, _ ->
        val source = lyrics("Hello")
        cache.write("song", "es", source, lyrics("Hola"))

        assertNull(cache.read("song", "es", lyrics("Hello!")))
    }

    @Test
    fun `contenido corrupto no se lee`() = withCache { cache, _ ->
        val source = lyrics("Hello")
        cache.write("song", "es", source, lyrics("Hola"))
        cache.cacheFileFor("song", "es").writeText("esto no es una cache")

        assertNull(cache.read("song", "es", source))
    }

    @Test
    fun `conteo declarado distinto se invalida`() = withCache { cache, _ ->
        val source = lyrics("One", "Two")
        cache.write("song", "es", source, lyrics("Uno", "Dos"))
        val file = cache.cacheFileFor("song", "es")
        file.writeText(file.readText().replace("count\t2", "count\t7"))

        assertNull(cache.read("song", "es", source))
    }

    @Test
    fun `id malicioso nunca sale del directorio`() = withCache { cache, dir ->
        val malicious = "../../fuera/../secreto"
        val file = cache.cacheFileFor(malicious, "../../es")

        assertEquals(dir.canonicalFile, file.canonicalFile.parentFile)
        cache.write(malicious, "es", lyrics("Hello"), lyrics("Hola"))
        assertTrue(cache.cacheFileFor(malicious, "es").isFile)
        assertTrue(dir.parentFile.listFiles()?.none { it.name == "fuera" || it.name == "secreto" } == true)
    }

    @Test
    fun `no acepta una traduccion con otro numero de lineas`() = withCache { cache, _ ->
        assertFailsWith<IllegalArgumentException> {
            cache.write("song", "es", lyrics("One", "Two"), lyrics("Uno"))
        }
    }

    private fun lyrics(vararg lines: String): Lyrics = Lyrics(
        synced = true,
        lines = lines.mapIndexed { index, text ->
            LyricLine(
                timeMs = index * 1_000L,
                text = text,
                endMs = index * 1_000L + 900L,
                words = listOf(LyricWord(index * 1_000L, index * 1_000L + 500L, text)),
            )
        },
    )

    private inline fun withCache(block: (LyricsTranslationCache, File) -> Unit) {
        val dir = createTempDirectory("lyrics-cache").toFile()
        try {
            block(LyricsTranslationCache(dir), dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
