package com.aar.privatemusic.data

import com.aar.privatemusic.data.db.ArtistPlays
import com.aar.privatemusic.data.db.DayPlays
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.LastPlay
import com.aar.privatemusic.data.db.PlayCount
import com.aar.privatemusic.data.db.PlayEvent
import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.PlaylistFolder
import com.aar.privatemusic.data.db.PlaylistSongCrossRef
import com.aar.privatemusic.data.db.SmartPlaylist
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.data.db.SongPlays
import com.aar.privatemusic.data.db.WatchedSource
import com.aar.privatemusic.downloader.YtDownloader
import com.aar.privatemusic.lyrics.Lyrics
import com.aar.privatemusic.lyrics.LyricsFetcher
import com.aar.privatemusic.util.AnalysisResult
import com.aar.privatemusic.util.AudioAnalyzer
import com.aar.privatemusic.util.LoudnessScanner
import com.aar.privatemusic.util.readAudioQuality
import com.aar.privatemusic.util.saveCoverImage
import kotlin.math.pow
import kotlinx.coroutines.flow.Flow

class MusicRepository(
    private val dao: MusicDao,
    private val downloader: YtDownloader,
) {
    val musicDir: java.io.File get() = downloader.musicDir

    fun observeSongs(): Flow<List<Song>> = dao.observeSongs()
    fun observeSongIds(): Flow<List<String>> = dao.observeSongIds()
    fun observeSong(id: String): Flow<Song?> = dao.observeSong(id)
    fun observeRecentlyPlayed(limit: Int = 10): Flow<List<Song>> = dao.observeRecentlyPlayed(limit)
    fun observePendingDownloads(): Flow<List<com.aar.privatemusic.data.db.PendingDownload>> =
        dao.observePendingDownloads()

    suspend fun setFavorite(id: String, favorite: Boolean) = dao.setFavorite(id, favorite)

    suspend fun recordPlay(songId: String) =
        dao.insertPlayEvent(PlayEvent(songId = songId, playedAt = System.currentTimeMillis()))

    /** Backfill quality info for songs downloaded before the quality badge existed. */
    suspend fun backfillQuality() {
        dao.songsMissingQuality().forEach { song ->
            readAudioQuality(song.filePath, song.durationSec)?.let {
                dao.updateQuality(song.id, it.codec, it.bitrateKbps, it.sampleRateHz)
            }
        }
    }
    fun observePlaylists(): Flow<List<Playlist>> = dao.observePlaylists()
    fun observePlaylistSongs(id: Long): Flow<List<Song>> = dao.observePlaylistSongsOrdered(id)
    fun observePlaylistSize(id: Long): Flow<Int> = dao.observePlaylistSize(id)

    suspend fun createPlaylist(name: String): Long =
        dao.insertPlaylist(Playlist(name = name, createdAt = System.currentTimeMillis()))

    suspend fun deletePlaylist(playlist: Playlist) {
        playlist.coverPath?.let { java.io.File(it).delete() }
        dao.clearPlaylist(playlist.id)
        dao.deletePlaylist(playlist)
    }

    // ---- Playlist folders ----

    fun observeFolders(): Flow<List<PlaylistFolder>> = dao.observeFolders()

    suspend fun createFolder(name: String): Long =
        dao.insertFolder(PlaylistFolder(name = name.trim(), createdAt = System.currentTimeMillis()))

    suspend fun renameFolder(id: Long, name: String) = dao.renameFolder(id, name.trim())

    /** Deletes a folder and loosens its playlists back to the top level. */
    suspend fun deleteFolder(folder: PlaylistFolder) {
        dao.clearFolder(folder.id)
        dao.deleteFolder(folder)
    }

    suspend fun movePlaylistToFolder(playlistId: Long, folderId: Long?) =
        dao.setPlaylistFolder(playlistId, folderId)

    suspend fun addToPlaylist(playlistId: Long, songId: String) {
        val position = dao.playlistSize(playlistId)
        dao.addToPlaylist(PlaylistSongCrossRef(playlistId, songId, position))
    }

    suspend fun removeFromPlaylist(playlistId: Long, songId: String) =
        dao.removeFromPlaylist(playlistId, songId)

    suspend fun reorderPlaylist(playlistId: Long, orderedSongIds: List<String>) =
        dao.reorderPlaylist(playlistId, orderedSongIds)

    fun observeMostPlayed(): Flow<List<Song>> = dao.observeMostPlayed()
    fun observeForgotten(): Flow<List<Song>> =
        dao.observeForgotten(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
    fun observeRecentlyAdded(): Flow<List<Song>> = dao.observeRecentlyAdded()

    suspend fun getLyrics(song: Song): Lyrics? =
        LyricsFetcher.getOrFetch(song, downloader.musicDir)

    // ---- Loudness ----

    /** Measures RMS loudness of any song that lacks it (runs on app start). */
    suspend fun backfillLoudness() {
        dao.songsMissingLoudness().forEach { song ->
            LoudnessScanner.measureRmsDb(song.filePath)?.let {
                dao.updateLoudness(song.id, it)
            }
        }
    }

    // ---- Metadata / storage ----

    suspend fun updateSongMeta(id: String, title: String, artist: String) =
        dao.updateSongMeta(id, title.trim(), artist.trim())

    /** Copies the picked image into the music dir and points the song at it. */
    suspend fun setSongArt(context: android.content.Context, song: Song, uri: android.net.Uri): Boolean {
        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            saveCoverImage(context, uri, downloader.musicDir, song.id)
        } ?: return false
        val oldArt = song.artPath
        dao.updateSongArt(song.id, file.absolutePath)
        if (oldArt != null && oldArt != file.absolutePath) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                java.io.File(oldArt).delete()
            }
        }
        return true
    }

    suspend fun setPlaylistCover(context: android.content.Context, playlist: Playlist, uri: android.net.Uri): Boolean {
        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            saveCoverImage(context, uri, downloader.musicDir, "playlist_${playlist.id}")
        } ?: return false
        val old = playlist.coverPath
        dao.updatePlaylistCover(playlist.id, file.absolutePath)
        if (old != null && old != file.absolutePath) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                java.io.File(old).delete()
            }
        }
        return true
    }

    data class StorageInfo(val songCount: Int, val totalBytes: Long)

    suspend fun storageInfo(): StorageInfo {
        val songs = dao.songsOnce()
        val bytes = downloader.musicDir.listFiles()?.sumOf { it.length() } ?: 0L
        return StorageInfo(songs.size, bytes)
    }

    // ---- Stats (Replay) ----

    data class ReplayStats(
        val plays: Int,
        val minutes: Long,
        val topSongs: List<SongPlays>,
        val topArtists: List<ArtistPlays>,
    )

    suspend fun replayStats(since: Long): ReplayStats {
        val totals = dao.statsTotals(since)
        return ReplayStats(
            plays = totals.plays,
            minutes = totals.seconds / 60,
            topSongs = dao.topSongs(since),
            topArtists = dao.topArtists(since),
        )
    }

    // ---- Recap (Wrapped local) ----

    data class Recap(
        val plays: Int,
        val minutes: Long,
        val distinctSongs: Int,
        val listeningDays: Int,
        val topSongs: List<SongPlays>,
        val topArtists: List<ArtistPlays>,
        val personality: String,
        val personalityEmoji: String,
        val memorableDays: List<DayPlays>,
        val badges: List<String>,
    )

    suspend fun recap(since: Long): Recap {
        val totals = dao.statsTotals(since)
        val topSongs = dao.topSongs(since, 5)
        val topArtists = dao.topArtists(since, 5)
        val distinctSongs = dao.distinctSongsPlayed(since)
        val days = dao.distinctListeningDays(since)
        val memorable = dao.topDays(since)

        // Listener archetype from repetition vs variety.
        val repetitionRatio = if (distinctSongs > 0) totals.plays.toFloat() / distinctSongs else 0f
        val artistVariety = topArtists.size
        val (personality, emoji) = when {
            totals.plays == 0 -> "Sin datos aún" to "🎧"
            repetitionRatio >= 5f -> "Fanático leal: cuando algo te gusta, lo exprimes" to "🔁"
            repetitionRatio >= 2.5f -> "Ritualista: tienes tus himnos y vuelves a ellos" to "🎯"
            distinctSongs >= 30 && artistVariety >= 4 -> "Explorador: siempre buscando el siguiente descubrimiento" to "🧭"
            else -> "Equilibrado: entre lo conocido y lo nuevo" to "⚖️"
        }

        val badges = buildList {
            if (totals.seconds >= 60L * 60) add("⏱️ Más de 1 hora escuchada")
            if (totals.seconds >= 10L * 3600) add("🔥 Más de 10 horas escuchadas")
            if (totals.seconds >= 100L * 3600) add("🏆 Club de las 100 horas")
            if (distinctSongs >= 10) add("🎵 10+ canciones distintas")
            if (distinctSongs >= 100) add("💿 100+ canciones distintas")
            if (days >= 7) add("📅 7+ días con música")
            if (days >= 30) add("🗓️ 30+ días con música")
            topArtists.firstOrNull()?.let { add("⭐ Top artista: ${it.artist} (${it.plays} reproducciones)") }
        }

        return Recap(
            plays = totals.plays,
            minutes = totals.seconds / 60,
            distinctSongs = distinctSongs,
            listeningDays = days,
            topSongs = topSongs,
            topArtists = topArtists,
            personality = personality,
            personalityEmoji = emoji,
            memorableDays = memorable,
            badges = badges,
        )
    }

    // ---- Daily mix / seasonal capsule ----

    /** Deterministic daily mix: same songs all day, new mix every day. */
    suspend fun buildDailyMix(size: Int = 25): List<Song> {
        val now = System.currentTimeMillis()
        val songs = dao.songsOnce().filter { it.snoozedUntil < now }
        if (songs.isEmpty()) return emptyList()
        val counts = dao.playCountsOnce().associate { it.songId to it.plays }
        val daySeed = now / 86_400_000L
        val rng = java.util.Random(daySeed)

        val favorites = songs.filter { it.isFavorite }
        val mostPlayed = songs.sortedByDescending { counts[it.id] ?: 0 }.take(size)
        val forgotten = songs.filter { (counts[it.id] ?: 0) == 0 }

        val mix = LinkedHashSet<Song>()
        fun pickFrom(pool: List<Song>, n: Int) {
            pool.shuffled(rng).take(n).forEach { mix.add(it) }
        }
        pickFrom(mostPlayed, size * 2 / 5)
        pickFrom(favorites, size * 3 / 10)
        pickFrom(forgotten, size * 3 / 10)
        if (mix.size < size) pickFrom(songs, size - mix.size)
        return mix.toList().shuffled(rng).take(size)
    }

    fun observeSeasonalTop(): Flow<List<Song>> {
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH)
        val seasonStartMonth = (month / 3) * 3 // quarters: dic-feb, mar-may, jun-ago, sep-nov approx.
        cal.set(java.util.Calendar.MONTH, seasonStartMonth)
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        return dao.observeTopPlayedBetween(cal.timeInMillis, System.currentTimeMillis())
    }

    suspend fun resetTailSilence() = dao.resetTailSilence()

    /** Indexes device music (MediaStore) into the library, in place. */
    suspend fun importLocal(context: android.content.Context): Int =
        com.aar.privatemusic.util.LocalImporter.scan(context, dao, musicDir)

    /** Measures the silent tail of songs missing it (crossfade anchor). */
    suspend fun backfillTailSilence() {
        dao.songsMissingTailSilence().forEach { song ->
            val ms = com.aar.privatemusic.util.TailSilence
                .measureTailSilenceMs(song.filePath, song.durationSec) ?: 0L
            dao.updateTailSilence(song.id, ms)
        }
    }

    // ---- On-device sonic analysis ----

    /** Analyzes any song without BPM/key/fingerprint (runs on app start). */
    suspend fun backfillAnalysis() {
        dao.songsMissingAnalysis().forEach { song ->
            AudioAnalyzer.analyze(song.filePath, song.durationSec)?.let {
                dao.updateAnalysis(song.id, it.bpm, it.camelot, it.featuresJson())
            }
        }
    }

    /** "Song radio": the N sonically closest songs, starting with the seed. */
    suspend fun radioFor(seed: Song, size: Int = 25): List<Song> {
        val seedFeatures = seed.sonicFeatures?.let { AnalysisResult.parseFeatures(it) }
            ?: return emptyList()
        val now = System.currentTimeMillis()
        val candidates = dao.songsOnce()
            .filter { it.id != seed.id && it.snoozedUntil < now && it.sonicFeatures != null }
            .mapNotNull { song ->
                AnalysisResult.parseFeatures(song.sonicFeatures!!)?.let { f ->
                    song to AudioAnalyzer.cosineSimilarity(seedFeatures, f)
                }
            }
            .sortedByDescending { it.second }
            .take(size - 1)
            .map { it.first }
        return listOf(seed) + candidates
    }

    /**
     * "Sonic Adventure": a queue that morphs gradually from one song to another.
     * Interpolates a straight line between the two feature vectors and, at each
     * step, picks the still-unused library song nearest that interpolated point.
     * The two chosen songs stay fixed as the endpoints of the journey.
     */
    suspend fun sonicAdventure(from: Song, to: Song, steps: Int = 8): List<Song> {
        val startF = from.sonicFeatures?.let { AnalysisResult.parseFeatures(it) } ?: return emptyList()
        val endF = to.sonicFeatures?.let { AnalysisResult.parseFeatures(it) } ?: return emptyList()
        if (startF.size != endF.size) return emptyList()
        val now = System.currentTimeMillis()
        val pool = dao.songsOnce()
            .filter { it.id != from.id && it.id != to.id && it.snoozedUntil < now && it.sonicFeatures != null }
            .mapNotNull { s -> AnalysisResult.parseFeatures(s.sonicFeatures!!)?.let { s to it } }
            .toMutableList()
        val path = mutableListOf(from)
        val innerSteps = steps.coerceAtLeast(2)
        for (i in 1 until innerSteps) {
            if (pool.isEmpty()) break
            val t = i.toFloat() / innerSteps
            val target = FloatArray(startF.size) { startF[it] + (endF[it] - startF[it]) * t }
            val bestIdx = pool.indices.minByOrNull { squaredDistance(target, pool[it].second) } ?: break
            path.add(pool.removeAt(bestIdx).first)
        }
        path.add(to)
        return path
    }

    private fun squaredDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sum
    }

    /**
     * Spotify's "Smart Reorder", offline: greedy path through the playlist
     * minimizing Camelot distance + BPM jumps between consecutive tracks.
     */
    suspend fun sonicReorderPlaylist(playlistId: Long): Boolean {
        val songs = dao.playlistSongsOnce(playlistId)
        if (songs.size < 3) return false
        val remaining = songs.toMutableList()
        val ordered = mutableListOf(remaining.removeAt(0))
        while (remaining.isNotEmpty()) {
            val last = ordered.last()
            val next = remaining.minByOrNull { candidate ->
                val keyCost = AudioAnalyzer.camelotDistance(last.camelot, candidate.camelot).toFloat()
                val bpmCost = if (last.bpm != null && candidate.bpm != null)
                    kotlin.math.abs(last.bpm - candidate.bpm) / 8f else 1.5f
                keyCost + bpmCost
            }!!
            remaining.remove(next)
            ordered.add(next)
        }
        dao.reorderPlaylist(playlistId, ordered.map { it.id })
        return true
    }

    // ---- Watched sources ----

    fun observeWatchedSources(): Flow<List<WatchedSource>> = dao.observeWatchedSources()

    suspend fun watchSource(url: String, title: String, targetPlaylistId: Long?) =
        dao.insertWatchedSource(
            WatchedSource(
                url = url,
                title = title,
                targetPlaylistId = targetPlaylistId,
                lastCheckedAt = 0,
                addedAt = System.currentTimeMillis(),
            )
        )

    suspend fun unwatchSource(source: WatchedSource) = dao.deleteWatchedSource(source)

    // ---- Snooze / pins ----

    suspend fun snoozeSong(id: String, days: Int = 30) =
        dao.setSnooze(id, System.currentTimeMillis() + days * 86_400_000L)

    suspend fun unsnoozeSong(id: String) = dao.setSnooze(id, 0)

    suspend fun setPlaylistPinned(id: Long, pinned: Boolean) = dao.setPlaylistPinned(id, pinned)

    // ---- Smart playlists ----

    fun observeSmartPlaylists(): Flow<List<SmartPlaylist>> = dao.observeSmartPlaylists()
    fun observeSmartPlaylist(id: Long): Flow<SmartPlaylist?> = dao.observeSmartPlaylist(id)
    fun observePlayCounts(): Flow<List<PlayCount>> = dao.observePlayCounts()

    /** Reproducciones por canción, para ordenar una vista puntualmente. */
    suspend fun playCounts(): Map<String, Int> =
        dao.playCountsOnce().associate { it.songId to it.plays }

    fun observeLastPlayed(): Flow<List<LastPlay>> = dao.observeLastPlayed()

    suspend fun createSmartPlaylist(sp: SmartPlaylist): Long = dao.insertSmartPlaylist(sp)
    suspend fun updateSmartPlaylist(sp: SmartPlaylist) = dao.updateSmartPlaylist(sp)
    suspend fun deleteSmartPlaylist(sp: SmartPlaylist) = dao.deleteSmartPlaylist(sp)

    /**
     * Aplica las reglas en memoria. Las playlists creadas antes del motor no
     * tienen `rulesJson`: [SmartRuleEngine.rulesOf] traduce sus columnas viejas.
     */
    fun evaluateSmartPlaylist(
        sp: SmartPlaylist,
        songs: List<Song>,
        counts: Map<String, Int>,
        lastPlayed: Map<String, Long> = emptyMap(),
    ): List<Song> = SmartRuleEngine.evaluate(
        SmartRuleEngine.rulesOf(sp),
        songs,
        RuleContext(playCounts = counts, lastPlayed = lastPlayed),
    )

    suspend fun deleteSong(song: Song) {
        downloader.deleteSongFiles(song)
        dao.removeSongFromAllPlaylists(song.id)
        dao.deleteSong(song.id)
    }

    // ---- Bulk actions ----

    /** Deletes several songs (files + playlist refs + rows) in one go. */
    suspend fun deleteSongs(songs: List<Song>) = songs.forEach { deleteSong(it) }

    /**
     * Adds [songIds] to a playlist, skipping any already present, appended in order.
     * Returns how many were actually added (the rest were duplicates).
     */
    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<String>): Int {
        val existing = dao.playlistSongsOnce(playlistId).map { it.id }.toSet()
        val toAdd = songIds.filter { it !in existing }
        var position = dao.playlistSize(playlistId)
        toAdd.forEach { dao.addToPlaylist(PlaylistSongCrossRef(playlistId, it, position++)) }
        return toAdd.size
    }

    /** True if a *different* library song already has this title+artist. */
    suspend fun libraryHasDuplicate(title: String, artist: String, exceptId: String): Boolean =
        dao.findByTitleArtist(title, artist)?.let { it.id != exceptId } ?: false

    /**
     * Groups of library songs sharing the same title+artist (each group has >1),
     * best-quality song first so callers can keep it and drop the rest.
     */
    suspend fun duplicateGroups(): List<List<Song>> =
        dao.songsOnce()
            .groupBy { it.title.trim().lowercase() to it.artist.trim().lowercase() }
            .values
            .filter { it.size > 1 }
            .map { group -> group.sortedByDescending { qualityScore(it) } }

    /** Higher = better: lossless codecs win, then bitrate. */
    private fun qualityScore(s: Song): Int {
        val codecBonus = when (s.codec?.lowercase()) {
            "flac", "alac" -> 1_000_000
            else -> 0
        }
        return codecBonus + (s.bitrateKbps ?: 0)
    }

    /** Removes every duplicate keeping the best-quality copy of each. Returns removed count. */
    suspend fun removeDuplicates(): Int {
        val toRemove = duplicateGroups().flatMap { it.drop(1) }
        deleteSongs(toRemove)
        return toRemove.size
    }

    /**
     * Aleatorio que evita repetir lo que acabas de escuchar. Sigue siendo azar:
     * es un muestreo sin reemplazo con pesos (Efraimidis–Spirakis, clave
     * `random^(1/peso)`), no una ordenación por antigüedad, que sería
     * determinista y sonaría siempre igual.
     *
     * El peso crece con los días desde la última escucha y se satura al mes;
     * lo nunca reproducido pesa lo máximo.
     */
    suspend fun shuffleFewerRepeats(songs: List<Song>): List<Song> {
        if (songs.size < 3) return songs.shuffled()
        val lastPlayed = runCatching { dao.lastPlayedOnce() }.getOrDefault(emptyList())
            .associate { it.songId to it.lastPlayed }
        val now = System.currentTimeMillis()
        val month = 30.0 * 24 * 60 * 60 * 1000

        return songs.map { song ->
            val age = lastPlayed[song.id]?.let { (now - it).coerceAtLeast(0L) / month } ?: 1.0
            // 0.05 evita que un peso 0 lo destierre al final para siempre.
            val weight = 0.05 + age.coerceAtMost(1.0)
            song to Math.random().pow(1.0 / weight)
        }.sortedByDescending { it.second }.map { it.first }
    }
}
