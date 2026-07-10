package com.aar.privatemusic.desktop.sync

import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.PlayEvent
import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.PlaylistSongCrossRef
import com.aar.privatemusic.data.db.SmartPlaylist
import com.aar.privatemusic.data.db.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class SyncResult(
    val songsAdded: Int,
    val filesDownloaded: Int,
    val playlists: Int,
    val bytes: Long,
    val playEvents: Int = 0,
    val smartPlaylists: Int = 0,
    val playlistsUploaded: Int = 0,
)

/**
 * Se trae del móvil lo que le falta al PC.
 *
 * El móvil manda ids; el PC decide dónde guarda cada fichero y escribe su
 * propia ruta absoluta en su propia base de datos. Ninguna ruta cruza la red:
 * `/storage/emulated/0/...` no significa nada aquí.
 *
 * Es aditivo: trae lo nuevo y no borra nada. Que el PC olvide canciones
 * borradas en el móvil es asunto de la sincronización de estado, que llega
 * después.
 */
class SyncClient(
    private val dao: MusicDao,
    private val musicDir: File,
    private val artDir: File,
) {

    suspend fun sync(phone: Phone, onProgress: (String) -> Unit): SyncResult = withContext(Dispatchers.IO) {
        onProgress("Pidiendo la biblioteca a ${phone.name}…")
        val root = JSONObject(httpGet("${phone.baseUrl}/library"))

        val songs = root.getJSONArray("songs")
        var downloaded = 0
        var bytes = 0L

        for (i in 0 until songs.length()) {
            val s = songs.getJSONObject(i)
            val id = s.getString("id")
            val title = s.getString("title")
            onProgress("[${i + 1}/${songs.length()}] $title")

            val ext = s.optString("ext").ifBlank { "mp3" }
            val expected = s.optLong("sizeBytes", 0L)
            val audio = File(musicDir, "$id.$ext")
            // Un fichero a medias tiene el tamaño equivocado; se vuelve a bajar.
            if (!audio.exists() || (expected > 0 && audio.length() != expected)) {
                download("${phone.baseUrl}/song/$id", audio)
                downloaded++
                bytes += audio.length()
            }

            // Sin carátula se puede vivir: si falla, la canción entra igual.
            val art: File? = if (!s.optBoolean("hasArt")) null else {
                val target = File(artDir, "$id.jpg")
                if (target.exists()) target
                else runCatching { download("${phone.baseUrl}/art/$id", target); target }.getOrNull()
            }

            dao.insertSong(
                Song(
                    id = id,
                    title = title,
                    artist = s.getString("artist"),
                    durationSec = s.getInt("durationSec"),
                    filePath = audio.absolutePath,
                    artPath = art?.takeIf { it.exists() }?.absolutePath,
                    thumbnailUrl = null,
                    addedAt = s.getLong("addedAt"),
                    isFavorite = s.optBoolean("isFavorite"),
                    codec = s.optStringOrNull("codec"),
                    bitrateKbps = s.optIntOrNull("bitrateKbps"),
                    sampleRateHz = s.optIntOrNull("sampleRateHz"),
                    loudnessDb = s.optFloatOrNull("loudnessDb"),
                    bpm = s.optFloatOrNull("bpm"),
                    camelot = s.optStringOrNull("camelot"),
                    album = s.optStringOrNull("album"),
                    albumArtist = s.optStringOrNull("albumArtist"),
                    year = s.optIntOrNull("year"),
                    trackNumber = s.optIntOrNull("trackNumber"),
                    mbid = s.optStringOrNull("mbid"),
                    isrc = s.optStringOrNull("isrc"),
                    metadataResolved = s.optBoolean("metadataResolved"),
                ),
            )
        }

        val playlists = root.getJSONArray("playlists")
        val uploaded = syncPlaylists(root, phone, onProgress)

        // Un móvil con la 1.69 o anterior no manda ni el historial ni las
        // inteligentes: el PC se queda como estaba en vez de vaciarse.
        val events = syncHistory(root, onProgress)
        val smart = syncSmartPlaylists(root)

        onProgress("Listo")
        SyncResult(songs.length(), downloaded, playlists.length(), bytes, events, smart, uploaded)
    }

    /**
     * Fusiona las playlists en los dos sentidos: gana la más reciente, playlist
     * por playlist. Devuelve cuántas se subieron al móvil.
     *
     * Un móvil con el protocolo 2 o anterior no manda `updatedAt` ni sabe recibir
     * nada, así que con él se conserva el comportamiento de siempre: el móvil
     * manda y el PC copia. Inventarse una fecha para poder "ganar" le borraría al
     * usuario playlists que nunca tocó.
     */
    private suspend fun syncPlaylists(root: JSONObject, phone: Phone, onProgress: (String) -> Unit): Int {
        val incoming = root.getJSONArray("playlists")
        val bidirectional = root.optInt("version", 1) >= 3
        val mine = dao.allPlaylistsForSync().associateBy { it.id }

        for (i in 0 until incoming.length()) {
            val p = incoming.getJSONObject(i)
            val id = p.getLong("id")
            val theirUpdatedAt = p.optLong("updatedAt", p.getLong("createdAt"))
            val current = mine[id]
            // Empate: no se toca nada. Reescribir por reescribir borra el orden.
            if (bidirectional && current != null && current.updatedAt >= theirUpdatedAt) continue

            val deletedAt = if (p.has("deletedAt")) p.getLong("deletedAt") else null
            dao.upsertPlaylist(
                Playlist(
                    id = id,
                    name = p.getString("name"),
                    createdAt = p.getLong("createdAt"),
                    description = p.optStringOrNull("description"),
                    coverPath = current?.coverPath,
                    isPinned = p.optBoolean("isPinned"),
                    folderId = current?.folderId,
                    updatedAt = theirUpdatedAt,
                    deletedAt = deletedAt,
                ),
            )
            dao.clearPlaylist(id)
            if (deletedAt == null) {
                val songIds = p.getJSONArray("songIds")
                for (j in 0 until songIds.length()) {
                    dao.addToPlaylist(PlaylistSongCrossRef(id, songIds.getString(j), j))
                }
            }
        }

        if (!bidirectional) return 0

        // Lo que aquí es más nuevo, o que el móvil no conoce, sube.
        val incomingDates = (0 until incoming.length()).associate {
            val p = incoming.getJSONObject(it)
            p.getLong("id") to p.optLong("updatedAt", p.getLong("createdAt"))
        }
        val toUpload = mine.values.filter { it.updatedAt > (incomingDates[it.id] ?: Long.MIN_VALUE) }
        if (toUpload.isEmpty()) return 0

        onProgress("Subiendo ${toUpload.size} playlists al móvil…")
        val payload = JSONArray()
        toUpload.forEach { playlist ->
            payload.put(
                JSONObject().apply {
                    put("id", playlist.id)
                    put("name", playlist.name)
                    put("createdAt", playlist.createdAt)
                    put("updatedAt", playlist.updatedAt)
                    playlist.deletedAt?.let { put("deletedAt", it) }
                    playlist.description?.let { put("description", it) }
                    put("isPinned", playlist.isPinned)
                    val ids = JSONArray()
                    if (playlist.deletedAt == null) {
                        dao.playlistSongsOnce(playlist.id).forEach { song -> ids.put(song.id) }
                    }
                    put("songIds", ids)
                },
            )
        }
        val response = httpPost("${phone.baseUrl}/playlists", payload.toString())
        return runCatching { JSONObject(response).optInt("applied") }.getOrDefault(0)
    }

    /**
     * Trae las escuchas que aquí no estén. La identidad de una escucha es
     * `canción@instante`: el `id` de `play_history` lo pone cada aparato por su
     * cuenta, así que sincronizar dos veces duplicaría todo si nos fiáramos de
     * él. Las escuchas propias del PC no se tocan.
     */
    private suspend fun syncHistory(root: JSONObject, onProgress: (String) -> Unit): Int {
        val history = root.optJSONArray("playHistory") ?: return 0
        if (history.length() == 0) return 0
        onProgress("Trayendo el historial…")
        val known = dao.playEventKeys().toHashSet()
        val fresh = ArrayList<PlayEvent>()
        for (i in 0 until history.length()) {
            val e = history.getJSONObject(i)
            val songId = e.getString("s")
            val playedAt = e.getLong("t")
            if (known.add("$songId@$playedAt")) fresh += PlayEvent(songId = songId, playedAt = playedAt)
        }
        // De mil en mil: SQLite tiene un tope de variables por sentencia.
        fresh.chunked(1_000).forEach { dao.insertPlayEvents(it) }
        return fresh.size
    }

    /**
     * Las inteligentes son un espejo: en el PC no se pueden crear ni editar, así
     * que borrarlas y rehacerlas es lo único que las mantiene iguales al móvil
     * cuando allí se borra una.
     */
    private suspend fun syncSmartPlaylists(root: JSONObject): Int {
        val smart = root.optJSONArray("smartPlaylists") ?: return 0
        dao.clearSmartPlaylists()
        for (i in 0 until smart.length()) {
            val s = smart.getJSONObject(i)
            dao.insertSmartPlaylist(
                SmartPlaylist(
                    id = s.getLong("id"),
                    name = s.getString("name"),
                    artistContains = s.optStringOrNull("artistContains"),
                    onlyFavorites = s.optBoolean("onlyFavorites"),
                    minPlays = s.optInt("minPlays"),
                    addedWithinDays = s.optInt("addedWithinDays"),
                    createdAt = s.getLong("createdAt"),
                    rulesJson = s.optStringOrNull("rulesJson"),
                ),
            )
        }
        return smart.length()
    }

    private fun httpPost(spec: String, body: String): String {
        val conn = URL(spec).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            require(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(spec: String): String {
        val conn = URL(spec).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 20_000
        try {
            require(conn.responseCode in 200..299) { "HTTP ${conn.responseCode} en $spec" }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /** Baja a un temporal y renombra: una descarga cortada nunca parece completa. */
    private fun download(spec: String, target: File) {
        val tmp = File(target.parentFile, "${target.name}.part")
        val conn = URL(spec).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 60_000
        try {
            require(conn.responseCode in 200..299) { "HTTP ${conn.responseCode} en $spec" }
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } finally {
            conn.disconnect()
        }
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "no se pudo renombrar ${tmp.name}" }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

private fun JSONObject.optFloatOrNull(key: String): Float? =
    if (has(key) && !isNull(key)) getDouble(key).toFloat() else null
