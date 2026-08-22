package com.aar.privatemusic.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.lerp
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Style
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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

    val djState by app.dj.state.collectAsState()
    var djRequestOpen by remember { mutableStateOf(false) }

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
    var styleMenuOpen by remember { mutableStateOf(false) }
    var castDialogOpen by remember { mutableStateOf(false) }
    var qualityExpanded by remember { mutableStateOf(false) }
    val playbackSpeed by controller.playbackSpeed.collectAsState()
    val pitchSemitones by controller.pitchSemitones.collectAsState()

    // Resultado de "Sincronizar letra": sustituye a la plana hasta cambiar de
    // canción (en disco ya queda guardada, así que al volver se lee sola).
    var syncedOverride by remember(song?.id) {
        mutableStateOf<com.aar.privatemusic.lyrics.Lyrics?>(null)
    }
    val context = LocalContext.current

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
                        text = {
                            val state = tempoPitchLabel(playbackSpeed, pitchSemitones)
                            Text(if (state == null) "Tempo y tono" else "Tempo y tono ($state)")
                        },
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

        if (djRequestOpen) {
            var req by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { djRequestOpen = false },
                title = { Text("Pídele al DJ") },
                text = {
                    Column {
                        Text(
                            "Dile qué quieres y re-secuencia lo que viene: “algo más animado”, “menos lento”, “más de los 90”…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = req,
                            onValueChange = { req = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            placeholder = { Text("algo más animado") },
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = req.isNotBlank(),
                        onClick = { app.dj.request(req.trim()); djRequestOpen = false },
                    ) { Text("Marchando") }
                },
                dismissButton = { TextButton(onClick = { djRequestOpen = false }) { Text("Cancelar") } },
            )
        }

        if (castDialogOpen) {
            com.aar.privatemusic.cast.CastRouteDialog(onDismiss = { castDialogOpen = false })
        }
        if (speedDialogOpen) {
            TempoPitchDialog(
                speed = playbackSpeed,
                semitones = pitchSemitones,
                onSpeed = { controller.setPlaybackSpeed(it) },
                onSemitones = { controller.setPitchSemitones(it) },
                onReset = { controller.resetPlaybackParameters() },
                onDismiss = { speedDialogOpen = false },
            )
        }

        if (djState.active && djState.line != null) {
            Surface(
                color = accent.copy(alpha = 0.16f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = "DJ", tint = accent)
                    Text(
                        djState.line ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    TextButton(onClick = { djRequestOpen = true }) { Text("Pedir") }
                }
            }
        }

        Spacer(Modifier.weight(0.6f))
        // La letra recién sincronizada manda sobre la plana que se cargó al
        // entrar; se olvida al cambiar de canción.
        val effectiveLyrics = syncedOverride ?: lyrics
        if (showLyrics && effectiveLyrics != null) {
            val base = effectiveLyrics
            // Letras en hangul, kana, cirílico…: un toggle para leerlas en latino.
            val romanize by app.settings.romanizeLyrics.collectAsState()
            val lyricStyle by app.settings.lyricStyle.collectAsState()
            val foreign = remember(base) { com.aar.privatemusic.lyrics.Romanizer.needsRomanization(base) }
            val shown by produceState(initialValue = base, base, romanize, foreign) {
                value = if (foreign && romanize) {
                    withContext(Dispatchers.Default) { com.aar.privatemusic.lyrics.Romanizer.romanize(base) }
                } else base
            }
            // Letra sin tiempos: se puede cuadrar con la voz de la canción.
            val syncState by com.aar.privatemusic.util.LyricsSyncManager
                .stateFor(song?.id.orEmpty()).collectAsState()
            LaunchedEffect(syncState.lyrics, song?.id) {
                // Al terminar, la letra recién sincronizada sustituye a la plana.
                val generated = syncState.lyrics ?: return@LaunchedEffect
                syncedOverride = generated
                song?.id?.let { com.aar.privatemusic.util.LyricsSyncManager.clear(it) }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    androidx.compose.material3.AssistChip(
                        onClick = { styleMenuOpen = true },
                        leadingIcon = {
                            Icon(Icons.Filled.Style, contentDescription = null, tint = accent)
                        },
                        label = { Text(lyricStyle.label) },
                    )
                    DropdownMenu(
                        expanded = styleMenuOpen,
                        onDismissRequest = { styleMenuOpen = false },
                    ) {
                        com.aar.privatemusic.data.LyricStyle.entries.forEach { st ->
                            DropdownMenuItem(
                                text = { Text(st.label) },
                                trailingIcon = {
                                    if (st == lyricStyle) Icon(Icons.Filled.Check, contentDescription = null)
                                },
                                onClick = {
                                    app.settings.setLyricStyle(st)
                                    styleMenuOpen = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (foreign) {
                    androidx.compose.material3.FilterChip(
                        selected = romanize,
                        onClick = { app.settings.setRomanizeLyrics(!romanize) },
                        label = { Text(if (romanize) "Romanizado" else "Romanizar") },
                    )
                }
                val songNow = song
                if (!shown.synced && songNow != null) {
                    if (foreign) Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.AssistChip(
                        onClick = {
                            if (!syncState.running) {
                                com.aar.privatemusic.util.LyricsSyncManager.start(
                                    context, songNow, app.repository.musicDir, shown.lines.map { it.text },
                                )
                            }
                        },
                        enabled = !syncState.running,
                        label = { Text(if (syncState.running) "Sincronizando…" else "Sincronizar letra") },
                    )
                }
            }
            if (syncState.running || syncState.failed) {
                Text(
                    syncState.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (syncState.failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
                if (syncState.running && syncState.progress > 0) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { syncState.progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 4.dp),
                    )
                }
            }
            val panelModifier = Modifier.fillMaxWidth().height(280.dp)
            when (lyricStyle) {
                com.aar.privatemusic.data.LyricStyle.FOCUS -> LyricsFocus(
                    lyrics = shown,
                    positionMs = { sliderPosition.floatValue.toLong() },
                    onSeek = { controller.seekTo(it) },
                    accent = accent,
                    onShareFrom = { lyricShareFrom = it },
                    livePositionMs = { controller.positionMs },
                    modifier = panelModifier,
                )
                com.aar.privatemusic.data.LyricStyle.MINIMAL -> LyricsMinimal(
                    lyrics = shown,
                    positionMs = { sliderPosition.floatValue.toLong() },
                    accent = accent,
                    dominant = dominant,
                    livePositionMs = { controller.positionMs },
                    modifier = panelModifier,
                )
                // El clásico va también detrás del videoclip: el diálogo lo tapa.
                else -> LyricsPanel(
                    lyrics = shown,
                    positionMs = { sliderPosition.floatValue.toLong() },
                    onSeek = { controller.seekTo(it) },
                    accent = accent,
                    onShareFrom = { lyricShareFrom = it },
                    // Para seguir una sílaba no basta el tic de medio segundo del
                    // deslizador: el resaltado por palabra pregunta la posición real.
                    livePositionMs = { controller.positionMs },
                    modifier = panelModifier,
                )
            }
            if (lyricStyle == com.aar.privatemusic.data.LyricStyle.VIDEOCLIP) {
                val castName by com.aar.privatemusic.cast.CastState.castDeviceName.collectAsState()
                val animatedBg by app.settings.animatedBackground.collectAsState()
                val vcVideo = remember(np.songId, song?.videoPath) {
                    (song?.videoPath?.let { File(it) }?.takeIf { it.canRead() })
                        ?: app.repository.guessVideoFile(np.songId)
                }
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showLyrics = false },
                    properties = androidx.compose.ui.window.DialogProperties(
                        usePlatformDefaultWidth = false,
                    ),
                ) {
                    VideoclipLyrics(
                        lyrics = shown,
                        title = np.title,
                        artist = np.artist,
                        song = song,
                        videoFile = vcVideo,
                        castActive = castName != null,
                        animatedBgEnabled = animatedBg,
                        dominant = dominant,
                        accent = accent,
                        isPlaying = isPlaying,
                        artFile = np.artPath?.let { File(it) },
                        positionMs = { sliderPosition.floatValue.toLong() },
                        livePositionMs = { controller.positionMs },
                        onSeek = { controller.seekTo(it) },
                        onPlayPause = { controller.togglePlayPause() },
                        onNext = { controller.next() },
                        onPrev = { controller.previous() },
                        onClose = { showLyrics = false },
                        onPickStyle = { app.settings.setLyricStyle(it) },
                    )
                }
            }
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
                val castName by com.aar.privatemusic.cast.CastState.castDeviceName.collectAsState()
                val animatedBg by app.settings.animatedBackground.collectAsState()
                val videoFile = remember(np.songId, song?.videoPath) {
                    (song?.videoPath?.let { File(it) }?.takeIf { it.canRead() })
                        ?: app.repository.guessVideoFile(np.songId)
                }
                when {
                    // El vídeo se queda en el móvil: con Cast activo, carátula/fondo.
                    videoFile != null && castName == null && videoFile.extension.lowercase() == "gif" ->
                        com.aar.privatemusic.ui.components.SongGif(videoFile, 280.dp)
                    videoFile != null && castName == null ->
                        com.aar.privatemusic.ui.components.SongVideo(videoFile, 280.dp, isPlaying, np.artPath?.let { File(it) })
                    animatedBg ->
                        Box(contentAlignment = Alignment.Center) {
                            com.aar.privatemusic.ui.components.AnimatedMoodBackground(
                                280.dp, cover.first,
                                song?.moodHappy, song?.moodSad, song?.moodAggressive,
                                song?.moodRelaxed, song?.bpm,
                            )
                            ArtImage(np.artPath?.let { File(it) }, 210.dp)
                        }
                    else -> ArtImage(np.artPath?.let { File(it) }, 280.dp)
                }
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
        // Modo correr: cadencia objetivo y cuánto se está estirando el tempo.
        val runState by app.runningMode.state.collectAsState()
        runState?.let { r ->
            Text(
                buildString {
                    append("🏃 ${r.targetSpm} pasos/min")
                    r.measuredSpm?.let { append(" (midiendo $it)") }
                    r.songBpm?.let { append(" · ${it.toInt()} BPM × ${"%.2f".format(r.speed)}") }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // Rasgos de ánimo (modelos locales): una línea discreta, sólo si hay alguno claro.
        val moods = remember(song) { com.aar.privatemusic.data.MoodLabels.of(song) }
        if (moods.isNotEmpty()) {
            Text(
                moods.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        song?.note?.takeIf { it.isNotBlank() }?.let { note ->
            Text(
                "📝 $note",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
            )
        }

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
            // Con la ficha desplegada, qué hizo el AutoMix en esta transición:
            // es la única forma de ver por qué una mezcla no cuadra (BPM sin
            // analizar, o tempos demasiado separados para el margen permitido).
            if (qualityExpanded) {
                val mixInfo by com.aar.privatemusic.player.MixInfoHolder.info.collectAsState()
                mixInfo?.describe()?.let { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                    )
                }
            }
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
    livePositionMs: () -> Long = positionMs,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // La posición se lee aquí dentro, no en el llamante: así el tic de medio
    // segundo recompone la letra y no la pantalla entera.
    val currentIdx = if (lyrics.synced) lyrics.lines.indexOfLast { it.timeMs <= positionMs() } else -1

    // Reloj fino SÓLO para el resaltado por palabra. El tic de medio segundo de
    // la pantalla no vale para seguir una sílaba, pero tampoco se puede
    // recomponer la letra a 60 fps: este valor se lee únicamente dentro de
    // `graphicsLayer` (fase de dibujo), así que mover la palabra activa repinta
    // y no recompone nada. Sólo corre si la letra trae tiempos por palabra.
    val wordClock = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(lyrics) {
        if (!lyrics.wordLevel) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameMillis { wordClock.longValue = livePositionMs() }
        }
    }

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
            val lineModifier = Modifier
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
                .padding(vertical = 6.dp)

            if (active && line.words.isNotEmpty()) {
                // La línea que suena, si trae tiempos por palabra, se enciende
                // sílaba a sílaba; las demás siguen pintándose de una pieza.
                WordLine(words = line.words, accent = accent, clock = wordClock, modifier = lineModifier)
            } else {
                Text(
                    line.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                    textAlign = TextAlign.Center,
                    // El mismo color de la portada que el resto de los mandos: si no,
                    // la línea que suena es la única cosa azul en una pantalla amarilla.
                    color = if (active) accent else MaterialTheme.colorScheme.onSurface,
                    modifier = lineModifier,
                )
            }
        }
    }
}

/**
 * La línea que se está cantando, palabra por palabra (Enhanced LRC o TTML).
 *
 * Cada palabra se pinta dos veces, una apagada y otra con el color de la
 * portada encima; lo que se mueve es la opacidad de la de arriba. Ese valor se
 * calcula dentro de `graphicsLayer`, o sea en la fase de dibujo: el reloj
 * avanza 60 veces por segundo sin recomponer ni una vez. Pintarlo cambiando el
 * `color` del texto obligaría a recomponer la línea entera en cada fotograma.
 *
 * La palabra se enciende con un pequeño degradado al entrar (no de golpe) y se
 * queda encendida hasta el final del verso, que es como se lee un karaoke: lo
 * ya cantado sigue visible.
 */
@Composable
private fun WordLine(
    words: List<com.aar.privatemusic.lyrics.LyricWord>,
    accent: Color,
    clock: androidx.compose.runtime.MutableLongState,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
    ) {
        words.forEach { word ->
            Box {
                Text(
                    word.text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 3.dp),
                )
                Text(
                    word.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .graphicsLayer { alpha = wordAlpha(clock.longValue, word) },
                )
            }
        }
    }
}

/**
 * Cuánto está encendida una palabra: 0 antes de que le toque, 1 en cuanto entra
 * (con [FADE_IN_MS] de transición para que no dé un salto) y 1 desde entonces.
 */
private fun wordAlpha(positionMs: Long, word: com.aar.privatemusic.lyrics.LyricWord): Float {
    val delta = positionMs - word.startMs
    return when {
        delta < 0 -> 0f
        delta >= FADE_IN_MS -> 1f
        else -> delta.toFloat() / FADE_IN_MS
    }
}

private const val FADE_IN_MS = 120f

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

/** "1,25× · +2 st", o null si todo está en su sitio. */
private fun tempoPitchLabel(speed: Float, semitones: Int): String? {
    val parts = buildList {
        if (speed != 1f) add(formatSpeed(speed))
        if (semitones != 0) add(formatSemitones(semitones))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatSpeed(speed: Float): String {
    // "1,50" → "1,5×", "1,00" → "1×", "0,75" se queda.
    val txt = String.format(java.util.Locale.getDefault(), "%.2f", speed).trimEnd('0').trimEnd(',', '.')
    return "$txt×"
}

private fun formatSemitones(semitones: Int): String =
    (if (semitones > 0) "+$semitones" else "$semitones") + " st"

/**
 * Tempo (0,5×–2×, time-stretch: el tono no cambia) y tono (±12 semitonos: el
 * tempo no cambia), cada uno con su deslizador. Se aplica al soltar, no en
 * cada pixel: reconfigurar Sonic en cada movimiento hace saltar el audio.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TempoPitchDialog(
    speed: Float,
    semitones: Int,
    onSpeed: (Float) -> Unit,
    onSemitones: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var speedDraft by remember(speed) { mutableFloatStateOf(speed) }
    var semitonesDraft by remember(semitones) { mutableFloatStateOf(semitones.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tempo y tono") },
        text = {
            Column {
                Text("Tempo: ${formatSpeed(speedDraft)}", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = speedDraft,
                    onValueChange = { speedDraft = (Math.round(it * 20f) / 20f) },
                    onValueChangeFinished = { onSpeed(speedDraft) },
                    valueRange = 0.5f..2f,
                    steps = 29,
                )
                // Cinco chips no caben en una fila en un móvil estrecho: que salten.
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { preset ->
                        AssistChip(
                            onClick = { speedDraft = preset; onSpeed(preset) },
                            label = { Text(formatSpeed(preset)) },
                            colors = if (speedDraft == preset) {
                                androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                )
                            } else {
                                androidx.compose.material3.AssistChipDefaults.assistChipColors()
                            },
                        )
                    }
                }
                Text(
                    "Tono: ${formatSemitones(semitonesDraft.toInt())}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Slider(
                    value = semitonesDraft,
                    onValueChange = { semitonesDraft = Math.round(it).toFloat() },
                    onValueChangeFinished = { onSemitones(semitonesDraft.toInt()) },
                    valueRange = -12f..12f,
                    steps = 23,
                )
                Text(
                    "El tempo no cambia el tono y el tono no cambia el tempo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = {
            TextButton(
                onClick = { speedDraft = 1f; semitonesDraft = 0f; onReset() },
                enabled = speedDraft != 1f || semitonesDraft != 0f,
            ) { Text("Restablecer") }
        },
    )
}

/**
 * Reloj fino (60 fps, sólo fase de dibujo) para el resaltado por palabra.
 * Igual que en [LyricsPanel]: sólo corre si la letra trae tiempos por palabra.
 */
@Composable
private fun rememberWordClock(
    lyrics: com.aar.privatemusic.lyrics.Lyrics,
    livePositionMs: () -> Long,
): androidx.compose.runtime.MutableLongState {
    val clock = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(lyrics) {
        if (!lyrics.wordLevel) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameMillis { clock.longValue = livePositionMs() }
        }
    }
    return clock
}

/** Índice de la línea que suena ahora, o -1 si la letra no está sincronizada. */
private fun activeLineIndex(lyrics: com.aar.privatemusic.lyrics.Lyrics, positionMs: Long): Int =
    if (lyrics.synced) lyrics.lines.indexOfLast { it.timeMs <= positionMs } else -1

/**
 * Estilo "Foco": la línea que suena, grande y centrada, con una o dos líneas
 * arriba y abajo muy apagadas. Pensado para cantar; el resaltado palabra a
 * palabra queda bien a la vista. Con letra sin sincronizar se lee como una
 * lista normal.
 */
@Composable
private fun LyricsFocus(
    lyrics: com.aar.privatemusic.lyrics.Lyrics,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    accent: Color,
    onShareFrom: (Int) -> Unit,
    livePositionMs: () -> Long,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val currentIdx = activeLineIndex(lyrics, positionMs())
    val wordClock = rememberWordClock(lyrics, livePositionMs)

    var touching by remember { mutableStateOf(false) }
    LaunchedEffect(currentIdx, touching) {
        if (currentIdx < 0 || touching) return@LaunchedEffect
        delay(250)
        // Centra la línea activa: la lista tiene relleno arriba/abajo de media
        // pantalla, así que llevarla al principio la deja en el centro.
        if (!touching) listState.animateScrollToItem(currentIdx)
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
        contentPadding = PaddingValues(vertical = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            val distance = if (currentIdx < 0) 0 else kotlin.math.abs(i - currentIdx)
            val active = currentIdx >= 0 && distance == 0
            val alpha by animateFloatAsState(
                targetValue = when {
                    currentIdx < 0 -> 0.85f
                    distance == 0 -> 1f
                    distance == 1 -> 0.35f
                    distance == 2 -> 0.18f
                    else -> 0f
                },
                label = "focusAlpha",
            )
            val scale by animateFloatAsState(
                targetValue = if (active) 1f else 0.8f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
                label = "focusScale",
            )
            val lineModifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
                .combinedClickable(
                    onClick = { if (lyrics.synced) onSeek(line.timeMs) },
                    onLongClick = { onShareFrom(i) },
                )
                .padding(vertical = 10.dp, horizontal = 16.dp)

            if (active && line.words.isNotEmpty()) {
                WordLine(words = line.words, accent = accent, clock = wordClock, modifier = lineModifier)
            } else {
                Text(
                    line.text,
                    style = if (active) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.titleMedium,
                    fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    textAlign = TextAlign.Center,
                    color = if (active) accent else MaterialTheme.colorScheme.onSurface,
                    modifier = lineModifier,
                )
            }
        }
    }
}

/**
 * Estilo "Minimalista": sólo la frase que suena, grande y centrada, sobre un
 * fondo liso del color de la portada. Sin scroll ni distracciones. Con letra
 * sin sincronizar cae a una lista centrada.
 */
@Composable
private fun LyricsMinimal(
    lyrics: com.aar.privatemusic.lyrics.Lyrics,
    positionMs: () -> Long,
    accent: Color,
    dominant: Color,
    livePositionMs: () -> Long,
    modifier: Modifier = Modifier,
) {
    val wordClock = rememberWordClock(lyrics, livePositionMs)
    Box(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(lerp(dominant, MaterialTheme.colorScheme.surface, 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!lyrics.synced) {
            LazyColumn(
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 24.dp),
            ) {
                itemsIndexed(lyrics.lines) { _, line ->
                    Text(
                        line.text,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 20.dp),
                    )
                }
            }
            return@Box
        }
        // El índice se lee aquí dentro: el tic recompone sólo esta caja.
        val idx = activeLineIndex(lyrics, positionMs())
        androidx.compose.animation.AnimatedContent(
            targetState = idx,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(tween(400)) +
                    androidx.compose.animation.scaleIn(tween(400), initialScale = 0.9f)) togetherWith
                    androidx.compose.animation.fadeOut(tween(250))
            },
            label = "minimalLine",
        ) { shownIdx ->
            val l = lyrics.lines.getOrNull(shownIdx)
            Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                when {
                    l == null -> Text(
                        "♪",
                        style = MaterialTheme.typography.displaySmall,
                        color = accent,
                    )
                    l.words.isNotEmpty() && shownIdx == idx ->
                        WordLineBig(words = l.words, accent = accent, clock = wordClock)
                    else -> Text(
                        l.text,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = accent,
                    )
                }
            }
        }
    }
}

