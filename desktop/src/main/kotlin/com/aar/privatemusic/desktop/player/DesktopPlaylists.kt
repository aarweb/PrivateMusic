package com.aar.privatemusic.desktop.player

import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.PlaylistSongCrossRef

/**
 * Crear, editar y borrar playlists desde el PC.
 *
 * Todo pasa por aquí para que ningún cambio se olvide de marcar `updatedAt`: la
 * sincronización decide quién gana comparando esa fecha, y un cambio sin fecha
 * nueva es un cambio que el móvil pisará sin avisar.
 */
class DesktopPlaylists(private val dao: MusicDao) {

    /**
     * El id lo elige el PC, y tiene que no chocar con los del móvil, que son
     * pequeños y consecutivos (1, 2, 3…). El instante en milisegundos está trece
     * órdenes de magnitud por encima y crece: dos playlists creadas en el mismo
     * milisegundo, en el mismo PC, no pasa.
     */
    suspend fun create(name: String): Long? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val now = System.currentTimeMillis()
        dao.upsertPlaylist(Playlist(id = now, name = clean, createdAt = now, updatedAt = now))
        return now
    }

    suspend fun rename(id: Long, name: String, description: String? = null) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        dao.renamePlaylist(id, clean, description?.trim()?.ifBlank { null })
        dao.touchPlaylist(id, System.currentTimeMillis())
    }

    /** Borrado suave: el móvil tiene que poder enterarse de que ya no está. */
    suspend fun delete(id: Long) = dao.softDeletePlaylist(id, System.currentTimeMillis())

    suspend fun setPinned(id: Long, pinned: Boolean) {
        dao.setPlaylistPinned(id, pinned)
        dao.touchPlaylist(id, System.currentTimeMillis())
    }

    /** Al final de la lista, y sin repetir: `addToPlaylist` ignora el duplicado. */
    suspend fun addSong(playlistId: Long, songId: String) {
        val already = dao.playlistSongsOnce(playlistId).any { it.id == songId }
        if (already) return
        dao.addToPlaylist(PlaylistSongCrossRef(playlistId, songId, dao.playlistSize(playlistId)))
        dao.touchPlaylist(playlistId, System.currentTimeMillis())
    }

    suspend fun removeSong(playlistId: Long, songId: String) {
        dao.removeFromPlaylist(playlistId, songId)
        // Las posiciones quedan con huecos (0, 2, 3…). No importa: el orden sólo
        // se lee, nunca se cuenta, y renumerar sería otra escritura por canción.
        dao.touchPlaylist(playlistId, System.currentTimeMillis())
    }
}
