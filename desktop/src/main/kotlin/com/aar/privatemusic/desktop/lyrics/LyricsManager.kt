package com.aar.privatemusic.desktop.lyrics

import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.lyrics.Lyrics
import com.aar.privatemusic.lyrics.LyricsFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import java.io.File

/** Lo que la pestaña de letra puede estar enseñando. */
sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
    data object Missing : LyricsState
}

/**
 * Trae la letra de lo que suena y la deja en un `StateFlow`.
 *
 * El trabajo cuelga del ámbito de la aplicación, no de la composición: buscar en
 * LRCLIB puede tardar sus segundos y cerrar el panel a media búsqueda no debe
 * cancelarla. Cambiar de canción sí la cancela, y de eso se encarga
 * `transformLatest`.
 *
 * La letra se cachea junto al audio (`<id>.lrc` o `<id>.txt`), exactamente igual
 * que en el móvil: si algún día se sincronizan los ficheros, la letra viaja con
 * la canción.
 */
class LyricsManager(dir: File, currentSong: Flow<Song?>, scope: CoroutineScope) {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LyricsState> = currentSong
        // Marcar una canción como favorita crea un objeto nuevo con el mismo id.
        // Eso no es cambiar de canción y no debe volver a pedir la letra.
        .distinctUntilChangedBy { it?.id }
        .transformLatest { song ->
            if (song == null) {
                emit(LyricsState.Idle)
                return@transformLatest
            }
            emit(LyricsState.Loading)
            val lyrics = runCatching { LyricsFetcher.getOrFetch(song, dir) }.getOrNull()
            emit(lyrics?.let(LyricsState::Found) ?: LyricsState.Missing)
        }
        .stateIn(scope, SharingStarted.Eagerly, LyricsState.Idle)
}
