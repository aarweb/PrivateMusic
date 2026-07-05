package com.aar.privatemusic.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Busca en Internet Archive (archive.org) — biblioteca legal y gratuita con mucho
 * audio en FLAC (conciertos en directo, dominio público, Creative Commons). Cada
 * resultado es un ÍTEM (álbum/concierto); la descarga la resuelve
 * [InternetArchiveDownloader], que baja las pistas del ítem y las importa como
 * playlist, igual que un torrent.
 */
object InternetArchiveSource {

    private const val UA = "PrivateMusic (https://github.com/aarweb/PrivateMusic)"

    suspend fun search(query: String, limit: Int = 30): List<SearchResult> =
        withContext(Dispatchers.IO) {
            // Sólo ítems de audio, ordenados por descargas (los más fiables primero).
            val q = URLEncoder.encode("($query) AND mediatype:(audio)", "UTF-8")
            val url = "https://archive.org/advancedsearch.php?q=$q" +
                "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=year&fl[]=format" +
                "&sort[]=downloads+desc&rows=$limit&output=json"
            val body = httpGet(url) ?: return@withContext emptyList()
            val docs = JSONObject(body).optJSONObject("response")?.optJSONArray("docs")
                ?: return@withContext emptyList()
            val out = ArrayList<SearchResult>(docs.length())
            for (i in 0 until docs.length()) {
                val d = docs.optJSONObject(i) ?: continue
                val id = d.optString("identifier").takeIf { it.isNotBlank() } ?: continue
                val title = d.optString("title").takeIf { it.isNotBlank() } ?: id
                // creator puede ser cadena o array; nos quedamos con el primero.
                val creator = when {
                    d.optJSONArray("creator") != null ->
                        d.optJSONArray("creator")?.optString(0).orEmpty()
                    else -> d.optString("creator")
                }.ifBlank { "Internet Archive" }
                val year = d.optString("year")
                val formats = ArrayList<String>()
                d.optJSONArray("format")?.let { arr ->
                    for (j in 0 until arr.length()) formats.add(arr.optString(j))
                } ?: d.optString("format").takeIf { it.isNotBlank() }?.let { formats.add(it) }
                out += SearchResult(
                    id = id,
                    title = title,
                    artist = if (year.isBlank()) creator else "$creator · $year",
                    durationSec = 0,
                    thumbnailUrl = "https://archive.org/services/img/$id",
                    isArchive = true,
                    qualityLabel = bestAudioLabel(formats),
                )
            }
            out
        }

    /** Ranking de formatos de audio de archive.org: menor = mejor calidad. 99 = no es audio. */
    internal fun audioFormatRank(format: String): Int {
        val f = format.lowercase()
        return when {
            "24bit" in f && "flac" in f -> 0
            "flac" in f -> 1
            "wav" in f || "aiff" in f -> 1
            "m4a" in f || "aac" in f -> 2
            "mp3" in f -> 2
            "ogg" in f || "vorbis" in f -> 3
            else -> 99
        }
    }

    /** Etiqueta corta del mejor formato de audio presente (o null si no hay audio). */
    internal fun bestAudioLabel(formats: List<String>): String? {
        val best = formats.filter { audioFormatRank(it) < 99 }
            .minByOrNull { audioFormatRank(it) } ?: return null
        val f = best.lowercase()
        return when {
            "24bit" in f && "flac" in f -> "FLAC 24-bit"
            "flac" in f -> "FLAC"
            "wav" in f -> "WAV"
            "aiff" in f -> "AIFF"
            "m4a" in f || "aac" in f -> "M4A"
            "mp3" in f -> "MP3"
            "ogg" in f || "vorbis" in f -> "OGG"
            else -> best
        }
    }

    private fun httpGet(spec: String): String? = try {
        val conn = URL(spec).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", UA)
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        } else {
            conn.disconnect()
            null
        }
    } catch (e: Exception) {
        null
    }
}
