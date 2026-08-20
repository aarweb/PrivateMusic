package com.aar.privatemusic.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Servicio de origen de un enlace de playlist/álbum público. Todos acaban
 * emparejándose en YouTube (o Deezer HQ) por duración, así que el resultado es
 * el mismo [SpotifyPlaylist] que ya usa el importador: sólo cambia de dónde se
 * leen los metadatos.
 */
enum class LinkService { SPOTIFY, DEEZER, APPLE_MUSIC, TIDAL }

/**
 * Detecta el servicio de un enlace y lee su lista de pistas pública. Reutiliza
 * [SpotifyResolver] para Spotify; Deezer usa su API pública (sin auth); Apple
 * Music y Tidal se leen del JSON embebido de la página pública.
 */
object PlaylistLinkResolver {

    private val SPOTIFY = Regex("""open\.spotify\.com/""")
    private val DEEZER = Regex("""(?:www\.|link\.)?deezer\.com/(?:[a-z]{2}/)?(playlist|album)/(\d+)""")
    private val APPLE = Regex("""music\.apple\.com/([a-z]{2})/(playlist|album)/[^/]+/((?:pl\.)?[A-Za-z0-9.-]+)""")
    private val TIDAL = Regex("""tidal\.com/(?:browse/)?(playlist|album)/([A-Za-z0-9-]+)""")

    /** Qué servicio reconoce el enlace, o null si ninguno. */
    fun serviceOf(text: String): LinkService? = when {
        SPOTIFY.containsMatchIn(text) -> LinkService.SPOTIFY
        DEEZER.containsMatchIn(text) -> LinkService.DEEZER
        APPLE.containsMatchIn(text) -> LinkService.APPLE_MUSIC
        TIDAL.containsMatchIn(text) -> LinkService.TIDAL
        else -> null
    }

    fun isSupportedLink(text: String): Boolean = serviceOf(text) != null

    suspend fun resolve(url: String): SpotifyPlaylist = when (serviceOf(url)) {
        LinkService.SPOTIFY -> SpotifyResolver.resolve(url)
        LinkService.DEEZER -> resolveDeezer(url)
        LinkService.APPLE_MUSIC -> resolveApple(url)
        LinkService.TIDAL -> resolveTidal(url)
        null -> throw IllegalArgumentException("Enlace no reconocido")
    }

    // ---- Deezer (API pública, sin auth) ----

    internal fun deezerTarget(url: String): Pair<String, String>? =
        DEEZER.find(url)?.let { it.groupValues[1] to it.groupValues[2] }

    private suspend fun resolveDeezer(url: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        val (type, id) = deezerTarget(url) ?: throw IllegalArgumentException("Enlace de Deezer no válido")
        // api.deezer.com/playlist/{id} y /album/{id} son públicos y no piden token.
        val root = JSONObject(fetch("https://api.deezer.com/$type/$id"))
        if (root.has("error")) throw IllegalStateException("Deezer no devolvió la lista (¿privada?)")
        val name = root.optString("title").ifBlank { "Deezer" }
        val albumArtist = root.optJSONObject("artist")?.optString("name").orEmpty()
        val data = root.optJSONObject("tracks")?.optJSONArray("data") ?: JSONArray()
        val tracks = (0 until data.length()).mapNotNull { i ->
            val t = data.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title")
            if (title.isBlank()) return@mapNotNull null
            SpotifyTrack(
                title = title,
                artists = t.optJSONObject("artist")?.optString("name").orEmpty().ifBlank { albumArtist },
                durationSec = t.optInt("duration", 0),
            )
        }
        SpotifyPlaylist(name, tracks)
    }

    // ---- Apple Music (JSON-LD / datos embebidos de la página pública) ----

    internal fun appleTarget(url: String): Triple<String, String, String>? =
        APPLE.find(url)?.let { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }

