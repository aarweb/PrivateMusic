package com.aar.privatemusic.data

import com.aar.privatemusic.data.db.Song

/** Lógica de la ficha de artista compartida y comprobable sin Android. */
object ArtistLogic {
    fun songsFor(songs: List<Song>, artistName: String): List<Song> {
        val wanted = normalize(artistName)
        if (wanted.isBlank()) return emptyList()
        return songs.filter { song ->
            normalize(song.artist) == wanted || normalize(song.albumArtist) == wanted
        }
    }

    fun albumsFor(songs: List<Song>, artistName: String): List<AlbumCollection> =
        AlbumLogic.group(songsFor(songs, artistName))

    fun featuredSongs(
        songs: List<Song>,
        playCounts: Map<String, Int>,
        limit: Int = 5,
    ): List<Song> {
        if (limit <= 0) return emptyList()
        return songs.sortedWith(
            compareByDescending<Song> { playCounts[it.id] ?: 0 }
                .thenByDescending { it.isFavorite }
                .thenBy { it.title.lowercase() }
                .thenBy { it.title }
                .thenBy { it.id },
        ).take(limit)
    }

    private fun normalize(value: String?): String = value
        ?.trim()
        ?.replace(whitespace, " ")
        ?.lowercase()
        .orEmpty()

    private val whitespace = Regex("\\s+")
}