/** Como [WordLine] pero en tipografía grande, para minimalista y videoclip. */
@Composable
private fun WordLineBig(
    words: List<com.aar.privatemusic.lyrics.LyricWord>,
    accent: Color,
    clock: androidx.compose.runtime.MutableLongState,
) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.Center) {
        words.forEach { word ->
            Box {
                Text(
                    word.text,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Text(
                    word.text,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = accent,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer { alpha = wordAlpha(clock.longValue, word) },
                )
            }
        }
    }
}

/**
 * Estilo "Videoclip": pantalla completa con el vídeo de fondo (o el fondo
 * animado por mood/BPM si no hay vídeo, o con Cast activo) y la letra grande
 * encima, con un velo oscuro para que se lea. La palabra activa se enciende
 * sobre el vídeo. Con letra sin tiempos se muestra estática.
 */
@Composable
private fun VideoclipLyrics(
    lyrics: com.aar.privatemusic.lyrics.Lyrics,
    title: String,
    artist: String,
    song: com.aar.privatemusic.data.db.Song?,
    videoFile: File?,
    castActive: Boolean,
    animatedBgEnabled: Boolean,
    dominant: Color,
    accent: Color,
    isPlaying: Boolean,
    artFile: File?,
    positionMs: () -> Long,
    livePositionMs: () -> Long,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    onPickStyle: (com.aar.privatemusic.data.LyricStyle) -> Unit,
) {
    val wordClock = rememberWordClock(lyrics, livePositionMs)
    Box(
        Modifier
            .fillMaxSize()
            .background(dominant),
        contentAlignment = Alignment.Center,
    ) {
        // Fondo: vídeo si lo hay y no se está compartiendo con la tele; si no,
        // fondo animado o carátula. El vídeo se queda en el móvil (fase 7).
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                videoFile != null && !castActive && videoFile.extension.lowercase() == "gif" ->
                    com.aar.privatemusic.ui.components.SongGif(videoFile, 1000.dp)
                videoFile != null && !castActive ->
                    com.aar.privatemusic.ui.components.SongVideo(videoFile, 1000.dp, isPlaying, artFile)
                animatedBgEnabled ->
                    com.aar.privatemusic.ui.components.AnimatedMoodBackground(
                        1000.dp, dominant,
                        song?.moodHappy, song?.moodSad, song?.moodAggressive,
                        song?.moodRelaxed, song?.bpm,
                    )
                artFile != null -> ArtImage(artFile, 1000.dp)
                else -> Unit
            }
        }
        // Velo para contraste (más oscuro abajo, donde va la letra).
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.5f to Color.Black.copy(alpha = 0.45f),
                        1f to Color.Black.copy(alpha = 0.85f),
                    )
                )
        )

        // Letra
        val currentIdx = activeLineIndex(lyrics, positionMs())
        if (!lyrics.synced) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = 96.dp, bottom = 120.dp),
            ) {
                itemsIndexed(lyrics.lines) { _, line ->
                    Text(
                        line.text,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
        } else {
            androidx.compose.animation.AnimatedContent(
                targetState = currentIdx,
                transitionSpec = {
                    (androidx.compose.animation.slideInVertically(tween(450)) { it / 3 } +
                        androidx.compose.animation.fadeIn(tween(450))) togetherWith
                        androidx.compose.animation.fadeOut(tween(300))
                },
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 28.dp),
                label = "clipLine",
            ) { idx ->
                val line = lyrics.lines.getOrNull(idx)
                val next = lyrics.lines.getOrNull(idx + 1)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        line == null -> Text("♪", style = MaterialTheme.typography.displayMedium, color = accent)
                        line.words.isNotEmpty() && idx == currentIdx ->
                            WordLineBig(words = line.words, accent = Color.White, clock = wordClock)
                        else -> Text(
                            line.text,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = Color.White,
                        )
                    }
                    next?.let {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            it.text,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }

        // Barra superior: cerrar + título + elegir otro estilo.
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Cerrar", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(artist, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.Style, contentDescription = "Estilo", tint = Color.White)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    com.aar.privatemusic.data.LyricStyle.entries.forEach { st ->
                        DropdownMenuItem(
                            text = { Text(st.label) },
                            onClick = { onPickStyle(st); menu = false },
                        )
                    }
                }
            }
        }

        // Controles mínimos abajo.
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onPrev) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Anterior", tint = Color.White)
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/Pausa",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Siguiente", tint = Color.White)
            }
        }
    }
}
