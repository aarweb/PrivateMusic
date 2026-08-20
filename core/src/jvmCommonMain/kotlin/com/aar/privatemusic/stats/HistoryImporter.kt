package com.aar.privatemusic.stats

import com.aar.privatemusic.data.db.Song
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Importa el historial de escucha de otros servicios para que el Recap y las
 * playlists automáticas ("Más escuchadas", "Olvidadas") tengan memoria desde el
 * primer día, en vez de empezar de cero al cambiar de Spotify.
 *
 * Tres formatos, detectados por el contenido:
 *  - Spotify (exportación GDPR): `StreamingHistory*.json` (`endTime`, `artistName`,
 *    `trackName`, `msPlayed`) y `Streaming_History_Audio_*.json` (`ts`,
 *    `master_metadata_*`, `ms_played`).
 *  - Last.fm (exportaciones tipo lastfm-to-csv): CSV con `uts,utc_time,artist,
 *    artist_mbid,album,album_mbid,track,track_mbid`.
 *  - YouTube Takeout: `watch-history.json` (`title` "Watched …", `subtitles[0].name`,
 *    `time`), del que sólo valen las entradas de YouTube Music o cuyo título
 *    casa con una canción.
 *
 * El emparejamiento es por título + artista normalizados (sin acentos, sin
 * "(feat. …)", "- Remastered", etc.). Las escuchas de menos de 30 s no cuentan.
 */
object HistoryImporter {

    data class ImportedPlay(
        val title: String,
        val artist: String,
        val playedAt: Long,
        /** null = el formato no lo trae (Last.fm, YouTube). */
        val msPlayed: Long? = null,
    )

    enum class Format { SPOTIFY, LASTFM, YOUTUBE }

    data class Parsed(val format: Format, val plays: List<ImportedPlay>)

    /** Escuchas emparejadas: id de canción → instantes; y cuántas no casaron. */
    data class Matched(
        val plays: List<Pair<String, Long>>,
        val unmatched: Int,
        val skippedShort: Int,
    ) {
        val songCount: Int get() = plays.map { it.first }.toSet().size
    }

    private const val MIN_MS = 30_000L

    // ----------------------------------------------------------------- parse

