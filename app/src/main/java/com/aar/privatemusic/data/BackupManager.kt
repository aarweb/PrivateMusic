package com.aar.privatemusic.data

import android.content.Context
import android.net.Uri
import com.aar.privatemusic.data.db.MusicDatabase
import com.aar.privatemusic.data.db.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Library export (ZIP with M3U playlists + CSV), M3U/CSV import and DB backups. */
object BackupManager {

    /** Writes a ZIP to the user-picked location: library.csv + one M3U per playlist. */
    suspend fun exportLibraryZip(context: Context, uri: Uri, repository: MusicRepository): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val dao = MusicDatabase.get(context).musicDao()
                val songs = dao.songsOnce()
                val playlists = dao.playlistsOnce()

                context.contentResolver.openOutputStream(uri)?.use { out ->
                    ZipOutputStream(out).use { zip ->
                        // Full library as CSV
                        zip.putNextEntry(ZipEntry("library.csv"))
                        val csv = buildString {
                            appendLine("id,title,artist,durationSec,favorite,filePath")
                            songs.forEach { s ->
                                appendLine(
                                    listOf(
                                        s.id, csvEscape(s.title), csvEscape(s.artist),
                                        s.durationSec.toString(),
                                        if (s.isFavorite) "1" else "0", csvEscape(s.filePath),
                                    ).joinToString(",")
                                )
                            }
                        }
                        zip.write(csv.toByteArray())
                        zip.closeEntry()

                        // One extended M3U per playlist
                        playlists.forEach { pl ->
                            val plSongs = dao.playlistSongsOnce(pl.id)
                            zip.putNextEntry(ZipEntry("playlists/${sanitize(pl.name)}.m3u"))
                            val m3u = buildString {
                                appendLine("#EXTM3U")
                                plSongs.forEach { s ->
                                    appendLine("#EXTINF:${s.durationSec},${s.artist} - ${s.title}")
                                    appendLine(s.filePath)
                                }
                            }
                            zip.write(m3u.toByteArray())
                            zip.closeEntry()
                        }
                    }
                } ?: return@withContext false
                true
            } catch (e: Exception) {
                false
            }
        }

    /**
     * Imports an .m3u/.m3u8 or .csv file as a new playlist, matching entries
     * against the library by file path first, then by "artist - title".
     * Returns the number of matched songs, or -1 on failure.
     */
    suspend fun importPlaylist(context: Context, uri: Uri, repository: MusicRepository): Int =
        withContext(Dispatchers.IO) {
            try {
                val dao = MusicDatabase.get(context).musicDao()
                val songs = dao.songsOnce()
                val byPath = songs.associateBy { it.filePath }
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: return@withContext -1

                val matched = mutableListOf<Song>()
                text.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                    when {
                        line.startsWith("#") -> Unit // M3U metadata
                        byPath.containsKey(line) -> matched.add(byPath.getValue(line))
                        else -> {
                            // CSV row or free text: fuzzy match on title/artist
                            val needle = line.substringAfterLast('/')
                                .removeSuffix(".webm").removeSuffix(".m4a").removeSuffix(".opus")
                            songs.firstOrNull {
                                needle.contains(it.title, ignoreCase = true) ||
                                    it.title.contains(needle, ignoreCase = true) ||
                                    "${it.artist} - ${it.title}".equals(needle, ignoreCase = true)
                            }?.let { matched.add(it) }
                        }
                    }
                }
                val unique = matched.distinctBy { it.id }
                if (unique.isEmpty()) return@withContext 0

                val name = uri.lastPathSegment?.substringAfterLast('/')
                    ?.substringBeforeLast('.')?.take(40) ?: "Importada"
                val playlistId = repository.createPlaylist(name)
                unique.forEach { repository.addToPlaylist(playlistId, it.id) }
                unique.size
            } catch (e: Exception) {
                -1
            }
        }

    /** Copies the Room DB (after a WAL checkpoint) into the app backup dir; keeps the last 5. */
    suspend fun backupDatabase(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val db = MusicDatabase.get(context)
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            val source = context.getDatabasePath("music.db")
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")
                .apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val dest = File(dir, "music-$stamp.db")
            source.copyTo(dest, overwrite = true)
            dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
            dest
        } catch (e: Exception) {
            null
        }
    }

    private fun csvEscape(value: String): String =
        if (value.contains(',') || value.contains('"')) "\"${value.replace("\"", "\"\"")}\"" else value

    private fun sanitize(name: String): String = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
}
