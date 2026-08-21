package com.aar.privatemusic.util

import android.content.Context
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.lyrics.Lyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Sincronizar una letra tarda minutos (hay que separar la voz), así que vive en
 * el scope de la aplicación: cerrar el reproductor, girar el móvil o irse a
 * otra pantalla no la corta, y la misma canción nunca se procesa dos veces a la
 * vez. La pantalla observa [stateFor] y llama a [start].
 *
 * Es el mismo patrón que [KaraokeManager], y de hecho se apoya en él: lo que
 * hace falta es justo el instrumental del karaoke, porque la voz es la
 * diferencia entre el original y ese instrumental. Si ya está separado (porque
 * el usuario cantó esa canción antes), esto tarda segundos.
 */
object LyricsSyncManager {

    data class State(
        val status: String = "",
        val progress: Int = 0,
        val lyrics: Lyrics? = null,
        val failed: Boolean = false,
        val running: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()
    private val states = mutableMapOf<String, MutableStateFlow<State>>()

    fun stateFor(songId: String): StateFlow<State> = flow(songId)

    private fun flow(songId: String): MutableStateFlow<State> =
        synchronized(states) { states.getOrPut(songId) { MutableStateFlow(State()) } }

    /** Olvida el resultado para que la pantalla no lo reaplique al volver. */
    fun clear(songId: String) {
        synchronized(states) { states[songId]?.value = State() }
    }

    fun cancel(songId: String) {
        synchronized(states) {
            jobs.remove(songId)?.cancel()
            states[songId]?.value = State()
        }
    }

    /**
     * Sincroniza la letra de [song] a partir de sus versos [plainLines].
     * Separa la voz si hace falta (descargando el modelo del karaoke la primera
     * vez) y deja el resultado en [State.lyrics].
     */
    fun start(context: Context, song: Song, musicDir: File, plainLines: List<String>) {
        val appContext = context.applicationContext
        synchronized(states) {
            if (jobs[song.id]?.isActive == true) return
            val state = flow(song.id)
            state.value = State(status = "Preparando…", running = true)
            jobs[song.id] = scope.launch {
                try {
                    if (song.durationSec > LyricsSync.MAX_DURATION_SEC) {
                        state.value = State(
                            status = "La canción es demasiado larga para sincronizarla (máximo 15 minutos).",
                            failed = true,
                        )
                        return@launch
                    }
                    val instrumental = ensureInstrumental(appContext, song, musicDir, state)
                        ?: return@launch

                    state.value = State(status = "Cuadrando la letra con la voz…", progress = 90, running = true)
                    val lyrics = LyricsSync.generate(song, instrumental, plainLines, musicDir)
                    state.value = if (lyrics != null) {
                        State(lyrics = lyrics, progress = 100)
                    } else {
                        State(
                            status = "No se ha encontrado voz suficiente para cuadrar la letra.",
                            failed = true,
                        )
                    }
                } finally {
                    synchronized(states) { jobs.remove(song.id) }
                }
            }
        }
    }

    /** El instrumental del karaoke: si ya estaba, se reutiliza tal cual. */
    private suspend fun ensureInstrumental(
        context: Context,
        song: Song,
        musicDir: File,
        state: MutableStateFlow<State>,
    ): File? {
        val cached = KaraokeSeparator.instrumentalFileFor(context, musicDir, song.id)
        if (cached.length() > 1000) return cached

        if (!KaraokeSeparator.isModelReady(context)) {
            val size = if (KaraokeSeparator.engine(context) == "mdx") "67 MB" else "36 MB"
            state.value = State(status = "Descargando modelo de IA ($size)…", running = true)
            val ok = KaraokeSeparator.downloadModel(context) {
                state.value = state.value.copy(progress = it)
            }
            if (!ok) {
                state.value = State(
                    status = "No se pudo descargar el modelo. Comprueba tu conexión.",
                    failed = true,
                )
                return null
            }
        }
        state.value = State(status = "Separando la voz… (puede tardar unos minutos)", running = true)
        val file = KaraokeSeparator.separate(context, song, musicDir) {
            // La separación es el 85% del trabajo; el resto es cuadrar la letra.
            state.value = state.value.copy(progress = it * 85 / 100)
        }
        if (file == null) {
            state.value = State(status = "No se pudo separar la voz de esta canción.", failed = true)
        }
        return file
    }
}
