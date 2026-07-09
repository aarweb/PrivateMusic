package com.aar.privatemusic.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.data.db.openMusicDatabase
import com.aar.privatemusic.desktop.audio.AudioEngine
import com.aar.privatemusic.desktop.audio.VlcAudioEngine
import com.aar.privatemusic.desktop.sync.PhoneDiscovery
import com.aar.privatemusic.desktop.sync.SyncClient
import com.aar.privatemusic.desktop.update.DesktopUpdater
import kotlinx.coroutines.launch
import java.io.File
import kotlin.system.exitProcess

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "PrivateMusic",
        state = rememberWindowState(width = 900.dp, height = 640.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) { PlayerWindow() }
        }
    }
}

@Composable
private fun PlayerWindow() {
    // libVLC es una biblioteca nativa del sistema. Si no está, la app no puede
    // sonar — pero tiene que decirlo, no reventar con un volcado de la JVM.
    val engine: AudioEngine? = remember { runCatching { VlcAudioEngine() }.getOrNull() }
    if (engine == null) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No se encuentra VLC", style = MaterialTheme.typography.headlineSmall)
            Text(
                "PrivateMusic usa libVLC para reproducir. Instala VLC y vuelve a abrir.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    val database = remember { openMusicDatabase(DesktopStorage.dataDir) }
    val dao = remember { database.musicDao() }
    val sync = remember { SyncClient(dao, DesktopStorage.musicDir, DesktopStorage.artDir) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { onDispose { engine.release() } }

    val songs by dao.observeSongs().collectAsState(emptyList())
    var current by remember { mutableStateOf(-1) }
    var status by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<DesktopUpdater.UpdateInfo?>(null) }

    // Una sola comprobación al abrir; no molesta más.
    androidx.compose.runtime.LaunchedEffect(Unit) { update = DesktopUpdater.check() }

    fun playAt(index: Int) {
        songs.getOrNull(index)?.let { current = index; engine.play(File(it.filePath)) }
    }
    // Al terminar una pista sigue la siguiente. La cola es cosa de la interfaz,
    // no del decodificador.
    DisposableEffect(songs) {
        engine.onFinished = { if (current + 1 in songs.indices) playAt(current + 1) }
        onDispose { engine.onFinished = null }
    }

    val isPlaying by engine.isPlaying.collectAsState()
    val positionMs by engine.positionMs.collectAsState()
    val durationMs by engine.durationMs.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = status == null,
                onClick = {
                    scope.launch {
                        status = "Buscando el móvil en la red…"
                        val phone = PhoneDiscovery.discover().firstOrNull()
                        if (phone == null) {
                            status = null
                            return@launch
                        }
                        runCatching { sync.sync(phone) { status = it } }
                            .onFailure { status = "Falló: ${it.message}" }
                        status = null
                    }
                },
            ) { Text("Sincronizar con el móvil") }

            status?.let {
                Text("  $it", style = MaterialTheme.typography.labelLarge)
            }

            update?.let { info ->
                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val file = DesktopUpdater.download(info) { status = "Descargando… $it %" }
                                status = "Instalando…"
                                // Si install() tiene éxito, el script relanza la
                                // app en cuanto este proceso muera.
                                if (DesktopUpdater.install(file)) exitProcess(0)
                                status = "No se pudo instalar"
                            }.onFailure { status = "Falló la actualización: ${it.message}" }
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Actualizar a ${info.version}") }
            }
        }

        if (songs.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Enciende «Compartir con el PC» en los ajustes del móvil",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Column
        }

        LazyColumn(Modifier.weight(1f).padding(vertical = 12.dp)) {
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongRow(song, playing = index == current, onClick = { playAt(index) })
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (current > 0) playAt(current - 1) }, enabled = current > 0) {
                Text("Anterior")
            }
            Button(
                onClick = { if (isPlaying) engine.pause() else engine.resume() },
                enabled = current >= 0,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(if (isPlaying) "Pausa" else "Reproducir") }
            Button(
                onClick = { if (current + 1 in songs.indices) playAt(current + 1) },
                enabled = current + 1 in songs.indices,
            ) { Text("Siguiente") }
            Text("  ${format(positionMs)} / ${format(durationMs)}", style = MaterialTheme.typography.labelLarge)
        }

        Slider(
            value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
            onValueChange = { if (durationMs > 0) engine.seekTo((it * durationMs).toLong()) },
            enabled = durationMs > 0,
        )
    }
}

@Composable
private fun SongRow(song: Song, playing: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (playing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun format(ms: Long): String {
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
