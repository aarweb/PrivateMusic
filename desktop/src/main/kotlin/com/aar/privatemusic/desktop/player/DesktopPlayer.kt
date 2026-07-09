package com.aar.privatemusic.desktop.player

import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.PlayEvent
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.desktop.audio.AudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * La cola y el "qué suena ahora", encima del [AudioEngine].
 *
 * Mismos verbos y mismos `StateFlow` que el `PlayerController` de Android, a
 * propósito: el día que la cola se sincronice entre los dos aparatos, hablarán
 * el mismo idioma.
 */
enum class RepeatMode { OFF, ALL, ONE }

class DesktopPlayer(private val engine: AudioEngine, private val dao: MusicDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _current = MutableStateFlow<Song?>(null)
    val current: StateFlow<Song?> = _current.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    private val _volume = MutableStateFlow(100)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    val isPlaying: StateFlow<Boolean> get() = engine.isPlaying
    val positionMs: StateFlow<Long> get() = engine.positionMs
    val durationMs: StateFlow<Long> get() = engine.durationMs

    init {
        // Terminar sola una canción y pulsar "siguiente" no son lo mismo: sólo
        // lo primero repite o vuelve al principio de la cola.
        engine.onFinished = { advanceAutomatically() }
    }

    private fun advanceAutomatically() {
        when (_repeat.value) {
            RepeatMode.ONE -> playAt(_index.value)
            RepeatMode.ALL -> playAt(if (_index.value + 1 in _queue.value.indices) _index.value + 1 else 0)
            RepeatMode.OFF -> next()
        }
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        _queue.value = songs
        playAt(startIndex.coerceIn(songs.indices))
    }

    fun playShuffled(songs: List<Song>) {
        if (songs.isEmpty()) return
        _shuffle.value = true
        playQueue(songs.shuffled(), 0)
    }

    fun addToQueue(song: Song) {
        _queue.value = _queue.value + song
        if (_index.value < 0) playAt(0)
    }

    fun playAt(position: Int) {
        val song = _queue.value.getOrNull(position) ?: return
        val file = File(song.filePath)
        // Un fichero que ya no está no debe dejar la cola en un estado raro:
        // se salta y sigue con la siguiente.
        if (!file.canRead()) {
            if (position + 1 in _queue.value.indices) playAt(position + 1)
            return
        }
        _index.value = position
        _current.value = song
        engine.play(file)
        scope.launch {
            runCatching { dao.insertPlayEvent(PlayEvent(songId = song.id, playedAt = System.currentTimeMillis())) }
        }
    }

    fun next() {
        val position = _index.value + 1
        if (position in _queue.value.indices) playAt(position)
    }

    fun previous() {
        // Como en cualquier reproductor: si ya sonó un rato, vuelve al principio.
        if (engine.positionMs.value > 3_000) {
            engine.seekTo(0)
            return
        }
        val position = _index.value - 1
        if (position in _queue.value.indices) playAt(position)
    }

    fun togglePlayPause() {
        if (engine.isPlaying.value) engine.pause() else engine.resume()
    }

    fun seekTo(ms: Long) = engine.seekTo(ms)

    /** Baraja lo que queda por sonar; lo ya escuchado no se toca. */
    fun toggleShuffle() {
        _shuffle.value = !_shuffle.value
        if (!_shuffle.value || _index.value < 0) return
        val played = _queue.value.take(_index.value + 1)
        val upcoming = _queue.value.drop(_index.value + 1).shuffled()
        _queue.value = played + upcoming
    }

    fun cycleRepeat() {
        _repeat.value = when (_repeat.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _volume.value = clamped
        engine.setVolume(clamped)
    }

    fun toggleFavorite() {
        val song = _current.value ?: return
        scope.launch { dao.setFavorite(song.id, !song.isFavorite) }
    }

    /** El objeto de la canción actual se queda viejo cuando cambia en la base. */
    fun refresh(songs: List<Song>) {
        val id = _current.value?.id ?: return
        songs.firstOrNull { it.id == id }?.let { _current.value = it }
    }
}
