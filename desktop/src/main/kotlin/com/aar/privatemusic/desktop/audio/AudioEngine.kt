package com.aar.privatemusic.desktop.audio

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Lo que el reproductor de escritorio necesita de un motor de audio, y nada
 * más. Existe para que la implementación (hoy VLCJ) sea reemplazable: si el
 * GPLv3 de vlcj o el tamaño de libVLC estorban, se escribe otro `AudioEngine`
 * sobre FFmpeg y no se toca ni una línea de la interfaz de usuario.
 *
 * Deliberadamente parecido al `PlayerController` de Android: mismos `StateFlow`,
 * mismos verbos. Cuando la cola se comparta, las dos plataformas hablarán igual.
 */
interface AudioEngine {

    val isPlaying: StateFlow<Boolean>

    /** Posición actual, refrescada con la finura que pidan las letras sincronizadas. */
    val positionMs: StateFlow<Long>

    /** Duración del medio en curso, o 0 mientras no se conozca. */
    val durationMs: StateFlow<Long>

    /** Carga el fichero y empieza a sonar. */
    fun play(file: File)

    fun pause()

    fun resume()

    fun seekTo(ms: Long)

    /** 1.0 = velocidad normal. */
    fun setRate(rate: Float)

    /** 0..100. */
    fun setVolume(percent: Int)

    /** Se invoca cuando la pista termina sola. Nunca por un `stop()` manual. */
    var onFinished: (() -> Unit)?

    fun release()
}
