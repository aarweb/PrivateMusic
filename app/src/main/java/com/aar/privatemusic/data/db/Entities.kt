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
    // Milliseconds of silent/fade tail at the end (crossfade anchors before it).
    val tailSilenceMs: Long? = null,
    // On-device analysis: tempo, Camelot key ("8B") and sonic feature vector (JSON).
    val bpm: Float? = null,
    val camelot: String? = null,
    val sonicFeatures: String? = null,
    // Canonical tags resolved from online services (iTunes/Deezer/MusicBrainz).
    val album: String? = null,
    val albumArtist: String? = null, // band/group or album artist when it differs
    val year: Int? = null,
    val trackNumber: Int? = null,
    val mbid: String? = null,        // MusicBrainz recording id
    val isrc: String? = null,        // international standard recording code
    // True once the online identify pipeline ran (avoids re-running forever).
    val metadataResolved: Boolean = false,
)

/** Rule-based playlist evaluated live against the library. */
@Entity(tableName = "smart_playlists")
data class SmartPlaylist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    // Reglas viejas, previas al motor. Se conservan para que las playlists ya
    // creadas sigan funcionando; SmartRuleEngine.rulesOf() las traduce al vuelo.
    val artistContains: String?,
    val onlyFavorites: Boolean,
    val minPlays: Int,          // 0 = no requirement
    val addedWithinDays: Int,   // 0 = any time
    val createdAt: Long,
    /** Reglas del motor (grupos Y/O anidados, orden y límite) serializadas a JSON. */
    val rulesJson: String? = null,
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
    // Optional grouping folder; null = loose at the top level.
    val folderId: Long? = null,
)

/** A folder that groups playlists in the library. */
@Entity(tableName = "playlist_folders")
data class PlaylistFolder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

data class DayPlays(val day: String, val plays: Int)

/** A queued download that survives process death (resumed on app start). */
@Entity(tableName = "pending_downloads")
data class PendingDownload(
    @PrimaryKey val id: String, // YouTube video id
    val title: String,
    val artist: String,
    val durationSec: Int,
    val thumbnailUrl: String,
    val targetPlaylistId: Long?,
    val attempts: Int = 0,
    val addedAt: Long,
)

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

data class LastPlay(val songId: String, val lastPlayed: Long)

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
