package com.aar.privatemusic.data

import com.aar.privatemusic.data.db.Song

data class AlbumKey(val title: String, val artist: String)

data class AlbumCollection(
    val key: AlbumKey,
    val songs: List<Song>,
    val year: Int?,
    val durationSec: Int,
    val latestAddedAt: Long,
)

/** Agrupación de álbumes compartida por Android y escritorio. */
object AlbumLogic {
    fun group(songs: List<Song>): List<AlbumCollection> {
        val groups = LinkedHashMap<NormalizedKey, MutableList<Song>>()
        songs.forEach { song ->
            val key = normalizedKey(song) ?: return@forEach
            groups.getOrPut(key) { mutableListOf() }.add(song)
        }

        return groups.values.map { albumSongs ->
            val title = albumSongs.mapNotNull { clean(it.album) }.minWith(displayComparator)
            val artist = albumSongs.map { clean(it.albumArtist) ?: clean(it.artist).orEmpty() }
                .minWith(displayComparator)
            AlbumCollection(
                key = AlbumKey(title, artist),
                songs = albumSongs.sortedWith(trackComparator),
                year = mostFrequentYear(albumSongs),
                durationSec = albumSongs.fold(0L) { total, song ->
                    (total + song.durationSec).coerceAtMost(Int.MAX_VALUE.toLong())
                }.toInt(),
                latestAddedAt = albumSongs.maxOfOrNull { it.addedAt } ?: 0L,
            )
        }.sortedWith(
            compareBy<AlbumCollection> { it.key.title.lowercase() }
                .thenBy { it.key.artist.lowercase() }
                .thenBy { it.key.title }
                .thenBy { it.key.artist },
        )
    }

    fun tracksFor(songs: List<Song>, title: String, artist: String): List<Song> {
        val wanted = NormalizedKey(normalize(title), normalize(artist))
        if (wanted.title.isBlank()) return emptyList()
        return songs.asSequence()
            .filter { normalizedKey(it) == wanted }
            .sortedWith(trackComparator)
            .toList()
    }

    private fun normalizedKey(song: Song): NormalizedKey? {
        val album = clean(song.album) ?: return null
        val albumArtist = clean(song.albumArtist) ?: clean(song.artist).orEmpty()
        return NormalizedKey(normalize(album), normalize(albumArtist))
    }

    private fun mostFrequentYear(songs: List<Song>): Int? {
        val counts = mutableMapOf<Int, Int>()
        songs.forEach { song -> song.year?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()
            ?.key
    }

    private fun clean(value: String?): String? = value
        ?.trim()
        ?.replace(whitespace, " ")
        ?.takeIf { it.isNotBlank() }

    private fun normalize(value: String): String = clean(value)?.lowercase().orEmpty()

    private data class NormalizedKey(val title: String, val artist: String)

    private val whitespace = Regex("\\s+")
    private val displayComparator = compareBy<String> { it.lowercase() }.thenBy { it }
    private val trackComparator = compareBy<Song> {
        if ((it.trackNumber ?: 0) > 0) 0 else 1
    }.thenBy {
        it.trackNumber?.takeIf { number -> number > 0 } ?: Int.MAX_VALUE
    }.thenBy { it.title.lowercase() }
        .thenBy { it.addedAt }
        .thenBy { it.id }
}
