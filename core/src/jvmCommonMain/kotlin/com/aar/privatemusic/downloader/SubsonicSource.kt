package com.aar.privatemusic.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Datos de conexión a un servidor OpenSubsonic (Navidrome, Jellyfin con su endpoint, etc.). */
data class SubsonicConfig(val baseUrl: String, val user: String, val pass: String) {
    val isSet: Boolean get() = baseUrl.isNotBlank() && user.isNotBlank()
}

/** Respuesta de `ping`: si el servidor es válido y qué tipo/versión anuncia. */
data class SubsonicPing(val ok: Boolean, val type: String, val serverVersion: String, val error: String? = null)

/** Una canción del servidor: [id] es el id del servidor, no el de la biblioteca local. */
data class SubsonicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val suffix: String,
    val coverArtId: String?,
)

data class SubsonicPlaylist(val id: String, val name: String, val songCount: Int)

/**
 * Cliente OpenSubsonic (Navidrome / Jellyfin-con-endpoint-Subsonic / Airsonic…):
 * la biblioteca del servidor propio del usuario como una fuente más. Autentica
 * con token salteado (`t = md5(password + salt)`), así la contraseña no viaja en
 * claro. Descarga el fichero original con `&format=raw` para no transcodificar.
 *
 * Las funciones de construcción de URL y de parseo son [companion] puras para
 * poder probarlas sin red.
 */
class SubsonicSource(private val config: SubsonicConfig) {

    suspend fun ping(): SubsonicPing = withContext(Dispatchers.IO) {
        val body = httpGet(url("ping")) ?: return@withContext SubsonicPing(false, "", "", "sin respuesta")
        parsePing(body)
    }

    suspend fun search(query: String, limit: Int = 30): List<SubsonicTrack> = withContext(Dispatchers.IO) {
        val body = httpGet(
            url("search3", "query" to query, "songCount" to limit.toString(), "artistCount" to "0", "albumCount" to "0"),
        ) ?: return@withContext emptyList()
        parseSearch3(body)
    }

    suspend fun playlists(): List<SubsonicPlaylist> = withContext(Dispatchers.IO) {
        val body = httpGet(url("getPlaylists")) ?: return@withContext emptyList()
        parsePlaylists(body)
    }

    suspend fun playlistTracks(id: String): List<SubsonicTrack> = withContext(Dispatchers.IO) {
        val body = httpGet(url("getPlaylist", "id" to id)) ?: return@withContext emptyList()
        parsePlaylist(body)
    }

    /** URL del fichero original (sin transcodificar) para descargar o streamear. */
    fun streamUrl(id: String): String = url("stream", "id" to id, "format" to "raw")

    /** URL de la carátula (lleva la auth incrustada, así Coil la carga directa). */
    fun coverArtUrl(coverArtId: String, size: Int = 500): String =
        url("getCoverArt", "id" to coverArtId, "size" to size.toString())

    private fun url(endpoint: String, vararg extra: Pair<String, String>): String =
        buildUrl(config.baseUrl, endpoint, config.user, config.pass, saltFor(endpoint, extra), extra)

    /** Salt determinista por llamada (no hace falta azar real: es anti-sniffing, no cripto). */
    private fun saltFor(endpoint: String, extra: Array<out Pair<String, String>>): String {
        val seed = endpoint + extra.joinToString { it.first + it.second } + config.user
        return md5Hex(seed).take(16)
    }

    private fun httpGet(spec: String): String? = try {
        val conn = URL(spec).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        } else {
            conn.disconnect(); null
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val UA = "PrivateMusic (https://github.com/aarweb/PrivateMusic)"
        const val API_VERSION = "1.16.1"
        const val CLIENT = "privatemusic"

        fun md5Hex(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /** `.../rest/<endpoint>.view?u=&t=&s=&v=&c=&f=json&...extra`, normalizando la base. */
        fun buildUrl(
            baseUrl: String,
            endpoint: String,
            user: String,
            pass: String,
            salt: String,
            extra: Array<out Pair<String, String>> = emptyArray(),
        ): String {
            val base = baseUrl.trim().trimEnd('/')
            val token = md5Hex(pass + salt)
            val sb = StringBuilder(base)
            sb.append("/rest/").append(endpoint).append(".view")
            sb.append("?u=").append(enc(user))
            sb.append("&t=").append(token)
            sb.append("&s=").append(enc(salt))
            sb.append("&v=").append(API_VERSION)
            sb.append("&c=").append(CLIENT)
            sb.append("&f=json")
            extra.forEach { (k, v) -> sb.append('&').append(k).append('=').append(enc(v)) }
            return sb.toString()
        }

        private fun response(json: String): JSONObject? =
            runCatching { JSONObject(json).optJSONObject("subsonic-response") }.getOrNull()

        fun parsePing(json: String): SubsonicPing {
            val r = response(json) ?: return SubsonicPing(false, "", "", "respuesta ilegible")
            val ok = r.optString("status") == "ok"
            val err = r.optJSONObject("error")?.optString("message")
            return SubsonicPing(
                ok = ok,
                type = r.optString("type").ifBlank { "subsonic" },
                serverVersion = r.optString("serverVersion").ifBlank { r.optString("version") },
                error = if (ok) null else (err ?: "el servidor rechazó la conexión"),
            )
        }

        private fun trackFrom(o: JSONObject): SubsonicTrack? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { return null }
            return SubsonicTrack(
                id = id,
                title = title,
                artist = o.optString("artist").ifBlank { "Desconocido" },
                album = o.optString("album"),
                durationSec = o.optInt("duration", 0),
                suffix = o.optString("suffix").ifBlank { "mp3" }.lowercase(),
                coverArtId = o.optString("coverArt").takeIf { it.isNotBlank() },
            )
        }

        fun parseSearch3(json: String): List<SubsonicTrack> {
            val songs = response(json)?.optJSONObject("searchResult3")?.optJSONArray("song")
                ?: return emptyList()
            return (0 until songs.length()).mapNotNull { songs.optJSONObject(it)?.let(::trackFrom) }
        }

        fun parsePlaylist(json: String): List<SubsonicTrack> {
            val entries = response(json)?.optJSONObject("playlist")?.optJSONArray("entry")
                ?: return emptyList()
            return (0 until entries.length()).mapNotNull { entries.optJSONObject(it)?.let(::trackFrom) }
        }

        fun parsePlaylists(json: String): List<SubsonicPlaylist> {
            val arr = response(json)?.optJSONObject("playlists")?.optJSONArray("playlist")
                ?: return emptyList()
            return (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
                SubsonicPlaylist(id, o.optString("name").ifBlank { "Playlist" }, o.optInt("songCount", 0))
            }
        }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
