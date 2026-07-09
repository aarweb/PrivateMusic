package com.aar.privatemusic.util

import java.io.File

/**
 * En el escritorio no hay `MediaExtractor`. El códec se deduce del contenedor,
 * que para esta biblioteca no miente: todo lo que descarga la app guarda la
 * extensión de su códec real (nunca reempaqueta).
 *
 * El bitrate y la frecuencia de muestreo salen de la cabecera del fichero. Si no
 * se puede leer (Opus en WebM, que jaudiotagger no abre) el bitrate se estima del
 * tamaño y la duración, igual que hace Android cuando el contenedor no lo declara.
 */
actual fun readAudioQuality(path: String, durationSec: Int): AudioQuality? {
    val file = File(path)
    if (!file.canRead()) return null
    val codec = when (file.extension.lowercase()) {
        "opus", "webm" -> "OPUS"
        "m4a", "mp4", "aac" -> "AAC"
        "ogg" -> "VORBIS"
        "flac" -> "FLAC"
        "mp3" -> "MP3"
        "wav" -> "WAV"
        else -> return null
    }
    val header = openAudioFile(path)?.audioHeader
    val estimated = if (durationSec > 0) ((file.length() * 8) / durationSec / 1000).toInt() else null
    // getBitRateAsNumber() lanza si la cabecera trae "~128" (VBR) en algunos formatos.
    val bitrate = header?.let { runCatching { it.bitRateAsNumber.toInt() }.getOrNull() }
        ?.takeIf { it > 0 } ?: estimated
    val sampleRate = header?.let { runCatching { it.sampleRateAsNumber }.getOrNull() }?.takeIf { it > 0 }
    return AudioQuality(codec, bitrate, sampleRate)
}
