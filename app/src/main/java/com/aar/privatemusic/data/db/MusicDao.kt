package com.aar.privatemusic.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {

    // ---- Songs ----

    @Query("SELECT * FROM songs ORDER BY addedAt DESC")
    fun observeSongs(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSong(id: String): Song?

    @Query("SELECT * FROM songs WHERE id = :id")
    fun observeSong(id: String): Flow<Song?>

    @Query("UPDATE songs SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE songs SET codec = :codec, bitrateKbps = :bitrateKbps, sampleRateHz = :sampleRateHz WHERE id = :id")
    suspend fun updateQuality(id: String, codec: String?, bitrateKbps: Int?, sampleRateHz: Int?)

    @Query("SELECT * FROM songs WHERE codec IS NULL")
    suspend fun songsMissingQuality(): List<Song>

    // ---- Play history ----

    @Insert
    suspend fun insertPlayEvent(event: PlayEvent)

    @Query(
        """
        SELECT s.* FROM songs s
        INNER JOIN (SELECT songId, MAX(playedAt) AS lastPlayed FROM play_history GROUP BY songId) h
            ON h.songId = s.id
        ORDER BY h.lastPlayed DESC
        LIMIT :limit
        """
    )
    fun observeRecentlyPlayed(limit: Int): Flow<List<Song>>

    // ---- Auto playlists ----

    @Query(
        """
        SELECT s.* FROM songs s
        INNER JOIN (SELECT songId, COUNT(*) AS cnt FROM play_history GROUP BY songId) p
            ON p.songId = s.id
        ORDER BY p.cnt DESC
        LIMIT 100
        """
    )
    fun observeMostPlayed(): Flow<List<Song>>

    @Query(
        """
        SELECT s.* FROM songs s
        LEFT JOIN (SELECT songId, MAX(playedAt) AS lastPlayed FROM play_history GROUP BY songId) p
            ON p.songId = s.id
        WHERE p.lastPlayed IS NULL OR p.lastPlayed < :cutoff
        ORDER BY COALESCE(p.lastPlayed, s.addedAt) ASC
        LIMIT 100
        """
    )
    fun observeForgotten(cutoff: Long): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY addedAt DESC LIMIT 100")
    fun observeRecentlyAdded(): Flow<List<Song>>

    // ---- Blocking snapshots for the Android Auto browse tree ----

    @Query("SELECT * FROM songs ORDER BY addedAt DESC")
    suspend fun songsOnce(): List<Song>

    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY addedAt DESC")
    suspend fun favoritesOnce(): List<Song>

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun playlistsOnce(): List<Playlist>

    @Query(
        """
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON ps.songId = s.id
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.position ASC
        """
    )
    suspend fun playlistSongsOnce(playlistId: Long): List<Song>

    // ---- Playlist reorder ----

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun updatePosition(playlistId: Long, songId: String, position: Int)

    @Transaction
    suspend fun reorderPlaylist(playlistId: Long, orderedSongIds: List<String>) {
        orderedSongIds.forEachIndexed { index, songId ->
            updatePosition(playlistId, songId, index)
        }
    }

    // ---- Loudness / metadata ----

    @Query("UPDATE songs SET loudnessDb = :loudnessDb WHERE id = :id")
    suspend fun updateLoudness(id: String, loudnessDb: Float)

    @Query("SELECT * FROM songs WHERE loudnessDb IS NULL")
    suspend fun songsMissingLoudness(): List<Song>

    @Query("UPDATE songs SET bpm = :bpm, camelot = :camelot, sonicFeatures = :features WHERE id = :id")
    suspend fun updateAnalysis(id: String, bpm: Float?, camelot: String?, features: String?)

    @Query("SELECT * FROM songs WHERE sonicFeatures IS NULL")
    suspend fun songsMissingAnalysis(): List<Song>

    @Query("SELECT loudnessDb FROM songs WHERE id = :id")
    suspend fun getLoudness(id: String): Float?

    @Query("SELECT bpm FROM songs WHERE id = :id")
    suspend fun getBpm(id: String): Float?

    @Query("SELECT tailSilenceMs FROM songs WHERE id = :id")
    suspend fun getTailSilence(id: String): Long?

    @Query("UPDATE songs SET tailSilenceMs = :ms WHERE id = :id")
    suspend fun updateTailSilence(id: String, ms: Long)

    @Query("SELECT * FROM songs WHERE tailSilenceMs IS NULL")
    suspend fun songsMissingTailSilence(): List<Song>

    @Query("UPDATE songs SET tailSilenceMs = NULL")
    suspend fun resetTailSilence()

    @Query("UPDATE songs SET title = :title, artist = :artist WHERE id = :id")
    suspend fun updateSongMeta(id: String, title: String, artist: String)

    @Query("UPDATE songs SET artPath = :artPath WHERE id = :id")
    suspend fun updateSongArt(id: String, artPath: String)

    @Query("UPDATE playlists SET coverPath = :coverPath WHERE id = :id")
    suspend fun updatePlaylistCover(id: Long, coverPath: String)

    // ---- Stats (Replay) ----

    @Query(
        """
        SELECT COUNT(*) AS plays, COALESCE(SUM(s.durationSec), 0) AS seconds
        FROM play_history ph INNER JOIN songs s ON s.id = ph.songId
        WHERE ph.playedAt >= :since
        """
    )
    suspend fun statsTotals(since: Long): StatsTotals

    @Query(
        """
        SELECT s.*, COUNT(ph.id) AS plays FROM play_history ph
        INNER JOIN songs s ON s.id = ph.songId
        WHERE ph.playedAt >= :since
        GROUP BY s.id ORDER BY plays DESC LIMIT :limit
        """
    )
    suspend fun topSongs(since: Long, limit: Int = 5): List<SongPlays>

    @Query(
        """
        SELECT s.artist AS artist, COUNT(ph.id) AS plays FROM play_history ph
        INNER JOIN songs s ON s.id = ph.songId
        WHERE ph.playedAt >= :since
        GROUP BY s.artist ORDER BY plays DESC LIMIT :limit
        """
    )
    suspend fun topArtists(since: Long, limit: Int = 5): List<ArtistPlays>

    @Query("SELECT songId, COUNT(*) AS plays FROM play_history GROUP BY songId")
    fun observePlayCounts(): Flow<List<PlayCount>>

    @Query("SELECT songId, COUNT(*) AS plays FROM play_history GROUP BY songId")
    suspend fun playCountsOnce(): List<PlayCount>

    /** Plays per calendar day, for the "memorable days" recap section. */
    @Query(
        """
        SELECT date(playedAt / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS plays
        FROM play_history WHERE playedAt >= :since
        GROUP BY day ORDER BY plays DESC LIMIT :limit
        """
    )
    suspend fun topDays(since: Long, limit: Int = 3): List<DayPlays>

    @Query("SELECT COUNT(DISTINCT date(playedAt / 1000, 'unixepoch', 'localtime')) FROM play_history WHERE playedAt >= :since")
    suspend fun distinctListeningDays(since: Long): Int

    @Query("SELECT COUNT(DISTINCT songId) FROM play_history WHERE playedAt >= :since")
    suspend fun distinctSongsPlayed(since: Long): Int

    @Query(
        """
        SELECT s.* FROM songs s
        INNER JOIN (SELECT songId, COUNT(*) AS cnt FROM play_history
                    WHERE playedAt BETWEEN :from AND :to GROUP BY songId) p
            ON p.songId = s.id
        ORDER BY p.cnt DESC LIMIT 50
        """
    )
    fun observeTopPlayedBetween(from: Long, to: Long): Flow<List<Song>>

    // ---- Snooze / pins ----

    @Query("UPDATE songs SET snoozedUntil = :until WHERE id = :id")
    suspend fun setSnooze(id: String, until: Long)

    @Query("UPDATE playlists SET isPinned = :pinned WHERE id = :id")
    suspend fun setPlaylistPinned(id: Long, pinned: Boolean)

    // ---- Smart playlists ----

    @Query("SELECT * FROM smart_playlists ORDER BY createdAt DESC")
    fun observeSmartPlaylists(): Flow<List<SmartPlaylist>>

    @Query("SELECT * FROM smart_playlists WHERE id = :id")
    fun observeSmartPlaylist(id: Long): Flow<SmartPlaylist?>

    @Insert
    suspend fun insertSmartPlaylist(sp: SmartPlaylist): Long

    @Delete
    suspend fun deleteSmartPlaylist(sp: SmartPlaylist)

    // ---- Watched sources ----

    @Query("SELECT * FROM watched_sources ORDER BY addedAt DESC")
    fun observeWatchedSources(): Flow<List<WatchedSource>>

    @Query("SELECT * FROM watched_sources")
    suspend fun watchedSourcesOnce(): List<WatchedSource>

    @Insert
    suspend fun insertWatchedSource(source: WatchedSource): Long

    @Delete
    suspend fun deleteWatchedSource(source: WatchedSource)

    @Query("UPDATE watched_sources SET lastCheckedAt = :time WHERE id = :id")
    suspend fun touchWatchedSource(id: Long, time: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM songs WHERE id = :id)")
    suspend fun songExists(id: String): Boolean

    @Query("SELECT id FROM songs")
    fun observeSongIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: Song)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteSong(id: String)

    @Query("DELETE FROM playlist_songs WHERE songId = :id")
    suspend fun removeSongFromAllPlaylists(id: String)

    // ---- Playlists ----

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observePlaylists(): Flow<List<Playlist>>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observePlaylistWithSongs(id: Long): Flow<PlaylistWithSongs?>

    @Query(
        """
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON ps.songId = s.id
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.position ASC
        """
    )
    fun observePlaylistSongsOrdered(playlistId: Long): Flow<List<Song>>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun playlistSize(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    fun observePlaylistSize(playlistId: Long): Flow<Int>

    @Insert
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToPlaylist(ref: PlaylistSongCrossRef)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeFromPlaylist(playlistId: Long, songId: String)

    // ---- Pending downloads (survive process death) ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPending(p: PendingDownload)

    @Query("DELETE FROM pending_downloads WHERE id = :id")
    suspend fun deletePending(id: String)

    @Query("UPDATE pending_downloads SET attempts = attempts + 1 WHERE id = :id")
    suspend fun bumpPendingAttempts(id: String)

    @Query("SELECT * FROM pending_downloads WHERE attempts < 3 ORDER BY addedAt ASC")
    suspend fun pendingDownloads(): List<PendingDownload>

    // ---- Playlist folders ----

    @Query("SELECT * FROM playlist_folders ORDER BY createdAt ASC")
    fun observeFolders(): Flow<List<PlaylistFolder>>

    @Insert
    suspend fun insertFolder(folder: PlaylistFolder): Long

    @Delete
    suspend fun deleteFolder(folder: PlaylistFolder)

    @Query("UPDATE playlist_folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: Long, name: String)

    @Query("UPDATE playlists SET folderId = :folderId WHERE id = :id")
    suspend fun setPlaylistFolder(id: Long, folderId: Long?)

    /** Loosen every playlist inside a folder (used before deleting the folder). */
    @Query("UPDATE playlists SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: Long)
}
