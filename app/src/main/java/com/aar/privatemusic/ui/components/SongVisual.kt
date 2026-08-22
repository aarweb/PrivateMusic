package com.aar.privatemusic.ui.components

import android.graphics.ImageDecoder
import android.os.Build
import android.widget.ImageView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reproduce en bucle un vídeo MUDO como fondo de la canción, con su propio
 * ExoPlayer. Crucial: `volume=0` y `handleAudioFocus=false` para que NUNCA pida
 * el foco de audio ni cree un AudioTrack — así no se pelea con los dos
 * reproductores de audio ni con el crossfade. Se pausa con la app en segundo
 * plano / pantalla apagada y con el audio en pausa, y se suelta al salir.
 *
 * Un ÚNICO ExoPlayer para toda la vida del composable: al cambiar de canción se
 * le hace `setMediaItem`+`prepare` (no se recrea), porque recrearlo dejaba la
 * superficie atada al player viejo y se veía congelado el fotograma anterior.
 * Mientras el vídeo nuevo no ha pintado su primer fotograma se muestra la
 * carátula por encima, para no ver un instante el vídeo de la canción de antes.
 *
 * `PlayerView` con `RESIZE_MODE_ZOOM`: llena el hueco recortando los bordes y
 * conservando la proporción (un clip 16:9 en un fondo vertical se recorta, no
 * se estira).
 */
@Composable
fun SongVideo(file: File, size: Dp, isAudioPlaying: Boolean, artFile: File? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            playWhenReady = true
        }
    }

    // Tapa el hueco visual hasta que el vídeo nuevo pinta su primer fotograma.
    val firstFrameShown = remember { androidx.compose.runtime.mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onRenderedFirstFrame(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                firstFrameShown.value = true
            }
        }
        player.addAnalyticsListener(listener)
        onDispose { player.removeAnalyticsListener(listener) }
    }

    // Cambio de canción: cargar el vídeo nuevo y ocultar el viejo hasta que pinte.
    LaunchedEffect(file.absolutePath) {
        firstFrameShown.value = false
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        player.prepare()
    }

    // El vídeo sólo corre si la pantalla está en primer plano Y el audio suena.
    val resumed = remember { androidx.compose.runtime.mutableStateOf(true) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> resumed.value = true
                Lifecycle.Event.ON_PAUSE -> resumed.value = false
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    player.playWhenReady = resumed.value && isAudioPlaying

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    Box(Modifier.size(size)) {
        AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    // Sin obturador negro propio: el hueco lo tapamos con la carátula.
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    this.player = player
                }
            },
            modifier = Modifier.size(size),
        )
        // Carátula por encima hasta el primer fotograma del vídeo nuevo.
        if (!firstFrameShown.value && artFile != null) {
            ArtImage(artFile, size)
        }
    }
}

/** GIF animado (Coil 2.7 no lo anima solo): ImageDecoder en API 28+, estático debajo. */
@Composable
fun SongGif(file: File, size: Dp) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching {
                        val d = ImageDecoder.decodeDrawable(
                            ImageDecoder.createSource(file)
                        )
                        setImageDrawable(d)
                        (d as? android.graphics.drawable.AnimatedImageDrawable)?.start()
                    }
                } else {
                    setImageURI(android.net.Uri.fromFile(file))
                }
            }
        },
        modifier = Modifier.size(size),
    )
}

/**
 * Fondo animado generado on-device cuando no hay vídeo: un degradado que gira,
 * teñido por el mood de la canción y a la velocidad de su BPM. Es sólo dibujo
 * (no decodifica nada, no toca el audio, no pide permisos).
 */
@Composable
fun AnimatedMoodBackground(
    size: Dp,
    baseColor: Color,
    happy: Float?,
    sad: Float?,
    aggressive: Float?,
    relaxed: Float?,
    bpm: Float?,
) {
    val warm = Color(0xFFFF7043)
    val cool = Color(0xFF42A5F5)
    val calm = Color(0xFF66BB6A)
    val dark = Color(0xFF5C6BC0)
    val tint = when (listOf(happy to warm, sad to dark, aggressive to warm, relaxed to calm)
        .maxByOrNull { it.first ?: 0f }?.takeIf { (it.first ?: 0f) > 0.4f }?.second) {
        null -> cool
        else -> listOf(happy to warm, sad to dark, aggressive to warm, relaxed to calm)
            .maxByOrNull { it.first ?: 0f }!!.second
    }
    val c1 = lerp(baseColor, tint, 0.5f)
    val c2 = lerp(baseColor, cool, 0.3f)
    val periodMs = ((60_000f / (bpm?.coerceIn(60f, 180f) ?: 100f)) * 8).toInt().coerceIn(2000, 12000)
    val transition = rememberInfiniteTransition(label = "moodbg")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(Modifier.size(size)) {
        val r = size.toPx()
        val cx = this.size.width / 2 + cos(phase) * r * 0.15f
        val cy = this.size.height / 2 + sin(phase) * r * 0.15f
        drawRect(
            Brush.radialGradient(
                colors = listOf(c1, c2, baseColor),
                center = Offset(cx, cy),
                radius = r,
            )
        )
    }
}
