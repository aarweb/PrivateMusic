package com.aar.privatemusic.data

import com.aar.privatemusic.data.db.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlbumLogicTest {
    @Test
    fun `agrupa normalizando album y usa artist como fallback`() {
        val songs = listOf(
            song("1", "Uno", artist = "La Banda", album = "  Luz   Azul ", track = 1),
            song("2", "Dos", artist = "LA  BANDA", album = "luz azul", track = 2),
            song("3", "Otro", artist = "Solista", album = "Luz Azul"),
            song("4", "Sin álbum", artist = "La Banda", album = "   "),
            song("5", "Tampoco", artist = "La Banda", album = null),
        )

        val albums = AlbumLogic.group(songs)

        assertEquals(2, albums.size)
        assertEquals(listOf("1", "2"), albums.first { it.key.artist.equals("La Banda", true) }.songs.map { it.id })
        assertTrue(albums.none { album -> album.songs.any { it.id == "4" || it.id == "5" } })
    }

    @Test
    fun `separa el mismo titulo por albumArtist`() {
        val albums = AlbumLogic.group(
            listOf(
                song("a", "Tema", artist = "Invitado", album = "Directo", albumArtist = "Grupo A"),
                song("b", "Tema", artist = "Invitado", album = "directo", albumArtist = "Grupo B"),
            ),
        )

        assertEquals(listOf("Grupo A", "Grupo B"), albums.map { it.key.artist })
    }

    @Test
    fun `ordena pistas y conserva duplicados y numeros desconocidos`() {
        val duplicate = song("dup", "Reprise", album = "Disco", track = 2, addedAt = 5)
        val album = AlbumLogic.group(
            listOf(
                song("zero", "Coda", album = "Disco", track = 0),
                song("three", "Final", album = "Disco", track = 3),
                duplicate,
                song("one", "Inicio", album = "Disco", track = 1),
                song("null", "Balada", album = "Disco", track = null),
                duplicate,
            ),
        ).single()

        assertEquals(listOf("one", "dup", "dup", "three", "null", "zero"), album.songs.map { it.id })
    }

    @Test
    fun `calcula moda de year desempate menor duracion y ultima alta`() {
        val mode = AlbumLogic.group(
            listOf(
                song("1", "A", album = "Moda", year = 2020, duration = 100, addedAt = 2),
                song("2", "B", album = "Moda", year = 2021, duration = 200, addedAt = 9),
                song("3", "C", album = "Moda", year = 2021, duration = 300, addedAt = 4),
                song("4", "D", album = "Moda", year = null, duration = 400, addedAt = 1),
            ),
        ).single()
        val tie = AlbumLogic.group(
            listOf(
                song("5", "A", album = "Empate", year = 2022),
                song("6", "B", album = "Empate", year = 2019),
            ),
        ).single()

        assertEquals(2021, mode.year)
        assertEquals(1_000, mode.durationSec)
        assertEquals(9, mode.latestAddedAt)
        assertEquals(2019, tie.year)
    }

    @Test
    fun `resultado es determinista con entrada invertida y ordena albumes`() {
        val input = listOf(
            song("2", "B", album = "zeta", albumArtist = "beta", addedAt = 2),
            song("1", "A", album = "ZETA", albumArtist = "Beta", addedAt = 1),
            song("3", "C", album = "Alfa", albumArtist = "Uno"),
        )

        val normal = AlbumLogic.group(input)
        val reversed = AlbumLogic.group(input.reversed())

        assertEquals(normal, reversed)
        assertEquals(listOf("Alfa", "ZETA"), normal.map { it.key.title })
        assertEquals("Beta", normal.last().key.artist)
    }

    @Test
    fun `tracksFor usa case y espacios de la misma identidad`() {
        val songs = listOf(
            song("2", "Segundo", album = "Gran   Disco", albumArtist = "La Banda", track = 2),
            song("1", "Primero", album = "gran disco", albumArtist = "la  banda", track = 1),
            song("x", "Ajeno", album = "Gran Disco", albumArtist = "Otra"),
        )

        assertEquals(
            listOf("1", "2"),
            AlbumLogic.tracksFor(songs, "  GRAN disco ", " LA   BANDA ").map { it.id },
        )
        assertTrue(AlbumLogic.tracksFor(songs, "inexistente", "La Banda").isEmpty())
    }

    @Test
    fun `protege la suma de duracion frente a overflow`() {
        val album = AlbumLogic.group(
            listOf(
                song("1", "A", album = "Largo", duration = Int.MAX_VALUE),
                song("2", "B", album = "Largo", duration = 10),
            ),
        ).single()

        assertEquals(Int.MAX_VALUE, album.durationSec)
    }

    private fun song(
        id: String,
        title: String,
        artist: String = "Artista",
        album: String?,
        albumArtist: String? = null,
        track: Int? = null,
        year: Int? = null,
        duration: Int = 180,
        addedAt: Long = 0,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        durationSec = duration,
        filePath = "/music/$id.opus",
        artPath = null,
        thumbnailUrl = null,
        addedAt = addedAt,
        album = album,
        albumArtist = albumArtist,
        year = year,
        trackNumber = track,
    )
}
