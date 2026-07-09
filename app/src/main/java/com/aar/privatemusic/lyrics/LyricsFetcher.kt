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

data class LyricLine(val timeMs: Long, val text: String)
data class Lyrics(val synced: Boolean, val lines: List<LyricLine>)

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
        val txt = File(dir, "${song.id}.txt")
        when {
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

    internal fun parseLrc(text: String): Lyrics? {
        val tag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val lines = mutableListOf<LyricLine>()
        text.lines().forEach { raw ->
            val tags = tag.findAll(raw).toList()
            if (tags.isEmpty()) return@forEach
            val content = raw.substring(tags.last().range.last + 1).trim()
            if (content.isBlank()) return@forEach
            tags.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracRaw = m.groupValues[3]
                val frac = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100
                    2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                lines += LyricLine(min * 60_000 + sec * 1_000 + frac, content)
            }
        }
        return if (lines.isEmpty()) null else Lyrics(true, lines.sortedBy { it.timeMs })
    }

    private fun plain(text: String): Lyrics? {
        val lines = text.lines().filter { it.isNotBlank() }.map { LyricLine(0L, it.trim()) }
        return if (lines.isEmpty()) null else Lyrics(false, lines)
    }
}
