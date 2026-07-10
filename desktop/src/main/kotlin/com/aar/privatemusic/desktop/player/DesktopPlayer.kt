package com.aar.privatemusic.desktop.player

import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.PlayEvent
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.desktop.DesktopSettings
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

class DesktopPlayer(
    private val engine: AudioEngine,
    private val dao: MusicDao,
    private val settings: DesktopSettings,
) {

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
        scope.launch { crossfadeWatcher() }
    }

    // --- Fundido y normalización -----------------------------------------

    /**
     * Ganancia que lleva la pista a [TARGET_LOUDNESS_DB]. Sólo se baja, nunca se
     * sube por encima de su nivel original: subir una pista bajita hasta el
     * objetivo la haría saturar.
     */
    private fun gainOf(song: Song): Float {
        val loudness = song.loudnessDb
        if (!settings.normalizeVolume.value || loudness == null) return 1f
        val gainDb = (TARGET_LOUDNESS_DB - loudness).coerceIn(-12f, 0f)
        return Math.pow(10.0, gainDb / 20.0).toFloat()
    }

    /**
     * Vigila el final de la pista para lanzar el cruce. VLC no avisa "quedan N
     * segundos": hay que preguntarle. A 200 ms el error del punto de entrada es
     * inaudible y no cuesta nada.
     */
    private suspend fun crossfadeWatcher() {
        while (true) {
            kotlinx.coroutines.delay(200)
            val crossfadeMs = settings.crossfadeSec.value * 1000L
            if (crossfadeMs == 0L) continue
            if (_preview.value != null || engine.crossfading.value) continue
            if (!engine.isPlaying.value) continue
            // Repetir-una vuelve a la misma pista: cruzarla consigo misma no.
            if (_repeat.value == RepeatMode.ONE) continue
            val song = _current.value ?: continue
            val duration = engine.durationMs.value
            // Una pista más corta que dos ventanas de cruce se pasaría la vida
            // fundiéndose. Y sin duración conocida no hay dónde apuntar.
            if (duration <= crossfadeMs * 2) continue
            // El silencio final ya medido no forma parte de la canción: cruzar
            // hacia él suena a corte por mucho que el motor sea perfecto.
            val effectiveEnd = duration - (song.tailSilenceMs ?: 0L)
            if (effectiveEnd - engine.positionMs.value > crossfadeMs) continue
            val position = nextPosition() ?: continue
            crossfadeTo(position, crossfadeMs, song)
        }
    }

    /** Índice de la siguiente según el modo de repetición, o null si no hay. */
    private fun nextPosition(): Int? {
        val next = _index.value + 1
        return when {
            next in _queue.value.indices -> next
            _repeat.value == RepeatMode.ALL && _queue.value.isNotEmpty() -> 0
            else -> null
        }
    }

    private fun crossfadeTo(position: Int, crossfadeMs: Long, outgoing: Song) {
        val song = _queue.value.getOrNull(position) ?: return
        val file = File(song.filePath)
        if (!file.canRead()) return
        // AutoMix: la saliente se lleva al tempo de la entrante mientras se apaga,
        // así el cruce no suena a dos canciones a destiempo. Nunca más de un 10%:
        // pasado ahí, el cambio de tono se nota.
        val outBpm = outgoing.bpm
        val inBpm = song.bpm
        val rateOut = if (settings.autoMix.value && outBpm != null && inBpm != null && outBpm > 0f) {
            (inBpm / outBpm).coerceIn(0.9f, 1.1f)
        } else 1f

        _index.value = position
        _current.value = song
        engine.crossfadeTo(file, gainOf(song), crossfadeMs, rateOut)
        scope.launch {
            runCatching { dao.insertPlayEvent(PlayEvent(songId = song.id, playedAt = System.currentTimeMillis())) }
        }
    }

    // --- Preescucha ------------------------------------------------------

    /** Lo que suena desde la red al preescuchar un resultado de búsqueda. */
    data class Preview(val id: String, val title: String, val artist: String, val coverUrl: String)

    private val _preview = MutableStateFlow<Preview?>(null)
    val preview: StateFlow<Preview?> = _preview.asStateFlow()

    /**
     * Toma el control de la reproducción: preescuchar es lo que el usuario quiere
     * oír AHORA. La cola sigue intacta, y vuelve en cuanto se pulse cualquier
     * canción de la biblioteca.
     */
    fun playPreview(preview: Preview, url: String) {
        _preview.value = preview
        _current.value = null
        engine.playUrl(url)
    }

    private fun advanceAutomatically() {
        // Una preescucha que se acaba no arrastra la cola detrás.
        if (_preview.value != null) {
            _preview.value = null
            return
        }
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

    /**
     * Quitar de la cola no debe cortar la música. Si se quita algo anterior a lo
     * que suena, el índice se corre para seguir apuntando a la misma canción; si
     * se quita lo que suena, se pasa a la siguiente (que ocupa ese mismo hueco).
     *
     * Quitar la última cuando era la que sonaba **termina**, no retrocede a la
     * anterior: es lo que hace ExoPlayer en el móvil, y que se ponga a sonar sola
     * una canción que no habías pedido es de las cosas que más asustan.
     */
    fun removeFromQueue(position: Int) {
        val queue = _queue.value
        if (position !in queue.indices) return
        val playing = _index.value
        _queue.value = queue.toMutableList().apply { removeAt(position) }

        if (position < playing) { _index.value = playing - 1; return }
        if (position != playing) return // se quitó algo posterior: nada que hacer

        // Durante una preescucha no suena nada de la cola: sólo hay que dejar el
        // índice donde toca, sin arrancar música que nadie ha pedido.
        if (_preview.value != null) {
            _index.value = position.coerceAtMost(_queue.value.lastIndex)
            return
        }
        if (_queue.value.getOrNull(position) != null) playAt(position) else stopPlayback()
    }

    /** Se acabó: ni cola, ni canción, ni sonido. */
    private fun stopPlayback() {
        engine.pause()
        _current.value = null
        _index.value = -1
    }

    /** Mueve una canción dentro de la cola sin tocar la que está sonando. */
    fun moveInQueue(from: Int, to: Int) {
        val queue = _queue.value
        if (from !in queue.indices || to !in queue.indices || from == to) return
        _queue.value = queue.toMutableList().apply { add(to, removeAt(from)) }

        val playing = _index.value
        if (playing < 0) return
        // Nada de buscar la canción por su id: la misma puede estar dos veces en
        // la cola y ganaría la copia equivocada, y durante una preescucha no hay
        // canción que buscar. Estas cuatro reglas son exactas.
        _index.value = when {
            playing == from -> to
            from < playing && to >= playing -> playing - 1
            from > playing && to <= playing -> playing + 1
            else -> playing
        }
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
        _preview.value = null
        engine.setGain(gainOf(song))
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
        toggleFavoriteOf(song)
    }

    /** El corazón de una fila cualquiera, no sólo el de la que suena. */
    fun toggleFavoriteOf(song: Song) {
        scope.launch { dao.setFavorite(song.id, !song.isFavorite) }
    }

    /** El objeto de la canción actual se queda viejo cuando cambia en la base. */
    fun refresh(songs: List<Song>) {
        val id = _current.value?.id ?: return
        songs.firstOrNull { it.id == id }?.let { _current.value = it }
    }

    companion object {
        /** Sonoridad objetivo al normalizar, la misma que el móvil (y que el streaming). */
        const val TARGET_LOUDNESS_DB = -14f
    }
}
