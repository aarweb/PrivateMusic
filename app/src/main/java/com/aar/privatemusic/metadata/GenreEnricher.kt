package com.aar.privatemusic.metadata

import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GenreEnrichmentState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val genresFound: Int = 0,
    val failed: Int = 0,
)

/**
 * Completa los géneros que faltan sin paralelizar peticiones a MusicBrainz.
 * No arranca por sí solo: la biblioteca antigua sólo se recorre cuando la UI
 * llama a [startMissing]. Las descargas nuevas usan [enrichSong].
 */
class GenreEnricher(
    private val dao: MusicDao,
    private val creditsService: MusicCreditsService,
    private val appScope: CoroutineScope,
) {
    private val enrichmentMutex = Mutex()
    private val _state = MutableStateFlow(GenreEnrichmentState())
    val state: StateFlow<GenreEnrichmentState> = _state.asStateFlow()

    @Volatile
    private var missingJob: Job? = null

    @Synchronized
    fun startMissing() {
        if (missingJob?.isActive == true) return
        missingJob = appScope.launch(Dispatchers.IO) {
            enrichmentMutex.withLock {
                val songs = dao.songsMissingGenres()
                _state.value = GenreEnrichmentState(running = true, total = songs.size)
                try {
                    songs.forEach { song ->
                        val result = resolve(song)
                        val previous = _state.value
                        _state.value = previous.copy(
                            done = previous.done + 1,
                            genresFound = previous.genresFound +
                                if (result.getOrNull()?.credits?.genres?.isNotEmpty() == true) 1 else 0,
                            failed = previous.failed + if (result.isFailure) 1 else 0,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    _state.value = _state.value.copy(running = false)
                }
            }
        }
    }

    /** Resuelve una descarga nueva; se serializa con el barrido manual. */
    suspend fun enrichSong(songId: String): Boolean = withContext(Dispatchers.IO) {
        enrichmentMutex.withLock {
            val song = dao.getSong(songId) ?: return@withLock false
            // No se omite aunque otra pasada se adelantara: tras autoIdentify la
            // clave de caché puede haber cambiado y debe prevalecer la fila canónica.
            resolve(song).isSuccess
        }
    }

    private suspend fun resolve(song: Song): Result<CreditsLoad> = creditsService.load(song)
}
