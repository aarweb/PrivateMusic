package com.aar.privatemusic.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricSearchTest {
    @Test fun `normaliza acentos y puntuacion`() {
        assertEquals("donde esta mi corazon", LyricSearch.normalize("¿Dónde está mi corazón?"))
        val hit = LyricSearch.search(listOf(doc("a", "Mi corazón, despierta")), "corazon").single()
        assertEquals("a", hit.songId)
    }

    @Test fun `encuentra frase exacta`() {
        val hit = LyricSearch.search(listOf(doc("a", "Bajo la misma luna")), "misma luna").single()
        assertTrue(hit.score >= 10_000)
    }

    @Test fun `encuentra frase cruzando dos lineas`() {
        val hit = LyricSearch.search(listOf(doc("a", "vuelvo a", "casa contigo")), "a casa").single()
        assertTrue(hit.snippet.contains("·"))
    }

    @Test fun `fallback exige todos los tokens`() {
        val docs = listOf(doc("a", "luna grande sobre el mar"), doc("b", "luna sin final"))
        assertEquals("a", LyricSearch.search(docs, "mar luna grande").single().songId)
    }

    @Test fun `original gana a traduccion con el mismo ranking`() {
        val docs = listOf(
            LyricSearchDocument("original", listOf("quiero volver")),
            LyricSearchDocument("traducida", listOf("nothing"), listOf("quiero volver")),
        )
        val hits = LyricSearch.search(docs, "quiero volver")
        assertEquals("original", hits.first().songId)
        assertFalse(hits.first().translated)
        assertTrue(hits.last().translated)
    }

    @Test fun `devuelve solo el mejor hit por cancion`() {
        val hits = LyricSearch.search(listOf(doc("a", "noche azul", "otra noche azul")), "noche azul")
        assertEquals(1, hits.size)
    }

    @Test fun `query minima es tres caracteres normalizados`() {
        assertTrue(LyricSearch.search(listOf(doc("a", "yo")), "yo").isEmpty())
        assertEquals(1, LyricSearch.search(listOf(doc("a", "sol")), "sol").size)
    }

    @Test fun `limite y orden son deterministas`() {
        val docs = listOf(doc("c", "luz eterna"), doc("a", "luz eterna"), doc("b", "luz eterna"))
        assertEquals(listOf("a", "b"), LyricSearch.search(docs, "luz eterna", limit = 2).map { it.songId })
    }

    private fun doc(id: String, vararg lines: String) = LyricSearchDocument(id, lines.toList())
}
