package com.aar.privatemusic.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Restaurar una copia en otro dispositivo: las rutas absolutas de la copia
 * deben pasar a las del destino, sólo si empiezan por la raíz de origen, y sin
 * tocar ids ni lo que cuelga de otra raíz.
 */
class LibraryRelocationTest {

    private fun newDb(): File {
        val dir = Files.createTempDirectory("relocate").toFile()
        val db = File(dir, "music.db")
        BundledSQLiteDriver().open(db.absolutePath).use { c ->
            c.execSQL("CREATE TABLE songs (id TEXT PRIMARY KEY NOT NULL, filePath TEXT NOT NULL, artPath TEXT)")
            c.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY, coverPath TEXT)")
            c.execSQL(
                """INSERT INTO songs VALUES
                ('yt_a', '/storage/emulated/0/Android/data/app/files/music/yt_a.opus', '/storage/emulated/0/Android/data/app/files/music/yt_a.jpg'),
                ('tor_b', '/data/user/0/app/files/torrents/Album/01.flac', NULL),
                ('local_c', '/storage/emulated/0/Music/c.mp3', '/storage/emulated/0/Music/c.jpg')"""
            )
            c.execSQL("INSERT INTO playlists VALUES (1, '/storage/emulated/0/Android/data/app/files/music/playlist_1.jpg'), (2, NULL)")
        }
        return db
    }

    private fun rows(db: File, sql: String): List<String?> =
        BundledSQLiteDriver().open(db.absolutePath).use { c ->
            c.prepare(sql).use { s ->
                buildList { while (s.step()) add(if (s.isNull(0)) null else s.getText(0)) }
            }
        }

    @Test
    fun rewritesOnlyMatchingPrefixes() {
        val db = newDb()
        relocateLibraryRoots(
            db.absolutePath,
            listOf(
                "/storage/emulated/0/Android/data/app/files" to "/storage/emulated/10/Android/data/app/files",
                "/data/user/0/app/files" to "/data/user/10/app/files",
            ),
        )
        assertEquals(
            listOf(
                "/storage/emulated/10/Android/data/app/files/music/yt_a.opus",
                "/data/user/10/app/files/torrents/Album/01.flac",
                "/storage/emulated/0/Music/c.mp3",
            ),
            rows(db, "SELECT filePath FROM songs ORDER BY rowid"),
        )
        assertEquals(
            listOf("/storage/emulated/10/Android/data/app/files/music/yt_a.jpg", null, "/storage/emulated/0/Music/c.jpg"),
            rows(db, "SELECT artPath FROM songs ORDER BY rowid"),
        )
        assertEquals(
            listOf("/storage/emulated/10/Android/data/app/files/music/playlist_1.jpg", null),
            rows(db, "SELECT coverPath FROM playlists ORDER BY id"),
        )
        assertEquals(listOf("yt_a", "tor_b", "local_c"), rows(db, "SELECT id FROM songs ORDER BY rowid"))
        assertFalse(File(db.path + "-wal").exists())
        assertEquals(3, countLibrarySongs(db.absolutePath))
    }

    @Test
    fun identicalRootsLeaveEverythingAlone() {
        val db = newDb()
        val before = rows(db, "SELECT filePath FROM songs ORDER BY rowid")
        relocateLibraryRoots(db.absolutePath, listOf("/data/user/0/app/files" to "/data/user/0/app/files", "" to "/x"))
        assertEquals(before, rows(db, "SELECT filePath FROM songs ORDER BY rowid"))
    }
}
