package com.aar.privatemusic.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.SmartPlaylist
import com.aar.privatemusic.data.db.openMusicDatabase
import com.aar.privatemusic.desktop.DesktopSettings
import com.aar.privatemusic.desktop.DesktopStorage
import com.aar.privatemusic.desktop.audio.AudioEngine
import com.aar.privatemusic.desktop.audio.VlcAudioEngine
import com.aar.privatemusic.desktop.downloader.DesktopDeezerAccount
import com.aar.privatemusic.desktop.downloader.DesktopDownloaderEnv
import com.aar.privatemusic.desktop.downloader.DesktopFeedback
import com.aar.privatemusic.desktop.downloader.YtDlpDownloader
import com.aar.privatemusic.desktop.lyrics.LyricsManager
import com.aar.privatemusic.desktop.player.DesktopPlayer
import com.aar.privatemusic.desktop.sync.Phone
import com.aar.privatemusic.desktop.sync.SHARE_PORT
import com.aar.privatemusic.desktop.sync.PhoneDiscovery
import com.aar.privatemusic.desktop.sync.SyncClient
import com.aar.privatemusic.desktop.sync.SyncResult
import com.aar.privatemusic.desktop.update.DesktopUpdater
import com.aar.privatemusic.downloader.DeezerDownloader
import com.aar.privatemusic.downloader.InternetArchiveDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * Lo que se ve en el área principal. Los destinos de la barra lateral son las
 * raíces; artista, álbum y playlist se apilan encima y vuelven con Atrás.
 */
private sealed interface View {
    data class Root(val destination: Destination) : View
    data class ArtistView(val name: String) : View
    data class AlbumView(val name: String) : View
    data class PlaylistView(val playlist: Playlist) : View
    data class SmartView(val smart: SmartPlaylist) : View
}

