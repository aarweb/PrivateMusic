package com.aar.privatemusic.lyrics

import com.aar.privatemusic.data.db.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Una línea de la letra. [endMs] y [words] son opcionales: sin ellos se resalta
 * la línea entera, como siempre; con ellos se puede barrer palabra a palabra.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val endMs: Long? = null,
    val words: List<LyricWord> = emptyList(),
)

data class Lyrics(val synced: Boolean, val lines: List<LyricLine>) {
    /** True si alguna línea trae tiempos por palabra (Enhanced LRC o TTML). */
    val wordLevel: Boolean get() = lines.any { it.words.isNotEmpty() }
}

/**
 * Lyrics from LRCLIB (free, no API key). Synced lyrics are cached as
 * <videoId>.lrc next to the audio; plain lyrics as <videoId>.txt.
 */
object LyricsFetcher {

    private const val UA = "PrivateMusic (https://github.com/aarweb/PrivateMusic)"

    /**
     * Canciones que ya se buscaron y no tienen letra. Sin esto, cada vez que
     * suena una instrumental se piden tres URLs a LRCLIB. Vive en memoria, no en
     * disco: si mañana alguien sube la letra, basta reabrir la app.
     */
    private val misses = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun getOrFetch(song: Song, dir: File): Lyrics? = withContext(Dispatchers.IO) {
        val lrc = File(dir, "${song.id}.lrc")
        val ttml = File(dir, "${song.id}.ttml")
        val txt = File(dir, "${song.id}.txt")
        when {
            // El TTML manda: si alguien lo ha puesto a mano es porque trae
            // tiempos por palabra, que es más de lo que da LRCLIB.
            ttml.exists() -> parseTtml(ttml.readText()) ?: parseLrcIfAny(lrc)
            lrc.exists() -> parseLrc(lrc.readText())
            txt.exists() -> plain(txt.readText())
            song.id in misses -> null
            else -> fetch(song)?.let { (synced, plainText) ->
                when {
                    !synced.isNullOrBlank() -> {
                        lrc.writeText(synced)
                        parseLrc(synced)
                    }
                    !plainText.isNullOrBlank() -> {
                        txt.writeText(plainText)
                        plain(plainText)
                    }
                    else -> null
                }
            }.also { if (it == null) misses.add(song.id) }
        }
    }

    /**
     * Tries the artist first: LRCLIB matches far better with it. Falls back to a
     * title-only search only as a last resort (mixes in wrong artists).
     */
    private fun fetch(song: Song): Pair<String?, String?>? {
        // 1. Exact hit: artist + title (+ duration).
        exactGet(song)?.let { return it }
        // 2. Structured search by artist + title.
        val artist = cleanArtist(song.artist)
        if (artist.isNotBlank()) {
            searchBest(
                "https://lrclib.net/api/search?artist_name=${enc(artist)}" +
                    "&track_name=${enc(cleanTitle(song.title))}",
                song,
            )?.let { return it }
        }
        // 3. Broad title-only search (último recurso).
        return searchBest("https://lrclib.net/api/search?q=${enc(cleanTitle(song.title))}", song)
    }

    /** LRCLIB /api/get: exact artist+track(+duration) lookup; one object or 404. */
    private fun exactGet(song: Song): Pair<String?, String?>? {
        val artist = cleanArtist(song.artist)
        if (artist.isBlank()) return null
        var url = "https://lrclib.net/api/get?artist_name=${enc(artist)}" +
            "&track_name=${enc(cleanTitle(song.title))}"
        if (song.durationSec > 0) url += "&duration=${song.durationSec}"
        val body = httpGet(url) ?: return null
        return try {
            val o = JSONObject(body)
            val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
            if (synced == null && plain == null) null else synced to plain
        } catch (e: Exception) {
            null
        }
    }

