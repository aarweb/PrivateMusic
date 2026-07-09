package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.desktop.DesktopSettings
import com.aar.privatemusic.desktop.downloader.YtDlpDownloader
import com.aar.privatemusic.desktop.player.DesktopPlayer
import com.aar.privatemusic.downloader.DeezerDownloader
import com.aar.privatemusic.downloader.DeezerSource
import com.aar.privatemusic.downloader.DeezerTrack
import com.aar.privatemusic.downloader.DownloadState
import com.aar.privatemusic.downloader.InternetArchiveDownloader
import com.aar.privatemusic.downloader.InternetArchiveSource
import com.aar.privatemusic.downloader.SearchResult
import com.aar.privatemusic.downloader.YouTubeMusicSource
import kotlinx.coroutines.launch

/** Calidad que YouTube sirve para el mejor audio: Opus a ~160 kbps. */
private const val YT_QUALITY = "Opus ~160k"

private data class SearchSource(val id: String, val label: String, val color: Color)

private val SOURCES = listOf(
    SearchSource("ytmusic", "YouTube Music", Color(0xFFFF0000)),
    SearchSource("yt", "YouTube", Color(0xFF606060)),
    SearchSource("deezer", "Deezer", Color(0xFFA238FF)),
    SearchSource("deezerhq", "Deezer HQ", Color(0xFFFF6B00)),
    SearchSource("archive", "Internet Archive · FLAC gratis", Color(0xFF2C7BB6)),
)

private fun deezerQualityLabel(quality: String): String = when (quality) {
    "FLAC" -> "FLAC"
    "MP3_320" -> "MP3 320k"
    else -> "MP3 128k"
}

private fun isPlaylistUrl(query: String): Boolean =
    query.startsWith("http") && ("list=" in query || "/playlist" in query || "/channel/" in query)

/**
 * La misma búsqueda que el móvil: se elige la fuente en una rejilla, se busca, y
 * cada resultado se puede preescuchar o descargar. Las fuentes viven en `:core`
 * (son HTTP y JSON puros), así que aquí sólo está la interfaz.
 *
 * Los torrents no están: el motor (libtorrent) sólo se compila para Android.
 */
