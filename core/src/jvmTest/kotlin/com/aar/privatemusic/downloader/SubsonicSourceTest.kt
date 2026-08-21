package com.aar.privatemusic.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubsonicSourceTest {

    @Test
    fun `construye la url con auth de token salteado`() {
        val url = SubsonicSource.buildUrl(
            baseUrl = "https://demo.navidrome.org/",
            endpoint = "ping",
            user = "demo",
            pass = "demo",
            salt = "abc123",
        )
        // t = md5("demo" + "abc123")
        val expectedToken = SubsonicSource.md5Hex("demoabc123")
        assertTrue(url.startsWith("https://demo.navidrome.org/rest/ping.view?"))
        assertTrue(url.contains("u=demo"))
        assertTrue(url.contains("t=$expectedToken"))
        assertTrue(url.contains("s=abc123"))
        assertTrue(url.contains("v=1.16.1"))
        assertTrue(url.contains("c=privatemusic"))
        assertTrue(url.contains("f=json"))
        // sin doble barra tras normalizar la base
        assertFalse(url.contains(".org//rest"))
    }

    @Test
    fun `parsea ping ok y error`() {
        val ok = SubsonicSource.parsePing(
            """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.63.2"}}""",
        )
        assertTrue(ok.ok)
        assertEquals("navidrome", ok.type)
        assertEquals("0.63.2", ok.serverVersion)

        val bad = SubsonicSource.parsePing(
            """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}""",
        )
        assertFalse(bad.ok)
        assertEquals("Wrong username or password", bad.error)
    }

    @Test
    fun `parsea search3`() {
        val json = """
            {"subsonic-response":{"status":"ok","searchResult3":{"song":[
              {"id":"300","title":"Canción A","artist":"Artista","album":"Álbum","duration":211,"suffix":"flac","coverArt":"al-9"},
              {"id":"301","name":"Sin título extra","artist":"Otro","duration":95,"suffix":"mp3"}
            ]}}}
        """.trimIndent()
        val tracks = SubsonicSource.parseSearch3(json)
        assertEquals(2, tracks.size)
        assertEquals("300", tracks[0].id)
        assertEquals("Canción A", tracks[0].title)
        assertEquals("flac", tracks[0].suffix)
        assertEquals("al-9", tracks[0].coverArtId)
        assertEquals(211, tracks[0].durationSec)
        assertEquals("Sin título extra", tracks[1].title)
    }

    @Test
    fun `parsea playlist y lista de playlists`() {
        val pl = SubsonicSource.parsePlaylist(
            """{"subsonic-response":{"playlist":{"id":"1","name":"Favs","entry":[
              {"id":"10","title":"T1","artist":"A","suffix":"mp3","duration":100},
              {"id":"11","title":"T2","artist":"B","suffix":"flac","duration":200}
            ]}}}""",
        )
        assertEquals(listOf("10", "11"), pl.map { it.id })

        val pls = SubsonicSource.parsePlaylists(
            """{"subsonic-response":{"playlists":{"playlist":[
              {"id":"1","name":"Favs","songCount":2},{"id":"2","name":"Rock","songCount":40}
            ]}}}""",
        )
        assertEquals(2, pls.size)
        assertEquals("Rock", pls[1].name)
        assertEquals(40, pls[1].songCount)
    }

    @Test
    fun `search y playlist vacías no revientan`() {
        assertTrue(SubsonicSource.parseSearch3("""{"subsonic-response":{"status":"ok"}}""").isEmpty())
        assertTrue(SubsonicSource.parsePlaylist("""{"subsonic-response":{}}""").isEmpty())
        assertTrue(SubsonicSource.parsePlaylists("basura no json").isEmpty())
    }
}
