package com.aar.privatemusic.desktop.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.Equalizer
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File

/**
 * `AudioEngine` sobre libVLC. Reproduce mp3, m4a, webm, opus, ogg, flac y wav
 * sin que tengamos que escribir un solo decodificador.
 *
 * Hay **dos** reproductores, no uno. Un fundido de verdad necesita que las dos
 * pistas suenen a la vez; con un solo reproductor lo único posible es bajar el
 * volumen y volver a subirlo, que es un corte con adorno. El que suena es el
 * `activo`; el otro está parado, o apagándose durante un cruce.
 *
 * Dos reglas de vlcj que no se pueden saltar:
 *  - Nunca se llama al reproductor desde el hilo de eventos nativo: cuelga.
 *    Por eso `onFinished` se despacha a una corrutina.
 *  - `release()` es obligatorio; si no, la JVM no termina.
 */
class VlcAudioEngine : AudioEngine {

    private val factory = MediaPlayerFactory()
    private val players = arrayOf(
        factory.mediaPlayers().newMediaPlayer(),
        factory.mediaPlayers().newMediaPlayer(),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var activeIndex = 0
    private val active: MediaPlayer get() = players[activeIndex]

    /** Volumen pedido por el usuario (0..100) y ganancia de la pista en curso. */
    @Volatile private var userVolume = 100
    @Volatile private var gain = 1f

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _crossfading = MutableStateFlow(false)
    override val crossfading: StateFlow<Boolean> = _crossfading.asStateFlow()

    override var onFinished: (() -> Unit)? = null

    private var equalizer: Equalizer? = null

    init {
        players.forEach { p ->
            p.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                // Sólo el activo manda: el que se apaga en un cruce sigue emitiendo
                // eventos, y si los escucháramos pararía la interfaz de la entrante.
                override fun playing(mediaPlayer: MediaPlayer) {
                    if (mediaPlayer === active) _isPlaying.value = true
                }

                override fun paused(mediaPlayer: MediaPlayer) {
                    if (mediaPlayer === active) _isPlaying.value = false
                }

                override fun stopped(mediaPlayer: MediaPlayer) {
                    if (mediaPlayer === active) _isPlaying.value = false
                }

                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                    if (mediaPlayer === active) _durationMs.value = newLength
                }

                override fun finished(mediaPlayer: MediaPlayer) {
                    if (mediaPlayer !== active) return
                    _isPlaying.value = false
                    scope.launch { onFinished?.invoke() }
                }
            })
        }

        // VLC anuncia el tiempo cada ~250 ms, demasiado grueso para que una
        // letra sincronizada caiga en su verso. Preguntamos a 100 ms.
        scope.launch {
            while (true) {
                if (_isPlaying.value) _positionMs.value = active.status().time()
                delay(100)
            }
        }
    }

    // --- Reproducción ----------------------------------------------------

    override fun play(file: File) = start(file.absolutePath)

    // libVLC no distingue entre una ruta y una URL: las dos son "media resource
    // locators". La preescucha remota no necesita nada más que pasarle la URL.
    override fun playUrl(url: String) {
        gain = 1f
        start(url)
    }

    private fun start(mrl: String) {
        cancelCrossfade()
        _positionMs.value = 0
        _durationMs.value = 0
        active.audio().setVolume(volumeFor(gain))
        active.media().play(mrl)
    }

    override fun crossfadeTo(file: File, gain: Float, durationMs: Long, rateOut: Float) {
        if (_crossfading.value || durationMs <= 0) {
            this.gain = gain
            play(file)
            return
        }
        val outgoing = active
        val incoming = players[1 - activeIndex]
        val outGain = this.gain

        incoming.audio().setVolume(0)
        incoming.media().play(file.absolutePath)
        if (rateOut != 1f) outgoing.controls().setRate(rateOut)

        // El activo cambia YA: la interfaz debe mostrar la que entra, y el tiempo
        // que se lee es el suyo. Lo de fuera es una cola que se apaga.
        activeIndex = 1 - activeIndex
        this.gain = gain
        _positionMs.value = 0
        _durationMs.value = 0
        _isPlaying.value = true

        crossfadeJob = scope.launch {
            _crossfading.value = true
            try {
                val steps = (durationMs / STEP_MS).toInt().coerceAtLeast(1)
                for (i in 1..steps) {
                    val t = i.toFloat() / steps
                    // Dos flujos sumados en el mezclador se pasan de 0 dBFS y
                    // saturan: un margen constante los mantiene dentro.
                    outgoing.audio().setVolume(volumeFor(outGain * (1f - t) * HEADROOM))
                    incoming.audio().setVolume(volumeFor(gain * t * HEADROOM))
                    delay(STEP_MS)
                }
            } finally {
                // Aunque nos cancelen a mitad: la saliente calla y vuelve a su
                // velocidad, y la entrante recupera su volumen íntegro.
                runCatching {
                    outgoing.controls().stop()
                    outgoing.controls().setRate(1f)
                    incoming.audio().setVolume(volumeFor(this@VlcAudioEngine.gain))
                }
                _crossfading.value = false
            }
        }
    }

    private var crossfadeJob: kotlinx.coroutines.Job? = null

    /** Un cruce a medias no debe sobrevivir a un cambio manual de canción. */
    private fun cancelCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
    }

    // El `finally` del cruce apaga la saliente y devuelve el volumen íntegro a la
    // entrante, que es justo lo que queremos al cortar el fundido y quedarnos en
    // ella. No hay que reiniciar nada: el reproductor entrante ya está sonando.
    override fun settleCrossfade() = cancelCrossfade()

    override fun pause() = active.controls().pause()

    override fun resume() = active.controls().play()

    override fun seekTo(ms: Long) {
        active.controls().setTime(ms)
        _positionMs.value = ms
    }

    override fun setRate(rate: Float) {
        active.controls().setRate(rate)
    }

    override fun setVolume(percent: Int) {
        userVolume = percent.coerceIn(0, 100)
        if (!_crossfading.value) active.audio().setVolume(volumeFor(gain))
    }

    override fun setGain(gain: Float) {
        this.gain = gain
        if (!_crossfading.value) active.audio().setVolume(volumeFor(gain))
    }

    private fun volumeFor(factor: Float): Int = (userVolume * factor).toInt().coerceIn(0, 100)

    // --- Ecualizador -----------------------------------------------------

    override val equalizerBands: List<Float> by lazy { factory.equalizer().bands() }

    override val equalizerPresets: List<String> by lazy { factory.equalizer().presets() }

    override fun presetAmps(preset: String): FloatArray? =
        runCatching { factory.equalizer().newEqualizer(preset).amps() }.getOrNull()

    override fun setEqualizer(enabled: Boolean, preamp: Float, amps: FloatArray) {
        if (!enabled) {
            equalizer = null
            players.forEach { it.audio().setEqualizer(null) }
            return
        }
        val eq = equalizer ?: factory.equalizer().newEqualizer().also { equalizer = it }
        eq.setPreamp(preamp)
        amps.forEachIndexed { i, amp -> if (i < eq.bandCount()) eq.setAmp(i, amp) }
        // El ecualizador va por reproductor: los dos, o el fundido lo apagaría.
        players.forEach { it.audio().setEqualizer(eq) }
    }

    override fun release() {
        scope.cancel()
        players.forEach { it.release() }
        factory.release()
    }

    private companion object {
        const val STEP_MS = 50L
        const val HEADROOM = 0.9f
    }
}
