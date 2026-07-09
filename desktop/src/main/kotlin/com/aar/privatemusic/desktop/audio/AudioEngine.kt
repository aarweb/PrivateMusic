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

    /** True mientras dos pistas suenan a la vez. Nadie debe pedir otro cruce. */
    val crossfading: StateFlow<Boolean>

    /** Carga el fichero y empieza a sonar. */
    fun play(file: File)

    /** Reproduce audio remoto por HTTP, para preescuchar sin descargar. */
    fun playUrl(url: String)

    /**
     * Arranca [file] mientras la pista en curso se apaga, cruzándolas en
     * [durationMs]. [gain] es la ganancia de normalización de la entrante y
     * [rateOut] la velocidad a la que se lleva la saliente (AutoMix): 1 = intacta.
     */
    fun crossfadeTo(file: File, gain: Float, durationMs: Long, rateOut: Float = 1f)

    fun pause()

    fun resume()

    fun seekTo(ms: Long)

    /** 1.0 = velocidad normal. */
    fun setRate(rate: Float)

    /** 0..100. Es el volumen que pide el usuario; la ganancia lo multiplica. */
    fun setVolume(percent: Int)

    /** Ganancia de normalización de la pista en curso. 1 = sonar tal cual. */
    fun setGain(gain: Float)

    /** Frecuencias centrales de las bandas del ecualizador, en Hz. */
    val equalizerBands: List<Float>

    /** Nombres de los ajustes predefinidos que trae libVLC. */
    val equalizerPresets: List<String>

    /** Amplificaciones (dB) de un preajuste, o null si no existe. */
    fun presetAmps(preset: String): FloatArray?

    /** Aplica el ecualizador. [amps] en dB, una por banda. */
    fun setEqualizer(enabled: Boolean, preamp: Float, amps: FloatArray)

    /** Se invoca cuando la pista termina sola. Nunca por un `stop()` manual. */
    var onFinished: (() -> Unit)?

    fun release()
}
