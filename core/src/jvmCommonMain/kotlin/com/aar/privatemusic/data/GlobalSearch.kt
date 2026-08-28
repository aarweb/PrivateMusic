package com.aar.privatemusic.data

import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.lyrics.LyricSearch

data class GlobalArtistResult(val name: String, val songs: List<Song>)

data class GlobalSearchResults(
    val songs: List<Song>,
    val albums: List<AlbumCollection>,
    val artists: List<GlobalArtistResult>,
    val playlists: List<Playlist>,
)

/** Búsqueda global determinista, sin dependencias de Android, disco ni red. */
object GlobalSearch {
    fun search(
        songs: List<Song>,
        playlists: List<Playlist>,
        query: String,
        lyricSongIds: Set<String> = emptySet(),
        maxPerSection: Int = 8,
    ): GlobalSearchResults {
        val wanted = LyricSearch.normalize(query)
        if (wanted.isBlank() || maxPerSection <= 0) return EMPTY

        val songResults = songs.mapNotNull { song ->
            val metadataRank = bestRank(
                wanted,
                song.title,
                song.artist,
                song.album,
                song.albumArtist,
                song.note,
            )
            val rank = metadataRank ?: if (song.id in lyricSongIds) LYRIC_RANK else null
            rank?.let { Ranked(song, it) }
        }.sortedWith(
            rankedComparator(
                primary = { it.title },
                secondary = { it.artist },
                stableId = { it.id },
            ),
        ).take(maxPerSection).map { it.value }

        val albumResults = AlbumLogic.group(songs).mapNotNull { album ->
            bestRank(wanted, album.key.title, album.key.artist, album.year?.toString())
                ?.let { Ranked(album, it) }
        }.sortedWith(
            rankedComparator(
                primary = { it.key.title },
                secondary = { it.key.artist },
                stableId = { "${it.key.artist}\u0000${it.key.title}" },
            ),
        ).take(maxPerSection).map { it.value }

        val artistResults = artistsOf(songs).mapNotNull { artist ->
            rank(artist.name, wanted)?.let { Ranked(artist, it) }
        }.sortedWith(
            rankedComparator(
                primary = { it.name },
                secondary = { "" },
                stableId = { it.name },
            ),
        ).take(maxPerSection).map { it.value }

        val playlistResults = playlists.mapNotNull { playlist ->
            bestRank(wanted, playlist.name, playlist.description)
                ?.let { Ranked(playlist, it) }
        }.sortedWith(
            rankedComparator(
                primary = { it.name },
                secondary = { it.description.orEmpty() },
                stableId = { it.id.toString() },
            ),
        ).take(maxPerSection).map { it.value }

        return GlobalSearchResults(songResults, albumResults, artistResults, playlistResults)
    }

    private fun artistsOf(songs: List<Song>): List<GlobalArtistResult> {
        data class ArtistBucket(
            val names: MutableSet<String> = linkedSetOf(),
            val songs: LinkedHashMap<String, Song> = LinkedHashMap(),
        )

        val buckets = LinkedHashMap<String, ArtistBucket>()
        songs.forEach { song ->
            listOf(song.artist, song.albumArtist).forEach { rawName ->
                val display = clean(rawName) ?: return@forEach
                val identity = LyricSearch.normalize(display)
                if (identity.isBlank()) return@forEach
                val bucket = buckets.getOrPut(identity) { ArtistBucket() }
                bucket.names += display
                bucket.songs.putIfAbsent(song.id, song)
            }
        }
        return buckets.values.map { bucket ->
            GlobalArtistResult(
                name = bucket.names.minWith(displayComparator),
                songs = bucket.songs.values.sortedWith(songComparator),
            )
        }
    }

    private fun bestRank(query: String, vararg values: String?): Int? =
        values.mapNotNull { value -> value?.let { rank(it, query) } }.minOrNull()

    private fun rank(value: String, query: String): Int? {
        val normalized = LyricSearch.normalize(value)
        return when {
            normalized == query -> EXACT_RANK
            normalized.startsWith(query) -> PREFIX_RANK
            normalized.contains(query) -> CONTAINS_RANK
            else -> null
        }
    }

    private fun clean(value: String?): String? = value
        ?.trim()
        ?.replace(spaces, " ")
        ?.takeIf { it.isNotBlank() }

    private data class Ranked<T>(val value: T, val rank: Int)

    private fun <T> rankedComparator(
        primary: (T) -> String,
        secondary: (T) -> String,
        stableId: (T) -> String,
    ): Comparator<Ranked<T>> = compareBy<Ranked<T>> { it.rank }
        .thenBy { LyricSearch.normalize(primary(it.value)) }
        .thenBy { LyricSearch.normalize(secondary(it.value)) }
        .thenBy { primary(it.value) }
        .thenBy { secondary(it.value) }
        .thenBy { stableId(it.value) }

    private val songComparator = compareBy<Song> { LyricSearch.normalize(it.title) }
        .thenBy { LyricSearch.normalize(it.artist) }
        .thenBy { it.addedAt }
        .thenBy { it.id }
    private val displayComparator = compareBy<String> { LyricSearch.normalize(it) }
        .thenBy { it.lowercase() }
        .thenBy { it }
    private val spaces = Regex("\\s+")

    private const val EXACT_RANK = 0
    private const val PREFIX_RANK = 1
    private const val CONTAINS_RANK = 2
    private const val LYRIC_RANK = 3
    private val EMPTY = GlobalSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
}
