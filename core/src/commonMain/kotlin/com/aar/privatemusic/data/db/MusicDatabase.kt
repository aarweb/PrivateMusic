package com.aar.privatemusic.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [Song::class, Playlist::class, PlaylistSongCrossRef::class, PlayEvent::class, SmartPlaylist::class, WatchedSource::class, PlaylistFolder::class, PendingDownload::class],
    version = 13,
    exportSchema = false,
)
@ConstructedBy(MusicDatabaseConstructor::class)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao
}

/**
 * KSP genera un `actual` por plataforma. Sin esto, Room multiplataforma no
 * sabe instanciar la base de datos: en Android usaba reflexión, y en KMP no
 * puede.
 */
@Suppress("KotlinNoActualForExpect")
expect object MusicDatabaseConstructor : RoomDatabaseConstructor<MusicDatabase> {
    override fun initialize(): MusicDatabase
}

/**
 * Las mismas doce migraciones que en Android, con la firma de Room KMP:
 * `SQLiteConnection` en vez de `SupportSQLiteDatabase`. El SQL es idéntico
 * — si alguna sentencia se separa de la del móvil, las dos bibliotecas dejan
 * de ser la misma y no hay forma de sincronizarlas.
 */
internal val MUSIC_MIGRATIONS: Array<Migration> = arrayOf(

    object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE songs ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            connection.execSQL("ALTER TABLE songs ADD COLUMN codec TEXT")
            connection.execSQL("ALTER TABLE songs ADD COLUMN bitrateKbps INTEGER")
            connection.execSQL("ALTER TABLE songs ADD COLUMN sampleRateHz INTEGER")
            connection.execSQL(
                """CREATE TABLE IF NOT EXISTS play_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    songId TEXT NOT NULL,
                    playedAt INTEGER NOT NULL)"""
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_play_history_songId ON play_history(songId)")
        }
    },

    object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE songs ADD COLUMN loudnessDb REAL")
            connection.execSQL(
                """CREATE TABLE IF NOT EXISTS smart_playlists (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    artistContains TEXT,
                    onlyFavorites INTEGER NOT NULL,
                    minPlays INTEGER NOT NULL,
                    addedWithinDays INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL)"""
            )
        }
    },

    object : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE playlists ADD COLUMN coverPath TEXT")
        }
    },

    object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE songs ADD COLUMN snoozedUntil INTEGER NOT NULL DEFAULT 0")
            connection.execSQL("ALTER TABLE playlists ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
        }
    },

    object : Migration(5, 6) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """CREATE TABLE IF NOT EXISTS watched_sources (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    url TEXT NOT NULL,
                    title TEXT NOT NULL,
                    targetPlaylistId INTEGER,
                    lastCheckedAt INTEGER NOT NULL,
                    addedAt INTEGER NOT NULL)"""
            )
        }
    },

    object : Migration(6, 7) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE songs ADD COLUMN bpm REAL")
            connection.execSQL("ALTER TABLE songs ADD COLUMN camelot TEXT")
            connection.execSQL("ALTER TABLE songs ADD COLUMN sonicFeatures TEXT")
        }
    },

    object : Migration(7, 8) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """CREATE TABLE IF NOT EXISTS playlist_folders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    createdAt INTEGER NOT NULL)"""
            )
            connection.execSQL("ALTER TABLE playlists ADD COLUMN folderId INTEGER")
        }
    },

    object : Migration(8, 9) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE songs ADD COLUMN tailSilenceMs INTEGER")
        }
    },

    object : Migration(9, 10) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """CREATE TABLE IF NOT EXISTS pending_downloads (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    durationSec INTEGER NOT NULL,
                    thumbnailUrl TEXT NOT NULL,
                    targetPlaylistId INTEGER,
                    attempts INTEGER NOT NULL,
                    addedAt INTEGER NOT NULL)"""
            )
        }
    },

    object : Migration(10, 11) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE songs ADD COLUMN album TEXT")
            connection.execSQL("ALTER TABLE songs ADD COLUMN albumArtist TEXT")
            connection.execSQL("ALTER TABLE songs ADD COLUMN year INTEGER")
            connection.execSQL("ALTER TABLE songs ADD COLUMN trackNumber INTEGER")
            connection.execSQL("ALTER TABLE songs ADD COLUMN mbid TEXT")
            connection.execSQL("ALTER TABLE songs ADD COLUMN isrc TEXT")
            connection.execSQL("ALTER TABLE songs ADD COLUMN metadataResolved INTEGER NOT NULL DEFAULT 0")
        }
    },

    /** Reglas del motor de smart playlists; null = usar las columnas viejas. */
    object : Migration(11, 12) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE smart_playlists ADD COLUMN rulesJson TEXT")
        }
    },

    object : Migration(12, 13) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE playlists ADD COLUMN description TEXT")
        }
    },
)
