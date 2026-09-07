package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.FilterChip
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.aar.privatemusic.downloader.DeezerSource
import com.aar.privatemusic.downloader.DeezerTrack
import com.aar.privatemusic.downloader.DownloadState
import com.aar.privatemusic.downloader.SearchResult
import com.aar.privatemusic.downloader.PlaylistLinkResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.aar.privatemusic.downloader.SpotifySync
import com.aar.privatemusic.downloader.SpotifyTrack
import com.aar.privatemusic.ui.components.ArtImage
import com.aar.privatemusic.ui.components.SongOverflowMenu
import com.aar.privatemusic.ui.components.SongRow
import com.aar.privatemusic.ui.components.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.io.File

/** Last search kept across navigation so switching tabs doesn't wipe results. */
private object SearchCache {
    var query = ""
    var source = "global"
    var results: List<SearchResult> = emptyList()
    var playlistTitle: String? = null
    var playlistUrl: String? = null
    var spotifyTracks: List<SpotifyTrack>? = null
    var deezerTracks: List<com.aar.privatemusic.downloader.DeezerTrack>? = null
    var onlineQuery = ""

    /** Deezer track id -> matched YouTube id (so rows can show download state). */
    val deezerMatches = mutableMapOf<String, String>()
}

private data class GlobalLyricsState(
    val query: String = "",
    val hits: List<com.aar.privatemusic.lyrics.LyricSearchHit> = emptyList(),
    val searching: Boolean = false,
)

/** Calidad de audio de YouTube (yt-dlp bestaudio): Opus/AAC lossy, ~160 kbps máx. */
private const val YT_QUALITY = "≤160k"

private fun deezerQualityLabel(quality: String): String = when (quality) {
    "FLAC" -> "FLAC"
    "MP3_320" -> "MP3 320"
    else -> "MP3 128"
}

