package com.aar.privatemusic.lyrics

import com.aar.privatemusic.data.db.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

    suspend fun getOrFetch(song: Song, dir: File): Lyrics? = withContext(Dispatchers.IO) {
        val lrc = File(dir, "${song.id}.lrc")
        val txt = File(dir, "${song.id}.txt")
        when {
            lrc.exists() -> parseLrc(lrc.readText())
            txt.exists() -> plain(txt.readText())
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
            }
        }
    }

    private fun fetch(song: Song): Pair<String?, String?>? {
        return try {
            val query = URLEncoder.encode(cleanTitle(song.title), "UTF-8")
            val url = URL("https://lrclib.net/api/search?q=$query")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "PrivateMusic/1.2")
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val results = JSONArray(body)
            var best: Pair<String?, String?>? = null
            var bestScore = Int.MIN_VALUE
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val duration = r.optDouble("duration", 0.0).toInt()
                val syncedLyrics = r.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                val plainLyrics = r.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
                if (syncedLyrics == null && plainLyrics == null) continue
                // Prefer synced lyrics and durations close to our file.
                var score = if (syncedLyrics != null) 100 else 0
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

    /** Strips YouTube noise: "(Official Video)", "[HD]", "ft. X"... */
    internal fun cleanTitle(title: String): String =
        title
            .replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
            .replace(Regex("""(?i)\b(official|video|audio|lyric[s]?|visualizer|hd|4k|remaster(ed)?)\b"""), " ")
            .replace(Regex("""(?i)\b(ft|feat)\.?\s.*"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

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
