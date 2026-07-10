package com.aar.privatemusic.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    app: PrivateMusicApp,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit = {},
    /** Ancla del elemento compartido con la carátula del mini-reproductor. */
    coverModifier: Modifier = Modifier,
) {
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

    // Dos colores de la carátula: uno apagado para el fondo y otro vivo para
    // los mandos. Se extraen juntos porque decodificar el bitmap es lo caro.
    val surface = MaterialTheme.colorScheme.surface
    val fallbackAccent = MaterialTheme.colorScheme.primary
    val cover by produceState(initialValue = surface to fallbackAccent, np.artPath) {
        value = withContext(Dispatchers.IO) {
            np.artPath?.let { path ->
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bitmap = BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
                    val palette = Palette.from(bitmap).generate()
                    val bg = palette.darkMutedSwatch?.rgb
                        ?: palette.darkVibrantSwatch?.rgb
                        ?: palette.mutedSwatch?.rgb
                    val fg = palette.vibrantSwatch?.rgb
                        ?: palette.lightVibrantSwatch?.rgb
                        ?: palette.lightMutedSwatch?.rgb
                    (bg?.let { Color(it) } ?: surface) to
                        (fg?.let { readable(Color(it), surface) } ?: fallbackAccent)
                }.getOrNull()
            } ?: (surface to fallbackAccent)
        }
    }
    val dominant = cover.first
    val accent = cover.second

    // La posición se guarda como estado, no se lee aquí: si la leyera esta
    // pantalla, el tic de medio segundo la recompondría entera, carátula incluida.
    // Quien la lee es [PositionBar], y sólo se recompone él.
    val sliderPosition = remember { mutableFloatStateOf(0f) }
    val draggingState = remember { mutableStateOf(false) }
    var sleepDialogOpen by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var playerMenuOpen by remember { mutableStateOf(false) }
    var addToPlaylistOpen by remember { mutableStateOf(false) }
    var karaokeOpen by remember { mutableStateOf(false) }
    var speedDialogOpen by remember { mutableStateOf(false) }
    var lyricShareFrom by remember { mutableStateOf<Int?>(null) }
    var castDialogOpen by remember { mutableStateOf(false) }
    var qualityExpanded by remember { mutableStateOf(false) }
    val playbackSpeed by controller.playbackSpeed.collectAsState()

    val lyrics by produceState<com.aar.privatemusic.lyrics.Lyrics?>(initialValue = null, song?.id) {
        // produceState recuerda su valor SIN clave: al cambiar de canción sólo
        // relanza el productor. Sin este borrado, la letra de la canción
        // anterior se seguía viendo -- sincronizada contra la nueva-- durante
        // toda la búsqueda, y para siempre si la nueva no tenía letra.
        value = null
        value = song?.let { s ->
            withContext(Dispatchers.IO) { runCatching { app.repository.getLyrics(s) }.getOrNull() }
        }
    }

    LaunchedEffect(isPlaying, np.songId) {
        while (true) {
            if (!draggingState.value) sliderPosition.floatValue = controller.positionMs.toFloat()
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
            // El temporizador sólo se anuncia mientras corre.
            sleepRemainingMs?.let {
                Text(
                    formatDuration((it / 1000).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
            IconButton(onClick = { castDialogOpen = true }) {
                Icon(
                    Icons.Filled.Cast,
                    contentDescription = "Enviar a TV/altavoz",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Lo demás baja aquí: siete iconos en fila no se aciertan con el pulgar.
            Box {
                IconButton(onClick = { playerMenuOpen = true }, enabled = song != null) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Más opciones")
                }
                DropdownMenu(expanded = playerMenuOpen, onDismissRequest = { playerMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Cola de reproducción") },
                        onClick = {
                            playerMenuOpen = false
                            onOpenQueue()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (sleepRemainingMs != null || stopAfterTrack) "Temporizador (activo)"
                                else "Temporizador de apagado"
                            )
                        },
                        onClick = {
                            playerMenuOpen = false
                            sleepDialogOpen = true
                        },
                    )
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

        Spacer(Modifier.weight(0.6f))
        if (showLyrics && lyrics != null) {
            LyricsPanel(
                lyrics = lyrics!!,
                positionMs = { sliderPosition.floatValue.toLong() },
                onSeek = { controller.seekTo(it) },
                accent = accent,
                onShareFrom = { lyricShareFrom = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
            )
        } else {
            // Encoge al pausar: el disco "respira" mientras suena.
            // Sin `by`: `Modifier.scale` leía el valor durante la composición y el
            // muelle recomponía la pantalla en cada fotograma. Leído dentro de
            // `graphicsLayer`, la animación no pasa de la fase de dibujo.
            val scale = animateFloatAsState(
                targetValue = if (isPlaying) 1f else 0.92f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
                label = "cover",
            )
            var dragged by remember { mutableFloatStateOf(0f) }
            Box(
                coverModifier
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    }
                    .pointerInput(np.songId) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragged <= -100f) controller.next()
                                else if (dragged >= 100f) controller.previous()
                                dragged = 0f
                            },
                            onDragCancel = { dragged = 0f },
                        ) { _, amount -> dragged += amount }
                    }
            ) {
                ArtImage(np.artPath?.let { File(it) }, 280.dp)
            }
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

        // Calidad real del fichero. Plegado dice sólo el formato; el resto de la
        // ficha técnica (bitrate, BPM, tonalidad) se despliega al tocarlo.
        song?.codec?.let { codec ->
            Spacer(Modifier.height(8.dp))
            val detail = buildList {
                song?.bitrateKbps?.let { add("$it kbps") }
                song?.sampleRateHz?.let {
                    val khz = it / 1000f
                    add(if (khz % 1f == 0f) "${khz.toInt()} kHz" else "$khz kHz")
                }
                song?.bpm?.let { add("${it.toInt()} BPM") }
                song?.camelot?.let { add(it) }
            }
            AssistChip(
                onClick = { qualityExpanded = !qualityExpanded },
                label = {
                    Text(
                        if (qualityExpanded && detail.isNotEmpty()) (listOf(codec) + detail).joinToString(" · ")
                        else codec,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }

        Spacer(Modifier.height(6.dp))
        OutputIndicator(onClick = { castDialogOpen = true })

        Spacer(Modifier.weight(1f))

        val durationMs = np.durationMs.coerceAtLeast(1)
        PositionBar(
            position = sliderPosition,
            dragging = draggingState,
            durationMs = durationMs,
            accent = accent,
            onSeek = { controller.seekTo(it) },
        )

        Spacer(Modifier.height(12.dp))

        // Acciones secundarias, al alcance del pulgar y no en el borde de arriba.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            PlayerActionChip(
                icon = if (song?.isFavorite == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                label = "Favorita",
                active = song?.isFavorite == true,
                accent = accent,
                onClick = {
                    song?.let { s -> scope.launch { app.repository.setFavorite(s.id, !s.isFavorite) } }
                },
            )
            Spacer(Modifier.width(8.dp))
            PlayerActionChip(
                icon = Icons.Filled.Lyrics,
                label = "Letra",
                // Marcado sólo si de verdad se está viendo la letra: con una
                // canción sin letra se muestra la carátula, y el chip encendido
                // prometía algo que no estaba en pantalla.
                active = showLyrics && lyrics != null,
                accent = accent,
                enabled = lyrics != null,
                onClick = { showLyrics = !showLyrics },
            )
            Spacer(Modifier.width(8.dp))
            PlayerActionChip(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = "Cola",
                active = false,
                accent = accent,
                onClick = onOpenQueue,
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.toggleShuffle() }) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = "Aleatorio",
                    tint = if (shuffle) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { controller.previous() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Anterior", modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { controller.togglePlayPause() },
                modifier = Modifier.size(72.dp),
                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                    containerColor = accent,
                    contentColor = if (accent.luminance() > 0.5f) Color.Black else Color.White,
                ),
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
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) accent
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
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

/**
 * La barra de progreso y los dos tiempos, aparte.
 *
 * Es lo único de la pantalla que cambia dos veces por segundo. Aquí dentro, esa
 * recomposición cuesta un `Slider` y dos `Text`; en la pantalla costaba la
 * carátula, la letra y los controles.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PositionBar(
    position: androidx.compose.runtime.MutableFloatState,
    dragging: androidx.compose.runtime.MutableState<Boolean>,
    durationMs: Long,
    accent: Color,
    onSeek: (Long) -> Unit,
) {
    val sliderInteractions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // Al arrastrar, el pulgar crece: te dice que lo tienes cogido, y el dedo
    // tapa menos de lo que estás buscando.
    val thumbWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (dragging.value) 8.dp else 4.dp,
        label = "thumbWidth",
    )
    val thumbHeight by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (dragging.value) 36.dp else 24.dp,
        label = "thumbHeight",
    )
    Slider(
        value = position.floatValue.coerceIn(0f, durationMs.toFloat()),
        onValueChange = {
            dragging.value = true
            position.floatValue = it
        },
        onValueChangeFinished = {
            onSeek(position.floatValue.toLong())
            dragging.value = false
        },
        valueRange = 0f..durationMs.toFloat(),
        interactionSource = sliderInteractions,
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
        ),
        thumb = {
            androidx.compose.material3.SliderDefaults.Thumb(
                interactionSource = sliderInteractions,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent),
                thumbSize = androidx.compose.ui.unit.DpSize(thumbWidth, thumbHeight),
            )
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatDuration((position.floatValue / 1000).toInt()), style = MaterialTheme.typography.labelMedium)
        Text(formatDuration((durationMs / 1000).toInt()), style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LyricsPanel(
    lyrics: com.aar.privatemusic.lyrics.Lyrics,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    accent: Color,
    onShareFrom: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // La posición se lee aquí dentro, no en el llamante: así el tic de medio
    // segundo recompone la letra y no la pantalla entera.
    val currentIdx = if (lyrics.synced) lyrics.lines.indexOfLast { it.timeMs <= positionMs() } else -1

    // Mientras haya un dedo en la letra no se desplaza sola. Antes, cada vez que
    // cambiaba la línea activa la lista se movía bajo el dedo, y el toque acababa
    // cayendo en otra línea: pulsabas un verso y saltaba a otro sitio.
    var touching by remember { mutableStateOf(false) }
    LaunchedEffect(currentIdx, touching) {
        if (currentIdx < 0 || touching) return@LaunchedEffect
        // La espera no es cosmética. Cuando la lista se desplaza, el gesto de
        // scroll cancela la pulsación de la línea que hay debajo del dedo, y el
        // toque se pierde. Con `touching` a secas no basta: el cambio de verso
        // puede caer en el mismo fotograma que el dedo, antes de que el efecto
        // se entere. Esperando, cualquier toque en curso da tiempo a completarse
        // y, al empezar, relanza este efecto y cancela el desplazamiento.
        delay(300)
        if (!touching) listState.animateScrollToItem(maxOf(0, currentIdx - 2))
    }

    LazyColumn(
        state = listState,
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    touching = event.changes.any { it.pressed }
                }
            }
        },
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            // La línea que suena manda; las de alrededor se apagan según lo
            // lejos que queden. Con la letra sin sincronizar no hay "ahora",
            // así que se leen todas por igual.
            val distance = if (currentIdx < 0) 0 else kotlin.math.abs(i - currentIdx)
            val active = currentIdx >= 0 && distance == 0
            val alpha by animateFloatAsState(
                targetValue = when {
                    currentIdx < 0 -> 0.9f
                    distance == 0 -> 1f
                    distance == 1 -> 0.55f
                    distance == 2 -> 0.38f
                    else -> 0.25f
                },
                label = "lyricAlpha",
            )
            val scale by animateFloatAsState(
                targetValue = if (active) 1.12f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
                label = "lyricScale",
            )
            Text(
                line.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                textAlign = TextAlign.Center,
                // El mismo color de la portada que el resto de los mandos: si no,
                // la línea que suena es la única cosa azul en una pantalla amarilla.
                color = if (active) accent else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                    }
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

/**
 * Sube el color de la carátula hasta que se lea sobre [background].
 *
 * El color vivo de una portada puede ser un azul marino casi negro: bonito en
 * el disco, ilegible en un slider sobre fondo oscuro. Se aclara (o se oscurece,
 * en tema claro) hasta pasar el 4,5:1 de la norma, o se deja de intentar.
 */
private fun readable(color: Color, background: Color): Color {
    fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return if (la > lb) la / lb else lb / la
    }
    val towards = if (background.luminance() < 0.5f) Color.White else Color.Black
    var result = color
    var mix = 0f
    while (contrast(result, background) < 4.5 && mix < 0.9f) {
        mix += 0.1f
        result = androidx.compose.ui.graphics.lerp(color, towards, mix)
    }
    return result
}

/** Acción secundaria del reproductor: se tiñe con el color de la carátula al estar activa. */
@Composable
private fun PlayerActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    androidx.compose.material3.FilterChip(
        selected = active,
        enabled = enabled,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = accent.copy(alpha = 0.22f),
            selectedLabelColor = accent,
            selectedLeadingIconColor = accent,
        ),
    )
}
