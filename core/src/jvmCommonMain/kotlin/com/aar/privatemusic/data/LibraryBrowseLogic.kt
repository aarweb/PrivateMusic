package com.aar.privatemusic.data

import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.lyrics.LyricSearch
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray

enum class BrowseKind { GENRE, DECADE, YEAR, FOLDER }

data class BrowseCollection(
    val kind: BrowseKind,
    val key: String,
    val label: String,
    val subtitle: String? = null,
    val songs: List<Song>,
)

/** Formato estable de los géneros guardados en Song. */
object GenreCodec {
    fun encode(genres: List<String>): String = JSONArray(canonical(genres)).toString()

    fun decode(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val parsed = runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    (array.opt(index) as? String)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
        return canonical(parsed)
    }

    fun encodeRaw(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val genres = canonical(value.split(rawSeparators))
        return genres.takeIf { it.isNotEmpty() }?.let(::encode)
    }

    private fun canonical(values: List<String>): List<String> {
        val variants = LinkedHashMap<String, MutableSet<String>>()
        values.forEach { raw ->
            val clean = clean(raw) ?: return@forEach
            val identity = LyricSearch.normalize(clean)
            if (identity.isNotBlank()) variants.getOrPut(identity) { linkedSetOf() } += clean
        }
        return variants.values.map { it.minWith(displayComparator) }
            .sortedWith(displayComparator)
    }

    private fun clean(value: String): String? = value.trim()
        .replace(spaces, " ")
        .takeIf { it.isNotBlank() }

    private val rawSeparators = Regex("[,;\\u0000]")
    private val spaces = Regex("\\s+")
    private val displayComparator = compareBy<String> { LyricSearch.normalize(it) }
        .thenBy { it.lowercase() }
        .thenBy { it }
}

/** Agrupa la biblioteca sin conocer Compose, Room ni Android. */
object LibraryBrowseLogic {
    fun genres(songs: List<Song>): List<BrowseCollection> {
        data class Bucket(
            val names: MutableSet<String> = linkedSetOf(),
            val songs: LinkedHashMap<String, Song> = LinkedHashMap(),
        )

        val buckets = LinkedHashMap<String, Bucket>()
        songs.forEach { song ->
            GenreCodec.decode(song.genresJson).forEach { genre ->
                val identity = LyricSearch.normalize(genre)
                val bucket = buckets.getOrPut(identity) { Bucket() }
                bucket.names += genre
                bucket.songs.putIfAbsent(song.id, song)
            }
        }
        return buckets.map { (identity, bucket) ->
            BrowseCollection(
                kind = BrowseKind.GENRE,
                key = identity,
                label = bucket.names.minWith(displayComparator),
                songs = bucket.songs.values.sortedWith(songComparator),
            )
        }.sortedWith(countThenLabelComparator)
    }

    fun decades(songs: List<Song>): List<BrowseCollection> = songs
        .validYears()
        .groupBy { (_, year) -> year / 10 * 10 }
        .entries
        .sortedByDescending { it.key }
        .map { (decade, entries) ->
            BrowseCollection(
                kind = BrowseKind.DECADE,
                key = decade.toString(),
                label = "Años $decade",
                songs = entries.map { it.first }.sortedWith(songComparator),
            )
        }

    fun years(songs: List<Song>): List<BrowseCollection> = songs
        .validYears()
        .groupBy { it.second }
        .entries
        .sortedByDescending { it.key }
        .map { (year, entries) ->
            BrowseCollection(
                kind = BrowseKind.YEAR,
                key = year.toString(),
                label = year.toString(),
                songs = entries.map { it.first }.sortedWith(songComparator),
            )
        }

    fun folders(songs: List<Song>, musicRootPath: String): List<BrowseCollection> {
        data class FolderInfo(val identity: String, val label: String, val subtitle: String?)
        data class Bucket(val info: FolderInfo, val songs: LinkedHashMap<String, Song> = LinkedHashMap())

        val root = canonicalDirectory(musicRootPath)
        val buckets = LinkedHashMap<String, Bucket>()
        songs.forEach { song ->
            val parent = canonicalParent(song.filePath)
            val info = when {
                parent == null -> FolderInfo(UNKNOWN_KEY, "Ubicación desconocida", null)
                root != null && parent.path == root.path ->
                    FolderInfo(folderKey(parent.path), "Descargas de PrivateMusic", null)
                root != null && parent.isInside(root) -> {
                    val relative = parent.path.removePrefix(root.path + File.separator)
                        .replace(File.separatorChar, '/')
                    FolderInfo(folderKey(parent.path), relative, null)
                }
                else -> {
                    val name = parent.name.ifBlank { "Ubicación desconocida" }
                    val parentName = parent.parentFile?.name?.takeIf { it.isNotBlank() }
                    val subtitle = listOfNotNull(parentName, name).joinToString("/")
                    FolderInfo(folderKey(parent.path), name, subtitle)
                }
            }
            buckets.getOrPut(info.identity) { Bucket(info) }.songs.putIfAbsent(song.id, song)
        }
        return buckets.values.map { bucket ->
            BrowseCollection(
                kind = BrowseKind.FOLDER,
                key = bucket.info.identity,
                label = bucket.info.label,
                subtitle = bucket.info.subtitle,
                songs = bucket.songs.values.sortedWith(songComparator),
            )
        }.sortedWith(countThenLabelComparator)
    }

    private fun List<Song>.validYears(): List<Pair<Song, Int>> = mapNotNull { song ->
        song.year?.takeIf { it in MIN_YEAR..MAX_YEAR }?.let { song to it }
    }

    private fun canonicalDirectory(path: String): File? {
        if (path.isBlank()) return null
        return runCatching { File(path).canonicalFile }.getOrNull()
    }

    private fun canonicalParent(path: String): File? {
        if (path.isBlank() || '\u0000' in path) return null
        return runCatching { File(path).canonicalFile.parentFile?.canonicalFile }.getOrNull()
    }

    private fun File.isInside(root: File): Boolean =
        path.startsWith(root.path + File.separator)

    private fun folderKey(path: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(path.encodeToByteArray())
        return "folder:" + bytes.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private val songComparator = compareBy<Song> { LyricSearch.normalize(it.artist) }
        .thenBy { LyricSearch.normalize(it.title) }
        .thenBy { it.id }
    private val displayComparator = compareBy<String> { LyricSearch.normalize(it) }
        .thenBy { it.lowercase() }
        .thenBy { it }
    private val countThenLabelComparator = compareByDescending<BrowseCollection> { it.songs.size }
        .thenBy { LyricSearch.normalize(it.label) }
        .thenBy { LyricSearch.normalize(it.subtitle.orEmpty()) }
        .thenBy { it.label }
        .thenBy { it.subtitle.orEmpty() }
        .thenBy { it.key }

    private const val MIN_YEAR = 1000
    private const val MAX_YEAR = 2100
    private const val UNKNOWN_KEY = "folder:unknown"
}