@Composable
fun App(shortcuts: KeyShortcuts) {
    // libVLC es una biblioteca nativa del sistema. Si no está, la app no puede
    // sonar — pero tiene que decirlo, no reventar con un volcado de la JVM.
    val engine: AudioEngine? = remember { runCatching { VlcAudioEngine() }.getOrNull() }
    if (engine == null) {
        EmptyState(
            "No se encuentra VLC",
            "PrivateMusic usa libVLC para reproducir. Instala VLC y vuelve a abrir.",
        )
        return
    }
    DisposableEffect(Unit) { onDispose { engine.release() } }

    val database = remember { openMusicDatabase(DesktopStorage.dataDir) }
    val dao = remember { database.musicDao() }
    val settings = remember { DesktopSettings() }
    val player = remember { DesktopPlayer(engine, dao, settings) }
    // El ecualizador guardado tiene que sonar desde la primera canción.
    remember {
        engine.setEqualizer(
            settings.eqEnabled.value,
            settings.eqPreamp.value,
            FloatArray(engine.equalizerBands.size) { settings.eqAmps.value.getOrElse(it) { 0f } },
        )
    }
    val sync = remember { SyncClient(dao, DesktopStorage.musicDir, DesktopStorage.artDir) }
    val scope = rememberCoroutineScope()

    // Las descargas sobreviven a cualquier recomposición y a cambiar de pestaña:
    // su ámbito es la aplicación, no la interfaz.
    val appScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val downloaderEnv = remember { DesktopDownloaderEnv() }
    val yt = remember { YtDlpDownloader(downloaderEnv, dao, appScope, DesktopStorage.binDir) }
    val deezer = remember {
        DeezerDownloader(downloaderEnv, DesktopDeezerAccount(settings), dao, appScope)
    }
    val archive = remember { InternetArchiveDownloader(downloaderEnv, dao, appScope) }
    // Busca la letra en cuanto cambia la canción, se esté mirando la pestaña o no.
    val lyricsManager = remember { LyricsManager(DesktopStorage.musicDir, player.current, appScope) }
    DisposableEffect(Unit) { onDispose { appScope.cancel() } }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        DesktopFeedback.messages.collect { snackbar.showSnackbar(it) }
    }

    val songs by dao.observeSongs().collectAsState(emptyList())
    val current by player.current.collectAsState()
    val preview by player.preview.collectAsState()
    val lyricsState by lyricsManager.state.collectAsState()

    // La canción que suena viene de una consulta vieja: cuando cambia en la
    // base (marcarla favorita), hay que refrescar la copia del reproductor.
    LaunchedEffect(songs) { player.refresh(songs) }

    var view by remember { mutableStateOf<View>(View.Root(Destination.INICIO)) }
    val back = remember { mutableStateListOf<View>() }
    var sidebarExpanded by remember { mutableStateOf(true) }
    val playlists by dao.observePlaylists().collectAsState(emptyList())
    // Las inteligentes no guardan canciones: guardan una regla que se evalúa aquí,
    // y necesita saber cuántas veces y cuándo se escuchó cada canción.
    val smartPlaylists by dao.observeSmartPlaylists().collectAsState(emptyList())
    val playCounts by dao.observePlayCounts().collectAsState(emptyList())
    val lastPlayed by dao.observeLastPlayed().collectAsState(emptyList())
    val densityName by settings.rowDensity.collectAsState()
    val density = remember(densityName) {
        runCatching { RowDensity.valueOf(densityName) }.getOrDefault(RowDensity.NORMAL)
    }

    fun go(destination: View) {
        // Cambiar de raíz no es "entrar": vacía la pila para que Atrás no
        // devuelva a un artista que ya no viene a cuento.
        if (destination is View.Root) back.clear() else back.add(view)
        view = destination
    }

    fun goBack() {
        if (back.isNotEmpty()) view = back.removeAt(back.lastIndex)
    }

    val actions = SongActions(
        onAddToQueue = player::addToQueue,
        onGoToArtist = { go(View.ArtistView(it.artist.trim().ifBlank { "Desconocido" })) },
        onGoToAlbum = { song -> song.album?.let { go(View.AlbumView(it.trim())) } },
    )

    var panelOpen by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<DesktopUpdater.UpdateInfo?>(null) }

    LaunchedEffect(Unit) { update = DesktopUpdater.check() }


    /** Lo que ha llegado, en una línea. Callar el historial sería esconderlo. */
    fun summaryOf(r: SyncResult): String = buildList {
        add("${r.songsAdded} canciones")
        if (r.filesDownloaded > 0) add("${r.filesDownloaded} ficheros nuevos")
        if (r.playlists > 0) add("${r.playlists} playlists")
        if (r.smartPlaylists > 0) add("${r.smartPlaylists} inteligentes")
        if (r.playEvents > 0) add("${r.playEvents} escuchas")
    }.joinToString(" · ")

    /** Acepta "192.168.1.152" o "192.168.1.152:8966". Sin puerto, el que publica el móvil. */
    fun runSyncAddress(input: String) {
        if (syncing || input.isBlank()) return
        val host = input.substringBefore(':').trim()
        val port = input.substringAfter(':', "").trim().toIntOrNull() ?: SHARE_PORT
        scope.launch {
            syncing = true
            syncStatus = "Conectando con $host:$port…"
            runCatching { sync.sync(Phone(host, host, port)) { syncStatus = it } }
                .onSuccess { syncStatus = summaryOf(it) }
                .onFailure { syncStatus = "Falló: ${it.message}" }
            syncing = false
        }
    }

    fun runSync() {
        if (syncing) return
        scope.launch {
            syncing = true
            syncStatus = "Buscando el móvil en la red…"
            val phone = PhoneDiscovery.discover().firstOrNull()
            if (phone == null) {
                syncStatus = "No se encontró ningún móvil. Si tu red bloquea el multicast, " +
                    "escribe su dirección a mano."
            } else {
                runCatching { sync.sync(phone) { syncStatus = it } }
                    .onSuccess { syncStatus = summaryOf(it) }
                    .onFailure { syncStatus = "Falló: ${it.message}" }
            }
            syncing = false
        }
    }

    // Los atajos se registran en la ventana (ver KeyShortcuts): un Modifier
    // deja de recibir teclas en cuanto el foco se va a un botón.
    SideEffect {
        shortcuts.handler = handler@{ event ->
            if (event.type != KeyEventType.KeyDown) return@handler false
            when {
                event.key == Key.Spacebar -> { player.togglePlayPause(); true }
                event.key == Key.DirectionRight && event.isCtrlPressed -> { player.next(); true }
                event.key == Key.DirectionLeft && event.isCtrlPressed -> { player.previous(); true }
                event.key == Key.Escape && panelOpen -> { panelOpen = false; true }
                event.key == Key.Escape && back.isNotEmpty() -> { goBack(); true }
                event.key == Key.F && event.isCtrlPressed -> { go(View.Root(Destination.BIBLIOTECA)); true }
                event.key == Key.F -> { player.toggleFavorite(); true }
                else -> false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                Sidebar(
                    expanded = sidebarExpanded,
                    onToggle = { sidebarExpanded = !sidebarExpanded },
                    selected = (view as? View.Root)?.destination,
                    onSelect = { go(View.Root(it)) },
                    playlists = playlists,
                    openPlaylistId = (view as? View.PlaylistView)?.playlist?.id,
                    onOpenPlaylist = { go(View.PlaylistView(it)) },
                    smartPlaylists = smartPlaylists,
                    openSmartId = (view as? View.SmartView)?.smart?.id,
                    onOpenSmart = { go(View.SmartView(it)) },
                )

                Column(Modifier.weight(1f)) {
                    // Sólo hay atrás cuando se ha entrado en algo: una flecha muerta
                    // permanente enseña a ignorarla.
                    if (back.isNotEmpty()) {
                        Row(Modifier.padding(start = 12.dp, top = 8.dp)) {
                            IconButton(::goBack) { Icon(Icons.Filled.ArrowBack, "Atrás") }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        when (val v = view) {
                            is View.Root -> when (v.destination) {
                                Destination.INICIO -> HomeScreen(songs, player, ::runSync, syncing)
                                Destination.BUSCAR -> SearchScreen(
                                    yt = yt,
                                    deezer = deezer,
                                    archive = archive,
                                    settings = settings,
                                    player = player,
                                    songs = songs,
                                    onMessage = { scope.launch { snackbar.showSnackbar(it) } },
                                )
                                Destination.BIBLIOTECA -> LibraryScreen(songs, player, current?.id, density, actions)
                                Destination.ARTISTAS -> ArtistsScreen(songs) { go(View.ArtistView(it)) }
                                Destination.ALBUMES -> AlbumsScreen(songs) { go(View.AlbumView(it)) }
                                Destination.FAVORITAS -> FavoritesScreen(
                                    songs, density, current?.id,
                                    onPlay = player::playQueue,
                                    onToggleFavorite = player::toggleFavoriteOf,
                                    actions = actions,
                                )
                                Destination.AJUSTES -> SettingsScreen(
                                    songs = songs,
                                    settings = settings,
                                    engine = engine,
                                    syncing = syncing,
                                    syncStatus = syncStatus,
                                    onSync = ::runSync,
                                    onSyncAddress = ::runSyncAddress,
                                    update = update,
                                    onUpdate = {
                                        scope.launch {
                                            val info = update ?: return@launch
                                            runCatching {
                                                val file = DesktopUpdater.download(info) { syncStatus = "Descargando… $it %" }
                                                syncStatus = "Instalando…"
                                                if (DesktopUpdater.install(file)) exitProcess(0)
                                                syncStatus = "No se pudo instalar"
                                            }.onFailure { syncStatus = "Falló la actualización: ${it.message}" }
                                        }
                                    },
                                )
                            }

                            is View.ArtistView -> {
                                val group = remember(songs, v.name) {
                                    artistsOf(songs).firstOrNull { it.name == v.name }
                                }
                                if (group == null) EmptyState("Artista vacío", "Ya no tiene canciones")
                                else GroupingDetail(
                                    group, round = true, density = density, currentId = current?.id,
                                    onPlay = player::playQueue,
                                    onShuffle = player::playShuffled,
                                    onToggleFavorite = player::toggleFavoriteOf,
                                    actions = actions,
                                )
                            }

                            is View.AlbumView -> {
                                val group = remember(songs, v.name) {
                                    albumsOf(songs).firstOrNull { it.name == v.name }
                                }
                                if (group == null) EmptyState("Álbum vacío", "Ya no tiene canciones")
                                else GroupingDetail(
                                    group, round = false, density = density, currentId = current?.id,
                                    onPlay = player::playQueue,
                                    onShuffle = player::playShuffled,
                                    onToggleFavorite = player::toggleFavoriteOf,
                                    actions = actions,
                                )
                            }

                            is View.PlaylistView ->
                                PlaylistDetail(v.playlist, dao, player, current?.id, density, actions)

                            is View.SmartView -> SmartPlaylistDetail(
                                smart = v.smart,
                                songs = songs,
                                playCounts = playCounts,
                                lastPlayed = lastPlayed,
                                density = density,
                                currentId = current?.id,
                                onPlay = player::playQueue,
                                onShuffle = player::playShuffled,
                                onToggleFavorite = player::toggleFavoriteOf,
                                actions = actions,
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = panelOpen && current != null,
                    enter = slideInHorizontally { it },
                    exit = slideOutHorizontally { it },
                ) {
                    current?.let {
                        NowPlayingPanel(it, player, lyricsState, onClose = { panelOpen = false })
                    }
                }
            }

            StatusBar(songs, current)

            // También cuando lo que suena es una preescucha: si hay audio, hay barra.
            if (current != null || preview != null) {
                PlayerBar(current, preview, player, panelOpen, onTogglePanel = { panelOpen = !panelOpen })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }
}
