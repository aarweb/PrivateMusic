package com.aar.privatemusic.player

import kotlin.test.Test
import kotlin.test.assertEquals

class CarMediaSearchTest {
    private fun candidate(
        id: String,
        title: String,
        artist: String = "",
        album: String? = null,
    ) = CarMediaSearch.Candidate(id, title, artist, album)

    @Test
    fun `ignora tildes espanolas`() {
        val songs = listOf(candidate("one", "Canción del corazón"))

        assertEquals(listOf("one"), CarMediaSearch.rank(songs, "cancion corazon"))
        assertEquals(listOf("one"), CarMediaSearch.rank(songs, "cancio\u0301n"))
    }

    @Test
    fun `prioriza titulo exacto prefijo tokens artista y album`() {
        val songs = listOf(
            candidate("album", "Otro", album = "Luz azul"),
            candidate("artist", "Otro", artist = "Luz azul"),
            candidate("tokens", "La luz más azul"),
            candidate("prefix", "Luz azul en directo"),
            candidate("exact", "Luz azul"),
        )

        assertEquals(
            listOf("exact", "prefix", "tokens", "artist", "album"),
            CarMediaSearch.rank(songs, "luz azul"),
        )
    }

    @Test
    fun `acepta prefijos de tokens aunque cambie su separacion`() {
        val songs = listOf(candidate("one", "Corazones de cristal"))

        assertEquals(listOf("one"), CarMediaSearch.rank(songs, "cora cris"))
    }

    @Test
    fun `consulta vacia conserva el orden de entrada`() {
        val songs = listOf(candidate("a", "A"), candidate("b", "B"))

        assertEquals(listOf("a", "b"), CarMediaSearch.rank(songs, "   "))
    }

    @Test
    fun `desempata por entrada y respeta el limite`() {
        val songs = listOf(
            candidate("first", "Tema uno", artist = "Marea"),
            candidate("second", "Tema dos", artist = "Marea"),
            candidate("third", "Tema tres", artist = "Marea"),
        )

        assertEquals(listOf("first", "second"), CarMediaSearch.rank(songs, "marea", limit = 2))
        assertEquals(emptyList(), CarMediaSearch.rank(songs, "marea", limit = 0))
    }
}