@Composable
fun SearchScreen(
    yt: YtDlpDownloader,
    deezer: DeezerDownloader,
    archive: InternetArchiveDownloader,
    settings: DesktopSettings,
    player: DesktopPlayer,
    songs: List<Song>,
    onMessage: (String) -> Unit,
) {
    var source by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var deezerTracks by remember { mutableStateOf<List<DeezerTrack>?>(null) }
    // Deezer normal no se puede descargar: se empareja en YouTube. Este mapa
    // recuerda con qué vídeo, para saber si la pista ya está en la biblioteca.
    val deezerMatches = remember { mutableStateMapOf<String, String>() }
    var resolving by remember { mutableStateOf(setOf<String>()) }
    var previewLoading by remember { mutableStateOf<String?>(null) }

    val ytDownloads by yt.downloads.collectAsState()
    val deezerDownloads by deezer.downloads.collectAsState()
    val archiveDownloads by archive.downloads.collectAsState()
    val preparing by yt.preparing.collectAsState()
    val deezerQuality by settings.deezerQuality.collectAsState()
    val preview by player.preview.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val libraryIds = remember(songs) { songs.map { it.id }.toSet() }
    val scope = rememberCoroutineScope()

    fun runSearch() {
        val q = query.trim()
        if (q.isBlank() || searching) return
        searching = true
        error = null
        scope.launch {
            results = emptyList()
            deezerTracks = null
            when {
                source == "archive" -> runCatching { InternetArchiveSource.search(q, limit = 30) }
                    .onSuccess {
                        results = it
                        if (it.isEmpty()) error = "Sin resultados en Internet Archive"
                    }
                    .onFailure { error = "Error al buscar en Internet Archive: ${it.message}" }

                source == "deezer" || source == "deezerhq" -> {
                    if (source == "deezerhq" && settings.deezerArl.value.isBlank()) {
                        error = "Inicia sesión en Deezer en Ajustes para descargar en HQ"
                    } else {
                        runCatching { DeezerSource.search(q) }
                            .onSuccess { deezerTracks = it }
                            .onFailure { error = "Error al buscar en Deezer: ${it.message}" }
                    }
                }

                isPlaylistUrl(q) -> runCatching { yt.resolvePlaylist(q) }
                    .onSuccess { (_, entries) -> results = entries }
                    .onFailure { error = "Error al resolver la playlist: ${it.message}" }

                source == "ytmusic" -> runCatching { YouTubeMusicSource.search(q) }
                    .onSuccess { results = it }
                    .onFailure { error = "Error al buscar en YouTube Music: ${it.message}" }

                else -> runCatching { yt.search(q) }
                    .onSuccess { results = it }
                    .onFailure { error = "Error al buscar: ${it.message}" }
            }
            if (error == null && results.isEmpty() && deezerTracks.isNullOrEmpty() && source != "deezerhq") {
                error = "Sin resultados"
            }
            searching = false
        }
    }

    /** Preescucha: pide la URL (puede tardar) y se la pasa al reproductor. */
    fun startPreview(id: String, title: String, artist: String, coverUrl: String, url: suspend () -> String?) {
        if (preview?.id == id) {
            player.togglePlayPause()
            return
        }
        previewLoading = id
        scope.launch {
            val resolved = runCatching { url() }.getOrNull()
            previewLoading = null
            if (resolved.isNullOrBlank()) {
                onMessage("Esta pista no tiene preescucha")
                return@launch
            }
            player.playPreview(DesktopPlayer.Preview(id, title, artist, coverUrl), resolved)
        }
    }

    if (source == null) {
        SourceGrid(onPick = { source = it; query = ""; results = emptyList(); deezerTracks = null; error = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ source = null }) { Icon(Icons.Filled.ArrowBack, "Volver") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                singleLine = true,
                placeholder = {
                    Text(
                        when (source) {
                            "archive" -> "Buscar en Internet Archive…"
                            "deezerhq" -> "Buscar en Deezer (descarga HQ)…"
                            "deezer" -> "Buscar en Deezer…"
                            "ytmusic" -> "Buscar en YouTube Music…"
                            else -> "Buscar en YouTube, o pega una URL de playlist…"
                        }
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Limpiar") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            )
        }

        if (preparing) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    "Instalando yt-dlp (sólo la primera vez)…",
                    Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        when {
            searching -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

            error != null -> EmptyState("Sin resultados", error!!)

            results.isEmpty() && deezerTracks.isNullOrEmpty() ->
                EmptyState("Busca música", "Escribe y pulsa Intro.")

            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                deezerTracks?.let { tracks ->
                    items(tracks, key = { "dz-${it.id}" }) { track ->
                        val dzId = "dz${track.id}"
                        val isHq = source == "deezerhq"
                        val hqKey = deezer.stateKey(track.id)
                        val matchedId = deezerMatches[dzId]
                        ResultRow(
                            title = track.title,
                            artist = if (track.album.isBlank()) track.artist else "${track.artist} · ${track.album}",
                            durationSec = track.durationSec,
                            coverUrl = track.coverUrl,
                            qualityLabel = if (isHq) deezerQualityLabel(deezerQuality) else YT_QUALITY,
                            inLibrary = if (isHq) hqKey in libraryIds else matchedId != null && matchedId in libraryIds,
                            state = if (isHq) deezerDownloads[hqKey] else when {
                                dzId in resolving -> DownloadState.Queued
                                else -> matchedId?.let { ytDownloads[it] }
                            },
                            previewLoading = previewLoading == dzId,
                            previewPlaying = preview?.id == dzId && isPlaying,
                            onPreview = {
                                startPreview(dzId, track.title, track.artist, track.coverUrl) {
                                    track.previewUrl.takeIf { it.isNotBlank() }
                                }
                            },
                            onDownload = {
                                if (isHq) {
                                    deezer.enqueue(track, deezerQuality)
                                    onMessage("Descargando \"${track.title}\" de Deezer…")
                                } else if (dzId !in resolving) {
                                    // El audio de Deezer lleva DRM: se busca la misma
                                    // pista en YouTube y se conservan los metadatos limpios.
                                    resolving = resolving + dzId
                                    scope.launch {
                                        val match = yt.searchBestMatch(track.searchQuery, track.durationSec)
                                        resolving = resolving - dzId
                                        if (match == null) {
                                            onMessage("No se encontró \"${track.title}\" en YouTube")
                                        } else {
                                            deezerMatches[dzId] = match.id
                                            yt.enqueue(match)
                                        }
                                    }
                                }
                            },
                            onCancel = {
                                if (isHq) Unit else deezerMatches[dzId]?.let(yt::cancel)
                            },
                        )
                    }
                }
                items(results, key = { it.id }) { result ->
                    val isArchive = result.isArchive
                    val state = if (isArchive) archiveDownloads[result.id] else ytDownloads[result.id]
                    ResultRow(
                        title = result.title,
                        artist = result.artist,
                        durationSec = result.durationSec,
                        coverUrl = result.thumbnailUrl,
                        qualityLabel = result.qualityLabel ?: YT_QUALITY,
                        inLibrary = result.id in libraryIds,
                        state = state,
                        previewLoading = previewLoading == result.id,
                        previewPlaying = preview?.id == result.id && isPlaying,
                        onPreview = {
                            startPreview(result.id, result.title, result.artist, result.thumbnailUrl) {
                                if (isArchive) archive.previewUrl(result.id) else yt.streamUrl(result.id)
                            }
                        },
                        onDownload = {
                            if (isArchive) {
                                archive.enqueue(result)
                                onMessage("Descargando \"${result.title}\" de Internet Archive…")
                            } else {
                                yt.enqueue(result)
                            }
                        },
                        onCancel = { if (isArchive) archive.cancel(result.id) else yt.cancel(result.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceGrid(onPick: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(260.dp),
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(SOURCES, key = { it.id }) { source ->
            Card(onClick = { onPick(source.id) }, modifier = Modifier.height(96.dp)) {
                Row(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(source.color),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(iconFor(source.id), null, Modifier.size(22.dp), tint = Color.White)
                    }
                    Text(
                        source.label,
                        Modifier.padding(start = 16.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}

private fun iconFor(sourceId: String) = when (sourceId) {
    "ytmusic" -> Icons.Filled.MusicNote
    "yt" -> Icons.Filled.PlayArrow
    "deezer" -> Icons.Filled.GraphicEq
    else -> Icons.Filled.Download
}

@Composable
private fun ResultRow(
    title: String,
    artist: String,
    durationSec: Int,
    coverUrl: String,
    qualityLabel: String,
    inLibrary: Boolean,
    state: DownloadState?,
    previewLoading: Boolean,
    previewPlaying: Boolean,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            RemoteCover(coverUrl, 48.dp)
            // Un icono blanco sobre una carátula clara no se ve. El velo lo salva
            // sin tapar la portada, que es lo que identifica el resultado.
            Box(
                Modifier.size(48.dp).clip(MaterialTheme.shapes.small)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(onClick = onPreview),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    previewLoading -> CircularProgressIndicator(
                        Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White,
                    )
                    previewPlaying -> Icon(Icons.Filled.Pause, "Pausar preescucha", tint = Color.White)
                    else -> Icon(Icons.Filled.PlayArrow, "Preescuchar", tint = Color.White)
                }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (durationSec > 0) {
            Text(
                formatDuration(durationSec * 1000L),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AssistChip({}, { Text(qualityLabel, style = MaterialTheme.typography.labelSmall) }, Modifier.padding(start = 8.dp))
        Spacer(Modifier.width(4.dp))
        DownloadButton(inLibrary, state, onDownload, onCancel)
    }
}

@Composable
private fun DownloadButton(
    inLibrary: Boolean,
    state: DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    when {
        inLibrary || state is DownloadState.Done -> Icon(
            Icons.Filled.Check,
            "Ya en la biblioteca",
            Modifier.size(40.dp).padding(8.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        state is DownloadState.Downloading -> IconButton(onCancel) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                )
                Icon(Icons.Filled.Close, "Cancelar", Modifier.size(14.dp))
            }
        }

        state is DownloadState.Queued -> IconButton(onCancel) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                Icon(Icons.Filled.Close, "Cancelar", Modifier.size(14.dp))
            }
        }

        state is DownloadState.Failed -> IconButton(onDownload) {
            Icon(Icons.Filled.Download, "Reintentar: ${state.message}", tint = MaterialTheme.colorScheme.error)
        }

        else -> IconButton(onDownload) { Icon(Icons.Filled.Download, "Descargar") }
    }
}
