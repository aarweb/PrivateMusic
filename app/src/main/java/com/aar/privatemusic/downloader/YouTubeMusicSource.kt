package com.aar.privatemusic.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Searches YouTube Music through its internal InnerTube API — the same one the
 * web player uses. Unlike yt-dlp's flat search it returns clean song metadata
 * (artist, album, duration, cover) instantly. Video ids match YouTube's, so
 * preview and download reuse the existing pipeline untouched.
 */
object YouTubeMusicSource {

    // Public web key used by music.youtube.com; not tied to any account.
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val ENDPOINT = "https://music.youtube.com/youtubei/v1/search?key=$KEY&prettyPrint=false"

    suspend fun search(query: String, limit: Int = 20): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("query", query)
                // "Songs" filter: only real tracks, no albums/artists/videos.
                put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", "es")
                        put("gl", "ES")
                    })
                })
            }
            val json = post(ENDPOINT, body.toString())
            val root = JSONObject(json)
            val items = ArrayList<SearchResult>()
            collectSongs(root, items, limit)
            items
        }

    /** Walks the response for musicResponsiveListItemRenderer song rows. */
    private fun collectSongs(node: Any?, out: ArrayList<SearchResult>, limit: Int) {
        if (out.size >= limit) return
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")?.let { row ->
                    parseSong(row)?.let { if (out.size < limit) out.add(it) }
                }
                for (k in node.keys()) collectSongs(node.opt(k), out, limit)
            }
            is JSONArray -> for (i in 0 until node.length()) collectSongs(node.opt(i), out, limit)
        }
    }

    private fun parseSong(row: JSONObject): SearchResult? {
        val videoId = findVideoId(row) ?: return null
        val cols = row.optJSONArray("flexColumns") ?: return null
        val title = flexText(cols, 0) ?: return null

        // Second column holds "Artist • Album • 3:45" (runs separated by " • ").
        val runs = cols.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")
        var artist = ""
        var durationSec = 0
        if (runs != null) {
            val texts = (0 until runs.length()).map { runs.optJSONObject(it)?.optString("text").orEmpty() }
                .filter { it.isNotBlank() && it != " • " }
            artist = texts.firstOrNull { !it.matches(Regex("""\d+:\d+""")) }.orEmpty()
            texts.lastOrNull { it.matches(Regex("""\d+:\d+""")) }?.let { durationSec = parseDuration(it) }
        }

        val thumb = findLastThumb(row) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        return SearchResult(
            id = videoId,
            title = title,
            artist = artist.ifBlank { "Desconocido" },
            durationSec = durationSec,
            thumbnailUrl = thumb,
        )
    }

    private fun flexText(cols: JSONArray, index: Int): String? =
        cols.optJSONObject(index)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")
            ?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }

    private fun parseDuration(text: String): Int {
        val parts = text.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    /** First watchEndpoint.videoId found anywhere under this row. */
    private fun findVideoId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("watchEndpoint")?.optString("videoId")
                    ?.takeIf { it.isNotBlank() }?.let { return it }
                for (k in node.keys()) findVideoId(node.opt(k))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findVideoId(node.opt(i))?.let { return it }
        }
        return null
    }

    /** Largest thumbnail url under this row. */
    private fun findLastThumb(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONArray("thumbnails")?.let { arr ->
                    arr.optJSONObject(arr.length() - 1)?.optString("url")
                        ?.takeIf { it.isNotBlank() }?.let { return it.substringBefore("=w") }
                }
                for (k in node.keys()) findLastThumb(node.opt(k))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findLastThumb(node.opt(i))?.let { return it }
        }
        return null
    }

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0 Safari/537.36")
        conn.setRequestProperty("Origin", "https://music.youtube.com")
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }
}