    internal fun parseApple(html: String): SpotifyPlaylist {
        // Apple incrusta JSON-LD (schema.org) con la lista de pistas.
        val ld = extractScripts(html, "application/ld+json")
        for (block in ld) {
            val json = runCatching { JSONObject(block) }.getOrNull() ?: continue
            val name = json.optString("name").ifBlank { "Apple Music" }
            // Álbum: "tracks"; playlist: "track" (ItemList).
            val arr = json.optJSONArray("tracks")
                ?: json.optJSONObject("track")?.optJSONArray("itemListElement")
                ?: json.optJSONArray("track")
            if (arr != null && arr.length() > 0) {
                val albumArtist = appleArtist(json)
                val tracks = (0 until arr.length()).mapNotNull { i ->
                    val node = arr.optJSONObject(i) ?: return@mapNotNull null
                    // ItemList envuelve cada pista en "item".
                    val t = node.optJSONObject("item") ?: node
                    val title = t.optString("name")
                    if (title.isBlank()) return@mapNotNull null
                    SpotifyTrack(
                        title = title,
                        artists = appleArtist(t).ifBlank { albumArtist },
                        durationSec = parseIsoDuration(t.optString("duration")),
                    )
                }
                if (tracks.isNotEmpty()) return SpotifyPlaylist(name, tracks)
            }
        }
        throw IllegalStateException("No se pudieron leer las pistas de Apple Music")
    }

    private fun appleArtist(node: JSONObject): String {
        node.optJSONObject("byArtist")?.optString("name")?.takeIf { it.isNotBlank() }?.let { return it }
        val arr = node.optJSONArray("byArtist")
        if (arr != null) {
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                .filter { it.isNotBlank() }.joinToString(", ")
        }
        return node.optString("author")
    }

    private suspend fun resolveApple(url: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        appleTarget(url) ?: throw IllegalArgumentException("Enlace de Apple Music no válido")
        parseApple(fetch(url))
    }

    // ---- Tidal (JSON embebido de la página pública) ----

    internal fun tidalTarget(url: String): Pair<String, String>? =
        TIDAL.find(url)?.let { it.groupValues[1] to it.groupValues[2] }

    internal fun parseTidal(html: String): SpotifyPlaylist {
        // Tidal expone JSON-LD como Apple; si no, no hay metadatos sin token.
        for (block in extractScripts(html, "application/ld+json")) {
            val json = runCatching { JSONObject(block) }.getOrNull() ?: continue
            val arr = json.optJSONArray("track") ?: json.optJSONArray("tracks")
            if (arr != null && arr.length() > 0) {
                val name = json.optString("name").ifBlank { "Tidal" }
                val tracks = (0 until arr.length()).mapNotNull { i ->
                    val node = arr.optJSONObject(i) ?: return@mapNotNull null
                    val t = node.optJSONObject("item") ?: node
                    val title = t.optString("name")
                    if (title.isBlank()) return@mapNotNull null
                    SpotifyTrack(
                        title = title,
                        artists = appleArtist(t),
                        durationSec = parseIsoDuration(t.optString("duration")),
                    )
                }
                if (tracks.isNotEmpty()) return SpotifyPlaylist(name, tracks)
            }
        }
        throw IllegalStateException("Tidal no expone las pistas públicamente sin sesión")
    }

    private suspend fun resolveTidal(url: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        tidalTarget(url) ?: throw IllegalArgumentException("Enlace de Tidal no válido")
        parseTidal(fetch(url))
    }

    // ---- Utilidades ----

    /** Todos los cuerpos de `<script type="...">` de la página. */
    internal fun extractScripts(html: String, type: String): List<String> {
        val out = mutableListOf<String>()
        val open = Regex("""<script[^>]*type=["']${Regex.escape(type)}["'][^>]*>""", RegexOption.IGNORE_CASE)
        var idx = 0
        while (true) {
            val m = open.find(html, idx) ?: break
            val start = m.range.last + 1
            val end = html.indexOf("</script>", start, ignoreCase = true)
            if (end < 0) break
            out += html.substring(start, end).trim()
            idx = end + 9
        }
        return out
    }

    /** ISO-8601 ("PT3M20S") o segundos en texto → segundos. */
    internal fun parseIsoDuration(raw: String): Int {
        if (raw.isBlank()) return 0
        raw.toIntOrNull()?.let { return it }
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(raw) ?: return 0
        val (h, min, s) = m.destructured
        return (h.toIntOrNull() ?: 0) * 3600 + (min.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
        )
        return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }
}
