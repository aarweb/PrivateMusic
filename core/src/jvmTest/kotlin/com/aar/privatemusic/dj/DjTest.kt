package com.aar.privatemusic.dj

import com.aar.privatemusic.data.db.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DjTest {

    private fun song(
        id: String, artist: String = "A", bpm: Float? = 120f, camelot: String? = "8A",
        dance: Float? = 0.5f, aggr: Float? = 0.2f, relax: Float? = 0.2f,
    ) = Song(
        id = id, title = "t$id", artist = artist, durationSec = 200, filePath = "/$id.mp3",
        artPath = null, thumbnailUrl = null, addedAt = 0, bpm = bpm, camelot = camelot,
        danceability = dance, moodAggressive = aggr, moodRelaxed = relax,
    )

    @Test fun energyRisesWithDanceAndAggression() {
        val calm = song("c", dance = 0.1f, aggr = 0.0f, relax = 0.9f, bpm = 70f)
        val hot = song("h", dance = 0.9f, aggr = 0.9f, relax = 0.0f, bpm = 160f)
        assertTrue(DjEngine.energyOf(hot) > DjEngine.energyOf(calm))
    }

    @Test fun energyFallsBackToBpmWhenNoMood() {
        val slow = song("s", bpm = 75f, dance = null, aggr = null, relax = null)
        val fast = song("f", bpm = 165f, dance = null, aggr = null, relax = null)
        assertTrue(DjEngine.energyOf(fast) > DjEngine.energyOf(slow))
    }

    @Test fun sessionFollowsEnergyCurveAndFormsBlocks() {
        val songs = (0 until 40).map {
            song("s$it", artist = "art${it % 7}", bpm = (70 + it * 3).toFloat(),
                dance = (it % 10) / 10f, aggr = (it % 5) / 5f, relax = ((9 - it % 10)) / 10f)
        }
        val s = DjEngine.buildSession(songs, playCounts = emptyMap(), favoriteIds = emptySet(), seed = 42)
        assertTrue(s.blocks.size >= 3, "debe haber varios bloques")
        assertTrue(s.tracks.size >= 8)
        val warm = s.blocks.first { it.kind == DjEngine.BlockKind.WARMUP }
        val peak = s.blocks.first { it.kind == DjEngine.BlockKind.PEAK }
        val warmE = warm.songs.map { DjEngine.energyOf(it) }.average()
        val peakE = peak.songs.map { DjEngine.energyOf(it) }.average()
        assertTrue(peakE > warmE, "el pico ($peakE) debe superar al arranque ($warmE)")
    }

    @Test fun noThreeSameArtistInARow() {
        val songs = (0 until 30).map { song("s$it", artist = arrayOf("uno","dos","tres","cuatro")[it % 4], bpm = (70 + it * 3).toFloat()) }
        val s = DjEngine.buildSession(songs, emptyMap(), emptySet(), seed = 7)
        s.blocks.forEach { b ->
            b.songs.windowed(3).forEach { w ->
                assertTrue(w.map { it.artist }.toSet().size >= 2, "tres seguidas del mismo artista")
            }
        }
    }

    @Test fun deterministicForSameSeed() {
        val songs = (0 until 30).map { song("s$it", bpm = (70 + it * 2).toFloat()) }
        val a = DjEngine.buildSession(songs, emptyMap(), emptySet(), seed = 99).tracks.map { it.id }
        val b = DjEngine.buildSession(songs, emptyMap(), emptySet(), seed = 99).tracks.map { it.id }
        assertEquals(a, b)
    }

    @Test fun blockAtAndIsBlockStart() {
        val songs = (0 until 30).map { song("s$it", bpm = (70 + it * 3).toFloat()) }
        val s = DjEngine.buildSession(songs, emptyMap(), emptySet(), seed = 3)
        assertTrue(s.isBlockStart(0))
        assertEquals(s.blocks.first(), s.blockAt(0))
    }

    @Test fun narratorAnnouncesTempoRise() {
        val n = DjNarrator(seed = 1)
        val cue = DjNarrator.Cue(
            block = DjEngine.Block(DjEngine.BlockKind.BUILD, listOf(song("i", bpm = 140f)), 0.7f),
            incoming = song("i", bpm = 140f), outgoing = song("o", bpm = 90f),
            incomingPlays = 5, incomingLastPlayedDays = 3, artistStreak = 1,
        )
        val line = n.narrate(cue)
        assertTrue(line.contains("140"), "debe citar el BPM real: $line")
    }

    @Test fun narratorNeverInventsWhenDataMissing() {
        val n = DjNarrator(seed = 2)
        val noData = song("x", bpm = null, camelot = null, dance = null, aggr = null, relax = null)
        val cue = DjNarrator.Cue(
            block = DjEngine.Block(DjEngine.BlockKind.WARMUP, listOf(noData), 0.4f),
            incoming = noData, outgoing = null, incomingPlays = 3, incomingLastPlayedDays = null, artistStreak = 1,
        )
        val line = n.narrate(cue)
        assertTrue(line.none { it.isDigit() }, "no debe inventar cifras: $line")
        assertTrue(line.isNotBlank())
    }

    @Test fun narratorFlagsDiscovery() {
        val n = DjNarrator(seed = 5)
        val cue = DjNarrator.Cue(
            block = DjEngine.Block(DjEngine.BlockKind.BUILD, listOf(song("i")), 0.6f),
            incoming = song("i", bpm = 121f), outgoing = song("o", bpm = 120f),
            incomingPlays = 0, incomingLastPlayedDays = null, artistStreak = 1,
        )
        val line = n.narrate(cue)
        assertTrue(line.contains("nunca", true) || line.contains("descubrimiento", true) || line.contains("Rescatada", true), line)
    }
}
