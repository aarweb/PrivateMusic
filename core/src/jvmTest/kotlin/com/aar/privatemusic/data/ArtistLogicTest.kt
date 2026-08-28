package com.aar.privatemusic.data

import com.aar.privatemusic.data.db.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtistLogicTest {
    @Test
    fun `encuentra por artista y albumArtist normalizando case y espacios`() {
        val songs = listOf(
            song("artist", "Uno", artist = "  La   Banda "),
            song("album", "Dos", artist = "Invitado", albumArtist = "LA BANDA"),
            song("other", "Tres", artist = "Otra Banda"),
        )

        assertEquals(
            listOf("artist", "album"),
            ArtistLogic.songsFor(songs, " la banda ").map { it.id },
        )
        assertEquals(emptyList(), ArtistLogic.songsFor(songs, "   "))
    }

    @Test
    fun `agrupa solo los albumes del artista`() {
        val albums = ArtistLogic.albumsFor(
            listOf(
                song("a1", "Uno", artist = "Artista", album = "Primero", track = 1),
                song("a2", "Dos", artist = "Artista", album = "Primero", track = 2),
                song("b", "Otro", artist = "Ajeno", album = "Segundo"),
                song("single", "Single", artist = "Artista", album = null),
            ),
            "ARTISTA",
        )

        assertEquals(1, albums.size)
        assertEquals("Primero", albums.single().key.title)
        assertEquals(listOf("a1", "a2"), albums.single().songs.map { it.id })
    }

    @Test
    fun `destacadas ordena por escuchas favorita titulo e id y respeta limite`() {
        val songs = listOf(
            song("z", "Zeta", favorite = true),
            song("b", "Beta"),
            song("a2", "alfa"),
            song("a1", "Alfa"),
            song("top", "Top"),
        )
        val counts = mapOf("top" to 9, "z" to 3, "b" to 3, "a1" to 1, "a2" to 1)

        assertEquals(
            listOf("top", "z", "b", "a1"),
            ArtistLogic.featuredSongs(songs, counts, limit = 4).map { it.id },
        )
        assertEquals(emptyList(), ArtistLogic.featuredSongs(songs, counts, limit = 0))
    }

    private fun song(
        id: String,
        title: String,
        artist: String = "Artista",
        album: String? = null,
        albumArtist: String? = null,
        track: Int? = null,
        favorite: Boolean = false,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        durationSec = 180,
        filePath = "/music/$id.opus",
        artPath = null,
        thumbnailUrl = null,
        addedAt = 0,
        isFavorite = favorite,
        album = album,
        albumArtist = albumArtist,
        trackNumber = track,
    )
}
