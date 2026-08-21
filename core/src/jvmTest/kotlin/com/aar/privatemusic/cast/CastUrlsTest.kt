package com.aar.privatemusic.cast

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Lo que se le manda a la tele tiene que ser SIEMPRE una URL del servidor del
 * móvil. Un `file://` colado aquí es una tele muda, que es justo el fallo que
 * estas pruebas fijan.
 */
class CastUrlsTest {

    @Test
    fun buildsHttpUrlsForTheTv() {
        val base = CastUrls.base("192.168.1.158", 8965)
        assertEquals("http://192.168.1.158:8965", base)
        assertEquals("http://192.168.1.158:8965/song/yt_abc", CastUrls.song(base, "yt_abc"))
        assertEquals("http://192.168.1.158:8965/art/yt_abc", CastUrls.art(base, "yt_abc"))
    }

    @Test
    fun idsWithPrefixesKeepTheirShape() {
        val base = CastUrls.base("10.0.0.5", 8965)
        assertEquals("http://10.0.0.5:8965/song/dz_12345", CastUrls.song(base, "dz_12345"))
        assertEquals("http://10.0.0.5:8965/song/local_42", CastUrls.song(base, "local_42"))
        assertEquals("http://10.0.0.5:8965/song/tor_9f8e", CastUrls.song(base, "tor_9f8e"))
    }

    @Test
    fun opusGetsItsOwnMimeNotAWildcard() {
        // Casi todo lo que baja de YouTube es opus: si sale como audio/* el
        // receptor por defecto tiene que adivinar.
        assertEquals("audio/ogg", CastUrls.mimeFor("/data/music/yt_abc.opus"))
        assertEquals("audio/ogg", CastUrls.mimeFor("cancion.ogg"))
    }

    @Test
    fun knownContainersMapToTheirMime() {
        assertEquals("audio/webm", CastUrls.mimeFor("a.webm"))
        assertEquals("audio/mp4", CastUrls.mimeFor("a.m4a"))
        assertEquals("audio/mp4", CastUrls.mimeFor("a.mp4"))
        assertEquals("audio/mpeg", CastUrls.mimeFor("a.mp3"))
        assertEquals("audio/flac", CastUrls.mimeFor("a.flac"))
        assertEquals("audio/wav", CastUrls.mimeFor("a.wav"))
    }

    @Test
    fun extensionIsCaseInsensitiveAndPathSafe() {
        assertEquals("audio/mpeg", CastUrls.mimeFor("/sdcard/Music/Mi Canción.MP3"))
        assertEquals("audio/flac", CastUrls.mimeFor("/una.carpeta.con.puntos/tema.FLAC"))
    }

    @Test
    fun unknownOrMissingExtensionFallsBack() {
        assertEquals("audio/*", CastUrls.mimeFor("/data/music/sin_extension"))
        assertEquals("audio/*", CastUrls.mimeFor("a.xyz"))
    }
}
