package com.aar.privatemusic.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import com.aar.privatemusic.PrivateMusicApp
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.aar.privatemusic.ui.components.AddToPlaylistDialog
import com.aar.privatemusic.ui.components.ArtImage
import com.aar.privatemusic.ui.components.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PlayerScreen(app: PrivateMusicApp, onBack: () -> Unit, onOpenQueue: () -> Unit = {}) {
    val controller = app.playerController
    val nowPlaying by controller.nowPlaying.collectAsState()
    val isPlaying by controller.isPlaying.collectAsState()
    val shuffle by controller.shuffle.collectAsState()
    val repeatMode by controller.repeatMode.collectAsState()
    val sleepRemainingMs by controller.sleepRemainingMs.collectAsState()
    val stopAfterTrack by controller.stopAfterTrack.collectAsState()
    val scope = rememberCoroutineScope()

    val np = nowPlaying ?: run { onBack(); return }

    val song by remember(np.songId) { app.repository.observeSong(np.songId) }
        .collectAsState(initial = null)

    // Dominant colour extracted from the cover for the dynamic background.
    val surface = MaterialTheme.colorScheme.surface
    val dominant by produceState(initialValue = surface, np.artPath) {
        value = withContext(Dispatchers.IO) {
            np.artPath?.let { path ->
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bitmap = BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
                    val palette = Palette.from(bitmap).generate()
                    val rgb = palette.darkMutedSwatch?.rgb
                        ?: palette.darkVibrantSwatch?.rgb
                        ?: palette.mutedSwatch?.rgb
                    rgb?.let { Color(it) }
                }.getOrNull()
            } ?: surface
        }
    }

    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var sleepDialogOpen by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var playerMenuOpen by remember { mutableStateOf(false) }
    var addToPlaylistOpen by remember { mutableStateOf(false) }
    var karaokeOpen by remember { mutableStateOf(false) }
    var speedDialogOpen by remember { mutableStateOf(false) }
    var lyricShareFrom by remember { mutableStateOf<Int?>(null) }
    var castDialogOpen by remember { mutableStateOf(false) }
    val playbackSpeed by controller.playbackSpeed.collectAsState()

    val lyrics by produceState<com.aar.privatemusic.lyrics.Lyrics?>(initialValue = null, song?.id) {
        value = song?.let { s ->
            withContext(Dispatchers.IO) { runCatching { app.repository.getLyrics(s) }.getOrNull() }
        }
    }

    LaunchedEffect(isPlaying, np.songId) {
        while (true) {
            if (!dragging) sliderPosition = controller.positionMs.toFloat()
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(dominant, surface)))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Cerrar")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { castDialogOpen = true }) {
                Icon(
                    Icons.Filled.Cast,
                    contentDescription = "Enviar a TV/altavoz",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenQueue) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Cola de reproducción",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showLyrics = !showLyrics }, enabled = lyrics != null) {
                Icon(
                    Icons.Filled.Lyrics,
                    contentDescription = "Letra",
                    tint = if (showLyrics) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Sleep timer with remaining time when active
            Row(verticalAlignment = Alignment.CenterVertically) {
                val sleepActive = sleepRemainingMs != null || stopAfterTrack
                sleepRemainingMs?.let {
                    Text(
                        formatDuration((it / 1000).toInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = { sleepDialogOpen = true }) {
                    Icon(
                        Icons.Filled.Bedtime,
                        contentDescription = "Temporizador de apagado",
                        tint = if (sleepActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = {
                song?.let { s ->
                    scope.launch { app.repository.setFavorite(s.id, !s.isFavorite) }
                }
            }) {
                Icon(
                    if (song?.isFavorite == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "Favorita",
                    tint = if (song?.isFavorite == true) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Actions on the playing song without leaving the player.
            Box {
                IconButton(onClick = { playerMenuOpen = true }, enabled = song != null) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Más opciones")
                }
                DropdownMenu(expanded = playerMenuOpen, onDismissRequest = { playerMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Añadir a playlist") },
                        onClick = {
                            playerMenuOpen = false
                            addToPlaylistOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Radio de esta canción") },
                        onClick = {
                            playerMenuOpen = false
                            song?.let { s ->
                                scope.launch {
                                    val radio = app.repository.radioFor(s)
                                    if (radio.size > 1) {
                                        controller.playQueue(radio, 0)
                                        com.aar.privatemusic.util.Feedback.show("Radio de \"${s.title}\" en marcha")
                                    } else {
                                        com.aar.privatemusic.util.Feedback.show("Aún no hay suficientes canciones analizadas")
                                    }
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (playbackSpeed == 1f) "Velocidad" else "Velocidad (${playbackSpeed}x)") },
                        onClick = {
                            playerMenuOpen = false
                            speedDialogOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Karaoke (quitar la voz)") },
                        onClick = {
                            playerMenuOpen = false
                            karaokeOpen = true
                        },
                    )
                }
            }
        }

        if (addToPlaylistOpen) {
            val playlists by app.repository.observePlaylists().collectAsState(initial = emptyList())
            song?.let { s ->
                AddToPlaylistDialog(
                    playlists = playlists,
                    onSelect = { pl ->
                        scope.launch { app.repository.addToPlaylist(pl.id, s.id) }
                        com.aar.privatemusic.util.Feedback.show("Añadida a \"${pl.name}\"")
                        addToPlaylistOpen = false
                    },
                    onCreateAndSelect = { name ->
                        scope.launch {
                            val plId = app.repository.createPlaylist(name)
                            app.repository.addToPlaylist(plId, s.id)
                        }
                        com.aar.privatemusic.util.Feedback.show("Creada \"$name\" con la canción")
                        addToPlaylistOpen = false
                    },
                    onDismiss = { addToPlaylistOpen = false },
                )
            }
        }
        if (karaokeOpen) {
            song?.let { s -> KaraokeDialog(app, s, onDismiss = { karaokeOpen = false }) }
        }
        lyricShareFrom?.let { fromIdx ->
            val allLines = lyrics?.lines ?: emptyList()
            val candidates = allLines.drop(fromIdx).take(4).map { it.text }
            val checked = remember(fromIdx) {
                androidx.compose.runtime.mutableStateListOf(*Array(candidates.size) { it < 2 })
            }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            AlertDialog(
                onDismissRequest = { lyricShareFrom = null },
                title = { Text("Compartir letra") },
                text = {
                    Column {
                        Text(
                            "Elige hasta 4 líneas para la tarjeta:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        candidates.forEachIndexed { i, lineText ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Checkbox(
                                    checked = checked[i],
                                    onCheckedChange = { checked[i] = it },
                                )
                                Text(lineText, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val chosen = candidates.filterIndexed { i, _ -> checked[i] }.filter { it.isNotBlank() }
                        val s0 = song
                        if (chosen.isNotEmpty() && s0 != null) {
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    val f = com.aar.privatemusic.util.LyricCard.render(ctx, s0, chosen)
                                    withContext(Dispatchers.Main) {
                                        com.aar.privatemusic.util.LyricCard.share(ctx, f)
                                    }
                                }
                            }
                        }
                        lyricShareFrom = null
                    }) { Text("Crear tarjeta") }
                },
                dismissButton = { TextButton(onClick = { lyricShareFrom = null }) { Text("Cancelar") } },
            )
        }

        if (castDialogOpen) {
            com.aar.privatemusic.cast.CastRouteDialog(onDismiss = { castDialogOpen = false })
        }
        if (speedDialogOpen) {
            AlertDialog(
                onDismissRequest = { speedDialogOpen = false },
                title = { Text("Velocidad de reproducción") },
                text = {
                    Column {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            Text(
                                if (speed == 1f) "1x (normal)" else "${speed}x",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (playbackSpeed == speed) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        controller.setPlaybackSpeed(speed)
                                        speedDialogOpen = false
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                        Text(
                            "El tono no cambia (time-stretch).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { speedDialogOpen = false }) { Text("Cerrar") } },
            )
        }

        Spacer(Modifier.height(16.dp))
        if (showLyrics && lyrics != null) {
            LyricsPanel(
                lyrics = lyrics!!,
                positionMs = sliderPosition.toLong(),
                onSeek = { controller.seekTo(it) },
                onShareFrom = { lyricShareFrom = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
            )
        } else {
            ArtImage(np.artPath?.let { File(it) }, 280.dp)
        }
        Spacer(Modifier.height(24.dp))

        Text(
            np.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            np.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Real quality of the local file, Amazon-style badge.
        song?.codec?.let { codec ->
            Spacer(Modifier.height(8.dp))
            val parts = buildList {
                add(codec)
                song?.bitrateKbps?.let { add("$it kbps") }
                song?.sampleRateHz?.let {
                    val khz = it / 1000f
                    add(if (khz % 1f == 0f) "${khz.toInt()} kHz" else "$khz kHz")
                }
                song?.bpm?.let { add("${it.toInt()} BPM") }
                song?.camelot?.let { add(it) }
            }
            AssistChip(
                onClick = {},
                label = { Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium) },
            )
        }

        Spacer(Modifier.height(6.dp))
        OutputIndicator(onClick = { castDialogOpen = true })

        Spacer(Modifier.height(10.dp))

        val durationMs = np.durationMs.coerceAtLeast(1)
        Slider(
            value = sliderPosition.coerceIn(0f, durationMs.toFloat()),
            onValueChange = {
                dragging = true
                sliderPosition = it
            },
            onValueChangeFinished = {
                controller.seekTo(sliderPosition.toLong())
                dragging = false
            },
            valueRange = 0f..durationMs.toFloat(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration((sliderPosition / 1000).toInt()), style = MaterialTheme.typography.labelMedium)
            Text(formatDuration((durationMs / 1000).toInt()), style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.toggleShuffle() }) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = "Aleatorio",
                    tint = if (shuffle) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { controller.previous() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Anterior", modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { controller.togglePlayPause() },
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/Pausa",
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = { controller.next() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Siguiente", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { controller.cycleRepeatMode() }) {
                Icon(
                    if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Repetir",
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (sleepDialogOpen) {
        AlertDialog(
            onDismissRequest = { sleepDialogOpen = false },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { sleepDialogOpen = false }) { Text("Cerrar") } },
            title = { Text("Temporizador de apagado") },
            text = {
                Column {
                    listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
                        Text(
                            "$minutes minutos",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    controller.startSleepTimer(minutes)
                                    sleepDialogOpen = false
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                    Text(
                        if (stopAfterTrack) "✓ Al acabar la canción" else "Al acabar la canción",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (stopAfterTrack) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                controller.toggleStopAfterTrack()
                                sleepDialogOpen = false
                            }
                            .padding(vertical = 10.dp),
                    )
                    if (sleepRemainingMs != null) {
                        Text(
                            "Cancelar temporizador",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    controller.cancelSleepTimer()
                                    sleepDialogOpen = false
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LyricsPanel(
    lyrics: com.aar.privatemusic.lyrics.Lyrics,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onShareFrom: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val currentIdx = if (lyrics.synced) lyrics.lines.indexOfLast { it.timeMs <= positionMs } else -1

    LaunchedEffect(currentIdx) {
        if (currentIdx >= 0) listState.animateScrollToItem(maxOf(0, currentIdx - 3))
    }

    LazyColumn(state = listState, modifier = modifier) {
        itemsIndexed(lyrics.lines) { i, line ->
            Text(
                line.text,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = if (i == currentIdx) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (lyrics.synced) onSeek(line.timeMs) },
                        onLongClick = { onShareFrom(i) },
                    )
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/**
 * Where the audio is coming out right now: cast device, headphones or the
 * phone speaker. Tapping opens the cast picker.
 */
@Composable
private fun OutputIndicator(onClick: () -> Unit) {
    val castName by com.aar.privatemusic.cast.CastState.castDeviceName.collectAsState()
    val context = LocalContext.current
    var localOutput by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    DisposableEffect(Unit) {
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        fun refresh() {
            val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            val headset = devices.firstOrNull {
                it.type in intArrayOf(
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_HEARING_AID,
                )
            }
            localOutput = if (headset != null) {
                true to (headset.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Auriculares")
            } else {
                false to "Altavoz del móvil"
            }
        }
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) = refresh()
        }
        am.registerAudioDeviceCallback(callback, null)
        refresh()
        onDispose { am.unregisterAudioDeviceCallback(callback) }
    }

    val (icon, label, highlight) = when {
        castName != null -> Triple(Icons.Filled.CastConnected, castName!!, true)
        localOutput?.first == true -> Triple(Icons.Filled.Headset, localOutput!!.second, false)
        else -> Triple(Icons.Filled.PhoneAndroid, localOutput?.second ?: "Altavoz del móvil", false)
    }
    val tint = if (highlight) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}
