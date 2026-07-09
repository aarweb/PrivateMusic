package com.aar.privatemusic.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.aar.privatemusic.data.db.openMusicDatabase
import com.aar.privatemusic.desktop.DesktopStorage
import com.aar.privatemusic.desktop.audio.AudioEngine
import com.aar.privatemusic.desktop.audio.VlcAudioEngine
import com.aar.privatemusic.desktop.player.DesktopPlayer
import com.aar.privatemusic.desktop.sync.PhoneDiscovery
import com.aar.privatemusic.desktop.sync.SyncClient
import com.aar.privatemusic.desktop.update.DesktopUpdater
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

private enum class Tab(val label: String, val icon: ImageVector) {
    INICIO("Inicio", Icons.Filled.Home),
    BIBLIOTECA("Biblioteca", Icons.Filled.LibraryMusic),
    PLAYLISTS("Playlists", Icons.Filled.QueueMusic),
    AJUSTES("Ajustes", Icons.Filled.Settings),
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
    val player = remember { DesktopPlayer(engine, dao) }
    val sync = remember { SyncClient(dao, DesktopStorage.musicDir, DesktopStorage.artDir) }
    val scope = rememberCoroutineScope()

    val songs by dao.observeSongs().collectAsState(emptyList())
    val current by player.current.collectAsState()

    // La canción que suena viene de una consulta vieja: cuando cambia en la
    // base (marcarla favorita), hay que refrescar la copia del reproductor.
    LaunchedEffect(songs) { player.refresh(songs) }

    var tab by remember { mutableStateOf(Tab.INICIO) }
    var panelOpen by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<DesktopUpdater.UpdateInfo?>(null) }

    LaunchedEffect(Unit) { update = DesktopUpdater.check() }

    fun runSync() {
        if (syncing) return
        scope.launch {
            syncing = true
            syncStatus = "Buscando el móvil en la red…"
            val phone = PhoneDiscovery.discover().firstOrNull()
            if (phone == null) {
                syncStatus = "No se encontró ningún móvil compartiendo"
            } else {
                runCatching { sync.sync(phone) { syncStatus = it } }
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
                event.key == Key.F && event.isCtrlPressed -> { tab = Tab.BIBLIOTECA; true }
                event.key == Key.F -> { player.toggleFavorite(); true }
                else -> false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            NavigationRail {
                Tab.entries.forEach { entry ->
                    NavigationRailItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }

            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.INICIO -> HomeScreen(songs, player, ::runSync, syncing)
                    Tab.BIBLIOTECA -> LibraryScreen(songs, player, current?.id)
                    Tab.PLAYLISTS -> PlaylistsScreen(dao, player, current?.id)
                    Tab.AJUSTES -> SettingsScreen(
                        songs = songs,
                        syncing = syncing,
                        syncStatus = syncStatus,
                        onSync = ::runSync,
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
            }

            AnimatedVisibility(
                visible = panelOpen && current != null,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
            ) {
                current?.let { NowPlayingPanel(it, onClose = { panelOpen = false }) }
            }
        }

        current?.let { song ->
            PlayerBar(song, player, panelOpen, onTogglePanel = { panelOpen = !panelOpen })
        }
    }
}
