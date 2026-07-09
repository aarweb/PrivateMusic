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
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File

/**
 * `AudioEngine` sobre libVLC. Reproduce mp3, m4a, webm, opus, ogg, flac y wav
 * sin que tengamos que escribir un solo decodificador.
 *
 * Dos reglas de vlcj que no se pueden saltar:
 *  - Nunca se llama al reproductor desde el hilo de eventos nativo: cuelga.
 *    Por eso `onFinished` se despacha a una corrutina.
 *  - `release()` es obligatorio; si no, la JVM no termina.
 */
class VlcAudioEngine : AudioEngine {

    private val factory = MediaPlayerFactory()
    private val player = factory.mediaPlayers().newMediaPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    override var onFinished: (() -> Unit)? = null

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) { _isPlaying.value = true }
            override fun paused(mediaPlayer: MediaPlayer) { _isPlaying.value = false }
            override fun stopped(mediaPlayer: MediaPlayer) { _isPlaying.value = false }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                _durationMs.value = newLength
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                _isPlaying.value = false
                scope.launch { onFinished?.invoke() }
            }
        })

        // VLC anuncia el tiempo cada ~250 ms, demasiado grueso para que una
        // letra sincronizada caiga en su verso. Preguntamos a 100 ms.
        scope.launch {
            while (true) {
                if (_isPlaying.value) _positionMs.value = player.status().time()
                delay(100)
            }
        }
    }

    override fun play(file: File) {
        _positionMs.value = 0
        _durationMs.value = 0
        player.media().play(file.absolutePath)
    }

    override fun pause() = player.controls().pause()

    override fun resume() = player.controls().play()

    override fun seekTo(ms: Long) {
        player.controls().setTime(ms)
        _positionMs.value = ms
    }

    override fun setRate(rate: Float) {
        player.controls().setRate(rate)
    }

    override fun setVolume(percent: Int) {
        player.audio().setVolume(percent.coerceIn(0, 100))
    }

    override fun release() {
        scope.cancel()
        player.release()
        factory.release()
    }
}
