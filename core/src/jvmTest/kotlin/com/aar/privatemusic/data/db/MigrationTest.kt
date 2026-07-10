package com.aar.privatemusic.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Las doce migraciones, ejecutadas de verdad.
 *
 * Una base de datos nueva nace en la versión 14 y no migra nada: el camino que
 * de verdad usa la gente —abrir una biblioteca vieja y subirla— no se prueba
 * solo. Aquí se escribe a mano el esquema de la v1, se mete música dentro, y se
 * comprueba que Room llega a la 14 sin perder una fila.
 *
 * Si Room aceptase la base pero el esquema resultante no coincidiera con las
 * entidades, la validación de Room al abrir la haría fallar. Esa validación
 * *es* la prueba.
 */
class MigrationTest {

    /** El esquema tal y como era antes de la primera migración. */
    private fun createVersion1(dir: File) {
        val connection = BundledSQLiteDriver().open(File(dir, "music.db").absolutePath)
        try {
            connection.execSQL(
                """CREATE TABLE IF NOT EXISTS songs (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    durationSec INTEGER NOT NULL,
                    filePath TEXT NOT NULL,
                    artPath TEXT,
                    thumbnailUrl TEXT,
                    addedAt INTEGER NOT NULL)"""
            )
            connection.execSQL(
                """CREATE TABLE IF NOT EXISTS playlists (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    createdAt INTEGER NOT NULL)"""
            )
            connection.execSQL(
                """CREATE TABLE IF NOT EXISTS playlist_songs (
                    playlistId INTEGER NOT NULL,
                    songId TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    PRIMARY KEY(playlistId, songId))"""
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_songs_songId ON playlist_songs(songId)")

            connection.execSQL(
                """INSERT INTO songs VALUES
                   ('abc', 'Una canción', 'Un artista', 200, '/musica/abc.webm', NULL, NULL, 1000)"""
            )
            connection.execSQL("INSERT INTO playlists (name, createdAt) VALUES ('Mi lista', 2000)")
            connection.execSQL("INSERT INTO playlist_songs VALUES (1, 'abc', 0)")
            connection.execSQL("PRAGMA user_version = 1")
        } finally {
            connection.close()
        }
    }

    @Test
    fun `sube de la version 1 a la 14 sin perder datos`(): Unit = runBlocking {
        val dir = Files.createTempDirectory("pm-migration").toFile()
        createVersion1(dir)

        // Abrir es migrar: si una migración se sale del esquema esperado, Room
        // lanza aquí en vez de devolver un dao.
        val dao = openMusicDatabase(dir).musicDao()

        val songs = dao.songsOnce()
        assertEquals(1, songs.size, "la canción de la v1 tiene que sobrevivir")
        val song = songs.single()
        assertEquals("Una canción", song.title)
        assertEquals("/musica/abc.webm", song.filePath)

        // Columnas nacidas en las migraciones, con sus valores por defecto.
        assertEquals(false, song.isFavorite)
        assertEquals(0L, song.snoozedUntil)
        assertEquals(false, song.metadataResolved)
        assertTrue(song.codec == null && song.bpm == null && song.album == null)

        // La playlist y su relación siguen en pie.
        val playlists = dao.playlistsOnce()
        assertEquals(1, playlists.size)
        assertEquals("Mi lista", playlists.single().name)
        assertEquals(null, playlists.single().description)
        // La 13→14 no puede dejar `updatedAt` a cero: una playlist de antes de la
        // sincronización bidireccional perdería siempre contra cualquier cambio.
        assertEquals(2000L, playlists.single().updatedAt, "updatedAt hereda createdAt")
        assertEquals(null, playlists.single().deletedAt)
        assertEquals(listOf("abc"), dao.playlistSongsOnce(playlists.single().id).map { it.id })

        // Tablas creadas por el camino: si no existen, estas consultas fallan.
        assertEquals(0, dao.smartPlaylistsOnce().size)
        assertEquals(0, dao.watchedSourcesOnce().size)
        assertEquals(0, dao.pendingDownloads().size)
        assertEquals(0, dao.playCountsOnce().size)

        dir.deleteRecursively()
    }
}
