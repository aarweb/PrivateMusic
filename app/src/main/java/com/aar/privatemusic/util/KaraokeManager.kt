package com.aar.privatemusic.util

import android.content.Context
import com.aar.privatemusic.data.db.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns karaoke separations in an app-level scope so they survive dialog
 * dismissal, navigation and rotation, and so the same song is never
 * separated twice concurrently. UI observes [stateFor] and calls [start]/[cancel].
 */
object KaraokeManager {

    data class State(
        val status: String = "",
        val progress: Int = 0,
        val file: File? = null,
        val failed: Boolean = false,
        val running: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()
    private val states = mutableMapOf<String, MutableStateFlow<State>>()

    fun stateFor(songId: String): StateFlow<State> = flow(songId)

    private fun flow(songId: String): MutableStateFlow<State> =
        synchronized(states) { states.getOrPut(songId) { MutableStateFlow(State()) } }

    /** Starts (or joins) the model download + separation for [song]. */
    fun start(context: Context, song: Song, musicDir: File) {
        val appContext = context.applicationContext
        synchronized(states) {
            if (jobs[song.id]?.isActive == true) return
            val state = flow(song.id)
            state.value = State(status = "Preparando…", running = true)
            jobs[song.id] = scope.launch {
                try {
                    if (!KaraokeSeparator.isModelReady(appContext)) {
                        state.value = State(status = "Descargando modelo de IA (36 MB)…", running = true)
                        val ok = KaraokeSeparator.downloadModel(appContext) {
                            state.value = state.value.copy(progress = it)
                        }
                        if (!ok) {
                            state.value = State(
                                status = "No se pudo descargar el modelo. Comprueba tu conexión.",
                                failed = true,
                            )
                            return@launch
                        }
                    }
                    state.value = State(
                        status = "Separando la voz… (puede tardar un par de minutos)",
                        running = true,
                    )
                    val file = KaraokeSeparator.separate(appContext, song, musicDir) {
                        state.value = state.value.copy(progress = it)
                    }
                    state.value = if (file != null) {
                        State(file = file, progress = 100)
                    } else {
                        State(status = separationErrorFor(song), failed = true)
                    }
                } finally {
                    synchronized(states) { jobs.remove(song.id) }
                }
            }
        }
    }

    fun cancel(songId: String) {
        synchronized(states) {
            jobs.remove(songId)?.cancel()
            states[songId]?.value = State()
        }
    }

    private fun separationErrorFor(song: Song): String =
        if (song.durationSec > KaraokeSeparator.MAX_DURATION_SEC)
            "La canción es demasiado larga para el karaoke (máximo 15 minutos)."
        else "No se pudo separar la voz de esta canción."
}
