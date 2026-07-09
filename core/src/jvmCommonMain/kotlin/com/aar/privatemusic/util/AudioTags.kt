package com.aar.privatemusic.util

/** Lo que se puede saber de un fichero de audio sin conocer de dónde salió. */
data class AudioTags(val title: String?, val artist: String?, val durationSec: Int)

/**
 * Lee título, artista y duración de las etiquetas del fichero. Cuando no las
 * tiene (o el formato no se entiende) devuelve nulos y duración 0: quien llama
 * ya sabe qué poner en su lugar.
 */
expect fun readAudioTags(path: String): AudioTags
