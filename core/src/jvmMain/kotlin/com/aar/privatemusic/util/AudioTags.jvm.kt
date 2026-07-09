package com.aar.privatemusic.util

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * jaudiotagger habla por `java.util.logging` y en un fichero sin etiquetas suelta
 * una advertencia por cada campo que busca. Aquí no aporta nada: si algo falla,
 * quien llama recibe nulos y ya decide.
 */
private val silenced: Unit by lazy {
    Logger.getLogger("org.jaudiotagger").level = Level.OFF
}

/** Abre el fichero una sola vez; lo comparten [readAudioTags] y [readAudioQuality]. */
internal fun openAudioFile(path: String): AudioFile? {
    silenced
    val file = File(path)
    if (!file.canRead()) return null
    return runCatching { AudioFileIO.read(file) }.getOrNull()
}

actual fun readAudioTags(path: String): AudioTags {
    val audio = openAudioFile(path) ?: return AudioTags(null, null, 0)
    val tag = audio.tag
    fun field(key: FieldKey): String? =
        tag?.let { runCatching { it.getFirst(key) }.getOrNull() }?.takeIf { it.isNotBlank() }
    return AudioTags(
        title = field(FieldKey.TITLE),
        artist = field(FieldKey.ARTIST),
        durationSec = audio.audioHeader?.trackLength ?: 0,
    )
}