    private fun searchBest(url: String, song: Song): Pair<String?, String?>? {
        val body = httpGet(url) ?: return null
        return try {
            val results = JSONArray(body)
            var best: Pair<String?, String?>? = null
            var bestScore = Int.MIN_VALUE
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val duration = r.optDouble("duration", 0.0).toInt()
                val syncedLyrics = r.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                val plainLyrics = r.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
                if (syncedLyrics == null && plainLyrics == null) continue
                // Prefer synced lyrics, matching artist and durations close to our file.
                var score = if (syncedLyrics != null) 100 else 0
                if (artistMatches(song.artist, r.optString("artistName"))) score += 40
                if (song.durationSec > 0) {
                    val diff = abs(duration - song.durationSec)
                    if (diff > 15) continue
                    score += 15 - diff
                }
                if (score > bestScore) {
                    bestScore = score
                    best = syncedLyrics to plainLyrics
                }
            }
            best
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(spec: String): String? = try {
        val conn = URL(spec).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", UA)
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        } else {
            conn.disconnect()
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Strips YouTube noise: "(Official Video)", "[HD]", "ft. X"... */
    internal fun cleanTitle(title: String): String =
        title
            .replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
            .replace(Regex("""(?i)\b(official|video|audio|lyric[s]?|visualizer|hd|4k|remaster(ed)?)\b"""), " ")
            .replace(Regex("""(?i)\b(ft|feat)\.?\s.*"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** Primary artist only: drops "- Topic", feats and extra collaborators. */
    internal fun cleanArtist(artist: String): String =
        artist
            .replace(Regex("""(?i)\s*-\s*topic\s*$"""), " ")
            .split(Regex("""(?i)\s*(,|&|\bfeat\.?\b|\bft\.?\b|\bx\b|·|;)\s*"""))
            .firstOrNull()?.trim().orEmpty()

    private fun artistMatches(mine: String, candidate: String): Boolean {
        val a = cleanArtist(mine).lowercase()
        val b = candidate.trim().lowercase()
        return a.isNotBlank() && b.isNotBlank() && (b.contains(a) || a.contains(b))
    }

    /**
     * LRC normal y Enhanced LRC en el mismo sitio: si la línea trae marcas
     * `<mm:ss.xx>` se guardan como palabras y el texto va limpio; si no, queda
     * la línea de siempre. Un mismo fichero puede mezclar ambas.
     */
    internal fun parseLrc(text: String): Lyrics? {
        val lines = mutableListOf<LyricLine>()
        text.lines().forEach { raw ->
            val tags = LyricFormats.lineTags(raw)
            if (tags.isEmpty()) return@forEach
            val content = raw.substring(tags.last().range.last + 1).trim()
            if (content.isBlank()) return@forEach
            val clean = LyricFormats.stripWordTags(content)
            if (clean.isBlank()) return@forEach
            tags.forEach { m ->
                val timeMs = LyricFormats.toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                lines += LyricLine(timeMs, clean, null, LyricFormats.parseWords(content, null))
            }
        }
        if (lines.isEmpty()) return null
        return Lyrics(true, withLineEnds(lines.sortedBy { it.timeMs }))
    }

    /**
     * Cierra cada línea con el arranque de la siguiente. Sin esto no se sabe
     * cuándo termina un verso, y el barrido de la última palabra se queda a
     * medias. La última línea se deja abierta (null): no hay dato que inventar.
     */
    private fun withLineEnds(sorted: List<LyricLine>): List<LyricLine> =
        sorted.mapIndexed { i, line ->
            val end = line.endMs ?: sorted.getOrNull(i + 1)?.timeMs
            val words = if (line.words.isEmpty()) {
                line.words
            } else {
                // La última palabra hereda el final de la línea.
                line.words.mapIndexed { w, word ->
                    if (w == line.words.lastIndex && end != null && end > word.startMs) {
                        word.copy(endMs = end)
                    } else word
                }
            }
            line.copy(endMs = end, words = words)
        }

    /** Lee un fichero TTML (estilo Apple Music) puesto a mano junto al audio. */
    internal fun parseTtml(xml: String): Lyrics? = LyricFormats.parseTtml(xml)

    private fun parseLrcIfAny(lrc: File): Lyrics? =
        if (lrc.exists()) parseLrc(lrc.readText()) else null

    /**
     * Guarda una letra sincronizada como Enhanced LRC junto al audio y la
     * devuelve ya parseada. Lo usa la alineación automática ([ForcedAligner]).
     */
    fun saveSynced(songId: String, dir: File, lyrics: Lyrics): Lyrics? {
        val lrc = File(dir, "$songId.lrc")
        lrc.writeText(LyricFormats.toEnhancedLrc(lyrics))
        // El .txt plano ya no hace falta: la letra sincronizada lo sustituye.
        File(dir, "$songId.txt").delete()
        misses.remove(songId)
        return parseLrc(lrc.readText())
    }

    private fun plain(text: String): Lyrics? {
        val lines = text.lines().filter { it.isNotBlank() }.map { LyricLine(0L, it.trim()) }
        return if (lines.isEmpty()) null else Lyrics(false, lines)
    }
}
