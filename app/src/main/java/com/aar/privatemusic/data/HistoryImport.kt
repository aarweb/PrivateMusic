package com.aar.privatemusic.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.PlayEvent
import com.aar.privatemusic.stats.HistoryImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Importa el historial de Spotify / Last.fm / YouTube a `play_history`. Corre en
 * el scope de la app: salir de Ajustes no lo cancela, y el resultado espera en
 * [outcome] hasta que la UI lo recoja.
 */
object HistoryImport {
    private const val TAG = "HistoryImport"

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress

    private val _outcome = MutableStateFlow<String?>(null)
    val outcome: StateFlow<String?> = _outcome

    private val running = AtomicBoolean(false)

    fun clearOutcome() { _outcome.value = null }

    fun start(context: Context, uri: Uri, dao: MusicDao, scope: CoroutineScope) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            _progress.value = "Leyendo el archivo…"
            val result = runCatching { import(app, uri, dao) }
                .onFailure { Log.e(TAG, "import failed", it) }
                .getOrElse { "Error al importar: ${it.message ?: it.javaClass.simpleName}" }
            _progress.value = null
            _outcome.value = result
            running.set(false)
        }
    }

    private suspend fun import(context: Context, uri: Uri, dao: MusicDao): String {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: return "No se pudo abrir el archivo"
        val parsed = HistoryImporter.parse(text)
            ?: return "No parece un historial de Spotify, Last.fm ni YouTube"
        if (parsed.plays.isEmpty()) return "El archivo no contiene escuchas"

        _progress.value = "Emparejando ${parsed.plays.size} escuchas con tu biblioteca…"
        val songs = dao.songsOnce()
        val existing = dao.playEventKeys().toHashSet()
        val matched = HistoryImporter.match(parsed.plays, songs, existing)
        if (matched.plays.isEmpty()) {
            return "Ninguna de las ${parsed.plays.size} escuchas casa con tu biblioteca" +
                (if (matched.skippedShort > 0) " (${matched.skippedShort} de menos de 30 s descartadas)" else "")
        }

        _progress.value = "Guardando ${matched.plays.size} escuchas…"
        matched.plays.chunked(500).forEach { chunk ->
            dao.insertPlayEvents(chunk.map { (id, at) -> PlayEvent(songId = id, playedAt = at) })
        }
        val source = when (parsed.format) {
            HistoryImporter.Format.SPOTIFY -> "Spotify"
            HistoryImporter.Format.LASTFM -> "Last.fm"
            HistoryImporter.Format.YOUTUBE -> "YouTube"
        }
        Log.i(TAG, "$source: ${matched.plays.size} escuchas, ${matched.songCount} canciones, ${matched.unmatched} sin casar")
        return "$source: ${"%,d".format(matched.plays.size)} reproducciones importadas para " +
            "${matched.songCount} canciones; ${"%,d".format(matched.unmatched)} sin coincidencia" +
            (if (matched.skippedShort > 0) ", ${"%,d".format(matched.skippedShort)} de menos de 30 s" else "")
    }
}
