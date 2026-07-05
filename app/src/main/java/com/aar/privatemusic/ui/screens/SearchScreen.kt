package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.downloader.DownloadState
import com.aar.privatemusic.downloader.SearchResult
import com.aar.privatemusic.downloader.SpotifyResolver
import com.aar.privatemusic.downloader.SpotifySync
import com.aar.privatemusic.downloader.SpotifyTrack
import com.aar.privatemusic.ui.components.ArtImage
import com.aar.privatemusic.ui.components.formatDuration
import kotlinx.coroutines.launch

/** Last search kept across navigation so switching tabs doesn't wipe results. */
private object SearchCache {
    var query = ""
    var source: String? = null
    var results: List<SearchResult> = emptyList()
    var playlistTitle: String? = null
    var playlistUrl: String? = null
    var spotifyTracks: List<SpotifyTrack>? = null
    var deezerTracks: List<com.aar.privatemusic.downloader.DeezerTrack>? = null

    /** Deezer track id -> matched YouTube id (so rows can show download state). */
    val deezerMatches = mutableMapOf<String, String>()

    fun clearResults() {
        query = ""
        results = emptyList()
        playlistTitle = null
        playlistUrl = null
        spotifyTracks = null
        deezerTracks = null
    }
}

@Composable
fun SearchScreen(app: PrivateMusicApp) {
    var query by remember { mutableStateOf(SearchCache.query) }
    var results by remember { mutableStateOf(SearchCache.results) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Non-null when the current results come from a playlist/channel URL.
    var playlistTitle by remember { mutableStateOf(SearchCache.playlistTitle) }
    var playlistUrl by remember { mutableStateOf(SearchCache.playlistUrl) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    // Non-null when the URL was a Spotify playlist/album/track.
    var spotifyTracks by remember { mutableStateOf(SearchCache.spotifyTracks) }
    // Selected source ("yt", "deezer"...); null shows the sources grid.
    var source by remember { mutableStateOf(SearchCache.source) }
    var deezerTracks by remember { mutableStateOf(SearchCache.deezerTracks) }
    // Deezer tracks currently being matched on YouTube before download.
    var deezerResolving by remember { mutableStateOf(setOf<String>()) }
    androidx.compose.runtime.SideEffect {
        SearchCache.query = query
        SearchCache.source = source
        SearchCache.results = results
        SearchCache.playlistTitle = playlistTitle
        SearchCache.playlistUrl = playlistUrl
        SearchCache.spotifyTracks = spotifyTracks
        SearchCache.deezerTracks = deezerTracks
    }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    val downloads by app.downloader.downloads.collectAsState()
    val torrentDownloads by app.torrents.downloads.collectAsState()
    val archiveDownloads by app.archive.downloads.collectAsState()
    val deezerDownloads by app.deezerDownloader.downloads.collectAsState()
    val libraryIds by app.repository.observeSongIds().collectAsState(initial = emptyList())
    val nowPlaying by app.playerController.nowPlaying.collectAsState()
    val isPlaying by app.playerController.isPlaying.collectAsState()
    var previewLoadingId by remember { mutableStateOf<String?>(null) }

    fun togglePreview(result: SearchResult) {
        // If this row's preview is already loaded (playing, paused or buffering),
        // toggle it instead of re-extracting the stream URL from scratch.
        if (nowPlaying?.songId == "preview:${result.id}") {
            app.playerController.togglePlayPause()
            return
        }
        if (previewLoadingId != null) return
        previewLoadingId = result.id
        scope.launch {
            val url = app.downloader.streamUrl(result.id)
            if (url != null) {
                app.playerController.playStream(result.id, result.title, result.artist, url, result.thumbnailUrl)
            } else {
                error = null
                actionMessage = "No se pudo obtener el stream de \"${result.title}\""
            }
            previewLoadingId = null
        }
    }

    fun isPlaylistUrl(q: String): Boolean =
        q.contains("list=") || q.contains("/playlist") ||
            q.contains("youtube.com/@") || q.contains("/channel/")

    fun runSearch() {
        if (query.isBlank() || searching) return
        keyboard?.hide()
        searching = true
        error = null
        playlistTitle = null
        playlistUrl = null
        actionMessage = null
        spotifyTracks = null
        deezerTracks = null
        scope.launch {
            if (source == "1337x") {
                // El scraper ya devuelve lista vacía si falla la red o el parseo.
                val torrents =
                    com.aar.privatemusic.downloader.Torrent1337xSource.search(query.trim(), limit = 30)
                results = torrents
                if (torrents.isEmpty()) error = "Sin resultados de torrents de música"
                searching = false
                return@launch
            }
            if (source == "archive") {
                runCatching {
                    com.aar.privatemusic.downloader.InternetArchiveSource.search(query.trim(), limit = 30)
                }
                    .onSuccess {
                        results = it
                        if (it.isEmpty()) error = "Sin resultados en Internet Archive"
                    }
                    .onFailure { error = "Error al buscar en Internet Archive: ${it.message}" }
                searching = false
                return@launch
            }
            if ((source == "deezer" || source == "deezerhq") && !query.trim().startsWith("http") &&
                !SpotifyResolver.isSpotifyUrl(query.trim())
            ) {
                if (source == "deezerhq" && app.settings.deezerArl.value.isBlank()) {
                    error = "Inicia sesión en Deezer en Ajustes para descargar en HQ"
                    searching = false
                    return@launch
                }
                runCatching { com.aar.privatemusic.downloader.DeezerSource.search(query.trim()) }
                    .onSuccess { results = emptyList(); deezerTracks = it }
                    .onFailure { error = "Error al buscar en Deezer: ${it.message}" }
                searching = false
                return@launch
            }
            if (SpotifyResolver.isSpotifyUrl(query.trim())) {
                runCatching { SpotifyResolver.resolve(query.trim()) }
                    .onSuccess { (title, tracks) ->
                        results = emptyList()
                        spotifyTracks = tracks
                        playlistTitle = title
                        playlistUrl = query.trim()
                    }
                    .onFailure { error = "Error al leer Spotify: ${it.message}" }
            } else if (isPlaylistUrl(query.trim())) {
                runCatching { app.downloader.resolvePlaylist(query.trim()) }
                    .onSuccess { (title, entries) ->
                        results = entries
                        playlistTitle = title
                        playlistUrl = query.trim()
                    }
                    .onFailure { error = "Error al resolver la playlist: ${it.message}" }
            } else if (source == "ytmusic") {
                runCatching { com.aar.privatemusic.downloader.YouTubeMusicSource.search(query.trim()) }
                    .onSuccess { results = it }
                    .onFailure { error = "Error al buscar en YouTube Music: ${it.message}" }
            } else {
                runCatching { app.downloader.search(query) }
                    .onSuccess { results = it }
                    .onFailure { error = "Error al buscar: ${it.message}" }
            }
            searching = false
        }
    }

    suspend fun downloadAll(watch: Boolean) {
        val title = playlistTitle ?: return
        val url = playlistUrl ?: return
        val playlistId = app.repository.createPlaylist(title.take(40))
        val spotify = spotifyTracks

        if (spotify != null) {
            val sourceId = if (watch) app.repository.watchSource(url, title, playlistId) else null
            // Matching each track needs one YouTube search: run in app scope
            // so it survives leaving this screen.
            app.appScope.launch {
                spotify.forEach { track ->
                    app.downloader.searchBestMatch(track.searchQuery, track.durationSec)?.let { match ->
                        app.downloader.enqueue(match, playlistId)
                        sourceId?.let { SpotifySync.markSeen(context, it, listOf(track.key)) }
                    }
                }
            }
            actionMessage = if (watch) {
                "Observando: emparejando ${spotify.size} canciones y vigilando novedades"
            } else {
                "Emparejando y descargando ${spotify.size} canciones en \"$title\""
            }
            return
        }

        results.forEach { app.downloader.enqueue(it, playlistId) }
        if (watch) {
            app.repository.watchSource(url, title, playlistId)
            actionMessage = "Observando: lo nuevo se descargará solo"
        } else {
            actionMessage = "Descargando ${results.size} canciones en \"$title\""
        }
    }

    // Sources catalog: adding one here adds its card to the grid.
    val sources = listOf(
        SearchSource("ytmusic", "YouTube Music", androidx.compose.ui.graphics.Color(0xFFFF0000)) {
            Icon(Icons.Filled.MusicNote, null, tint = androidx.compose.ui.graphics.Color.White)
        },
        SearchSource("yt", "YouTube", androidx.compose.ui.graphics.Color(0xFF606060)) {
            Icon(Icons.Filled.PlayArrow, null, tint = androidx.compose.ui.graphics.Color.White)
        },
        SearchSource("deezer", "Deezer", androidx.compose.ui.graphics.Color(0xFFA238FF)) {
            Icon(Icons.Filled.GraphicEq, null, tint = androidx.compose.ui.graphics.Color.White)
        },
        SearchSource("deezerhq", "Deezer HQ", androidx.compose.ui.graphics.Color(0xFFFF6B00)) {
            Icon(Icons.Filled.Download, null, tint = androidx.compose.ui.graphics.Color.White)
        },
        SearchSource("1337x", "1337x · Torrents música", androidx.compose.ui.graphics.Color(0xFFE0592A)) {
            Icon(Icons.Filled.Search, null, tint = androidx.compose.ui.graphics.Color.White)
        },
        SearchSource("archive", "Internet Archive · FLAC gratis", androidx.compose.ui.graphics.Color(0xFF2C7BB6)) {
            Icon(Icons.Filled.Download, null, tint = androidx.compose.ui.graphics.Color.White)
        },
    )

    androidx.activity.compose.BackHandler(enabled = source != null) { source = null }

    if (source == null) {
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Text(
                    "¿Dónde quieres buscar?",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(sources.size, key = { sources[it].id }) { i ->
                val s = sources[i]
                SourceCard(s) {
                    if (SearchCache.source != s.id) {
                        SearchCache.clearResults()
                        query = ""
                        results = emptyList()
                        playlistTitle = null
                        playlistUrl = null
                        spotifyTracks = null
                        deezerTracks = null
                        error = null
                        actionMessage = null
                    }
                    source = s.id
                }
            }
        }
        return
    }

    val current = sources.first { it.id == source }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        ) {
            IconButton(onClick = { source = null }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fuentes")
            }
            SourceBadge(current)
            Text(
                current.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = {
                Text(
                    when (source) {
                        "deezer" -> "Buscar en Deezer…"
                        "deezerhq" -> "Buscar en Deezer (descarga HQ)…"
                        "ytmusic" -> "Buscar en YouTube Music…"
                        "1337x" -> "Buscar torrents de música…"
                        "archive" -> "Buscar en Internet Archive…"
                        else -> "Buscar en YouTube o pegar URL…"
                    }
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            trailingIcon = {
                IconButton(onClick = { runSearch() }) {
                    Icon(Icons.Filled.Search, contentDescription = "Buscar")
                }
            },
        )

        if (playlistTitle == null) {
            actionMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        when {
            searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            else -> LazyColumn {
                playlistTitle?.let { title ->
                    item(key = "playlist-header") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            val count = spotifyTracks?.size ?: results.size
                            Text(
                                "$title · $count canciones" +
                                    if (spotifyTracks != null) " · Spotify" else "",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Row(
                                modifier = Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = { scope.launch { downloadAll(watch = false) } }) {
                                    Text("Descargar todo")
                                }
                                OutlinedButton(onClick = { scope.launch { downloadAll(watch = true) } }) {
                                    Text("Observar")
                                }
                            }
                            actionMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }
                spotifyTracks?.let { tracks ->
                    items(tracks.size, key = { "sp-$it" }) { i ->
                        val track = tracks[i]
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "${track.artists} · ${formatDuration(track.durationSec)}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                deezerTracks?.let { tracks ->
                    items(tracks, key = { "dz-${it.id}" }) { track ->
                        val dzId = "dz${track.id}"
                        val display = SearchResult(
                            id = dzId,
                            title = track.title,
                            artist = if (track.album.isBlank()) track.artist
                            else "${track.artist} · ${track.album}",
                            durationSec = track.durationSec,
                            thumbnailUrl = track.coverUrl,
                        )
                        // "deezerhq": descarga directa FLAC/MP3 con la sesión del
                        // usuario. "deezer": empareja en YouTube (audio DRM).
                        val isHq = source == "deezerhq"
                        val hqKey = app.deezerDownloader.stateKey(track.id)
                        val matchedId = SearchCache.deezerMatches[dzId]
                        SearchResultRow(
                            result = display,
                            inLibrary = if (isHq) hqKey in libraryIds
                            else matchedId != null && matchedId in libraryIds,
                            state = if (isHq) deezerDownloads[hqKey] else when {
                                dzId in deezerResolving -> DownloadState.Queued
                                else -> matchedId?.let { downloads[it] }
                            },
                            previewResolving = false,
                            previewPlaying = nowPlaying?.songId == "preview:$dzId" && isPlaying,
                            previewLoaded = nowPlaying?.songId == "preview:$dzId",
                            onPreview = {
                                if (nowPlaying?.songId == "preview:$dzId") {
                                    app.playerController.togglePlayPause()
                                } else if (track.previewUrl.isNotBlank()) {
                                    app.playerController.playStream(
                                        dzId, track.title, track.artist,
                                        track.previewUrl, track.coverUrl,
                                    )
                                } else {
                                    actionMessage = "Esta pista no tiene preescucha"
                                }
                            },
                            onDownload = {
                                if (isHq) {
                                    app.deezerDownloader.enqueue(track, app.settings.deezerQuality.value)
                                    actionMessage = "Descargando \"${track.title}\" de Deezer…"
                                } else if (dzId !in deezerResolving) {
                                    // Deezer audio is DRM'd: match the track on YouTube
                                    // and download that, keeping Deezer's clean metadata.
                                    deezerResolving = deezerResolving + dzId
                                    scope.launch {
                                        val match = app.downloader.searchBestMatch(
                                            track.searchQuery, track.durationSec,
                                        )
                                        if (match == null) {
                                            actionMessage =
                                                "Sin resultado en YouTube para \"${track.title}\""
                                        } else {
                                            SearchCache.deezerMatches[dzId] = match.id
                                            app.downloader.enqueue(
                                                SearchResult(
                                                    id = match.id,
                                                    title = "${track.artist} - ${track.title}",
                                                    artist = track.artist,
                                                    durationSec = track.durationSec,
                                                    thumbnailUrl = track.coverUrl
                                                        .ifBlank { match.thumbnailUrl },
                                                )
                                            )
                                        }
                                        deezerResolving = deezerResolving - dzId
                                    }
                                }
                            },
                        )
                    }
                }
                items(results, key = { it.id }) { result ->
                    val inLibrary = result.id in libraryIds
                    // Torrents e Internet Archive descargan por su propio motor.
                    val state = when {
                        result.isTorrent -> torrentDownloads[result.id]
                        result.isArchive -> archiveDownloads[result.id]
                        else -> downloads[result.id]
                    }
                    SearchResultRow(
                        result = result,
                        inLibrary = inLibrary,
                        state = state,
                        previewResolving = previewLoadingId == result.id,
                        previewPlaying = nowPlaying?.songId == "preview:${result.id}" && isPlaying,
                        previewLoaded = nowPlaying?.songId == "preview:${result.id}",
                        onPreview = {
                            when {
                                result.isTorrent -> app.torrents.enqueue(result)
                                result.isArchive -> app.archive.enqueue(result)
                                else -> togglePreview(result)
                            }
                        },
                        onDownload = {
                            when {
                                result.isTorrent -> {
                                    app.torrents.enqueue(result)
                                    actionMessage = "Descargando torrent \"${result.title}\"…"
                                }
                                result.isArchive -> {
                                    app.archive.enqueue(result)
                                    actionMessage = "Descargando de Internet Archive \"${result.title}\"…"
                                }
                                else -> app.downloader.enqueue(result)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** A searchable catalog: id, display name, brand color and badge icon. */
private data class SearchSource(
    val id: String,
    val name: String,
    val color: androidx.compose.ui.graphics.Color,
    val icon: @Composable () -> Unit,
)

@Composable
private fun SourceBadge(source: SearchSource, size: Dp = 28.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(source.color),
    ) { source.icon() }
}

@Composable
private fun SourceCard(source: SearchSource, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            SourceBadge(source, size = 44.dp)
            Text(
                source.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    result: SearchResult,
    inLibrary: Boolean,
    state: DownloadState?,
    previewResolving: Boolean,
    previewPlaying: Boolean,
    previewLoaded: Boolean,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Tapping a result streams it (listen first); download is the explicit icon.
            .clickable { onPreview() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(result.thumbnailUrl.ifBlank { null }, 48.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(result.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (result.isTorrent || result.isArchive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (result.isTorrent) "TORRENT" else "ARCHIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Text(
                        result.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            } else {
                Text(
                    "${result.artist} · ${formatDuration(result.durationSec)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Preview: listen before deciding to download (torrents/archive are albums).
        if (!result.isTorrent && !result.isArchive) when {
            previewResolving ->
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            previewPlaying ->
                IconButton(onClick = onPreview) {
                    Icon(Icons.Filled.StopCircle, "Pausar preescucha", tint = MaterialTheme.colorScheme.primary)
                }
            previewLoaded ->
                // Loaded but paused/buffering: highlighted play resumes in place.
                IconButton(onClick = onPreview) {
                    Icon(Icons.Filled.PlayCircleOutline, "Reanudar preescucha", tint = MaterialTheme.colorScheme.primary)
                }
            else ->
                IconButton(onClick = onPreview) {
                    Icon(Icons.Filled.PlayCircleOutline, "Escuchar sin descargar")
                }
        }
        when {
            inLibrary || state is DownloadState.Done ->
                Icon(Icons.Filled.Check, "Descargada", tint = MaterialTheme.colorScheme.primary)
            state is DownloadState.Queued ->
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            state is DownloadState.Downloading ->
                CircularProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            state is DownloadState.Failed ->
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.ErrorOutline, "Reintentar", tint = MaterialTheme.colorScheme.error)
                }
            else ->
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, "Descargar")
                }
        }
    }
}
