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
    /** Primer artista: el que usan Deezer/YouTube como principal. */
    val mainArtist: String get() = artists.split(",", "&", " feat", " Feat").first().trim()
}

data class SpotifyPlaylist(
    val name: String,
    val tracks: List<SpotifyTrack>,
) {
    /** El embed nunca sirve más de [EMBED_LIMIT]: si vienen justo esas, hay más detrás. */
    val truncated: Boolean get() = tracks.size >= EMBED_LIMIT
}

/** Tope duro de la página de embed de Spotify. */
private const val EMBED_LIMIT = 100

/**
 * Lee la lista de pistas pública de una playlist/álbum/pista de Spotify desde
 * la página de embed. El audio de Spotify tiene DRM: aquí sólo leemos
 * metadatos, y cada pista se descarga después de Deezer (o de YouTube).
 *
 * Sin API oficial a propósito. Spotify responde **403 a `playlists/{id}/tracks`**
 * para las aplicaciones registradas después de nov-2024, y no lo levanta ni el
 * token de usuario con `playlist-read-private` sobre una playlist propia y
 * pública (medido: `/me` y `/me/playlists` responden 200, y las pistas 403).
 * Las credenciales sólo servían para álbumes y pistas sueltas, que el embed ya
 * lee igual de bien, así que no compensaban llevar un client secret dentro del
 * APK. El precio es el tope de 100 pistas por playlist, que se avisa en pantalla.
 */
object SpotifyResolver {

    private val URL_PATTERN = Regex("""open\.spotify\.com/(?:intl-[a-z-]+/)?(playlist|album|track)/([A-Za-z0-9]+)""")

    fun isSpotifyUrl(text: String): Boolean = URL_PATTERN.containsMatchIn(text)

    suspend fun resolve(url: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        val match = URL_PATTERN.find(url) ?: throw IllegalArgumentException("URL de Spotify no válida")
        val (type, id) = match.destructured
        resolveViaEmbed(type, id)
    }

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
            return SpotifyPlaylist(name, tracks)
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
        return SpotifyPlaylist(title, listOf(track))
    }

    private fun JSONArray.joinNames(): String =
        (0 until length()).mapNotNull { optJSONObject(it)?.optString("name") }
            .filter { it.isNotBlank() }
            .joinToString(", ")

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
