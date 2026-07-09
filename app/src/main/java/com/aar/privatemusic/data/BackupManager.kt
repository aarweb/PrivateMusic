package com.aar.privatemusic.data

import com.aar.privatemusic.data.db.openMusicDatabase
import com.aar.privatemusic.data.db.walCheckpoint
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

    /**
     * Los dos sitios donde viven las canciones: el directorio externo de la app
     * (descargas) y el interno (torrents). Las rutas absolutas cambian al
     * reinstalar o al cambiar de móvil, así que en los M3U se guarda la ruta
     * relativa a uno de estos dos y al importar se prueba con los de la máquina
     * de destino.
     */
    private fun roots(context: Context): List<File> =
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir)

    /** "…/files/music/x.m4a" → "music/x.m4a". Devuelve la absoluta si no cuelga de ninguna raíz. */
    private fun relativize(path: String, roots: List<File>): String {
        val file = File(path)
        roots.forEach { root ->
            val prefix = root.absolutePath + File.separator
            if (path.startsWith(prefix)) return file.absolutePath.removePrefix(prefix)
        }
        return path
    }

    /** Writes a ZIP to the user-picked location: library.csv + one M3U per playlist. */
    suspend fun exportLibraryZip(context: Context, uri: Uri, repository: MusicRepository): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val dao = openMusicDatabase(context).musicDao()
                val songs = dao.songsOnce()
                val playlists = dao.playlistsOnce()
                val smart = dao.smartPlaylistsOnce()
                val roots = roots(context)

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
                                        if (s.isFavorite) "1" else "0",
                                        csvEscape(relativize(s.filePath, roots)),
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
                                pl.description?.takeIf { it.isNotBlank() }?.let { appendLine("#PLAYLIST-DESC:$it") }
                                plSongs.forEach { s ->
                                    appendLine("#EXTINF:${s.durationSec},${s.artist} - ${s.title}")
                                    appendLine(relativize(s.filePath, roots))
                                }
                            }
                            zip.write(m3u.toByteArray())
                            zip.closeEntry()
                        }

                        // Las playlists inteligentes no son una lista de canciones,
                        // son sus reglas: sin esto, una copia de seguridad las perdía.
                        if (smart.isNotEmpty()) {
                            zip.putNextEntry(ZipEntry("smart-playlists.json"))
                            val json = org.json.JSONArray()
                            smart.forEach { sp ->
                                json.put(
                                    org.json.JSONObject()
                                        .put("name", sp.name)
                                        .put(
                                            "rules",
                                            org.json.JSONObject(
                                                SmartRuleEngine.toJson(SmartRuleEngine.rulesOf(sp))
                                            ),
                                        )
                                )
                            }
                            zip.write(json.toString(2).toByteArray())
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
     * @param matched canciones del fichero que existen en la biblioteca
     * @param added las que de verdad se añadieron (el resto ya estaban)
     * @param merged true si se fusionó con una playlist existente del mismo nombre
     */
    data class ImportResult(
        val playlistName: String,
        val matched: Int,
        val added: Int,
        val merged: Boolean,
        val smartImported: Int = 0,
    )

    /**
     * Importa un .m3u/.m3u8, un .csv o un smart-playlists.json.
     *
     * Las entradas se buscan primero por ruta (relativa a las raíces de esta
     * máquina, o absoluta si viene de una exportación vieja) y después por
     * "artista - título" del #EXTINF, que es lo único que sobrevive a un cambio
     * de móvil. Si ya existe una playlist con ese nombre, se fusiona en vez de
     * crear una copia: importar dos veces el mismo fichero no duplica nada.
     */
    suspend fun importPlaylist(context: Context, uri: Uri, repository: MusicRepository): ImportResult? =
        withContext(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: return@withContext null

                if (text.trimStart().startsWith("[")) {
                    return@withContext importSmartPlaylists(context, text)
                }

                val dao = openMusicDatabase(context).musicDao()
                val songs = dao.songsOnce()
                val byPath = songs.associateBy { it.filePath }
                val roots = roots(context)

                val matched = mutableListOf<Song>()
                var lastExtinf: String? = null
                var descFromFile: String? = null

                fun byExtinf(label: String): Song? {
                    val artist = label.substringBefore(" - ", "").trim()
                    val title = label.substringAfter(" - ", label).trim()
                    return songs.firstOrNull {
                        it.title.equals(title, ignoreCase = true) &&
                            (artist.isEmpty() || it.artist.equals(artist, ignoreCase = true))
                    } ?: songs.firstOrNull { it.title.equals(title, ignoreCase = true) }
                }

                text.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                    when {
                        line.startsWith("#PLAYLIST-DESC:") ->
                            descFromFile = line.removePrefix("#PLAYLIST-DESC:").trim()
                        line.startsWith("#EXTINF:") ->
                            lastExtinf = line.substringAfter(',', "").trim().ifBlank { null }
                        line.startsWith("#") -> Unit
                        else -> {
                            val resolved = byPath[line]
                                ?: roots.firstNotNullOfOrNull { byPath[File(it, line).absolutePath] }
                                ?: lastExtinf?.let { byExtinf(it) }
                                ?: run {
                                    val needle = line.substringAfterLast('/').substringBeforeLast('.')
                                    songs.firstOrNull {
                                        needle.contains(it.title, ignoreCase = true) ||
                                            it.title.contains(needle, ignoreCase = true)
                                    }
                                }
                            resolved?.let { matched.add(it) }
                            lastExtinf = null
                        }
                    }
                }

                val unique = matched.distinctBy { it.id }
                val name = uri.lastPathSegment?.substringAfterLast('/')
                    ?.substringBeforeLast('.')?.take(40) ?: "Importada"
                if (unique.isEmpty()) return@withContext ImportResult(name, 0, 0, false)

                val existing = dao.playlistsOnce().firstOrNull { it.name.equals(name, ignoreCase = true) }
                val playlistId = existing?.id ?: repository.createPlaylist(name)
                if (existing == null) {
                    descFromFile?.let { repository.renamePlaylist(playlistId, name, it) }
                }
                val added = repository.addSongsToPlaylist(playlistId, unique.map { it.id })
                ImportResult(name, unique.size, added, merged = existing != null)
            } catch (e: Exception) {
                null
            }
        }

    /** Reglas exportadas: se salta las que ya existen con el mismo nombre. */
    private suspend fun importSmartPlaylists(context: Context, json: String): ImportResult {
        val dao = openMusicDatabase(context).musicDao()
        val existing = dao.smartPlaylistsOnce().map { it.name.lowercase() }.toSet()
        val array = org.json.JSONArray(json)
        var imported = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            if (name.lowercase() in existing) continue
            val rules = obj.optJSONObject("rules")?.toString() ?: continue
            // Se valida antes de guardar: unas reglas ilegibles darían una
            // playlist inteligente que no se puede ni abrir ni editar.
            if (SmartRuleEngine.fromJson(rules) == null) continue
            dao.insertSmartPlaylist(
                com.aar.privatemusic.data.db.SmartPlaylist(
                    name = name,
                    artistContains = null,
                    onlyFavorites = false,
                    minPlays = 0,
                    addedWithinDays = 0,
                    createdAt = System.currentTimeMillis(),
                    rulesJson = rules,
                )
            )
            imported++
        }
        return ImportResult("Playlists inteligentes", 0, 0, merged = false, smartImported = imported)
    }

    /** Copies the Room DB (after a WAL checkpoint) into the app backup dir; keeps the last 5. */
    suspend fun backupDatabase(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            openMusicDatabase(context).walCheckpoint()
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
