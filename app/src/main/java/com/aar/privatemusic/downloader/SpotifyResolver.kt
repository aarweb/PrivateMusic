package com.aar.privatemusic.downloader

import android.util.Base64
import android.util.Log
import com.aar.privatemusic.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SpotifyTrack(
    val title: String,
    val artists: String,
    val durationSec: Int,
) {
    val searchQuery: String get() = "$artists $title".trim()
    val key: String get() = "${artists.lowercase()}|${title.lowercase()}"
    /** Primer artista: el que usan Deezer/YouTube como principal. */
    val mainArtist: String get() = artists.split(",", "&", " feat", " Feat").first().trim()
}

data class SpotifyPlaylist(
    val name: String,
    val tracks: List<SpotifyTrack>,
    /** Pistas que dice Spotify que hay; mayor que `tracks.size` si nos quedamos cortos. */
    val total: Int,
) {
    val truncated: Boolean get() = total > tracks.size
}

/**
 * Lee la lista de pistas pública de una playlist/álbum/pista de Spotify. El
 * audio de Spotify tiene DRM: aquí sólo leemos metadatos, y cada pista se
 * descarga después de Deezer (o de YouTube).
 *
 * Dos caminos, porque ninguno vale solo:
 * - **API oficial** (client credentials), si hay claves compiladas: playlists
 *   completas y paginadas. Pero desde nov-2024 Spotify responde 404 a las
 *   playlists *editoriales* (`37i9dQZF1...`) para las apps nuevas.
 * - **Página de embed**, sin claves: sí lee las editoriales, pero corta en 100
 *   pistas. Es la red de seguridad cuando la API falla o no hay claves.
 */
object SpotifyResolver {

    private val URL_PATTERN = Regex("""open\.spotify\.com/(?:intl-[a-z-]+/)?(playlist|album|track)/([A-Za-z0-9]+)""")
    private const val EMBED_LIMIT = 100

    private val hasKeys: Boolean
        get() = BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank() && BuildConfig.SPOTIFY_CLIENT_SECRET.isNotBlank()

    /** Token de client-credentials cacheado (valor, instante de caducidad). */
    @Volatile private var token: Pair<String, Long>? = null

    fun isSpotifyUrl(text: String): Boolean = URL_PATTERN.containsMatchIn(text)

    suspend fun resolve(url: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        val match = URL_PATTERN.find(url) ?: throw IllegalArgumentException("URL de Spotify no válida")
        val (type, id) = match.destructured

        if (hasKeys) {
            runCatching { resolveViaApi(type, id) }
                .onSuccess { return@withContext it }
                .onFailure { Log.w("Spotify", "API falló (${it.message}); usando el embed", it) }
        }
        resolveViaEmbed(type, id)
    }

    // ---------------------------------------------------------------- API

