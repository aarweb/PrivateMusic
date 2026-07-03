package com.aar.privatemusic.data.db

import androidx.room.Entity
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "songs")
data class Song(
    @PrimaryKey val id: String, // YouTube video id
    val title: String,
    val artist: String,
    val durationSec: Int,
    val filePath: String,
    val artPath: String?,
    val thumbnailUrl: String?,
    val addedAt: Long,
    val isFavorite: Boolean = false,
    // Real stream properties read from the file (quality badge).
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    // Measured RMS loudness in dBFS; drives volume normalization.
    val loudnessDb: Float? = null,
    // Excluded from mixes/auto-playlists until this epoch millis (snooze).
    val snoozedUntil: Long = 0,
    // On-device analysis: tempo, Camelot key ("8B") and sonic feature vector (JSON).
    val bpm: Float? = null,
    val camelot: String? = null,
    val sonicFeatures: String? = null,
)

/** Rule-based playlist evaluated live against the library. */
@Entity(tableName = "smart_playlists")
data class SmartPlaylist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val artistContains: String?,
    val onlyFavorites: Boolean,
    val minPlays: Int,          // 0 = no requirement
    val addedWithinDays: Int,   // 0 = any time
    val createdAt: Long,
)

@Entity(
    tableName = "play_history",
    indices = [androidx.room.Index("songId")],
)
data class PlayEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val playedAt: Long,
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val coverPath: String? = null,
    val isPinned: Boolean = false,
)

data class DayPlays(val day: String, val plays: Int)

/** A YouTube playlist/channel watched for new songs (auto-download). */
@Entity(tableName = "watched_sources")
data class WatchedSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val targetPlaylistId: Long?, // downloads land in this playlist
    val lastCheckedAt: Long,
    val addedAt: Long,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [androidx.room.Index("songId")],
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: String,
    val position: Int,
)

// ---- Query result holders ----

data class StatsTotals(val plays: Int, val seconds: Long)

data class SongPlays(@androidx.room.Embedded val song: Song, val plays: Int)

data class ArtistPlays(val artist: String, val plays: Int)

data class PlayCount(val songId: String, val plays: Int)

data class PlaylistWithSongs(
    @androidx.room.Embedded val playlist: Playlist,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = PlaylistSongCrossRef::class,
            parentColumn = "playlistId",
            entityColumn = "songId",
        ),
    )
    val songs: List<Song>,
)
