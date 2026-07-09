package com.aar.privatemusic.cast

import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Song
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * Tiny HTTP server that streams library files to the Chromecast over the
 * local network. Serves GET /song/<id> with Range support (the default
 * media receiver seeks with ranges). Runs only while a cast session lives.
 */
class MediaHttpServer(
    private val dao: MusicDao,
    port: Int = PORT,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri ?: return notFound()
        if (path == "/library") return serveLibrary()
        if (path.startsWith("/art/")) return serveArt(path.removePrefix("/art/").substringBefore('.'))
        if (!path.startsWith("/song/")) return notFound()
        val songId = path.removePrefix("/song/").substringBefore('.')
        val song = runBlocking { runCatching { dao.getSong(songId) }.getOrNull() }
            ?: return notFound()
        val file = File(song.filePath)
        if (!file.canRead()) return notFound()

        val mime = when (file.extension.lowercase()) {
            "webm" -> "audio/webm"
            "m4a", "mp4" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "opus", "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
        val total = file.length()

        // Range: bytes=start-end (end optional)
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=")
            val start = spec.substringBefore('-').toLongOrNull() ?: 0L
            val end = spec.substringAfter('-').toLongOrNull() ?: (total - 1)
            if (start in 0 until total && end >= start) {
                val len = end - start + 1
                val fis = FileInputStream(file).apply { skip(start) }
                val res = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, mime, fis, len,
                )
                res.addHeader("Content-Range", "bytes $start-$end/$total")
                res.addHeader("Accept-Ranges", "bytes")
                return res
            }
        }
        val res = newFixedLengthResponse(
            Response.Status.OK, mime, FileInputStream(file), total,
        )
        res.addHeader("Accept-Ranges", "bytes")
        return res
    }

    /**
     * Catálogo completo para el PC: canciones y playlists.
     *
     * Ni una ruta sale de aquí. `filePath` y `artPath` son absolutos y del
     * móvil ("/storage/emulated/0/..."), y en el escritorio no existen. Viaja
     * el `id`; cada aparato resuelve dónde guarda el fichero. Sí viaja `ext`,
     * porque el PC necesita saber cómo llamar al suyo.
     */
    private fun serveLibrary(): Response {
        val (songs, playlists) = runBlocking {
            runCatching {
                val ps = dao.playlistsOnce().map { it to dao.playlistSongsOnce(it.id).map { s -> s.id } }
                dao.songsOnce() to ps
            }.getOrNull() ?: return@runBlocking emptyList<Song>() to emptyList()
        }

        val root = JSONObject()
        root.put("version", LIBRARY_VERSION)

        val songsJson = JSONArray()
        songs.forEach { song ->
            val file = File(song.filePath)
            songsJson.put(
                JSONObject().apply {
                    put("id", song.id)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("durationSec", song.durationSec)
                    put("ext", file.extension.lowercase())
                    put("sizeBytes", if (file.canRead()) file.length() else 0L)
                    put("addedAt", song.addedAt)
                    put("isFavorite", song.isFavorite)
                    put("hasArt", song.artPath?.let { File(it).canRead() } == true)
                    putOpt("codec", song.codec)
                    song.bitrateKbps?.let { put("bitrateKbps", it) }
                    song.sampleRateHz?.let { put("sampleRateHz", it) }
                    song.loudnessDb?.let { put("loudnessDb", it.toDouble()) }
                    song.bpm?.let { put("bpm", it.toDouble()) }
                    putOpt("camelot", song.camelot)
                    putOpt("album", song.album)
                    putOpt("albumArtist", song.albumArtist)
                    song.year?.let { put("year", it) }
                    song.trackNumber?.let { put("trackNumber", it) }
                    putOpt("mbid", song.mbid)
                    putOpt("isrc", song.isrc)
                    put("metadataResolved", song.metadataResolved)
                },
            )
        }
        root.put("songs", songsJson)

        val playlistsJson = JSONArray()
        playlists.forEach { (playlist, songIds) ->
            playlistsJson.put(
                JSONObject().apply {
                    put("id", playlist.id)
                    put("name", playlist.name)
                    putOpt("description", playlist.description)
                    put("createdAt", playlist.createdAt)
                    put("isPinned", playlist.isPinned)
                    put("songIds", JSONArray(songIds))
                },
            )
        }
        root.put("playlists", playlistsJson)

        return newFixedLengthResponse(Response.Status.OK, "application/json", root.toString())
    }

    /** Album art for the receiver's now-playing screen. */
    private fun serveArt(songId: String): Response {
        val song = runBlocking { runCatching { dao.getSong(songId) }.getOrNull() }
            ?: return notFound()
        val file = song.artPath?.let(::File)?.takeIf { it.canRead() } ?: return notFound()
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")

    companion object {
        const val PORT = 8965

        /** Puerto propio para compartir con el PC: el 8965 es de Chromecast. */
        const val SHARE_PORT = 8966

        /** Sube cuando el JSON de /library cambie de forma. */
        const val LIBRARY_VERSION = 1

        /** LAN address of this device (Wi-Fi), or null when unavailable. */
        fun localIp(): String? =
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
    }
}