    private fun accessToken(): String {
        token?.let { (value, expiry) -> if (System.currentTimeMillis() < expiry) return value }
        val basic = Base64.encodeToString(
            "${BuildConfig.SPOTIFY_CLIENT_ID}:${BuildConfig.SPOTIFY_CLIENT_SECRET}".toByteArray(),
            Base64.NO_WRAP,
        )
        val conn = (URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Authorization", "Basic $basic")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write("grant_type=client_credentials".toByteArray()) }
        val body = conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        val json = JSONObject(body)
        val value = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Spotify no devolvió token")
        // Un minuto de margen sobre la caducidad que anuncia.
        token = value to (System.currentTimeMillis() + (json.optLong("expires_in", 3600) - 60) * 1000)
        return value
    }

    private fun api(path: String): JSONObject {
        val conn = (URL("https://api.spotify.com/v1/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer ${accessToken()}")
        }
        if (conn.responseCode == 404) {
            conn.disconnect()
            // Playlists editoriales: la API las niega a las apps nuevas.
            throw IllegalStateException("404 (¿playlist editorial de Spotify?)")
        }
        if (conn.responseCode >= 400) {
            val err = conn.errorStream?.bufferedReader()?.readText().orEmpty().take(200)
            conn.disconnect()
            throw IllegalStateException("HTTP ${conn.responseCode}: $err")
        }
        return JSONObject(conn.inputStream.bufferedReader().readText().also { conn.disconnect() })
    }

    private fun resolveViaApi(type: String, id: String): SpotifyPlaylist = when (type) {
        "track" -> {
            val t = api("tracks/$id")
            SpotifyPlaylist(t.optString("name"), listOfNotNull(parseApiTrack(t)), 1)
        }
        "album" -> {
            val album = api("albums/$id")
            val name = album.optString("name")
            val artist = album.optJSONArray("artists")?.joinNames().orEmpty()
            val total = album.optJSONObject("tracks")?.optInt("total") ?: 0
            // Los tracks de un álbum no repiten el artista: se lo ponemos nosotros.
            val tracks = pageThrough(total, 50) { offset ->
                api("albums/$id/tracks?limit=50&offset=$offset").optJSONArray("items")
            }.mapNotNull { parseApiTrack(it, fallbackArtist = artist) }
            SpotifyPlaylist(name, tracks, total)
        }
        else -> {
            val meta = api("playlists/$id?fields=name,tracks(total)")
            val name = meta.optString("name")
            val total = meta.optJSONObject("tracks")?.optInt("total") ?: 0
            val tracks = pageThrough(total, 100) { offset ->
                api(
                    "playlists/$id/tracks?limit=100&offset=$offset" +
                        "&fields=" + URLEncoder.encode("items(track(name,duration_ms,artists(name)))", "UTF-8"),
                ).optJSONArray("items")
            }.mapNotNull { item ->
                // Los episodios de podcast y las pistas locales vienen sin `track`.
                parseApiTrack(item.optJSONObject("track") ?: return@mapNotNull null)
            }
            SpotifyPlaylist(name, tracks, total)
        }
    }

    /** Recorre las páginas hasta juntar [total] elementos (o hasta que una venga vacía). */
    private fun pageThrough(total: Int, pageSize: Int, page: (Int) -> JSONArray?): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var offset = 0
        while (offset < total) {
            val items = page(offset) ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) items.optJSONObject(i)?.let { out += it }
            offset += pageSize
        }
        return out
    }

    private fun parseApiTrack(t: JSONObject, fallbackArtist: String = ""): SpotifyTrack? {
        val title = t.optString("name").takeIf { it.isNotBlank() } ?: return null
        val artists = t.optJSONArray("artists")?.joinNames().orEmpty().ifBlank { fallbackArtist }
        return SpotifyTrack(title, artists, (t.optLong("duration_ms") / 1000).toInt())
    }

    private fun JSONArray.joinNames(): String =
        (0 until length()).mapNotNull { optJSONObject(it)?.optString("name") }
            .filter { it.isNotBlank() }
            .joinToString(", ")

    // -------------------------------------------------------------- Embed

    private fun resolveViaEmbed(type: String, id: String): SpotifyPlaylist {
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
            // El embed nunca da más de 100; si vienen justo 100, puede haber más.
            val total = if (tracks.size == EMBED_LIMIT) Int.MAX_VALUE else tracks.size
            return SpotifyPlaylist(name, tracks, total)
        }

        // Single track embeds have no trackList: take the entity itself. Ojo: ahí
        // `subtitle` viene vacío y el artista vive en el array `artists`.
        val entity = findObjectWithKey(root, "audioPreview")
            ?: findObjectWithKey(root, "duration")
            ?: throw IllegalStateException("No se pudo leer la pista de Spotify")
        val title = entity.optString("title").ifBlank { entity.optString("name") }
        val track = SpotifyTrack(
            title = title,
            artists = entity.optString("subtitle").ifBlank {
                entity.optJSONArray("artists")?.joinNames().orEmpty()
            },
            durationSec = (entity.optLong("duration", 0) / 1000).toInt(),
        )
        return SpotifyPlaylist(title, listOf(track), 1)
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
