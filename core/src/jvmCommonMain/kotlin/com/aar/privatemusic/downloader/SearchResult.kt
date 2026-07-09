package com.aar.privatemusic.downloader

/**
 * Un resultado de cualquier fuente de descarga. Vive en :core porque las
 * fuentes (YouTube Music, Deezer, Internet Archive, 1337x) son HTTP y JSON
 * puros, y las usan igual el móvil y el escritorio.
 */
data class SearchResult(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val thumbnailUrl: String,
    /** Resultado de torrent (1337x): sin preescucha, la acción copia el magnet. */
    val isTorrent: Boolean = false,
    val magnetUri: String? = null,
    /** Ítem de Internet Archive (álbum/concierto): sin preescucha, descarga el ítem. */
    val isArchive: Boolean = false,
    /** Etiqueta de calidad que se descargará (p.ej. "FLAC", "FLAC 24-bit", "MP3"). */
    val qualityLabel: String? = null,
)

sealed interface DownloadState {
    data object Queued : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}