@Composable
fun SearchScreen(
    app: PrivateMusicApp,
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (title: String, artist: String) -> Unit = { _, _ -> },
    onOpenPlaylist: (Long) -> Unit = {},
) {
    var query by remember { mutableStateOf(SearchCache.query) }
    var results by remember { mutableStateOf(SearchCache.results) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Non-null when the current results come from a playlist/channel URL.
    var playlistTitle by remember { mutableStateOf(SearchCache.playlistTitle) }
    var playlistUrl by remember { mutableStateOf(SearchCache.playlistUrl) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    // Non-null when the URL was a Spotify/Deezer/Apple/Tidal playlist/album/track.
    var spotifyTracks by remember { mutableStateOf(SearchCache.spotifyTracks) }
    // Nombre del servicio de origen del enlace ("Spotify", "Deezer"…), para la cabecera.
    var linkServiceName by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf(SearchCache.source) }
    var deezerTracks by remember { mutableStateOf(SearchCache.deezerTracks) }
    // Deezer tracks currently being matched on YouTube before download.
    var deezerResolving by remember { mutableStateOf(setOf<String>()) }
    // Resultados del servidor Subsonic/Navidrome del usuario.
    var subsonicTracks by remember { mutableStateOf<List<com.aar.privatemusic.downloader.SubsonicTrack>?>(null) }
    var subsonicPlaylistsDialog by remember { mutableStateOf<List<com.aar.privatemusic.downloader.SubsonicPlaylist>?>(null) }
    var loadingServerPlaylists by remember { mutableStateOf(false) }
    // Importación de Spotify: progreso del emparejado y pistas a repasar a mano.
    var matching by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var review by remember { mutableStateOf<List<SpotifyReviewItem>>(emptyList()) }
    var reviewPlaylistId by remember { mutableStateOf<Long?>(null) }
    var onlineQuery by remember { mutableStateOf(SearchCache.onlineQuery) }
    var onlineSearchGeneration by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var onlineSearchJob by remember { mutableStateOf<Job?>(null) }
    var youtubeLoading by remember { mutableStateOf(false) }
    var youtubeError by remember { mutableStateOf<String?>(null) }
    var deezerLoading by remember { mutableStateOf(false) }
    var deezerError by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.SideEffect {
        SearchCache.query = query
        SearchCache.source = source
        SearchCache.results = results
        SearchCache.playlistTitle = playlistTitle
        SearchCache.playlistUrl = playlistUrl
        SearchCache.spotifyTracks = spotifyTracks
        SearchCache.deezerTracks = deezerTracks
        SearchCache.onlineQuery = onlineQuery
    }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    val localSongs by app.repository.observeSongs().collectAsState(initial = emptyList())
    val localPlaylists by app.repository.observePlaylists().collectAsState(initial = emptyList())
    val localSongIds = remember(localSongs) { localSongs.map { it.id } }
    val trimmedQuery = query.trim()
    val normalizedGlobalQueryLength = remember(trimmedQuery) {
        com.aar.privatemusic.lyrics.LyricSearch.normalize(trimmedQuery).length
    }
    var globalLyricsState by remember { mutableStateOf(GlobalLyricsState()) }

    LaunchedEffect(source, trimmedQuery, localSongIds) {
        globalLyricsState = GlobalLyricsState(query = trimmedQuery)
        if (source != "global" || normalizedGlobalQueryLength < 3) return@LaunchedEffect
        globalLyricsState = GlobalLyricsState(query = trimmedQuery, searching = true)
        delay(250)
        val hits = try {
            app.lyricsSearchIndex.search(localSongs, trimmedQuery)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        globalLyricsState = GlobalLyricsState(query = trimmedQuery, hits = hits)
    }

    val validGlobalLyricHits = remember(globalLyricsState, trimmedQuery, source) {
        if (source == "global" && globalLyricsState.query == trimmedQuery) {
            globalLyricsState.hits.associateBy { it.songId }
        } else emptyMap()
    }
    val globalLocalResults = remember(
        source, localSongs, localPlaylists, trimmedQuery, validGlobalLyricHits,
    ) {
        if (source == "global" && trimmedQuery.isNotBlank()) {
            com.aar.privatemusic.data.GlobalSearch.search(
                songs = localSongs,
                playlists = localPlaylists,
                query = trimmedQuery,
                lyricSongIds = validGlobalLyricHits.keys,
            )
        } else {
            com.aar.privatemusic.data.GlobalSearchResults(
                songs = emptyList(),
                albums = emptyList(),
                artists = emptyList(),
                playlists = emptyList(),
            )
        }
    }

    val downloads by app.downloader.downloads.collectAsState()
    val torrentDownloads by app.torrents.downloads.collectAsState()
    val archiveDownloads by app.archive.downloads.collectAsState()
    val subsonicDownloads by app.subsonic.downloads.collectAsState()
    val bandcampDownloads by app.bandcamp.downloads.collectAsState()
    val deezerDownloads by app.deezerDownloader.downloads.collectAsState()
    val deezerQuality by app.settings.deezerQuality.collectAsState()
    val deezerArl by app.settings.deezerArl.collectAsState()
    val libraryIds by app.repository.observeSongIds().collectAsState(initial = emptyList())
    val nowPlaying by app.playerController.nowPlaying.collectAsState()
    val isPlaying by app.playerController.isPlaying.collectAsState()
    var previewLoadingId by remember { mutableStateOf<String?>(null) }
    // Búsqueda de capítulos en curso, y el diálogo cuando el vídeo tiene.
    var chapterChecking by remember { mutableStateOf<String?>(null) }
    var chapterPrompt by remember {
        mutableStateOf<Pair<SearchResult, List<com.aar.privatemusic.downloader.Chapter>>?>(null)
    }

    fun checkChapters(result: SearchResult) {
        if (chapterChecking != null) return
        chapterChecking = result.id
        scope.launch {
            val chapters = app.downloader.fetchChapters(result.id)
            chapterChecking = null
            if (chapters.size >= 2) {
                chapterPrompt = result to chapters
            } else {
                actionMessage = "Este vídeo no tiene capítulos para dividir"
            }
        }
    }

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
            // Internet Archive streamea sus ficheros HTTP directamente (1ª pista
            // del ítem); el resto de fuentes resuelven el stream de YouTube.
            val url = when {
                result.isArchive -> app.archive.previewUrl(result.id)
                result.isBandcamp -> app.bandcamp.previewUrl(result.id)
                else -> app.downloader.streamUrl(result.id)
            }
            if (url != null) {
                app.playerController.playStream(result.id, result.title, result.artist, url, result.thumbnailUrl)
            } else {
                actionMessage = "No se pudo reproducir \"${result.title}\""
            }
            previewLoadingId = null
        }
    }

    fun isPlaylistUrl(q: String): Boolean =
        q.contains("list=") || q.contains("/playlist") ||
            q.contains("youtube.com/@") || q.contains("/channel/")

    fun clearSearch() {
        onlineSearchGeneration++
        onlineSearchJob?.cancel()
        onlineSearchJob = null
        onlineQuery = ""
        searching = false
        error = null
        youtubeLoading = false
        youtubeError = null
        deezerLoading = false
        deezerError = null
        results = emptyList()
        deezerTracks = null
        subsonicTracks = null
        spotifyTracks = null
        playlistTitle = null
        playlistUrl = null
        linkServiceName = null
        actionMessage = null
    }

    // All catalog requests share this job and identity, including section retries.
    fun runSearch(
        targetSource: String,
        retrySource: String? = null,
    ) {
        val requestedQuery = query.trim()
        if (requestedQuery.isBlank()) return
        val requestedSource = targetSource
        val requestGeneration = ++onlineSearchGeneration
        val isGlobalText = requestedSource == "global" &&
            !requestedQuery.startsWith("http", ignoreCase = true) &&
            !PlaylistLinkResolver.isSupportedLink(requestedQuery)
        // Preserve the other section on retry; restart it only if it was cancelled in flight.
        val retry = isGlobalText && onlineQuery == requestedQuery && retrySource != null
        val searchYoutube = !retry || retrySource == "yt" || youtubeLoading
        val searchDeezer = !retry || retrySource == "deezer" || deezerLoading
        onlineSearchJob?.cancel()
        keyboard?.hide()
        onlineQuery = requestedQuery
        searching = true
        error = null
        playlistTitle = null
        playlistUrl = null
        actionMessage = null
        spotifyTracks = null
        linkServiceName = null
        subsonicTracks = null
        if (searchYoutube) {
            results = emptyList()
            youtubeError = null
        }
        if (searchDeezer) {
            deezerTracks = null
            deezerError = null
        }
        youtubeLoading = isGlobalText && searchYoutube
        deezerLoading = isGlobalText && searchDeezer

        fun isCurrent() = source == requestedSource && query.trim() == requestedQuery &&
            onlineSearchGeneration == requestGeneration

        onlineSearchJob = scope.launch {
            try {
                if (isGlobalText) {
                    supervisorScope {
                        if (searchYoutube) launch {
                            try {
                                val found = app.downloader.search(requestedQuery)
                                if (isCurrent()) results = found
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                if (isCurrent()) youtubeError = failure.message ?: "No se pudo completar la consulta"
                            } finally {
                                if (isCurrent()) youtubeLoading = false
                            }
                        }
                        if (searchDeezer) launch {
                            try {
                                val found = DeezerSource.search(requestedQuery)
                                if (isCurrent()) deezerTracks = found
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                if (isCurrent()) deezerError = failure.message ?: "No se pudo completar la consulta"
                            } finally {
                                if (isCurrent()) deezerLoading = false
                            }
                        }
                    }
                } else if (PlaylistLinkResolver.isSupportedLink(requestedQuery)) {
                    val svc = PlaylistLinkResolver.serviceOf(requestedQuery)
                    val svcName = when (svc) {
                        com.aar.privatemusic.downloader.LinkService.DEEZER -> "Deezer"
                        com.aar.privatemusic.downloader.LinkService.APPLE_MUSIC -> "Apple Music"
                        com.aar.privatemusic.downloader.LinkService.TIDAL -> "Tidal"
                        else -> "Spotify"
                    }
                    val pl = PlaylistLinkResolver.resolve(requestedQuery)
                    if (isCurrent()) {
                        spotifyTracks = pl.tracks
                        linkServiceName = svcName
                        playlistTitle = pl.name
                        playlistUrl = requestedQuery
                        if (pl.truncated && svc == com.aar.privatemusic.downloader.LinkService.SPOTIFY) {
                            actionMessage = "Spotify sólo deja leer ${pl.tracks.size} pistas por " +
                                "playlist; si tiene más, el resto se quedará fuera"
                        }
                    }
                } else when (requestedSource) {
                    "1337x" -> {
                        val found = com.aar.privatemusic.downloader.Torrent1337xSource.search(requestedQuery, limit = 30)
                        if (isCurrent()) results = found
                    }
                    "bandcamp" -> {
                        val found = com.aar.privatemusic.downloader.BandcampSource.search(requestedQuery, limit = 30)
                        if (isCurrent()) results = found
                    }
                    "subsonic" -> {
                        if (app.settings.subsonicConfig() == null) {
                            if (isCurrent()) error = "Configura tu servidor en Ajustes → Servidor de música"
                        } else {
                            val found = app.subsonic.search(requestedQuery, limit = 40)
                            if (isCurrent()) subsonicTracks = found
                        }
                    }
                    "archive" -> {
                        val found = com.aar.privatemusic.downloader.InternetArchiveSource.search(requestedQuery, limit = 30)
                        if (isCurrent()) results = found
                    }
                    else -> {
                        when {
                            (requestedSource == "deezer" || requestedSource == "deezerhq") &&
                                !requestedQuery.startsWith("http", ignoreCase = true) -> {
                                // The public catalog does not require an ARL, even in HQ.
                                val found = DeezerSource.search(requestedQuery)
                                if (isCurrent()) deezerTracks = found
                            }
                            isPlaylistUrl(requestedQuery) -> {
                                val (title, entries) = app.downloader.resolvePlaylist(requestedQuery)
                                if (isCurrent()) {
                                    results = entries
                                    playlistTitle = title
                                    playlistUrl = requestedQuery
                                }
                            }
                            requestedSource == "ytmusic" -> {
                                val found = com.aar.privatemusic.downloader.YouTubeMusicSource.search(requestedQuery)
                                if (isCurrent()) results = found
                            }
                            else -> {
                                val found = app.downloader.search(requestedQuery)
                                if (isCurrent()) results = found
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isCurrent()) error = failure.message ?: "No se pudo completar la consulta"
            } finally {
                if (isCurrent()) searching = false
            }
        }
    }

    // A request cancelled by navigation must not look like a completed empty search.
    LaunchedEffect(Unit) {
        if (onlineQuery.isNotBlank() && onlineQuery == query.trim()) {
            runSearch(targetSource = source)
        }
    }

    /**
     * Importa la playlist de Spotify descargando cada pista de Deezer en HQ.
     * Sólo se bajan solas las coincidencias exactas (título, artista y duración):
     * las dudosas y las que Deezer no tiene van a [review] para que decidas.
     * La posición en la playlist es la de Spotify, no la de fin de descarga.
     */
    suspend fun downloadAllFromDeezer() {
        val title = playlistTitle ?: return
        val tracks = spotifyTracks ?: return
        if (matching != null) return
        val quality = app.settings.deezerQuality.value
        val playlistId = app.repository.createPlaylist(title.take(40))
        reviewPlaylistId = playlistId
        actionMessage = null
        matching = 0 to tracks.size

        val problems = mutableListOf<SpotifyReviewItem>()
        var done = 0
        // Una búsqueda por pista: en tandas de 4 para no encadenar 100 esperas.
        tracks.withIndex().chunked(4).forEach { chunk ->
            val matches = coroutineScope {
                chunk.map { (i, t) ->
                    async {
                        i to runCatching {
                            DeezerSource.bestMatch(t.title, t.mainArtist, t.durationSec)
                        }.getOrNull()
                    }
                }.awaitAll()
            }
            matches.forEach { (i, match) ->
                val track = tracks[i]
                if (match != null && match.exact) {
                    app.deezerDownloader.enqueue(match.track, quality, playlistId, i)
                } else {
                    problems += SpotifyReviewItem(track, i, match?.track)
                }
                done++
            }
            matching = done to tracks.size
        }
        matching = null
        review = problems
        val ok = tracks.size - problems.size
        actionMessage = "Descargando $ok de ${tracks.size} desde Deezer en \"$title\"" +
            if (problems.isNotEmpty()) " · ${problems.size} por repasar" else ""
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

    val sources = remember {
        listOf(
            SearchSource("global", "Global", "Biblioteca y letras al escribir; Deezer y YouTube al pulsar Buscar. También puedes pegar enlaces."),
            SearchSource("ytmusic", "YouTube Music", "Busca canciones en YouTube Music; escucha y descarga el audio disponible en YouTube."),
            SearchSource("yt", "YouTube", "Busca vídeos o pega enlaces de vídeos, playlists y canales. Mantén pulsado un resultado para buscar capítulos."),
            SearchSource("deezer", "Deezer", "Catálogo y preescuchas de Deezer. Las descargas buscan una coincidencia en YouTube."),
            SearchSource("deezerhq", "Deezer HQ", "Busca el catálogo sin iniciar sesión. Para descargar directamente de Deezer necesitas una sesión en Ajustes; calidad según tu plan."),
            SearchSource("1337x", "1337x", "Busca torrents de música en 1337x. No hay preescucha; disponibilidad y calidad dependen del torrent."),
            SearchSource("archive", "Archive", "Busca música en Internet Archive. Preescucha la primera pista; formatos y calidad dependen del archivo."),
            SearchSource("subsonic", "Servidor", "Busca y descarga música de tu servidor Subsonic o Navidrome configurado en Ajustes."),
            SearchSource("bandcamp", "Bandcamp", "Busca música en Bandcamp. La preescucha y la descarga dependen de lo disponible para cada publicación."),
        )
    }
    val current = sources.first { it.id == source }
    // One row implementation for the Deezer catalog in Global, Deezer and Deezer HQ.
    val deezerRow: @Composable (DeezerTrack) -> Unit = { track ->
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
            // HQ: la calidad de tu plan; si no, audio de YouTube.
            qualityLabel = if (isHq) deezerQualityLabel(deezerQuality) else YT_QUALITY,
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
                    if (app.settings.deezerArl.value.isBlank()) {
                        actionMessage = "Inicia sesión en Deezer en Ajustes para descargar en HQ"
                    } else {
                        app.deezerDownloader.enqueue(track, app.settings.deezerQuality.value)
                        actionMessage = "Descargando \"${track.title}\" de Deezer…"
                    }
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
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            label = { Text("Buscar música") },
            onValueChange = { newQuery ->
                if (newQuery.trim() != query.trim()) clearSearch()
                query = newQuery
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = {
                Text(
                    when (source) {
                        "global" -> "Canciones, letras, álbumes, artistas o playlists…"
                        "deezer" -> "Buscar en Deezer…"
                        "deezerhq" -> "Buscar en Deezer (descarga HQ)…"
                        "ytmusic" -> "Buscar en YouTube Music…"
                        "1337x" -> "Buscar torrents de música…"
                        "archive" -> "Buscar en Internet Archive…"
                        "subsonic" -> "Buscar en tu servidor…"
                        "bandcamp" -> "Buscar en Bandcamp…"
                        else -> "Buscar en YouTube o pegar URL…"
                    }
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch(targetSource = source) }),
            trailingIcon = {
                IconButton(onClick = { runSearch(targetSource = source) }) {
                    Icon(Icons.Filled.Search, contentDescription = "Buscar")
                }
            },
        )

        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sources, key = { it.id }) { candidate ->
                FilterChip(
                    selected = source == candidate.id,
                    onClick = {
                        if (source != candidate.id) {
                            clearSearch()
                            source = candidate.id
                            if (query.isNotBlank()) runSearch(targetSource = candidate.id)
                        }
                    },
                    label = { Text(candidate.name) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Text(
            current.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (source == "subsonic") {
            OutlinedButton(
                onClick = {
                    if (app.settings.subsonicConfig() == null) {
                        error = "Configura tu servidor en Ajustes"
                    } else {
                        loadingServerPlaylists = true
                        scope.launch {
                            val pls = app.subsonic.playlists()
                            loadingServerPlaylists = false
                            if (pls.isEmpty()) actionMessage = "Tu servidor no tiene playlists"
                            else subsonicPlaylistsDialog = pls
                        }
                    }
                },
                enabled = !loadingServerPlaylists,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(if (loadingServerPlaylists) "Cargando…" else "Playlists del servidor")
            }
        }

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

        val isGlobalText = source == "global" &&
            !trimmedQuery.startsWith("http", ignoreCase = true) &&
            !PlaylistLinkResolver.isSupportedLink(trimmedQuery)
        if (isGlobalText) {
            val hasLocalResults = globalLocalResults.songs.isNotEmpty() ||
                globalLocalResults.albums.isNotEmpty() ||
                globalLocalResults.artists.isNotEmpty() ||
                globalLocalResults.playlists.isNotEmpty()
            val searchingLyrics = globalLyricsState.query == trimmedQuery &&
                globalLyricsState.searching
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (trimmedQuery.isBlank()) {
                    item(key = "global-help") {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "Busca en toda tu música",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Encontrarás canciones, texto de letras, álbumes, artistas y " +
                                    "playlists al escribir. Pulsa Buscar para añadir también " +
                                    "resultados online.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    if (!hasLocalResults && !searchingLyrics) {
                        item(key = "global-no-local") {
                            Text(
                                "No hay coincidencias en tu biblioteca.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }

                    if (globalLocalResults.songs.isNotEmpty()) {
                        item(key = "global-songs-title") {
                            GlobalSectionHeader("Canciones", Icons.Filled.LibraryMusic)
                        }
                        items(
                            globalLocalResults.songs,
                            key = { "global-song-${it.id}" },
                            contentType = { "global-song" },
                        ) { song ->
                            SongRow(
                                song = song,
                                isCurrent = song.id == nowPlaying?.songId,
                                supportingText = validGlobalLyricHits[song.id]?.let { hit ->
                                    if (hit.translated) "Traducción: “${hit.snippet}”"
                                    else "“${hit.snippet}”"
                                },
                                onClick = {
                                    val queue = globalLocalResults.songs
                                    val index = queue.indexOfFirst { it.id == song.id }
                                    app.playerController.playQueue(queue, index.coerceAtLeast(0))
                                },
                                onSwipeToQueue = {
                                    app.playerController.addToQueue(song)
                                    com.aar.privatemusic.util.Feedback.show("Añadida a la cola")
                                },
                                trailing = { SongOverflowMenu(app, song) },
                            )
                        }
                    }

                    if (searchingLyrics) {
                        item(key = "global-lyrics-progress") {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    "Buscando también dentro de las letras…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (globalLocalResults.albums.isNotEmpty()) {
                        item(key = "global-albums-title") {
                            GlobalSectionHeader("Álbumes", Icons.Filled.Album)
                        }
                        items(globalLocalResults.albums, key = {
                            "global-album-${it.key.artist}-${it.key.title}"
                        }) { album ->
                            GlobalAlbumRow(album) {
                                onOpenAlbum(album.key.title, album.key.artist)
                            }
                        }
                    }

                    if (globalLocalResults.artists.isNotEmpty()) {
                        item(key = "global-artists-title") {
                            GlobalSectionHeader("Artistas", Icons.Filled.Person)
                        }
                        items(globalLocalResults.artists, key = { "global-artist-${it.name}" }) { artist ->
                            GlobalArtistRow(artist) { onOpenArtist(artist.name) }
                        }
                    }

                    if (globalLocalResults.playlists.isNotEmpty()) {
                        item(key = "global-playlists-title") {
                            GlobalSectionHeader("Playlists", Icons.Filled.QueueMusic)
                        }
                        items(globalLocalResults.playlists, key = { "global-playlist-${it.id}" }) { playlist ->
                            GlobalPlaylistRow(playlist) { onOpenPlaylist(playlist.id) }
                        }
                    }

                    item(key = "global-deezer-title") {
                        GlobalSectionHeader(
                            "Deezer · ${deezerTracks?.size ?: 0}",
                            Icons.Filled.GraphicEq,
                        )
                        SearchStatus(
                            sourceName = "Deezer",
                            searched = onlineQuery == trimmedQuery,
                            loading = deezerLoading,
                            error = deezerError,
                            empty = deezerTracks.isNullOrEmpty(),
                            onRetry = {
                                runSearch(targetSource = "global", retrySource = "deezer")
                            },
                        )
                    }
                    if (onlineQuery == trimmedQuery) {
                        items(deezerTracks.orEmpty(), key = { "global-dz-${it.id}" }) { track ->
                            deezerRow(track)
                        }
                    }
                    item(key = "global-youtube-title") {
                        GlobalSectionHeader("YouTube · ${results.size}", Icons.Filled.PlayArrow)
                        SearchStatus(
                            sourceName = "YouTube",
                            searched = onlineQuery == trimmedQuery,
                            loading = youtubeLoading,
                            error = youtubeError,
                            empty = results.isEmpty(),
                            onRetry = {
                                runSearch(targetSource = "global", retrySource = "yt")
                            },
                        )
                    }

                    if (onlineQuery == trimmedQuery) {
                        items(results, key = { "global-online-${it.id}" }) { result ->
                            SearchResultRow(
                                result = result,
                                inLibrary = result.id in libraryIds,
                                state = downloads[result.id],
                                qualityLabel = YT_QUALITY,
                                previewResolving = previewLoadingId == result.id,
                                previewPlaying = nowPlaying?.songId == "preview:${result.id}" && isPlaying,
                                previewLoaded = nowPlaying?.songId == "preview:${result.id}",
                                onPreview = { togglePreview(result) },
                                onDownload = { app.downloader.enqueue(result) },
                                onCancel = { app.downloader.cancel(result.id) },
                                onLongPress = { checkChapters(result) },
                            )
                        }
                    }
                }
            }
        } else when {
            searching || error != null ||
                (results.isEmpty() && spotifyTracks.isNullOrEmpty() &&
                    deezerTracks.isNullOrEmpty() && subsonicTracks.isNullOrEmpty()) -> {
                SearchStatus(
                    sourceName = current.name,
                    searched = onlineQuery.isNotBlank() && onlineQuery == trimmedQuery,
                    loading = searching,
                    error = error,
                    empty = true,
                    onRetry = { runSearch(targetSource = source) },
                )
            }
            else -> LazyColumn {
                playlistTitle?.let { title ->
                    item(key = "playlist-header") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            val count = spotifyTracks?.size ?: results.size
                            Text(
                                "$title · $count " + (if (count == 1) "canción" else "canciones") +
                                    (linkServiceName?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Row(
                                modifier = Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val hasDeezer = spotifyTracks != null && deezerArl.isNotBlank()
                                if (hasDeezer) {
                                    Button(
                                        onClick = { scope.launch { downloadAllFromDeezer() } },
                                        enabled = matching == null,
                                    ) {
                                        Text("Descargar de Deezer")
                                    }
                                    OutlinedButton(onClick = { scope.launch { downloadAll(watch = false) } }) {
                                        Text("YouTube")
                                    }
                                } else {
                                    Button(onClick = { scope.launch { downloadAll(watch = false) } }) {
                                        Text("Descargar todo")
                                    }
                                }
                                OutlinedButton(onClick = { scope.launch { downloadAll(watch = true) } }) {
                                    Text("Observar")
                                }
                            }
                            if (spotifyTracks != null && deezerArl.isBlank()) {
                                Text(
                                    "Inicia sesión en Deezer (Ajustes) para bajarla en HQ/FLAC",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            matching?.let { (done, total) ->
                                Text(
                                    "Buscando en Deezer: $done de $total",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
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
                        deezerRow(track)
                    }
                }
                subsonicTracks?.let { tracks ->
                    items(tracks, key = { "sub-${it.id}" }) { track ->
                        val key = app.subsonic.localId(track.id)
                        val coverUrl = app.subsonic.coverUrl(track.coverArtId).orEmpty()
                        val display = SearchResult(
                            id = track.id,
                            title = track.title,
                            artist = if (track.album.isBlank()) track.artist else "${track.artist} · ${track.album}",
                            durationSec = track.durationSec,
                            thumbnailUrl = coverUrl,
                            isSubsonic = true,
                        )
                        SearchResultRow(
                            result = display,
                            inLibrary = key in libraryIds,
                            state = subsonicDownloads[key],
                            qualityLabel = track.suffix.uppercase(),
                            previewResolving = previewLoadingId == track.id,
                            previewPlaying = nowPlaying?.songId == "preview:${track.id}" && isPlaying,
                            previewLoaded = nowPlaying?.songId == "preview:${track.id}",
                            onPreview = {
                                if (nowPlaying?.songId == "preview:${track.id}") {
                                    app.playerController.togglePlayPause()
                                } else {
                                    val url = app.subsonic.streamUrl(track.id)
                                    if (url != null) {
                                        app.playerController.playStream(
                                            track.id, track.title, track.artist, url, coverUrl,
                                        )
                                    } else actionMessage = "Servidor no configurado"
                                }
                            },
                            onDownload = {
                                app.subsonic.enqueue(track)
                                actionMessage = "Descargando \"${track.title}\" de tu servidor…"
                            },
                            onCancel = {},
                        )
                    }
                }
                items(results, key = { it.id }) { result ->
                    val inLibrary = result.id in libraryIds
                    // Torrents e Internet Archive descargan por su propio motor.
                    val state = when {
                        result.isTorrent -> torrentDownloads[result.id]
                        result.isArchive -> archiveDownloads[result.id]
                        result.isBandcamp -> bandcampDownloads[app.bandcamp.localId(result.id)]
                        else -> downloads[result.id]
                    }
                    SearchResultRow(
                        result = result,
                        inLibrary = inLibrary,
                        state = state,
                        // Álbumes traen su calidad parseada; el resto es audio de YouTube.
                        qualityLabel = if (result.isTorrent || result.isArchive || result.isBandcamp) result.qualityLabel
                        else YT_QUALITY,
                        previewResolving = previewLoadingId == result.id,
                        previewPlaying = nowPlaying?.songId == "preview:${result.id}" && isPlaying,
                        previewLoaded = nowPlaying?.songId == "preview:${result.id}",
                        onPreview = {
                            when {
                                result.isTorrent -> app.torrents.enqueue(result)
                                // Archive: preescucha la 1ª pista antes de bajar el ítem.
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
                                result.isBandcamp -> {
                                    app.bandcamp.enqueue(result)
                                    actionMessage = "Descargando de Bandcamp \"${result.title}\"…"
                                }
                                else -> app.downloader.enqueue(result)
                            }
                        },
                        onCancel = {
                            when {
                                result.isTorrent -> app.torrents.cancel(result.id)
                                result.isArchive -> app.archive.cancel(result.id)
                                result.isBandcamp -> {}
                                else -> app.downloader.cancel(result.id)
                            }
                        },
                        // Sólo YouTube tiene capítulos; long-press para dividir.
                        onLongPress = if (!result.isTorrent && !result.isArchive && !result.isBandcamp && !result.isSubsonic) {
                            { checkChapters(result) }
                        } else null,
                    )
                }
            }
        }
    }

    chapterChecking?.let {
        actionMessage = "Buscando capítulos…"
    }

    subsonicPlaylistsDialog?.let { playlists ->
        AlertDialog(
            onDismissRequest = { subsonicPlaylistsDialog = null },
            title = { Text("Playlists del servidor") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(playlists, key = { it.id }) { pl ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    app.subsonic.importPlaylist(pl)
                                    actionMessage = "Importando \"${pl.name}\" (${pl.songCount})…"
                                    subsonicPlaylistsDialog = null
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(pl.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${pl.songCount} canciones",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { subsonicPlaylistsDialog = null }) { Text("Cerrar") }
            },
        )
    }

    chapterPrompt?.let { (result, chapters) ->
        AlertDialog(
            onDismissRequest = { chapterPrompt = null },
            title = { Text("Dividir por capítulos") },
            text = {
                Column {
                    Text("\"${result.title}\" tiene ${chapters.size} capítulos.")
                    Text(
                        "Se descargará entero y se partirá en ${chapters.size} pistas, en una playlist nueva.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    chapters.take(6).forEach {
                        Text("• ${it.title}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (chapters.size > 6) Text("…y ${chapters.size - 6} más", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    app.downloader.enqueueChapters(result, chapters, result.title)
                    actionMessage = "Dividiendo \"${result.title}\" en ${chapters.size} pistas…"
                    chapterPrompt = null
                }) { Text("Dividir en ${chapters.size}") }
            },
            dismissButton = {
                TextButton(onClick = {
                    app.downloader.enqueue(result)
                    chapterPrompt = null
                }) { Text("Descargar entero") }
            },
        )
    }

    if (review.isNotEmpty()) {
        val playlistId = reviewPlaylistId
        SpotifyReviewDialog(
            items = review,
            onDismiss = { review = emptyList() },
            onUseDeezer = { item ->
                item.candidate?.let {
                    app.deezerDownloader.enqueue(it, deezerQuality, playlistId, item.index)
                }
                review = review - item
            },
            onYouTube = { item ->
                // La búsqueda en YouTube sobrevive a salir de la pantalla.
                app.appScope.launch {
                    app.downloader.searchBestMatch(item.track.searchQuery, item.track.durationSec)
                        ?.let { app.downloader.enqueue(it, playlistId) }
                }
                review = review - item
            },
            onSkip = { review = review - it },
        )
    }
}

/** Pista de Spotify que Deezer no supo casar: [candidate] es lo más parecido, si hay algo. */
private data class SpotifyReviewItem(
    val track: SpotifyTrack,
    /** Posición en la playlist de Spotify, para no perder el orden. */
    val index: Int,
    val candidate: DeezerTrack?,
)

/**
 * Repaso de las pistas dudosas: Deezer devuelve remixes, directos y versiones
 * alargadas con el mismo título, así que estas no se bajan solas.
 */
@Composable
private fun SpotifyReviewDialog(
    items: List<SpotifyReviewItem>,
    onDismiss: () -> Unit,
    onUseDeezer: (SpotifyReviewItem) -> Unit,
    onYouTube: (SpotifyReviewItem) -> Unit,
    onSkip: (SpotifyReviewItem) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repasar ${items.size} canciones") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(items, key = { it.index }) { item ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(
                            item.track.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "${item.track.artists} · ${formatDuration(item.track.durationSec)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val candidate = item.candidate
                        Text(
                            if (candidate == null) "Deezer no la tiene"
                            else "Deezer: ${candidate.title} · ${candidate.artist} · " +
                                formatDuration(candidate.durationSec),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (candidate != null) {
                                TextButton(onClick = { onUseDeezer(item) }) { Text("Usar") }
                            }
                            TextButton(onClick = { onYouTube(item) }) { Text("YouTube") }
                            TextButton(onClick = { onSkip(item) }) { Text("Omitir") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun GlobalSectionHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun GlobalAlbumRow(
    album: com.aar.privatemusic.data.AlbumCollection,
    onClick: () -> Unit,
) {
    val coverSong = album.songs.firstOrNull { !it.artPath.isNullOrBlank() }
        ?: album.songs.firstOrNull()
    GlobalCatalogRow(
        title = album.key.title,
        subtitle = "${album.key.artist} · ${album.songs.size} canciones",
        artModel = coverSong?.artPath?.let(::File) ?: coverSong?.thumbnailUrl,
        fallbackIcon = Icons.Filled.Album,
        contentDescription = "Abrir álbum ${album.key.title}",
        onClick = onClick,
    )
}

@Composable
private fun GlobalArtistRow(
    artist: com.aar.privatemusic.data.GlobalArtistResult,
    onClick: () -> Unit,
) {
    val coverSong = artist.songs.firstOrNull { !it.artPath.isNullOrBlank() }
        ?: artist.songs.firstOrNull()
    GlobalCatalogRow(
        title = artist.name,
        subtitle = "${artist.songs.size} canciones",
        artModel = coverSong?.artPath?.let(::File) ?: coverSong?.thumbnailUrl,
        fallbackIcon = Icons.Filled.Person,
        contentDescription = "Abrir artista ${artist.name}",
        onClick = onClick,
    )
}

@Composable
private fun GlobalPlaylistRow(
    playlist: com.aar.privatemusic.data.db.Playlist,
    onClick: () -> Unit,
) {
    GlobalCatalogRow(
        title = playlist.name,
        subtitle = playlist.description?.takeIf { it.isNotBlank() } ?: "Playlist",
        artModel = playlist.coverPath?.let(::File),
        fallbackIcon = Icons.Filled.QueueMusic,
        contentDescription = "Abrir playlist ${playlist.name}",
        onClick = onClick,
    )
}

@Composable
private fun GlobalCatalogRow(
    title: String,
    subtitle: String,
    artModel: Any?,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (artModel != null) {
            ArtImage(artModel, 48.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    fallbackIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The idle state gives guidance; empty is shown only after a completed request. */
@Composable
private fun SearchStatus(
    sourceName: String,
    searched: Boolean,
    loading: Boolean,
    error: String?,
    empty: Boolean,
    onRetry: () -> Unit,
) {
    if (searched && !loading && error == null && !empty) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("Buscando en $sourceName…")
            }
            error != null -> {
                Text(
                    "$sourceName: $error",
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry) { Text("Reintentar en $sourceName") }
            }
            !searched -> Text(
                "Escribe una consulta y pulsa Buscar para consultar $sourceName.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            empty -> Text(
                "Sin resultados en $sourceName.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class SearchSource(
    val id: String,
    val name: String,
    val description: String,
)

/** Etiqueta compacta (fuente o calidad) mostrada junto al subtítulo del resultado. */
@Composable
private fun Pill(
    text: String,
    background: androidx.compose.ui.graphics.Color,
    foreground: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    onCancel: () -> Unit = {},
    qualityLabel: String? = null,
    onLongPress: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Tapping a result streams it (listen first); download is the explicit icon.
            // Long-press on a YouTube result offers splitting by chapters.
            .then(
                if (onLongPress != null) Modifier.combinedClickable(
                    onClick = onPreview,
                    onLongClick = onLongPress,
                ) else Modifier.clickable { onPreview() }
            )
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
            val isAlbum = result.isTorrent || result.isArchive
            // Álbum (torrent/archive): sin duración fiable, muestra sólo el artista.
            val subtitle = if (isAlbum) result.artist
            else "${result.artist} · ${formatDuration(result.durationSec)}"
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isAlbum) {
                    Pill(
                        if (result.isTorrent) "TORRENT" else "ARCHIVE",
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                // Calidad que se descargará (FLAC / MP3 320 / ≤160k…).
                qualityLabel?.let {
                    Pill(
                        it,
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(start = if (isAlbum) 4.dp else 0.dp),
                    )
                }
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = if (isAlbum || qualityLabel != null) 6.dp else 0.dp),
                )
            }
        }
        // Preview: listen before downloading. Torrents have no stream; Archive
        // streams its first track (its files are open HTTP).
        if (!result.isTorrent) when {
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
                IconButton(onClick = onCancel) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Icon(Icons.Filled.Close, "Cancelar", modifier = Modifier.size(14.dp))
                    }
                }
            state is DownloadState.Downloading ->
                IconButton(onClick = onCancel) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                        Icon(Icons.Filled.Close, "Cancelar", modifier = Modifier.size(14.dp))
                    }
                }
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
