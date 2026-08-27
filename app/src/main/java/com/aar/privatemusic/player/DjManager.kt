package com.aar.privatemusic.player

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.aar.privatemusic.data.AppSettings
import com.aar.privatemusic.data.MusicRepository
import com.aar.privatemusic.data.MixPromptParser
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.dj.DjEngine
import com.aar.privatemusic.dj.DjNarrator
import com.aar.privatemusic.util.Feedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private var buildJob: Job? = null
    private var requestJob: Job? = null
    private var observeJob: Job? = null
    @Volatile private var lifecycleGeneration = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var ttsGeneration = 0L

    /** Arranca una nueva sesión de DJ. [seed] la hace repetible; 0 = del día/hora. */
    fun start(seed: Long) {
        buildJob?.cancel()
        requestJob?.cancel()
        observeJob?.cancel()
        lifecycleGeneration++
        val generation = lifecycleGeneration
        session = null
        pendingSpeech = null
        runCatching { tts?.stop() }
        _state.value = State(active = true, building = true)
        buildJob = scope.launch {
            try {
                val now = System.currentTimeMillis()
                val effectiveSeed = if (seed == 0L) now / 3_600_000L else seed
                val songs = dao.songsOnce().filter { it.snoozedUntil < now }
                currentCoroutineContext().ensureActive()
                val counts = dao.playCountsOnce().associate { it.songId to it.plays }
                currentCoroutineContext().ensureActive()
                val played = dao.lastPlayedOnce().associate { it.songId to it.lastPlayed }
                val favs = songs.filter { it.isFavorite }.map { it.id }.toSet()
                val newSession = DjEngine.buildSession(songs, counts, favs, seed = effectiveSeed)
                currentCoroutineContext().ensureActive()

                if (newSession.tracks.size < 2) {
                    withContext(Dispatchers.Main) {
                        currentCoroutineContext().ensureActive()
                        if (generation != lifecycleGeneration) return@withContext
                        session = null
                        _state.value = State(active = false)
                        Feedback.show("Se necesitan al menos 2 canciones analizadas para iniciar el DJ")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    currentCoroutineContext().ensureActive()
                    if (generation != lifecycleGeneration) return@withContext
                    playCounts = counts
                    lastPlayed = played
                    session = newSession
                    narrator = DjNarrator(seed = effectiveSeed)
                    lastAnnouncedIndex = -1
                    if (settings.djVoice.value) ensureTts()
                    player.playQueueInOrder(newSession.tracks)
                    currentCoroutineContext().ensureActive()
                    if (generation != lifecycleGeneration || session !== newSession) return@withContext
                    _state.value = State(active = true, building = false)
                    observeBlocks(generation)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "No se pudo construir la sesión del DJ", error)
                withContext(NonCancellable + Dispatchers.Main) {
                    if (generation != lifecycleGeneration) return@withContext
                    session = null
                    _state.value = State(active = false, building = false)
                    Feedback.show("No se pudo iniciar el DJ")
                }
            }
        }
    }

    fun stop() {
        lifecycleGeneration++
        buildJob?.cancel()
        requestJob?.cancel()
        observeJob?.cancel()
        session = null
        pendingSpeech = null
        runCatching { tts?.stop() }
        _state.value = State(active = false)
    }

    /** El usuario pide algo ("más animado"): re-secuencia el resto de la cola. */
    fun request(text: String) {
        val s = session ?: return
        requestJob?.cancel()
        val generation = lifecycleGeneration
        requestJob = scope.launch {
            try {
                val parsed = MixPromptParser.parse(text)
                val mix = repository.buildCustomMix(parsed, size = 20)
                currentCoroutineContext().ensureActive()
                if (generation != lifecycleGeneration || session !== s) return@launch
                if (mix.songs.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        currentCoroutineContext().ensureActive()
                        if (generation == lifecycleGeneration && session === s) {
                            Feedback.show("No encontré nada así en tu biblioteca")
                        }
                    }
                    return@launch
                }

                val idx = withContext(Dispatchers.Main) { player.currentIndex.value }
                currentCoroutineContext().ensureActive()
                if (generation != lifecycleGeneration || session !== s || idx !in s.tracks.indices) return@launch

                val playedIds = s.tracks.take(idx + 1).mapTo(HashSet()) { it.id }
                val candidates = mix.songs
                    .asSequence()
                    .filter { it.id !in playedIds }
                    .distinctBy { it.id }
                    .toList()
                val rest = DjEngine.sonicOrder(
                    candidates,
                    maxStretch = AppSettings.readAutoMixMaxStretch(context),
                )
                currentCoroutineContext().ensureActive()
                if (rest.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        currentCoroutineContext().ensureActive()
                        if (generation == lifecycleGeneration && session === s) {
                            Feedback.show("No quedan canciones nuevas para añadir a la cola")
                        }
                    }
                    return@launch
                }

                val replacement = s.replaceFuture(idx, rest, DjEngine.BlockKind.BUILD, 0.6f)
                withContext(Dispatchers.Main) {
                    currentCoroutineContext().ensureActive()
                    if (
                        generation != lifecycleGeneration ||
                        session !== s ||
                        idx !in s.tracks.indices ||
                        player.currentIndex.value != idx
                    ) return@withContext
                    val line = narrator.narrateRequest(parsed.summary)
                    player.replaceUpcoming(rest)
                    session = replacement
                    lastAnnouncedIndex = idx
                    announce(line, DjEngine.BlockKind.BUILD)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "No se pudo ajustar la sesión del DJ", error)
                withContext(NonCancellable + Dispatchers.Main) {
                    if (generation == lifecycleGeneration && session === s) {
                        Feedback.show("No se pudo ajustar la sesión del DJ")
                    }
                }
            }
        }
    }

    private fun observeBlocks(generation: Long) {
        observeJob?.cancel()
        observeJob = scope.launch {
            player.currentIndex.collect { index ->
                currentCoroutineContext().ensureActive()
                if (generation != lifecycleGeneration) return@collect
                val s = session ?: return@collect
                if (!s.isBlockStart(index) || index == lastAnnouncedIndex) return@collect
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
                withContext(Dispatchers.Main) {
                    currentCoroutineContext().ensureActive()
                    if (
                        generation != lifecycleGeneration ||
                        session !== s ||
                        !_state.value.active ||
                        index == lastAnnouncedIndex
                    ) return@withContext
                    val line = narrator.narrate(cue)
                    lastAnnouncedIndex = index
                    announce(line, block.kind)
                }
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
        ttsReady = false
        val generation = ++ttsGeneration
        try {
            tts = TextToSpeech(context) { status ->
                if (generation != ttsGeneration) return@TextToSpeech
                if (status != TextToSpeech.SUCCESS) {
                    ttsReady = false
                    pendingSpeech = null
                    Log.w(TAG_VOICE, "TTS no disponible ($status): el DJ se queda en texto")
                    return@TextToSpeech
                }

                val languageResult = runCatching {
                    tts?.setLanguage(Locale.forLanguageTag("es-ES"))
                }.onFailure {
                    Log.w(TAG_VOICE, "No se pudo configurar el idioma del TTS", it)
                }.getOrNull()
                ttsReady = languageResult != null &&
                    languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED

                if (!ttsReady) {
                    pendingSpeech = null
                    Log.w(TAG_VOICE, "El TTS no admite español de España ($languageResult)")
                    return@TextToSpeech
                }

                val pending = pendingSpeech
                pendingSpeech = null
                if (pending != null && _state.value.active && settings.djVoice.value) {
                    speak(pending)
                }
            }
        } catch (error: Throwable) {
            tts = null
            ttsReady = false
            pendingSpeech = null
            Log.w(TAG_VOICE, "No se pudo inicializar el TTS", error)
        }
    }

    private fun speak(line: String) {
        pendingSpeech = line
        val engine = tts ?: run {
            ensureTts()
            return
        }
        if (!ttsReady) return
        pendingSpeech = null
        val result = runCatching {
            // QUEUE_FLUSH: si empalma otro bloque, la locución nueva manda.
            engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, "dj-${line.hashCode()}")
        }.onFailure {
            pendingSpeech = null
            Log.w(TAG_VOICE, "No se pudo hablar", it)
        }.getOrNull()
        if (result == TextToSpeech.ERROR) {
            pendingSpeech = null
            Log.w(TAG_VOICE, "El TTS rechazó la locución")
        }
    }

    fun release() {
        stop()
        ttsGeneration++
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        pendingSpeech = null
    }

    private companion object {
        const val TAG = "DjManager"
        const val TAG_VOICE = "DjVoice"
    }
}
