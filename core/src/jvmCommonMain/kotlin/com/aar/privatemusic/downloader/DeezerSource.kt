package com.aar.privatemusic.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
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

    private val tokenPattern = Regex("""[\p{L}\p{N}]+""")
    private val trackPath = Regex("""/(?:[a-z]{2}(?:-[a-z]{2})?/)?track/(\d+)(?:/|$)""", RegexOption.IGNORE_CASE)

    /** Reconoce enlaces oficiales y los enlaces cortos que genera Compartir en Deezer. */
    fun isDeezerLink(text: String): Boolean = runCatching {
        val uri = URI(text.trim())
        uri.scheme.equals("https", ignoreCase = true) && isDeezerHost(uri.host)
    }.getOrDefault(false)

    /** Extrae el id de una URL de pista ya expandida, con o sin prefijo de idioma. */
    internal fun trackIdFromUrl(text: String): Long? = runCatching {
        val uri = URI(text.trim())
        if (!uri.scheme.equals("https", ignoreCase = true) || !isDeezerHost(uri.host)) return@runCatching null
        trackPath.find(uri.path.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
    }.getOrNull()

    suspend fun search(query: String, limit: Int = 25): List<DeezerTrack> =
        withContext(Dispatchers.IO) {
            val input = query.trim()
            if (isDeezerLink(input)) {
                val id = trackIdFromUrl(input)
                    ?: trackIdFromUrl(expandLink(input))
                    ?: throw IllegalArgumentException("El enlace de Deezer no apunta a una canción")
                return@withContext listOfNotNull(fetchTrack(id))
            }

            val primary = searchOnce(input, limit)
            val fallback = fallbackTokens(input, primary).flatMap { searchOnce(it, limit) }
            // El índice general puede devolver una canción homónima antigua y no
            // incluir aún el lanzamiento nuevo. El catálogo del artista sí contiene
            // todas sus versiones, incluso cuando ya hubo una coincidencia completa.
            val catalog = runCatching { searchArtistCatalog(input, limit) }.getOrDefault(emptyList())
            mergeResults(input, catalog + primary, fallback, limit)
        }

    private fun searchOnce(query: String, limit: Int): List<DeezerTrack> {
        val q = URLEncoder.encode(query, "UTF-8")
        return parseSearch(fetch("https://api.deezer.com/search?q=$q&limit=$limit"))
    }

    internal fun parseSearch(json: String): List<DeezerTrack> {
        return try {
            val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
            (0 until data.length()).mapNotNull { i -> parseTrack(data.optJSONObject(i)) }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    internal fun parseTrack(t: JSONObject?): DeezerTrack? {
        t ?: return null
        val id = t.optLong("id")
        val title = t.optString("title")
        if (id <= 0 || title.isBlank()) return null
        val album = t.optJSONObject("album")
        return DeezerTrack(
            id = id,
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

    private fun fetchTrack(id: Long): DeezerTrack? {
        val root = JSONObject(fetch("https://api.deezer.com/track/$id"))
        if (root.has("error")) return null
        return parseTrack(root)
    }

    private fun searchArtistCatalog(query: String, limit: Int): List<DeezerTrack> {
        if ('"' in query || '\'' in query || ':' in query) return emptyList()
        val queryTokens = queryTokens(query)
        if (queryTokens.size < 2) return emptyList()
        val edgeSize = minOf(3, queryTokens.size - 1)
        val artistQueries = listOf(
            query,
            queryTokens.takeLast(edgeSize).joinToString(" "),
            queryTokens.take(edgeSize).joinToString(" "),
        ).distinct()

        val artists = artistQueries.firstNotNullOfOrNull { candidate ->
            val q = URLEncoder.encode(candidate, "UTF-8")
            val data = JSONObject(fetch("https://api.deezer.com/search/artist?q=$q&limit=8"))
                .optJSONArray("data") ?: return@firstNotNullOfOrNull null
            (0 until data.length()).mapNotNull { i ->
                val artist = data.optJSONObject(i) ?: return@mapNotNull null
                val id = artist.optLong("id")
                val tokens = queryTokens(artist.optString("name"))
                if (id <= 0 || tokens.isEmpty() || !queryTokens.containsAll(tokens)) null else id to tokens
            }.take(3).takeIf { it.isNotEmpty() }
        }.orEmpty()

        val recovered = mutableListOf<DeezerTrack>()
        for ((artistId, artistTokens) in artists) {
            val titleTokens = queryTokens.filterNot { it in artistTokens }
            if (titleTokens.isEmpty()) continue
            val albums = JSONObject(fetch("https://api.deezer.com/artist/$artistId/albums?limit=100"))
                .optJSONArray("data") ?: continue
            val matchingAlbums = (0 until albums.length()).mapNotNull(albums::optJSONObject)
                .filter { queryTokens(it.optString("title")).containsAll(titleTokens) }
                .take(5)
            for (album in matchingAlbums) {
                val albumId = album.optLong("id")
                if (albumId <= 0) continue
                val tracks = JSONObject(fetch("https://api.deezer.com/album/$albumId/tracks?limit=100"))
                    .optJSONArray("data") ?: continue
                for (i in 0 until tracks.length()) {
                    val raw = tracks.optJSONObject(i) ?: continue
                    raw.put("album", JSONObject().apply {
                        put("title", album.optString("title"))
                        put("cover_big", album.optString("cover_big"))
                        put("cover_medium", album.optString("cover_medium"))
                    })
                    parseTrack(raw)?.takeIf { matchesQuery(it, queryTokens) }?.let(recovered::add)
                    if (recovered.size >= limit) return recovered
                }
            }
        }
        return recovered
    }

    internal fun matchesQuery(track: DeezerTrack, tokens: List<String>): Boolean {
        val searchable = queryTokens("${track.title} ${track.artist} ${track.album}")
        return tokens.isNotEmpty() && searchable.containsAll(tokens)
    }

    /** Sigue el redirect de un enlace corto cuyo host inicial pertenece a Deezer. */
    private fun expandLink(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.responseCode
        return conn.url.toString().also { conn.disconnect() }
    }

    internal fun queryTokens(query: String): List<String> =
        tokenPattern.findAll(query).map { it.value.lowercase() }.toList()

    /** Sólo amplía consultas libres que todavía no tienen una coincidencia completa. */
    internal fun fallbackTokens(query: String, primary: List<DeezerTrack>): List<String> {
        if ('"' in query || '\'' in query || ':' in query) return emptyList()
        val tokens = queryTokens(query)
        if (tokens.size < 2) return emptyList()
        val hasCompleteMatch = primary.any { track ->
            val present = queryTokens("${track.title} ${track.artist}").toSet()
            tokens.all { it in present }
        }
        if (hasCompleteMatch) return emptyList()

        val distinct = tokens.distinct()
        return distinct.filter { it.codePointCount(0, it.length) >= 4 }
            .ifEmpty { distinct.filter { it.codePointCount(0, it.length) >= 3 } }
            .take(3)
    }

    /** Conserva la primera aparición de cada id y el orden de llegada en los empates. */
    internal fun mergeResults(
        query: String,
        primary: List<DeezerTrack>,
        fallback: List<DeezerTrack>,
        limit: Int,
    ): List<DeezerTrack> {
        val tokens = queryTokens(query).toSet()
        return (primary + fallback).distinctBy { it.id }
            .map { track ->
                val titleTokens = queryTokens(track.title).toSet()
                val allTokens = titleTokens + queryTokens(track.artist)
                Triple(track, tokens.count { it in allTokens }, tokens.count { it in titleTokens })
            }
            .sortedWith(
                compareByDescending<Triple<DeezerTrack, Int, Int>> { it.second }
                    .thenByDescending { it.third },
            )
            .take(limit)
            .map { it.first }
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

    private fun isDeezerHost(host: String?): Boolean {
        val normalized = host?.lowercase() ?: return false
        return normalized == "deezer.com" || normalized.endsWith(".deezer.com")
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}
