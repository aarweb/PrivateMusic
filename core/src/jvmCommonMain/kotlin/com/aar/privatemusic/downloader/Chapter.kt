package com.aar.privatemusic.downloader

/**
 * Un capítulo de un vídeo de YouTube (yt-dlp los expone en `chapters`). Muchos
 * álbumes y sets largos vienen como un solo vídeo con marcadores; dividir por
 * capítulos los convierte en una pista por canción.
 */
data class Chapter(
    val index: Int,       // 1-based, el que usa yt-dlp para %(section_number)s
    val title: String,
    val startSec: Double,
    val endSec: Double,
) {
    val durationSec: Int get() = (endSec - startSec).toInt().coerceAtLeast(0)
}
