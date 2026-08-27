package com.aar.privatemusic.dj

import com.aar.privatemusic.data.db.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        s.tracks.windowed(3).forEach { w ->
            assertTrue(w.map { it.artist.lowercase() }.toSet().size >= 2, "tres seguidas del mismo artista")
        }
    }

    @Test fun requestedSizesAreExactUniqueAndFlattened() {
        val songs = (0 until 30).map { song("s$it", artist = "art${it % 6}") }
        for (size in listOf(0, 1, 7, 24)) {
            val session = DjEngine.buildSession(songs, emptyMap(), emptySet(), seed = 17, size = size)
            assertEquals(size, session.tracks.size, "tamaño solicitado $size")
            assertEquals(size, session.tracks.map { it.id }.toSet().size)
            assertEquals(session.tracks, session.blocks.flatMap { it.songs })
            assertTrue(session.blocks.all { it.songs.isNotEmpty() })
        }

        val smallPool = songs.take(4) + songs.take(2)
        val session = DjEngine.buildSession(smallPool, emptyMap(), emptySet(), seed = 17, size = 24)
        assertEquals(4, session.tracks.size)
        assertEquals(4, session.tracks.map { it.id }.toSet().size)
        assertEquals(session.tracks, session.blocks.flatMap { it.songs })
        assertTrue(DjEngine.buildSession(songs, emptyMap(), emptySet(), 17, -3).tracks.isEmpty())
    }

    @Test fun avoidsArtistTripleAcrossBlockSeam() {
        fun atEnergy(id: String, artist: String, energy: Float): Song = song(
            id, artist = artist, bpm = 115f, dance = ((energy - 0.275f) / 0.45f),
            aggr = 0f, relax = 0f,
        )
        val songs = listOf(
            atEnergy("warm-1", "Same", 0.450f),
            atEnergy("warm-2", "Same", 0.451f),
            atEnergy("build-same", "Same", 0.650f),
            atEnergy("build-alternative", "Alternative", 0.660f),
            atEnergy("peak", "Third", 0.850f),
            atEnergy("late-build", "Fourth", 0.700f),
            atEnergy("cooldown", "Fifth", 0.350f),
        )
        val session = DjEngine.buildSession(songs, emptyMap(), emptySet(), seed = 1, size = 7)
        assertEquals(session.tracks, session.blocks.flatMap { it.songs })
        session.tracks.windowed(3).forEach { window ->
            assertTrue(window.map { it.artist.lowercase() }.toSet().size > 1, window.map { it.id }.toString())
        }
        val starts = session.blocks.runningFold(0) { total, block -> total + block.songs.size }.dropLast(1)
        assertTrue(starts.drop(1).isNotEmpty())
        assertEquals(2, starts[1], "el fixture debe ejercer la primera costura")
        assertEquals("Alternative", session.tracks[starts[1]].artist)
        starts.drop(1).forEach { start ->
            val seam = session.tracks.subList((start - 2).coerceAtLeast(0), (start + 2).coerceAtMost(session.tracks.size))
            seam.windowed(3).forEach { window ->
                assertTrue(window.map { it.artist.lowercase() }.toSet().size > 1)
            }
        }
    }

    @Test fun sonicOrderTreatsHalfAndDoubleTempoAsCompatible() {
        val start = song("start", bpm = 80f)
        val equivalent = song("equivalent", bpm = 160f)
        val incompatible = song("incompatible", bpm = 123f)
        val ordered = DjEngine.sonicOrder(listOf(start, incompatible, equivalent))
        assertEquals(listOf("start", "equivalent", "incompatible"), ordered.map { it.id })
        assertEquals(ordered, DjEngine.sonicOrder(listOf(start, incompatible, equivalent), maxStretch = 0.20f))
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
        assertNull(s.blockAt(-1))
        assertNull(s.blockAt(s.tracks.size))
        assertFalse(s.isBlockStart(-1))
        assertFalse(s.isBlockStart(s.tracks.size))
    }

    @Test fun replaceFutureFromMiddleOfBlockKeepsMetadataAligned() {
        val original = DjEngine.buildSession(
            (0 until 24).map { song("s$it", artist = "a${it % 5}") },
            emptyMap(), emptySet(), seed = 4, size = 24,
        )
        val currentIndex = 2
        val rest = listOf(song("r1", artist = "R"), song("r2", artist = "S"))
        val replaced = original.replaceFuture(currentIndex, rest, DjEngine.BlockKind.PEAK, 0.9f)

        assertEquals(original.tracks.take(currentIndex + 1) + rest, replaced.tracks)
        assertEquals(replaced.tracks, replaced.blocks.flatMap { it.songs })
        assertEquals(3, replaced.blocks.first().songs.size)
        assertTrue(replaced.isBlockStart(0))
        assertTrue(replaced.isBlockStart(currentIndex + 1))
        assertEquals(DjEngine.BlockKind.PEAK, replaced.blockAt(currentIndex + 1)?.kind)
        assertEquals(replaced.blocks.first(), replaced.blockAt(currentIndex))
    }

    @Test fun replaceFutureFromLaterBlockTruncatesAndHandlesEdgesSafely() {
        val original = DjEngine.buildSession(
            (0 until 24).map { song("s$it", artist = "a${it % 5}") },
            emptyMap(), emptySet(), seed = 8, size = 24,
        )
        val secondStart = original.blocks.first().songs.size
        val currentIndex = secondStart + 1
        val rest = listOf(song("new", artist = "New"))
        val replaced = original.replaceFuture(currentIndex, rest)

        assertEquals(original.tracks.take(currentIndex + 1) + rest, replaced.tracks)
        assertEquals(replaced.tracks, replaced.blocks.flatMap { it.songs })
        assertEquals(original.blocks[0], replaced.blocks[0])
        assertEquals(2, replaced.blocks[1].songs.size)
        assertTrue(replaced.isBlockStart(currentIndex + 1))
        assertEquals(replaced.blocks.last(), replaced.blockAt(currentIndex + 1))

        val truncated = original.replaceFuture(currentIndex, emptyList())
        assertEquals(original.tracks.take(currentIndex + 1), truncated.tracks)
        assertEquals(truncated.tracks, truncated.blocks.flatMap { it.songs })
        assertEquals(original, original.replaceFuture(-1, rest))
        assertEquals(original, original.replaceFuture(original.tracks.size, rest))
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
