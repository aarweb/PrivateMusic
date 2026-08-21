package com.aar.privatemusic.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Una pista pública de Bandcamp resuelta: [streamUrl] es el MP3-128 abierto. */
data class BandcampTrack(
    val title: String,
    val artist: String,
    val durationSec: Int,
    val streamUrl: String,
    val trackNum: Int,
)

/** Contenido de una página de Bandcamp (tema o álbum) con sus pistas y carátula. */
data class BandcampItem(val artist: String, val artUrl: String?, val tracks: List<BandcampTrack>)

/**
 * Bandcamp como fuente: búsqueda pública y descarga del stream MP3-128 abierto
 * que la propia página sirve (previews y temas "name your price / free"). No
 * toca compras ni FLAC (eso exige login de cuenta). El audio de calidad de pago
 * queda fuera a propósito.
 */
object BandcampSource {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    suspend fun search(query: String, limit: Int = 30): List<SearchResult> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("search_text", query)
            .put("search_filter", "t") // sólo temas (tracks)
            .put("full_page", false)
            .toString()
        val json = httpPost(
            "https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic", body,
        ) ?: return@withContext emptyList()
        parseSearch(json).take(limit)
    }

    /** Resuelve una página de tema/álbum a sus pistas con stream MP3-128. */
    suspend fun resolve(pageUrl: String): BandcampItem? = withContext(Dispatchers.IO) {
        val html = httpGet(pageUrl) ?: return@withContext null
        parseTralbum(html)
    }

    // -------- parseo puro (testeable sin red) --------

    fun parseSearch(json: String): List<SearchResult> {
        val results = runCatching { JSONObject(json).optJSONObject("auto")?.optJSONArray("results") }
            .getOrNull() ?: return emptyList()
        val out = ArrayList<SearchResult>(results.length())
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            if (o.optString("type") != "t") continue // sólo temas
            val url = o.optString("item_url_path").ifBlank { o.optString("url") }
            if (url.isBlank()) continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            val band = o.optString("band_name").ifBlank { "Bandcamp" }
            val album = o.optString("album_name")
            out += SearchResult(
                id = url,
                title = name,
                artist = if (album.isBlank() || album == name) band else "$band · $album",
                durationSec = 0, // Bandcamp no lo da en la búsqueda; se sabe al resolver.
                thumbnailUrl = o.optString("img"),
                isBandcamp = true,
                qualityLabel = "MP3 128",
            )
        }
        return out
    }

    fun parseTralbum(html: String): BandcampItem? {
        val attr = extractAttr(html, "data-tralbum") ?: return null
        val obj = runCatching { JSONObject(unescapeHtml(attr)) }.getOrNull() ?: return null
        val artist = obj.optString("artist").ifBlank { "Bandcamp" }
        val artUrl = obj.optString("art_id").takeIf { it.isNotBlank() }
            ?.let { "https://f4.bcbits.com/img/a${it.padStart(10, '0')}_16.jpg" }
        val trackinfo = obj.optJSONArray("trackinfo") ?: return null
        val tracks = ArrayList<BandcampTrack>()
        for (i in 0 until trackinfo.length()) {
            val t = trackinfo.optJSONObject(i) ?: continue
            val title = t.optString("title")
            if (title.isBlank()) continue
            val stream = t.optJSONObject("file")?.optString("mp3-128").orEmpty()
            if (stream.isBlank()) continue
            tracks += BandcampTrack(
                title = title,
                artist = t.optString("artist").ifBlank { artist },
                durationSec = t.optDouble("duration", 0.0).toInt(),
                streamUrl = stream,
                trackNum = t.optInt("track_num", i + 1),
            )
        }
        if (tracks.isEmpty()) return null
        return BandcampItem(artist, artUrl, tracks)
    }

    /** Saca el valor de `attr="..."` respetando que el JSON va HTML-escapado. */
    internal fun extractAttr(html: String, attr: String): String? {
        val key = "$attr=\""
        val start = html.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        val end = html.indexOf('"', from)
        if (end < 0) return null
        return html.substring(from, end)
    }

    /** Entidades que Bandcamp mete en el atributo; `&amp;` la última para no romper URLs. */
    internal fun unescapeHtml(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&amp;", "&")

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

    private fun httpPost(spec: String, body: String): String? = try {
        val conn = URL(spec).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 12_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        } else {
            conn.disconnect(); null
        }
    } catch (e: Exception) {
        null
    }
}
