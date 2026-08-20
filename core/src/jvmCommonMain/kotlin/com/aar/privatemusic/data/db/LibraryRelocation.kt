package com.aar.privatemusic.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File

/** Columnas de la biblioteca que guardan rutas absolutas de ficheros. */
private val PATH_COLUMNS = listOf(
    "songs" to "filePath",
    "songs" to "artPath",
    "playlists" to "coverPath",
)

/**
 * Reescribe los prefijos de ruta de una biblioteca **cerrada** (un `music.db`
 * de copia, no el que Room tiene abierto): cada ruta que empiece por `from`
 * pasa a empezar por `to`. Es lo que permite restaurar una copia en otro
 * móvil, otro usuario de Android o un PC, donde `files/music` cuelga de otro
 * sitio. Los ids no se tocan: son sólo claves, aunque los de torrents se
 * calcularan un día a partir de la ruta.
 *
 * Deja el fichero sin WAL colgando, listo para copiarlo o moverlo.
 */
fun relocateLibraryRoots(dbPath: String, mappings: List<Pair<String, String>>) {
    val valid = mappings.filter { (from, to) -> from.isNotBlank() && from != to }
    if (valid.isEmpty()) return
    val connection = BundledSQLiteDriver().open(dbPath)
    try {
        connection.execSQL("BEGIN IMMEDIATE")
        try {
            valid.forEach { (from, to) ->
                PATH_COLUMNS.forEach { (table, column) ->
                    connection.prepare(
                        "UPDATE $table SET $column = ? || substr($column, ?) WHERE substr($column, 1, ?) = ?"
                    ).use { statement ->
                        statement.bindText(1, to)
                        statement.bindInt(2, from.length + 1)
                        statement.bindInt(3, from.length)
                        statement.bindText(4, from)
                        statement.step()
                    }
                }
            }
            connection.execSQL("COMMIT")
        } catch (e: Exception) {
            connection.execSQL("ROLLBACK")
            throw e
        }
        connection.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { it.step() }
    } finally {
        connection.close()
    }
    File("$dbPath-wal").delete()
    File("$dbPath-shm").delete()
}

/** Cuántas canciones hay en una biblioteca cerrada; 0 si no se puede leer. */
fun countLibrarySongs(dbPath: String): Int = runCatching {
    val connection = BundledSQLiteDriver().open(dbPath)
    try {
        connection.prepare("SELECT count(*) FROM songs").use { statement ->
            if (statement.step()) statement.getInt(0) else 0
        }
    } finally {
        connection.close()
    }
}.getOrDefault(0).also {
    File("$dbPath-wal").delete()
    File("$dbPath-shm").delete()
}
