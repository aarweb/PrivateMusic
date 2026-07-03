package com.aar.privatemusic.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SpotifyTrack(
    val title: String,
    val artists: String,
    val durationSec: Int,
) {
    val searchQuery: String get() = "$artists $title".trim()
    val key: String get() = "${artists.lowercase()}|${title.lowercase()}"
}

/**
 * Reads the public track list of a Spotify playlist/album/track from the
 * open.spotify.com embed page (no account or API key). Spotify audio is
 * DRM-protected — we only read metadata and match each track on YouTube.
 */
object SpotifyResolver {

    private val URL_PATTERN = Regex("""open\.spotify\.com/(?:intl-[a-z-]+/)?(playlist|album|track)/([A-Za-z0-9]+)""")

    fun isSpotifyUrl(text: String): Boolean = URL_PATTERN.containsMatchIn(text)

    suspend fun resolve(url: String): Pair<String, List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        val match = URL_PATTERN.find(url) ?: throw IllegalArgumentException("URL de Spotify no válida")
        val (type, id) = match.destructured

        val html = fetch("https://open.spotify.com/embed/$type/$id")
        val marker = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
        val start = html.indexOf(marker)
        if (start < 0) throw IllegalStateException("Spotify no devolvió datos (¿playlist privada?)")
        val jsonText = html.substring(start + marker.length, html.indexOf("</script>", start))
        val root = JSONObject(jsonText)

        val holder = findObjectWithKey(root, "trackList")
        if (holder != null) {
            val name = holder.optString("title").ifBlank { holder.optString("name") }.ifBlank { "Spotify" }
            val list = holder.getJSONArray("trackList")
            val tracks = (0 until list.length()).mapNotNull { i ->
                val t = list.optJSONObject(i) ?: return@mapNotNull null
                val title = t.optString("title")
                if (title.isBlank()) return@mapNotNull null
                SpotifyTrack(
                    title = title,
                    artists = t.optString("subtitle"),
                    durationSec = (t.optLong("duration", 0) / 1000).toInt(),
                )
            }
            return@withContext name to tracks
        }

        // Single track embeds have no trackList: take the entity itself.
        val entity = findObjectWithKey(root, "audioPreview")
            ?: findObjectWithKey(root, "duration")
            ?: throw IllegalStateException("No se pudo leer la pista de Spotify")
        val title = entity.optString("title").ifBlank { entity.optString("name") }
        val track = SpotifyTrack(
            title = title,
            artists = entity.optString("subtitle"),
            durationSec = (entity.optLong("duration", 0) / 1000).toInt(),
        )
        title to listOf(track)
    }

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

    /** Depth-first search for the first JSONObject containing the given key. */
    private fun findObjectWithKey(node: Any?, key: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (node.has(key)) return node
                for (k in node.keys()) {
                    findObjectWithKey(node.opt(k), key)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findObjectWithKey(node.opt(i), key)?.let { return it }
                }
            }
        }
        return null
    }
}
