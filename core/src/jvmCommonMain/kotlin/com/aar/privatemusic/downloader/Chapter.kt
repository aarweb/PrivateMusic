package com.aar.privatemusic.downloader

import org.json.JSONObject

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

    companion object {
        /** Lee los capítulos del JSON de `yt-dlp --dump-single-json`. */
        fun parseFrom(dumpJson: String): List<Chapter> {
            val arr = runCatching { JSONObject(dumpJson).optJSONArray("chapters") }.getOrNull()
                ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                Chapter(
                    index = i + 1,
                    title = c.optString("title").ifBlank { "Capítulo ${i + 1}" },
                    startSec = c.optDouble("start_time", 0.0),
                    endSec = c.optDouble("end_time", 0.0),
                )
            }
        }
    }
}
