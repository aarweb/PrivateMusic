package com.aar.privatemusic.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistLinkResolverTest {

    @Test
    fun `reconoce el servicio de cada enlace`() {
        assertEquals(LinkService.SPOTIFY, PlaylistLinkResolver.serviceOf("https://open.spotify.com/playlist/37i9"))
        assertEquals(LinkService.DEEZER, PlaylistLinkResolver.serviceOf("https://www.deezer.com/en/playlist/1234567"))
        assertEquals(LinkService.APPLE_MUSIC, PlaylistLinkResolver.serviceOf("https://music.apple.com/us/album/x/1440913150"))
        assertEquals(LinkService.TIDAL, PlaylistLinkResolver.serviceOf("https://tidal.com/browse/playlist/abc-123"))
        assertNull(PlaylistLinkResolver.serviceOf("https://example.com/foo"))
    }

    @Test
    fun `extrae tipo e id de Deezer`() {
        assertEquals("playlist" to "1234567", PlaylistLinkResolver.deezerTarget("https://deezer.com/playlist/1234567"))
        assertEquals("album" to "42", PlaylistLinkResolver.deezerTarget("https://www.deezer.com/fr/album/42?utm=x"))
    }

    @Test
    fun `extrae tipo pais e id de Apple`() {
        val t = PlaylistLinkResolver.appleTarget("https://music.apple.com/us/playlist/hits/pl.abc123")
        assertEquals(Triple("us", "playlist", "pl.abc123"), t)
    }

    @Test
    fun `duracion ISO-8601 a segundos`() {
        assertEquals(200, PlaylistLinkResolver.parseIsoDuration("PT3M20S"))
        assertEquals(3661, PlaylistLinkResolver.parseIsoDuration("PT1H1M1S"))
        assertEquals(45, PlaylistLinkResolver.parseIsoDuration("45"))
        assertEquals(0, PlaylistLinkResolver.parseIsoDuration(""))
    }

    @Test
    fun `parsea un album de Apple Music del JSON-LD`() {
        val html = """
            <html><head>
            <script type="application/ld+json">
            {"@type":"MusicAlbum","name":"Mi Álbum","byArtist":{"name":"El Artista"},
             "tracks":[
               {"@type":"MusicRecording","name":"Pista Uno","duration":"PT3M20S"},
               {"@type":"MusicRecording","name":"Pista Dos","duration":"PT2M10S","byArtist":{"name":"Invitado"}}
             ]}
            </script>
            </head><body></body></html>
        """.trimIndent()
        val pl = PlaylistLinkResolver.parseApple(html)
        assertEquals("Mi Álbum", pl.name)
        assertEquals(2, pl.tracks.size)
        assertEquals("Pista Uno", pl.tracks[0].title)
        assertEquals("El Artista", pl.tracks[0].artists)
        assertEquals(200, pl.tracks[0].durationSec)
        // La pista con su propio artista lo conserva; la otra hereda el del álbum.
        assertEquals("Invitado", pl.tracks[1].artists)
        assertEquals(130, pl.tracks[1].durationSec)
    }

    @Test
    fun `parsea una playlist de Apple Music con itemListElement`() {
        val html = """
            <script type="application/ld+json">
            {"@type":"MusicPlaylist","name":"Mi Lista",
             "track":{"itemListElement":[
               {"item":{"name":"A","byArtist":{"name":"X"},"duration":"PT1M"}},
               {"item":{"name":"B","byArtist":{"name":"Y"},"duration":"PT2M"}}
             ]}}
            </script>
        """.trimIndent()
        val pl = PlaylistLinkResolver.parseApple(html)
        assertEquals("Mi Lista", pl.name)
        assertEquals(listOf("A", "B"), pl.tracks.map { it.title })
        assertEquals(60, pl.tracks[0].durationSec)
    }

    @Test
    fun `parsea Tidal del JSON-LD`() {
        val html = """
            <script type="application/ld+json">
            {"@type":"MusicPlaylist","name":"Tidal Mix",
             "track":[{"name":"Uno","byArtist":{"name":"Art"},"duration":"PT3M"}]}
            </script>
        """.trimIndent()
        val pl = PlaylistLinkResolver.parseTidal(html)
        assertEquals("Tidal Mix", pl.name)
        assertEquals("Uno", pl.tracks.single().title)
        assertEquals(180, pl.tracks.single().durationSec)
    }

    @Test
    fun `extractScripts saca varios bloques`() {
        val html = """<script type="application/ld+json">{"a":1}</script>
            <script type="text/javascript">ignore</script>
            <script type="application/ld+json">{"b":2}</script>"""
        val blocks = PlaylistLinkResolver.extractScripts(html, "application/ld+json")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].contains("\"a\""))
        assertTrue(blocks[1].contains("\"b\""))
    }
}
