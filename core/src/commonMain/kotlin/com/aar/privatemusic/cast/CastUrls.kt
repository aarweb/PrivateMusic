package com.aar.privatemusic.cast

/**
 * Direcciones y tipos MIME con los que la tele lee la biblioteca del móvil.
 *
 * La tele no puede abrir un `file://` del teléfono: todo lo que se le manda
 * tiene que apuntar al servidor HTTP que el móvil levanta mientras dura la
 * sesión. Aquí viven esas direcciones, en un solo sitio, porque las construyen
 * dos caminos distintos (al empezar a compartir y cada vez que eliges otra
 * canción o playlist mientras suena en la tele) y si se separan vuelve a pasar
 * lo de siempre: uno se queda con rutas locales y la tele se queda muda.
 */
object CastUrls {

    fun base(ip: String, port: Int): String = "http://$ip:$port"

    fun song(base: String, songId: String): String = "$base/song/$songId"

    fun art(base: String, songId: String): String = "$base/art/$songId"

    /**
     * Tipo MIME por la extensión del fichero. El receptor por defecto de
     * Chromecast lo usa para decidir si sabe reproducir algo, así que un tipo
     * genérico en un opus (el formato de casi todo lo que baja de YouTube) es
     * pedirle que adivine.
     */
    fun mimeFor(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "webm" -> "audio/webm"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "opus", "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> "audio/*"
        }
}
