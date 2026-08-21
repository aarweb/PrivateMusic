package com.aar.privatemusic.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BandcampSourceTest {

    @Test
    fun `parsea la busqueda autocomplete y se queda solo con temas`() {
        val json = """
            {"auto":{"results":[
              {"type":"t","id":1,"name":"Windowlicker","band_name":"Aphex Twin","album_name":"Windowlicker",
               "item_url_path":"https://aphextwin.bandcamp.com/track/windowlicker","img":"https://f4.bcbits.com/img/x_3.jpg"},
              {"type":"a","id":2,"name":"Un álbum","band_name":"Grupo","item_url_path":"https://g.bandcamp.com/album/x"},
              {"type":"b","id":3,"name":"Una banda","band_name":"Grupo"}
            ]}}
        """.trimIndent()
        val results = BandcampSource.parseSearch(json)
        assertEquals(1, results.size)
        val r = results[0]
        assertEquals("https://aphextwin.bandcamp.com/track/windowlicker", r.id)
        assertEquals("Windowlicker", r.title)
        assertEquals("Aphex Twin", r.artist) // album == name → no se duplica
        assertTrue(r.isBandcamp)
        assertEquals("MP3 128", r.qualityLabel)
    }

    @Test
    fun `unescape respeta las URLs con amp`() {
        val esc = "https://x.com/a?b=1&amp;c=2&amp;d=&quot;q&quot;"
        assertEquals("https://x.com/a?b=1&c=2&d=\"q\"", BandcampSource.unescapeHtml(esc))
    }

    @Test
    fun `extrae data-tralbum con json escapado`() {
        val html = """<div id="x" data-tralbum="{&quot;artist&quot;:&quot;A&quot;,&quot;art_id&quot;:&quot;123&quot;,""" +
            """&quot;trackinfo&quot;:[{&quot;title&quot;:&quot;T1&quot;,&quot;duration&quot;:200.5,&quot;track_num&quot;:1,""" +
            """&quot;file&quot;:{&quot;mp3-128&quot;:&quot;https://t4.bcbits.com/stream/abc/mp3-128/9?token=1&amp;ts=2&quot;}}]}"></div>"""
        val item = BandcampSource.parseTralbum(html)
        assertNotNull(item)
        assertEquals("A", item.artist)
        assertEquals("https://f4.bcbits.com/img/a0000000123_16.jpg", item.artUrl)
        assertEquals(1, item.tracks.size)
        val t = item.tracks[0]
        assertEquals("T1", t.title)
        assertEquals(200, t.durationSec)
        assertEquals("https://t4.bcbits.com/stream/abc/mp3-128/9?token=1&ts=2", t.streamUrl)
    }

    @Test
    fun `pagina sin audio devuelve null`() {
        assertTrue(BandcampSource.parseSearch("""{"auto":{}}""").isEmpty())
        assertEquals(null, BandcampSource.parseTralbum("<html>sin nada</html>"))
        val noFile = """<div data-tralbum="{&quot;artist&quot;:&quot;A&quot;,&quot;trackinfo&quot;:[{&quot;title&quot;:&quot;T&quot;}]}"></div>"""
        assertEquals(null, BandcampSource.parseTralbum(noFile))
    }
}
