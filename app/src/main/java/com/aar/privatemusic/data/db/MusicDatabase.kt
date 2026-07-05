package com.aar.privatemusic.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Song::class, Playlist::class, PlaylistSongCrossRef::class, PlayEvent::class, SmartPlaylist::class, WatchedSource::class, PlaylistFolder::class, PendingDownload::class],
    version = 10,
    exportSchema = false,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao

    companion object {
        @Volatile private var instance: MusicDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE songs ADD COLUMN codec TEXT")
                db.execSQL("ALTER TABLE songs ADD COLUMN bitrateKbps INTEGER")
                db.execSQL("ALTER TABLE songs ADD COLUMN sampleRateHz INTEGER")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS play_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        songId TEXT NOT NULL,
                        playedAt INTEGER NOT NULL)"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_play_history_songId ON play_history(songId)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN loudnessDb REAL")
                db.execSQL(
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
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN coverPath TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN snoozedUntil INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlists ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS watched_sources (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        url TEXT NOT NULL,
                        title TEXT NOT NULL,
                        targetPlaylistId INTEGER,
                        lastCheckedAt INTEGER NOT NULL,
                        addedAt INTEGER NOT NULL)"""
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN bpm REAL")
                db.execSQL("ALTER TABLE songs ADD COLUMN camelot TEXT")
                db.execSQL("ALTER TABLE songs ADD COLUMN sonicFeatures TEXT")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN tailSilenceMs INTEGER")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS playlist_folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL)"""
                )
                db.execSQL("ALTER TABLE playlists ADD COLUMN folderId INTEGER")
            }
        }

        fun get(context: Context): MusicDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music.db",
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                )
                    .build().also { instance = it }
            }
    }
}
