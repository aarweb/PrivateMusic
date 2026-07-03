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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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

@Composable
fun SearchScreen(app: PrivateMusicApp) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Non-null when the current results come from a playlist/channel URL.
    var playlistTitle by remember { mutableStateOf<String?>(null) }
    var playlistUrl by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    // Non-null when the URL was a Spotify playlist/album/track.
    var spotifyTracks by remember { mutableStateOf<List<SpotifyTrack>?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    val downloads by app.downloader.downloads.collectAsState()
    val libraryIds by app.repository.observeSongIds().collectAsState(initial = emptyList())
    val nowPlaying by app.playerController.nowPlaying.collectAsState()
    val isPlaying by app.playerController.isPlaying.collectAsState()
    var previewLoadingId by remember { mutableStateOf<String?>(null) }

    fun togglePreview(result: SearchResult) {
        if (nowPlaying?.songId == "preview:${result.id}" && isPlaying) {
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
        scope.launch {
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

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text("Buscar en YouTube o pegar URL…") },
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
                items(results, key = { it.id }) { result ->
                    val inLibrary = result.id in libraryIds
                    val state = downloads[result.id]
                    SearchResultRow(
                        result = result,
                        inLibrary = inLibrary,
                        state = state,
                        previewResolving = previewLoadingId == result.id,
                        previewPlaying = isPlaying && nowPlaying?.songId == "preview:${result.id}",
                        onPreview = { togglePreview(result) },
                        onDownload = { app.downloader.enqueue(result) },
                    )
                }
            }
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
    onPreview: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !inLibrary && state == null) { onDownload() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(result.thumbnailUrl, 48.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(result.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${result.artist} · ${formatDuration(result.durationSec)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Preview: listen before deciding to download.
        when {
            previewResolving ->
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            previewPlaying ->
                IconButton(onClick = onPreview) {
                    Icon(Icons.Filled.StopCircle, "Parar preescucha", tint = MaterialTheme.colorScheme.primary)
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
