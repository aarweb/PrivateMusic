package com.aar.privatemusic.stats

import com.aar.privatemusic.data.db.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HistoryImporterTest {

    private fun song(id: String, title: String, artist: String, albumArtist: String? = null) = Song(
        id = id, title = title, artist = artist, durationSec = 200, filePath = "/m/$id.opus",
        artPath = null, thumbnailUrl = null, addedAt = 0L, albumArtist = albumArtist,
    )

    private val library = listOf(
        song("a", "Blinding Lights", "The Weeknd"),
        song("b", "Vivir Mi Vida", "Marc Anthony"),
        song("c", "Shape of You", "Ed Sheeran"),
        song("d", "Despacito (feat. Daddy Yankee)", "Luis Fonsi"),
        song("e", "Hotel California - 2013 Remaster", "Eagles"),
        song("f", "Hello", "Adele"),
        song("g", "Hello", "Lionel Richie"),
    )

    @Test
    fun `spotify streaming history viejo y nuevo`() {
        val json = """
            [
              {"endTime": "2021-03-14 12:34", "artistName": "The Weeknd", "trackName": "Blinding Lights", "msPlayed": 200040},
              {"endTime": "2021-03-14 12:38", "artistName": "The Weeknd", "trackName": "Blinding Lights", "msPlayed": 4000},
              {"endTime": "2021-03-15 08:00", "artistName": "Marc Anthony", "trackName": "Vivir Mi Vida", "msPlayed": 250000},
              {"endTime": "2021-03-15 09:00", "artistName": "Nadie", "trackName": "No Existe", "msPlayed": 250000}
            ]
        """.trimIndent()
        val parsed = assertNotNull(HistoryImporter.parse(json))
        assertEquals(HistoryImporter.Format.SPOTIFY, parsed.format)
        assertEquals(4, parsed.plays.size)
        val m = HistoryImporter.match(parsed.plays, library)
        assertEquals(2, m.plays.size)
        assertEquals(1, m.skippedShort)
        assertEquals(1, m.unmatched)
        assertEquals("a", m.plays[0].first)
        // 2021-03-14T12:34Z
        assertEquals(1615725240000L, m.plays[0].second)

        val extended = """
            [{"ts":"2023-07-01T10:00:00Z","ms_played":180000,"master_metadata_track_name":"Shape of You",
              "master_metadata_album_artist_name":"Ed Sheeran","master_metadata_album_album_name":"÷"},
             {"ts":"2023-07-01T11:00:00Z","ms_played":180000,"master_metadata_track_name":null,
              "master_metadata_album_artist_name":null,"episode_name":"Un podcast"}]
        """.trimIndent()
        val p2 = assertNotNull(HistoryImporter.parse(extended))
        assertEquals(1, p2.plays.size)
        assertEquals("c", HistoryImporter.match(p2.plays, library).plays.single().first)
    }

    @Test
    fun `lastfm csv con comillas`() {
        val csv = """
            uts,utc_time,artist,artist_mbid,album,album_mbid,track,track_mbid
            1700000000,"14 Nov 2023, 22:13",Luis Fonsi,,Vida,,"Despacito (feat. Daddy Yankee)",
            1700000100,"14 Nov 2023, 22:15",Eagles,,Hotel California,,Hotel California,
            1700000200,"14 Nov 2023, 22:17",Adele,,25,,Hello,
        """.trimIndent()
        val parsed = assertNotNull(HistoryImporter.parse(csv))
        assertEquals(HistoryImporter.Format.LASTFM, parsed.format)
        val m = HistoryImporter.match(parsed.plays, library)
        assertEquals(listOf("d", "e", "f"), m.plays.map { it.first })
        assertEquals(1700000000000L, m.plays[0].second)
    }

    @Test
    fun `youtube takeout empareja por canal o por guion`() {
        val json = """
            [
              {"header":"YouTube Music","title":"Watched Blinding Lights","titleUrl":"https://music.youtube.com/watch?v=x",
               "subtitles":[{"name":"The Weeknd - Topic","url":"u"}],"time":"2024-01-02T03:04:05.678Z","products":["YouTube Music"]},
              {"header":"YouTube","title":"Watched Ed Sheeran - Shape of You (Official Music Video)","titleUrl":"https://www.youtube.com/watch?v=y",
               "subtitles":[{"name":"Ed Sheeran","url":"u"}],"time":"2024-01-02T04:00:00Z"},
              {"header":"YouTube","title":"Watched https://www.youtube.com/watch?v=borrado","time":"2024-01-02T05:00:00Z"},
              {"header":"YouTube","title":"Watched Hello","subtitles":[{"name":"Canal Random","url":"u"}],"time":"2024-01-02T06:00:00Z"}
            ]
        """.trimIndent()
        val parsed = assertNotNull(HistoryImporter.parse(json))
        assertEquals(HistoryImporter.Format.YOUTUBE, parsed.format)
        assertEquals(3, parsed.plays.size)
        val m = HistoryImporter.match(parsed.plays, library)
        // "Hello" es ambiguo (Adele / Lionel Richie) y el canal no ayuda: no se empareja.
        assertEquals(listOf("a", "c"), m.plays.map { it.first })
        assertEquals(1, m.unmatched)
        assertEquals(1704164645678L, m.plays[0].second)
    }

    @Test
    fun `deduplica contra lo existente y dentro del fichero`() {
        val plays = listOf(
            HistoryImporter.ImportedPlay("Blinding Lights", "The Weeknd", 1000L, 60_000),
            HistoryImporter.ImportedPlay("Blinding Lights", "The Weeknd", 1000L, 60_000),
            HistoryImporter.ImportedPlay("Blinding Lights", "The Weeknd", 2000L, 60_000),
        )
        val m = HistoryImporter.match(plays, library, existingKeys = setOf("a@2000"))
        assertEquals(listOf("a" to 1000L), m.plays)
    }

    @Test
    fun `normaliza coletillas y acentos`() {
        assertEquals("despacito", HistoryImporter.normalize("Despacito (feat. Daddy Yankee)"))
        assertEquals("hotel california", HistoryImporter.normalize("Hotel California - 2013 Remaster"))
        assertEquals("cancion del mariachi", HistoryImporter.normalize("Canción del Mariachi [Official Video]"))
        assertNull(HistoryImporter.parse("esto no es nada"))
    }
}