    /** Devuelve null si el texto no se reconoce como ninguno de los tres formatos. */
    fun parse(text: String): Parsed? {
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("[") -> parseJsonArray(trimmed)
            trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrNull()
                ?.optJSONArray("items")?.let { parseJsonArray(it.toString()) }
            looksLikeLastFm(trimmed) -> Parsed(Format.LASTFM, parseLastFm(trimmed))
            else -> null
        }
    }

    private fun parseJsonArray(text: String): Parsed? {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return null
        if (array.length() == 0) return null
        val first = array.optJSONObject(0) ?: return null
        return when {
            first.has("endTime") || first.has("ts") || first.has("master_metadata_track_name") ->
                Parsed(Format.SPOTIFY, parseSpotify(array))
            first.has("titleUrl") || first.optString("title").startsWith("Watched") || first.has("header") ->
                Parsed(Format.YOUTUBE, parseYouTube(array))
            else -> null
        }
    }

    private fun parseSpotify(array: JSONArray): List<ImportedPlay> = buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val title = o.optString("trackName").ifBlank { o.optString("master_metadata_track_name") }
            val artist = o.optString("artistName").ifBlank { o.optString("master_metadata_album_artist_name") }
            if (title.isBlank() || title == "null") continue // podcasts y episodios no traen pista
            val stamp = o.optString("ts").ifBlank { o.optString("endTime") }
            val at = parseSpotifyTime(stamp) ?: continue
            val ms = when {
                o.has("msPlayed") -> o.optLong("msPlayed")
                o.has("ms_played") -> o.optLong("ms_played")
                else -> -1L
            }
            add(ImportedPlay(title, artist, at, ms.takeIf { it >= 0 }))
        }
    }

    private val spotifyShort = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun parseSpotifyTime(s: String): Long? = runCatching {
        if (s.contains('T')) Instant.parse(s).toEpochMilli()
        else LocalDateTime.parse(s, spotifyShort).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()

    private fun parseYouTube(array: JSONArray): List<ImportedPlay> = buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val rawTitle = o.optString("title")
            if (!rawTitle.startsWith("Watched ")) continue
            val watched = rawTitle.removePrefix("Watched ").trim()
            if (watched.isBlank() || watched.startsWith("https://")) continue // vídeo borrado
            val channel = o.optJSONArray("subtitles")?.optJSONObject(0)?.optString("name").orEmpty()
            val at = runCatching { Instant.parse(o.optString("time")).toEpochMilli() }.getOrNull() ?: continue
            // "Artista - Título" es lo habitual en música; si no, el canal ("X - Topic", "XVEVO") hace de artista.
            val dash = watched.indexOf(" - ")
            val (artist, title) = if (dash > 0) {
                watched.substring(0, dash) to watched.substring(dash + 3)
            } else {
                channel.removeSuffix(" - Topic").removeSuffix("VEVO").trim() to watched
            }
            add(ImportedPlay(title, artist, at))
        }
    }

    private fun looksLikeLastFm(text: String): Boolean {
        val header = text.lineSequence().firstOrNull()?.lowercase() ?: return false
        return header.contains("uts") && header.contains("artist") && header.contains("track")
    }

    private fun parseLastFm(text: String): List<ImportedPlay> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val header = splitCsv(lines.first()).map { it.trim().lowercase() }
        val iUts = header.indexOf("uts")
        val iArtist = header.indexOf("artist")
        val iTrack = header.indexOf("track")
        if (iUts < 0 || iArtist < 0 || iTrack < 0) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val cols = splitCsv(line)
            val uts = cols.getOrNull(iUts)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            val artist = cols.getOrNull(iArtist)?.trim().orEmpty()
            val track = cols.getOrNull(iTrack)?.trim().orEmpty()
            if (track.isBlank()) null else ImportedPlay(track, artist, uts * 1000)
        }
    }

    /** CSV con comillas dobles y comas dentro de campos entrecomillados. */
    internal fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    // ----------------------------------------------------------------- match

    private val noise = listOf(
        Regex("""\s*[\(\[][^\)\]]*(feat\.?|ft\.?|with|remaster|remastered|live|version|edit|mix|mono|stereo|deluxe|bonus|explicit|radio)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s+-\s+.*(remaster|remastered|live|version|edit|mix|mono|stereo|deluxe|bonus|explicit|radio).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+(feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[]\s*(official|lyric|audio|video|hd|4k|visualizer|music video)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE),
    )

    /** Minúsculas, sin acentos, sin coletillas de edición, sólo letras y números. */
    fun normalize(s: String): String {
        var t = s.trim()
        noise.forEach { t = it.replace(t, "") }
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        t = t.lowercase().replace('&', ' ').replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        return t
    }

    private fun artistKey(s: String): String = normalize(s).substringBefore(" feat ").substringBefore(" x ").trim()

    /**
     * Empareja las escuchas con la biblioteca. Primero título+artista; si el
     * artista no casa (colaboraciones, "Topic"), título solo cuando es único en
     * la biblioteca. [existingKeys] (`songId@playedAt`) evita duplicar lo que ya
     * está, y también se deduplica dentro del propio fichero.
     */
    fun match(plays: List<ImportedPlay>, songs: List<Song>, existingKeys: Set<String> = emptySet()): Matched {
        val byTitleArtist = HashMap<String, String>()
        val byTitle = HashMap<String, MutableList<String>>()
        songs.forEach { s ->
            val t = normalize(s.title)
            if (t.isBlank()) return@forEach
            byTitleArtist.putIfAbsent("$t|${artistKey(s.artist)}", s.id)
            s.albumArtist?.let { byTitleArtist.putIfAbsent("$t|${artistKey(it)}", s.id) }
            byTitle.getOrPut(t) { mutableListOf() }.add(s.id)
        }
        val seen = HashSet(existingKeys)
        val out = ArrayList<Pair<String, Long>>()
        var unmatched = 0
        var short = 0
        plays.forEach { p ->
            if (p.msPlayed != null && p.msPlayed < MIN_MS) { short++; return@forEach }
            val t = normalize(p.title)
            val id = byTitleArtist["$t|${artistKey(p.artist)}"]
                ?: byTitle[t]?.takeIf { it.size == 1 }?.single()
                // "Artista - Título" al revés ("Título - Artista") en YouTube.
                ?: byTitleArtist["${normalize(p.artist)}|${artistKey(p.title)}"]
            if (id == null) { unmatched++; return@forEach }
            if (seen.add("$id@${p.playedAt}")) out.add(id to p.playedAt)
        }
        return Matched(out, unmatched, short)
    }
}
