package com.aar.privatemusic.cast

import com.aar.privatemusic.data.db.MusicDao
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
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

        /** LAN address of this device (Wi-Fi), or null when unavailable. */
        fun localIp(): String? =
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
    }
}
