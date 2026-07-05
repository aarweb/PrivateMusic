package com.aar.privatemusic.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Scraper de 1337x (mirror 1377x.to, funciona sin Cloudflare). No hay API:
 * se parsea el HTML de la tabla de resultados con regex y se filtran solo
 * las filas de la categoría Música. El magnet no aparece en el listado, así
 * que cada resultado requiere una petición extra a su página de detalle.
 */
object Torrent1337xSource {

    private const val BASE = "https://www.1377x.to"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val titleRegex = Regex("""href="(/torrent/[^"]+)"[^>]*>([^<]+)</a>""")
    private val seedsRegex = Regex("""class="coll-2[^"]*">(\d+)""")
    private val leechesRegex = Regex("""class="coll-3[^"]*">(\d+)""")
    private val sizeRegex = Regex("""class="coll-4[^"]*">([^<]+)""")
    private val dateRegex = Regex("""class="coll-date[^"]*">([^<]+)""")
    private val magnetRegex = Regex("""magnet:\?xt=urn:btih:[a-zA-Z0-9]+[^"'\s<]*""")
    private val hashRegex = Regex("""urn:btih:([a-zA-Z0-9]+)""")

    suspend fun search(query: String, limit: Int = 20): List<SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                val q = URLEncoder.encode(query.trim(), "UTF-8")
                val html = fetch("$BASE/search/$q/1/")
                val rows = parseRows(html).take(limit)
                // Máximo 6 peticiones de detalle en vuelo para no castigar al mirror.
                val slots = Semaphore(6)
                coroutineScope {
                    rows.map { row -> async { slots.withPermit { row.toResult() } } }.awaitAll()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

    private data class Row(
        val detailPath: String,
        val title: String,
        val seeds: Int,
        val leeches: Int,
        val size: String,
        val date: String,
    )

    private fun parseRows(html: String): List<Row> {
        val tableStart = html.indexOf("table-list")
        if (tableStart < 0) return emptyList()
        val tableEnd = html.indexOf("</table>", tableStart).let { if (it < 0) html.length else it }
        return html.substring(tableStart, tableEnd).split("<tr").drop(1).mapNotNull { row ->
            // El icono de categoría de cada fila enlaza a /sub/music/{Sub}/1/
            // (el icono en sí varía: flaticon-lossless, flaticon-music…).
            val isMusic = row.contains("href=\"/sub/music/", ignoreCase = true) ||
                row.contains("flaticon-music", ignoreCase = true)
            if (!isMusic) return@mapNotNull null
            val link = titleRegex.findAll(row).firstOrNull { it.groupValues[2].isNotBlank() }
                ?: return@mapNotNull null
            Row(
                detailPath = link.groupValues[1],
                title = unescape(link.groupValues[2].trim()),
                seeds = seedsRegex.find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                leeches = leechesRegex.find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                size = sizeRegex.find(row)?.groupValues?.get(1)?.trim().orEmpty(),
                date = dateRegex.find(row)?.groupValues?.get(1)?.trim().orEmpty(),
            )
        }
    }

    /** La página de detalle contiene el magnet; su hash btih hace de id estable. */
    private fun Row.toResult(): SearchResult {
        val magnet = try {
            magnetRegex.find(fetch("$BASE$detailPath"))?.value?.replace("&amp;", "&")
        } catch (e: Exception) {
            null
        }
        val hash = magnet?.let { hashRegex.find(it)?.groupValues?.get(1)?.lowercase() }
        return SearchResult(
            id = hash ?: "$BASE$detailPath",
            title = title,
            // Sin artista fiable en un torrent: la línea secundaria muestra los
            // datos que sí importan para elegir (tamaño, seeds y antigüedad).
            artist = listOf(size, "$seeds S / $leeches L", date)
                .filter { it.isNotBlank() }.joinToString(" · "),
            durationSec = 0,
            thumbnailUrl = "",
            isTorrent = true,
            magnetUri = magnet,
        )
    }

    private fun unescape(text: String): String = text
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", UA)
        return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }
}
