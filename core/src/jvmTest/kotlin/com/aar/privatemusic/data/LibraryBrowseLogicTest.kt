package com.aar.privatemusic.data

import com.aar.privatemusic.data.db.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryBrowseLogicTest {
    @Test
    fun `codec conserva json especial y rechaza json roto`() {
        val genres = listOf("Rock \"&\" roll", "R&B\\Soul", "Música\nlatina")
        val encoded = GenreCodec.encode(genres)

        assertEquals(listOf("Música latina", "R&B\\Soul", "Rock \"&\" roll"), GenreCodec.decode(encoded))
        assertEquals(emptyList(), GenreCodec.decode("{roto"))
        assertEquals(emptyList(), GenreCodec.decode("[1,true,null]"))
    }

    @Test
    fun `codec raw separa limpia y deduplica acentos case y espacios`() {
        val encoded = GenreCodec.encodeRaw("  Róck ; rock, ROCK\u0000 Jazz   latino ")

        assertEquals(listOf("Jazz latino", "ROCK"), GenreCodec.decode(encoded))
        assertNull(GenreCodec.encodeRaw(" , ; \u0000 "))
        assertNull(GenreCodec.encodeRaw(null))
    }

    @Test
    fun `generos admiten varios por cancion deduplican ids y ordenan por cantidad`() {
        val duplicate = song("same", "Beta", "Zoé", genres = listOf("Róck", "Indie"))
        val collections = LibraryBrowseLogic.genres(
            listOf(
                song("two", "Alfa", "Ana", genres = listOf("rock", "Jazz")),
                duplicate,
                duplicate,
                song("three", "Coda", "Beto", genres = listOf("INDIE")),
            ),
        )

        assertEquals(listOf("INDIE", "rock", "Jazz"), collections.map { it.label })
        assertEquals(listOf("three", "same"), collections[0].songs.map { it.id })
        assertEquals(listOf("two", "same"), collections[1].songs.map { it.id })
        assertEquals(1, collections[1].songs.count { it.id == "same" })
        assertTrue(collections.all { it.kind == BrowseKind.GENRE })
    }

    @Test
    fun `years excluye limites invalidos y ordena descendente`() {
        val collections = LibraryBrowseLogic.years(
            listOf(
                song("low", "A", year = 999),
                song("min", "B", year = 1000),
                song("modern", "C", year = 2024),
                song("max", "D", year = 2100),
                song("high", "E", year = 2101),
                song("none", "F", year = null),
            ),
        )

        assertEquals(listOf("2100", "2024", "1000"), collections.map { it.label })
        assertTrue(collections.all { it.kind == BrowseKind.YEAR })
    }

    @Test
    fun `decades agrupa y ordena cronologicamente`() {
        val collections = LibraryBrowseLogic.decades(
            listOf(
                song("a", "A", year = 1999),
                song("b", "B", year = 2000),
                song("c", "C", year = 2008),
                song("d", "D", year = 2021),
            ),
        )

        assertEquals(listOf("Años 2020", "Años 2000", "Años 1990"), collections.map { it.label })
        assertEquals(listOf("b", "c"), collections[1].songs.map { it.id })
        assertTrue(collections.all { it.kind == BrowseKind.DECADE })
    }

    @Test
    fun `folders distingue raiz subcarpetas externas colisionadas y desconocidas`() {
        val root = "/tmp/pm-library-browse/music"
        val collections = LibraryBrowseLogic.folders(
            listOf(
                song("root-b", "Beta", "Zoé", path = "$root/b.opus"),
                song("root-a", "Álbum", "Ana", path = "$root/a.opus"),
                song("sub", "Tema", path = "$root/Artista/Disco/c.opus"),
                song("ext-a", "Uno", path = "/tmp/pm-external-a/Rock/a.opus"),
                song("ext-b", "Dos", path = "/tmp/pm-external-b/Rock/b.opus"),
                song("unknown", "Tres", path = ""),
                song("broken", "Cuatro", path = "roto\u0000path"),
            ),
            root,
        )

        val direct = collections.first { it.label == "Descargas de PrivateMusic" }
        assertEquals(listOf("root-a", "root-b"), direct.songs.map { it.id })
        assertEquals("Artista/Disco", collections.single { it.label == "Artista/Disco" }.label)
        val external = collections.filter { it.label == "Rock" }
        assertEquals(2, external.size)
        assertEquals(setOf("pm-external-a/Rock", "pm-external-b/Rock"), external.mapNotNull { it.subtitle }.toSet())
        assertTrue(external.none { it.subtitle?.startsWith("/") == true })
        assertEquals(
            listOf("broken", "unknown"),
            collections.single { it.label == "Ubicación desconocida" }.songs.map { it.id },
        )
        assertEquals(2, collections.first().songs.size)
    }

    @Test
    fun `orden de canciones usa artista titulo e id normalizados`() {
        val collection = LibraryBrowseLogic.genres(
            listOf(
                song("z", "Beta", "Zoe", genres = listOf("Pop")),
                song("b", "Álfa", "Ana", genres = listOf("Pop")),
                song("a", "Alfa", "Ána", genres = listOf("Pop")),
            ),
        ).single()

        assertEquals(listOf("a", "b", "z"), collection.songs.map { it.id })
    }

    private fun song(
        id: String,
        title: String,
        artist: String = "Artista",
        year: Int? = null,
        genres: List<String> = emptyList(),
        path: String = "/music/$id.opus",
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        durationSec = 180,
        filePath = path,
        artPath = null,
        thumbnailUrl = null,
        addedAt = 0,
        year = year,
        genresJson = GenreCodec.encode(genres),
    )
}
