package com.aar.privatemusic.data

import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalSearchTest {
    @Test
    fun `consulta vacia o limite no positivo devuelve todo vacio`() {
        val song = song("1", "Tema")
        val playlist = playlist(1, "Lista")

        assertEquals(emptyResults, GlobalSearch.search(listOf(song), listOf(playlist), "  "))
        assertEquals(emptyResults, GlobalSearch.search(listOf(song), listOf(playlist), "tema", maxPerSection = 0))
        assertEquals(emptyResults, GlobalSearch.search(listOf(song), listOf(playlist), "tema", maxPerSection = -2))
    }

    @Test
    fun `canciones rankean exacto prefijo contiene y desempatan estable`() {
        val input = listOf(
            song("contains", "Mi Café Azul"),
            song("prefix-b", "Cafe solo", artist = "B"),
            song("exact-b", "café", artist = "B"),
            song("prefix-a", "Cafetería", artist = "A"),
            song("exact-a", "CAFE", artist = "A"),
        )

        val expected = listOf("exact-a", "exact-b", "prefix-b", "prefix-a", "contains")
        assertEquals(expected, GlobalSearch.search(input, emptyList(), " café ").songs.map { it.id })
        assertEquals(expected, GlobalSearch.search(input.reversed(), emptyList(), "CAFE").songs.map { it.id })
    }

    @Test
    fun `busca todos los metadatos y deja coincidencia solo de letra al final`() {
        val input = listOf(
            song("artist", "Uno", artist = "Horizonte"),
            song("album", "Dos", album = "Horizonte"),
            song("albumArtist", "Tres", albumArtist = "Horizonte"),
            song("note", "Cuatro", note = "Recuerdo del horizonte"),
            song("lyrics", "Cinco"),
            song("none", "Seis"),
        )

        val ids = GlobalSearch.search(
            input,
            emptyList(),
            "horizonte",
            lyricSongIds = setOf("lyrics", "ausente"),
        ).songs.map { it.id }

        assertEquals(listOf("album", "albumArtist", "artist", "note", "lyrics"), ids)
    }

    @Test
    fun `albumes buscan titulo artista y year con ranking`() {
        val songs = listOf(
            song("1", "A", album = "Sol", albumArtist = "Ana", year = 2020),
            song("2", "B", album = "Sol naciente", albumArtist = "Beto", year = 2021),
            song("3", "C", album = "Bajo el sol", albumArtist = "Cora", year = 2020),
        )

        assertEquals(
            listOf("Sol", "Sol naciente", "Bajo el sol"),
            GlobalSearch.search(songs, emptyList(), "sol").albums.map { it.key.title },
        )
        assertEquals("Sol naciente", GlobalSearch.search(songs, emptyList(), "beto").albums.single().key.title)
        assertEquals(
            listOf("Bajo el sol", "Sol"),
            GlobalSearch.search(songs, emptyList(), "2020").albums.map { it.key.title },
        )
    }

    @Test
    fun `playlists buscan nombre y descripcion y rankean estable`() {
        val playlists = listOf(
            playlist(4, "Mi ruta", "Café para viajar"),
            playlist(3, "Café lento"),
            playlist(2, "CAFE"),
            playlist(1, "Cafe"),
        )

        assertEquals(
            listOf(2L, 1L, 3L, 4L),
            GlobalSearch.search(emptyList(), playlists, "café").playlists.map { it.id },
        )
    }

    @Test
    fun `artistas deduplican acentos case espacios y canciones repetidas`() {
        val repeated = song(
            "same",
            "Tema",
            artist = "  José   Luna ",
            albumArtist = "JOSE LUNA",
        )
        val songs = listOf(
            song("two", "Beta", artist = "Jose Luna"),
            repeated,
            repeated,
            song("other", "Otro", artist = "José Lunar"),
        )

        val artists = GlobalSearch.search(songs, emptyList(), "jose lun").artists

        assertEquals(listOf("JOSE LUNA", "José Lunar"), artists.map { it.name })
        assertEquals(listOf("two", "same"), artists.first().songs.map { it.id })
        assertEquals(2, artists.first().songs.map { it.id }.distinct().size)
    }

    @Test
    fun `aplica limite independiente a cada seccion`() {
        val songs = (1..5).map { index ->
            song(
                id = "s$index",
                title = "Tema $index",
                artist = "Tema artista $index",
                album = "Tema album $index",
                albumArtist = "Tema artista $index",
            )
        }
        val playlists = (1L..5L).map { playlist(it, "Tema lista $it") }

        val results = GlobalSearch.search(songs, playlists, "tema", maxPerSection = 2)

        assertEquals(2, results.songs.size)
        assertEquals(2, results.albums.size)
        assertEquals(2, results.artists.size)
        assertEquals(2, results.playlists.size)
    }

    @Test
    fun `sin coincidencias devuelve secciones vacias`() {
        val results = GlobalSearch.search(listOf(song("1", "Tema")), listOf(playlist(1, "Lista")), "nada")
        assertTrue(results.songs.isEmpty())
        assertTrue(results.albums.isEmpty())
        assertTrue(results.artists.isEmpty())
        assertTrue(results.playlists.isEmpty())
    }

    private fun song(
        id: String,
        title: String,
        artist: String = "Artista",
        album: String? = null,
        albumArtist: String? = null,
        year: Int? = null,
        note: String? = null,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        durationSec = 180,
        filePath = "/music/$id.opus",
        artPath = null,
        thumbnailUrl = null,
        addedAt = 0,
        album = album,
        albumArtist = albumArtist,
        year = year,
        note = note,
    )

    private fun playlist(id: Long, name: String, description: String? = null) = Playlist(
        id = id,
        name = name,
        createdAt = id,
        description = description,
    )

    private val emptyResults = GlobalSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
}
