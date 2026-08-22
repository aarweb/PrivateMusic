package com.aar.privatemusic.player

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.aar.privatemusic.data.MusicRepository
import com.aar.privatemusic.data.MixPromptParser
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.dj.DjEngine
import com.aar.privatemusic.dj.DjNarrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * AI DJ local: arma una sesión con curva de energía y bloques (DjEngine), la
 * reproduce, y cuando cambia de bloque presenta una locución (DjNarrator) por
 * texto y, opcionalmente, por voz (TTS del sistema, offline). Reacciona a
 * peticiones en español re-secuenciando el resto de la cola.
 *
 * La voz usa el TTS del sistema (neuronal y offline en los Android modernos):
 * cero peso en el APK y sin permisos. Se sintetiza la frase corta y se habla
 * SIN pedir foco de audio, así que no pausa la música ni toca el crossfade;
 * como mucho el sistema hace un leve ducking. Off por defecto.
 */
class DjManager(
    private val context: Context,
    private val dao: MusicDao,
    private val repository: MusicRepository,
    private val player: PlayerController,
    private val settings: com.aar.privatemusic.data.AppSettings,
    private val scope: CoroutineScope,
) {
    data class State(
        val active: Boolean = false,
        /** Locución del bloque actual, para la tarjeta del reproductor. */
        val line: String? = null,
        val blockKind: DjEngine.BlockKind? = null,
        val building: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var session: DjEngine.Session? = null
    private var narrator = DjNarrator()
    private var playCounts: Map<String, Int> = emptyMap()
    private var lastPlayed: Map<String, Long> = emptyMap()
    private var lastAnnouncedIndex = -1
    private var observeJob: Job? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Arranca una nueva sesión de DJ. [seed] la hace repetible; 0 = del día/hora. */
    fun start(seed: Long) {
        _state.value = State(active = true, building = true)
        scope.launch {
            val now = System.currentTimeMillis()
            val songs = dao.songsOnce().filter { it.snoozedUntil < now }
            playCounts = dao.playCountsOnce().associate { it.songId to it.plays }
            lastPlayed = dao.lastPlayedOnce().associate { it.songId to it.lastPlayed }
            val favs = songs.filter { it.isFavorite }.map { it.id }.toSet()
            val s = DjEngine.buildSession(songs, playCounts, favs, seed = seed)
            if (s.tracks.isEmpty()) {
                _state.value = State(active = false)
                com.aar.privatemusic.util.Feedback.show("Aún no hay suficientes canciones analizadas para el DJ")
                return@launch
            }
            session = s
            narrator = DjNarrator(seed = seed)
            lastAnnouncedIndex = -1
            if (settings.djVoice.value) ensureTts()
            withContext(Dispatchers.Main) { player.playQueueInOrder(s.tracks) }
            _state.value = State(active = true, building = false)
            observeBlocks()
        }
    }

    fun stop() {
        observeJob?.cancel()
        session = null
        _state.value = State(active = false)
    }

    /** El usuario pide algo ("más animado"): re-secuencia el resto de la cola. */
    fun request(text: String) {
        val s = session ?: return
        scope.launch {
            val parsed = MixPromptParser.parse(text)
            val mix = repository.buildCustomMix(parsed, size = 20)
            if (mix.songs.isEmpty()) {
                com.aar.privatemusic.util.Feedback.show("No encontré nada así en tu biblioteca")
                return@launch
            }
            // Mantiene lo que ya suena y reordena lo que viene con la petición.
            val idx = player.currentIndex.value
            val head = s.tracks.take(idx + 1)
            val rest = DjEngine.sonicOrder(mix.songs.filter { song -> head.none { it.id == song.id } })
            val newTracks = head + rest
            val favs = head.filter { it.isFavorite }.map { it.id }.toSet()
            // Un solo bloque "a petición" para el resto.
            val block = DjEngine.Block(DjEngine.BlockKind.BUILD, rest, 0.6f)
            session = DjEngine.Session(newTracks, s.blocks.take(1) + block)
            lastAnnouncedIndex = idx
            withContext(Dispatchers.Main) {
                // Reemplaza sólo la parte futura de la cola.
                player.replaceUpcoming(rest)
            }
            val line = narrator.narrateRequest(parsed.summary)
            announce(line, DjEngine.BlockKind.BUILD)
        }
    }

    private fun observeBlocks() {
        observeJob?.cancel()
        observeJob = scope.launch {
            player.currentIndex.collect { index ->
                val s = session ?: return@collect
                if (!s.isBlockStart(index) || index == lastAnnouncedIndex) return@collect
                lastAnnouncedIndex = index
                val block = s.blockAt(index) ?: return@collect
                val incoming = s.tracks.getOrNull(index) ?: return@collect
                val outgoing = s.tracks.getOrNull(index - 1)
                val streak = run {
                    var n = 1
                    var i = index - 1
                    while (i >= 0 && s.tracks[i].artist.equals(incoming.artist, true)) { n++; i-- }
                    n
                }
                val lastMs = lastPlayed[incoming.id]
                val days = lastMs?.let { ((System.currentTimeMillis() - it) / 86_400_000L).toInt() }
                val cue = DjNarrator.Cue(
                    block = block, incoming = incoming, outgoing = outgoing,
                    incomingPlays = playCounts[incoming.id] ?: 0,
                    incomingLastPlayedDays = days, artistStreak = streak,
                )
                announce(narrator.narrate(cue), block.kind)
            }
        }
    }

    private fun announce(line: String, kind: DjEngine.BlockKind) {
        _state.value = _state.value.copy(line = line, blockKind = kind)
        if (settings.djVoice.value) speak(line)
    }

    // ---- Voz (TTS del sistema, offline) ----

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = runCatching {
                    tts?.language = Locale("es", "ES")
                    true
                }.getOrDefault(false)
            } else {
                Log.w("DjVoice", "TTS no disponible ($status): el DJ se queda en texto")
            }
        }
    }

    private fun speak(line: String) {
        val engine = tts ?: run { ensureTts(); return }
        if (!ttsReady) return
        runCatching {
            // QUEUE_FLUSH: si empalma otro bloque, la locución nueva manda.
            engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, "dj-${line.hashCode()}")
        }.onFailure { Log.w("DjVoice", "no se pudo hablar", it) }
    }

    fun release() {
        runCatching { tts?.shutdown() }
        tts = null; ttsReady = false
    }
}
