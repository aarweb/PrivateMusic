package com.aar.privatemusic.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** Un candidato de Deezer para una pista de Spotify: [exact] = se puede bajar sin preguntar. */
data class DeezerMatch(val track: DeezerTrack, val exact: Boolean)

private const val DURATION_TOLERANCE_SEC = 3

data class DeezerTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val coverUrl: String,
    val previewUrl: String,
) {
    val searchQuery: String get() = "$artist $title".trim()
}

/**
 * Deezer public search (no account/API key). Full Deezer audio is DRM'd, so
 * the 30s preview streams straight from Deezer and downloads are matched on
 * YouTube — keeping Deezer's clean title/artist/cover as the metadata.
 */
object DeezerSource {

    suspend fun search(query: String, limit: Int = 25): List<DeezerTrack> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query, "UTF-8")
            val json = fetch("https://api.deezer.com/search?q=$q&limit=$limit")
            val data = JSONObject(json).optJSONArray("data") ?: return@withContext emptyList()
            (0 until data.length()).mapNotNull { i ->
                val t = data.optJSONObject(i) ?: return@mapNotNull null
                val title = t.optString("title")
                if (title.isBlank()) return@mapNotNull null
                val album = t.optJSONObject("album")
                DeezerTrack(
                    id = t.optLong("id"),
                    title = title,
                    artist = t.optJSONObject("artist")?.optString("name").orEmpty()
                        .ifBlank { "Desconocido" },
                    album = album?.optString("title").orEmpty(),
                    durationSec = t.optInt("duration", 0),
                    coverUrl = album?.optString("cover_big").orEmpty()
                        .ifBlank { album?.optString("cover_medium").orEmpty() },
                    previewUrl = t.optString("preview"),
                )
            }
        }

    /**
     * Busca en Deezer la pista que corresponde a [title]/[artist]. Devuelve el
     * mejor candidato por cercanía de duración, marcado como [DeezerMatch.exact]
     * sólo si además el título y el artista coinciden: sin esa comprobación
     * Deezer cuela remixes, directos y versiones alargadas del mismo nombre.
     */
    suspend fun bestMatch(title: String, artist: String, durationSec: Int): DeezerMatch? {
        val clean = cleanTitle(title)
        // La búsqueda por campos es la precisa; la libre rescata los títulos con
        // sufijos raros ('- From "Toy Story 5"'), que la primera no encuentra.
        val candidates = search("""track:"$clean" artist:"$artist"""", limit = 5)
            .ifEmpty { search("$clean $artist", limit = 5) }
        if (candidates.isEmpty()) return null

        val best = candidates.minByOrNull { abs(it.durationSec - durationSec) } ?: return null
        val closeEnough = abs(best.durationSec - durationSec) <= DURATION_TOLERANCE_SEC
        val titleMatches = normalize(best.title).startsWith(normalize(clean)) ||
            normalize(clean).startsWith(normalize(best.title))
        val artistMatches = normalize(best.artist).contains(normalize(artist)) ||
            normalize(artist).contains(normalize(best.artist))
        return DeezerMatch(best, exact = closeEnough && titleMatches && artistMatches)
    }

    /** Quita sufijos que Spotify añade y Deezer indexa entre paréntesis o no tiene. */
    private fun cleanTitle(title: String): String = title
        .replace(Regex("""\s*-\s*(From|from|De)\s+".*$"""), "")
        .replace(Regex("""\s*-\s*(Remastered|Remaster)\b.*$""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("""[^a-z0-9]"""), "")

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
        )
        return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }
}
