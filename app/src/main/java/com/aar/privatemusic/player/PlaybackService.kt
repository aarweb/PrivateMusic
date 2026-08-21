package com.aar.privatemusic.player

import com.aar.privatemusic.data.db.openMusicDatabase
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import com.aar.privatemusic.MainActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.aar.privatemusic.data.AppSettings
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.MusicDatabase
import com.aar.privatemusic.data.db.Song
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow

/** MediaLibraryService: background playback + browse tree for Android Auto. */
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** ¿Suena algo ahora mismo? Lo usa el bucle de volumen para dormirse. */
    private val playing = kotlinx.coroutines.flow.MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        AudioDsp.reload(this)
        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(AudioDsp.buildRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Sin esto, la CPU puede entrar en reposo profundo con la pantalla apagada
        // y la reproducción se corta. `WAKE_MODE_LOCAL` retiene sólo el wakelock de
        // CPU, y sólo mientras suena algo: los ficheros son locales, así que no hace
        // falta también el de Wi-Fi. ExoPlayer lo suelta al parar.
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        val dao = openMusicDatabase(this).musicDao()
        // Tapping the media notification opens the app.
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback(dao, serviceScope))
            .setSessionActivity(sessionActivity)
            .build()
        EqHolder.init(this, player.audioSessionId)
        // Second player for TRUE crossfade: it plays the tail of the outgoing
        // track while the main player already runs the incoming one, so both
        // songs genuinely overlap. No audio focus: it rides on the main one.
        tailPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(AudioDsp.buildRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            .build()
        // The ONLY reliable "audio is really coming out" signal: currentPosition
        // interpolates with the system clock and lies during AudioTrack warm-up.
        tailPlayer?.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioPositionAdvancing(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                playoutStartSystemTimeMs: Long,
            ) {
                tailAudioAdvancing = true
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Stream previews use signed URLs that expire; a dead preview item
                // would otherwise fail silently forever from the notification.
                if (player.currentMediaItem?.mediaId?.startsWith("preview:") == true) {
                    player.clearMediaItems()
                    android.util.Log.w("Playback", "player error", error)
                    return
                }
                android.util.Log.w("Playback", "player error", error)
                // Media3 1.5: al cambiar el tempo a mitad de canción (tempo manual,
                // AutoMix, modo correr) el sink vacía la cadena de procesadores y
                // `Sonic.queueEndOfStream` puede lanzar ArrayIndexOutOfBounds. El
                // reproductor se queda en ERROR con la cola intacta: se vuelve a
                // preparar y se sigue donde iba, con los parámetros nuevos ya puestos.
                if (error.cause is ArrayIndexOutOfBoundsException && player.mediaItemCount > 0) {
                    val index = player.currentMediaItemIndex
                    val position = player.currentPosition
                    android.util.Log.w("Playback", "recovering from audio processor error at $index/$position")
                    player.prepare()
                    player.seekTo(index, position)
                    player.play()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing.value = isPlaying
            }
        })
        startVolumeLoop(player, dao)
        localPlayer = player
        castDao = dao
        // Cast support is best-effort: no Play Services -> no cast, no crash.
        runCatching {
            val castContext = com.google.android.gms.cast.framework.CastContext
                .getSharedInstance(this)
            this.castContext = castContext
            castPlayer = androidx.media3.cast.CastPlayer(castContext).apply {
                setSessionAvailabilityListener(
                    object : androidx.media3.cast.SessionAvailabilityListener {
                        override fun onCastSessionAvailable() = switchToCast()
                        override fun onCastSessionUnavailable() = switchToLocal()
                    }
                )
            }
        }.onFailure { android.util.Log.w("Cast", "cast unavailable", it) }
    }

    private var tailPlayer: ExoPlayer? = null
    private var tailAudioAdvancing = false
    private var localPlayer: ExoPlayer? = null
    private var castPlayer: androidx.media3.cast.CastPlayer? = null
    private var castContext: com.google.android.gms.cast.framework.CastContext? = null
    private var httpServer: com.aar.privatemusic.cast.MediaHttpServer? = null
    private var castDao: MusicDao? = null

    /** Chromecast: move the whole session (queue + position) to the TV. */
    private fun switchToCast() {
        val local = localPlayer ?: return
        val cast = castPlayer ?: return
        val dao = castDao ?: return
        val session = mediaSession ?: return
        val ids = (0 until local.mediaItemCount).map { local.getMediaItemAt(it).mediaId }
        if (ids.isEmpty()) return
        val index = local.currentMediaItemIndex
        val position = local.currentPosition
        val wasPlaying = local.isPlaying
        local.pause()
        serviceScope.launch {
            runCatching {
                httpServer?.stop()
                httpServer = com.aar.privatemusic.cast.MediaHttpServer(dao).also { it.start() }
            }
            val ip = com.aar.privatemusic.cast.MediaHttpServer.localIp() ?: return@launch
            val items = ids.mapNotNull { id ->
                dao.getSong(id)?.let { song ->
                    val ext = java.io.File(song.filePath).extension.lowercase()
                    val mime = when (ext) {
                        "webm" -> "audio/webm"; "m4a", "mp4" -> "audio/mp4"
                        "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"
                        "wav" -> "audio/wav"; else -> "audio/*"
                    }
                    // Artwork must be an URL the TV can fetch: local art via our
                    // server, else the original remote thumbnail.
                    val artUri = when {
                        song.artPath?.let { java.io.File(it).canRead() } == true ->
                            "http://$ip:${com.aar.privatemusic.cast.MediaHttpServer.PORT}/art/${song.id}"
                        !song.thumbnailUrl.isNullOrBlank() -> song.thumbnailUrl
                        else -> null
                    }
                    MediaItem.Builder()
                        .setMediaId(song.id)
                        .setUri("http://$ip:${com.aar.privatemusic.cast.MediaHttpServer.PORT}/song/${song.id}")
                        .setMimeType(mime)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(song.title)
                                .setArtist(song.artist)
                                .apply { artUri?.let { setArtworkUri(android.net.Uri.parse(it)) } }
                                .build()
                        )
                        .build()
                }
            }
            if (items.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                cast.setMediaItems(items, index.coerceIn(0, items.size - 1), position)
                cast.prepare()
                if (wasPlaying) cast.play()
                session.player = cast
                com.aar.privatemusic.cast.CastState.castDeviceName.value = runCatching {
                    castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName
                }.getOrNull() ?: "TV"
                android.util.Log.d("Cast", "session moved to cast: ${items.size} items @$position")
            }
        }
    }

    private fun switchToLocal() {
        val local = localPlayer ?: return
        val cast = castPlayer ?: return
        val session = mediaSession ?: return
        val index = cast.currentMediaItemIndex
        val position = cast.currentPosition
        cast.stop()
        session.player = local
        if (local.mediaItemCount > index) {
            local.seekTo(index, position)
        }
        // While casting, Android suspends the Bluetooth A2DP link; a fresh
        // AudioTrack can then land on the loudspeaker even though the user's
        // headphones are still connected. Route explicitly to the external
        // output when one exists (null = default routing).
        runCatching {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val external = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull {
                    it.type in intArrayOf(
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                        android.media.AudioDeviceInfo.TYPE_HEARING_AID,
                    )
                }
            local.setPreferredAudioDevice(external)
            android.util.Log.d("Cast", "local output -> ${external?.productName ?: "default"}")
        }
        runCatching { httpServer?.stop() }
        httpServer = null
        com.aar.privatemusic.cast.CastState.castDeviceName.value = null
        android.util.Log.d("Cast", "session back to local @$position")
    }

    /**
     * Drives volume normalization and the REAL crossfade: when the current
     * track enters its last N seconds, its tail keeps playing on [tailPlayer]
     * while the main player jumps to the next track — both songs overlap with
     * crossed equal-power curves (and AutoMix tempo-bends the outgoing tail).
     */
    private fun startVolumeLoop(player: ExoPlayer, dao: MusicDao) {
        mainScope.launch {
            // Any throw inside the loop used to kill the crossfade engine silently
            // for the rest of the session: mainScope has a SupervisorJob, so the
            // child just dies — no crash, and no more crossfades until the service
            // restarts. Restart the loop instead of dying.
            while (isActive) {
                try {
                    volumeLoop(player, dao)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("Crossfade", "volume loop crashed; restarting", e)
                    runCatching {
                        tailPlayer?.let {
                            it.volume = 0f
                            it.stop()
                            it.clearMediaItems()
                            it.setPlaybackSpeed(1f)
                        }
                        player.volume = 1f
                    }
                    delay(500)
                }
            }
        }
    }

    private suspend fun volumeLoop(player: ExoPlayer, dao: MusicDao) = coroutineScope {
            // Two streams summed in the OS mixer can peak past 0 dBFS at the
            // crossfade midpoint and clip; a small constant margin (~ -1 dB) keeps
            // the sum in range without an audible loudness dip.
            val xfHeadroom = 0.9f
            var gainFactor = 1f
            var gainForId: String? = null
            var touchedVolume = false
            // Active-overlap state.
            var xfEndsAt = 0L
            var xfDurationMs = 1L
            var xfGainA = 1f
            var xfMainId: String? = null
            // Pre-arm state: the tail player is prepared AHEAD of the window so
            // firing is instant — preparing at fire time leaves a ~100-300ms
            // hole while the decoder spins up (audible micro-cut). Gains and the
            // AutoMix ratio are also resolved here so the fire path does zero IO
            // and the tempo bend never re-prepares an already-READY pipeline.
            var armedForId: String? = null
            var armedAtPos = 0L
            var armedGainA = 1f
            var armedGainB = 1f
            var armedNextId: String? = null
            // Tempo del AutoMix resuelto al pre-armar. Hay que recordarlo: la vía
            // de respaldo re-prepara el tail y antes lo dejaba SIEMPRE a 1.0, así
            // que había fundido pero el AutoMix desaparecía en silencio.
            var armedRatio = 1f
            // Effective end of the outgoing track (production fade-outs and
            // trailing silence trimmed): crossfading INTO a dying tail sounds
            // like a cut no matter how seamless the engine is.
            var armedEffDur = 0L
            // A failed handover (audio never surfaced / still misaligned) skips
            // the crossfade for THAT track: gapless is better than replaying A.
            var skipXfForId: String? = null

            // Modo de normalización (RMS medido o ReplayGain del tag); se relee
            // cada iteración. Sólo atenúa (nunca sube) para no saturar sin peak.
            var normalizeMode = "rms"
            fun gainOf(loudness: Float?, replayGain: Float?, normalize: Boolean): Float {
                if (!normalize) return 1f
                val gainDb = if (normalizeMode == "replaygain" && replayGain != null) {
                    replayGain
                } else if (loudness != null) {
                    AppSettings.TARGET_LOUDNESS_DB - loudness
                } else return 1f
                return 10.0.pow(gainDb.coerceIn(-12f, 0f) / 20.0).toFloat()
            }

            while (isActive) {
                // En pausa no hay volumen que gestionar, y el servicio sigue vivo
                // mucho después de pausar. Sin esta guarda el bucle ticaba dos
                // veces por segundo para siempre: medido en el Pixel, 0,75% de un
                // núcleo con la pantalla apagada y la música parada.
                //
                // El tope de 5 s es una red: si alguna vez se perdiera el evento
                // de "ha empezado a sonar", el bucle despierta solo en vez de
                // quedarse dormido con la música puesta.
                if (!player.isPlaying && xfEndsAt == 0L) {
                    kotlinx.coroutines.withTimeoutOrNull(5_000) {
                        playing.first { it }
                    }
                    continue
                }
                val tail = tailPlayer
                val crossfadeMs = AppSettings.readCrossfadeSec(this@PlaybackService) * 1000L
                val normalize = AppSettings.readNormalizeVolume(this@PlaybackService)
                normalizeMode = AppSettings.readNormalizeMode(this@PlaybackService)
                val autoMix = AppSettings.readAutoMix(this@PlaybackService)
                val xfActive = xfEndsAt != 0L
                // Fundido del temporizador de apagado (0..1): multiplicador global
                // que se aplica a TODA escritura de volumen, para que no se pelee con
                // el crossfade/normalización escribiendo controller.volume por fuera.
                val fade = SleepFade.level

                // Con el fade activo (fade < 1) NO hacemos el early-return: hay que
                // seguir aplicando el multiplicador al volumen aunque no haya crossfade
                // ni normalización.
                if (crossfadeMs == 0L && !normalize && !xfActive && fade >= 1f) {
                    if (touchedVolume) {
                        player.volume = 1f
                        touchedVolume = false
                        gainForId = null
                    }
                    delay(500)
                    continue
                }

                val id = player.currentMediaItem?.mediaId
                if (normalize && id != null && id != gainForId) {
                    gainForId = id
                    val (loudness, rg) = withContext(Dispatchers.IO) {
                        runCatching { dao.getLoudness(id) to dao.getReplayGain(id) }.getOrDefault(null to null)
                    }
                    gainFactor = gainOf(loudness, rg, true)
                } else if (!normalize) {
                    gainFactor = 1f
                    gainForId = null
                }

                // ---- Overlap in progress: drive both volume curves. ----
                if (xfActive && tail != null) {
                    val remainingXf = xfEndsAt - android.os.SystemClock.elapsedRealtime()
                    // NOT isPlaying: right after seekToNextMediaItem the main player
                    // dips into BUFFERING for a tick (much more likely with the screen
                    // off / CPU throttled), and aborting there cut track A dead.
                    // playWhenReady only drops when the user actually pauses.
                    val aborted = player.currentMediaItem?.mediaId != xfMainId || !player.playWhenReady
                    if (remainingXf <= 0 || aborted) {
                        tail.volume = 0f
                        tail.stop()
                        tail.clearMediaItems()
                        tail.setPlaybackSpeed(1f)
                        xfEndsAt = 0L
                        player.volume = gainFactor * fade
                        android.util.Log.d("Crossfade", "overlap end aborted=$aborted")
                    } else {
                        val t = (1f - remainingXf.toFloat() / xfDurationMs).coerceIn(0f, 1f)
                        // Different tracks here (A on tail, B on main) → uncorrelated
                        // → equal-power (sqrt) keeps perceived loudness flat; headroom
                        // guards the mixer against midpoint peak clipping.
                        player.volume = xfHeadroom * gainFactor * kotlin.math.sqrt(t) * fade
                        tail.volume = xfHeadroom * xfGainA * kotlin.math.sqrt(1f - t) * fade
                        android.util.Log.d("Crossfade", "overlap t=$t mainVol=${player.volume} tailVol=${tail.volume}")
                        if (fade < 1f) android.util.Log.d("SleepFade", "overlap fade=$fade mainVol=${player.volume} tailVol=${tail.volume}")
                    }
                    touchedVolume = true
                    delay(100)
                    continue
                }

                // ---- Pre-arm & fire the overlap around the crossfade window. ----
                // Algunos opus/webm de YouTube no reportan duración en el contenedor
                // (player.duration == TIME_UNSET) y sin ella el fundido nunca se
                // armaba para esas canciones. La duración real viaja en el MediaItem
                // (Song.durationSec) y se usa como respaldo.
                val duration = player.duration.let { d ->
                    if (d != androidx.media3.common.C.TIME_UNSET) d
                    else player.currentMediaItem?.mediaMetadata?.durationMs
                        ?: androidx.media3.common.C.TIME_UNSET
                }
                if (crossfadeMs > 0 && tail != null && player.isPlaying &&
                    duration != androidx.media3.common.C.TIME_UNSET &&
                    duration > crossfadeMs * 2 &&
                    player.hasNextMediaItem() &&
                    // Repeat-one loops the same track: no crossfade there.
                    player.repeatMode != Player.REPEAT_MODE_ONE
                ) {
                    val curItem = player.currentMediaItem
                    val curUri = curItem?.localConfiguration?.uri
                    val curId = curItem?.mediaId
                    if (skipXfForId != null && skipXfForId != curId) skipXfForId = null
                    val eligible = curUri != null && curId != null &&
                        !curId.startsWith("preview:") && curId != skipXfForId
                    // Until armed we don't know the tail silence yet; pre-arm uses
                    // a wide window so the IO lookup happens in time either way.
                    val effDur = if (armedForId == curId) armedEffDur else duration
                    val remaining = effDur - player.currentPosition

                    // Pre-arm: prepare the tail (paused, muted, tempo already set)
                    // at the exact spot where the window opens — anchored to the
                    // MUSICAL end of the track — and resolve gains so the fire
                    // path is instantaneous.
                    // Wide window: until armed, `remaining` is measured against the
                    // FILE end, but the anchor may sit up to ~20s earlier (trimmed
                    // tail silence) — arm early enough to cover the worst case.
                    val nextIdx = player.nextMediaItemIndex
                    if (eligible && armedForId != curId &&
                        remaining < crossfadeMs + 25000 &&
                        nextIdx != androidx.media3.common.C.INDEX_UNSET
                    ) {
                        val nextId = player.getMediaItemAt(nextIdx).mediaId
                        val (gains, ratio, tailSilence) = withContext(Dispatchers.IO) {
                            runCatching {
                                val la = dao.getLoudness(curId)
                                val lb = dao.getLoudness(nextId)
                                val rga = dao.getReplayGain(curId)
                                val rgb = dao.getReplayGain(nextId)
                                val ba = dao.getBpm(curId)
                                val bb = dao.getBpm(nextId)
                                val ts = dao.getTailSilence(curId) ?: 0L
                                val r = if (ba != null && bb != null && ba > 0f)
                                    (bb / ba).coerceIn(0.9f, 1.1f) else 1f
                                Triple(listOf(la, lb, rga, rgb), r, ts)
                            }.getOrDefault(Triple(listOf<Float?>(null, null, null, null), 1f, 0L))
                        }
                        if (player.currentMediaItem?.mediaId == curId) {
                            armedForId = curId
                            // Trim the silent/fading tail, but never more than half the window.
                            armedEffDur = (duration - tailSilence)
                                .coerceAtLeast(player.currentPosition + 500)
                            armedAtPos = armedEffDur - crossfadeMs
                            armedGainA = gainOf(gains[0], gains[2], normalize)
                            armedGainB = gainOf(gains[1], gains[3], normalize)
                            armedNextId = nextId
                            armedRatio = ratio
                            if (armedAtPos > player.currentPosition + 100) {
                                tail.playWhenReady = false
                                tail.volume = 0f
                                tail.setPlaybackSpeed(if (autoMix) ratio else 1f)
                                tail.setMediaItem(MediaItem.Builder().setUri(curUri).build(), armedAtPos)
                                tail.prepare()
                            }
                            android.util.Log.d(
                                "Crossfade",
                                "pre-armed at $armedAtPos ratio=$ratio tailSilenceMs=$tailSilence",
                            )
                        }
                    }

                    if (eligible && armedForId == curId && remaining < crossfadeMs &&
                        nextIdx != androidx.media3.common.C.INDEX_UNSET
                    ) {
                        val armedReady = armedForId == curId &&
                            tail.playbackState == Player.STATE_READY
                        if (!armedReady) {
                            // Fallback (seek straight into the window, odd file):
                            // prepare now and WAIT for READY. Proceeding while
                            // BUFFERING made the handover blind — the tail came up
                            // seconds late and replayed part of A.
                            tail.playWhenReady = false
                            tail.volume = 0f
                            // El ratio del pre-armado sigue siendo el bueno (esta
                            // rama sólo entra con armedForId == curId): reponerlo,
                            // o el AutoMix se perdía justo en las transiciones que
                            // más lo necesitan.
                            tail.setPlaybackSpeed(if (autoMix) armedRatio else 1f)
                            tail.setMediaItem(
                                MediaItem.Builder().setUri(curUri).build(),
                                player.currentPosition,
                            )
                            tail.prepare()
                            armedForId = curId
                            armedAtPos = player.currentPosition
                            armedGainA = gainFactor
                            armedGainB = 1f
                            armedNextId = player.getMediaItemAt(nextIdx).mediaId
                            var prep = 0
                            while (tail.playbackState != Player.STATE_READY && prep < 1200) {
                                delay(20)
                                prep += 20
                            }
                        }
                        val pos = player.currentPosition
                        val fadeLen = (effDur - pos).coerceIn(500L, crossfadeMs)
                        xfGainA = armedGainA
                        xfMainId = armedNextId
                        // Re-sync: the main player is 0-200ms ahead of the armed
                        // position (loop tick). Align to <60ms so the brief
                        // double-A coverage sounds like width, not a flam echo.
                        val mainPos = player.currentPosition
                        if (kotlin.math.abs(mainPos - tail.currentPosition) > 60) {
                            tail.seekTo(mainPos + 40)
                            var resync = 0
                            while (tail.playbackState != Player.STATE_READY && resync < 500) {
                                delay(20)
                                resync += 20
                            }
                        }
                        // Spin the tail up MUTED: its AudioTrack takes 100-800ms
                        // (longer with the AutoMix tempo bend: Sonic lengthens the
                        // pipeline) to actually push samples, and during that
                        // warm-up the main player keeps advancing — an audible
                        // tail would replay that stretch of A. Muted, waiting
                        // longer costs nothing: A keeps sounding on the main.
                        tail.volume = 0f
                        tailAudioAdvancing = false
                        tail.play()
                        // AutoMix routes the tail through Sonic (tempo bend), which
                        // lengthens the pipeline and delays real audio.
                        // Antes esto se capaba a crossfadeMs/2: con fundidos cortos
                        // (2-4 s) el tail no llegaba a empujar audio dentro de ~1000 ms
                        // y se abortaba a corte seco (por eso "unas canciones funden y
                        // otras no"). Ahora damos SIEMPRE un mínimo de calentamiento
                        // (más con AutoMix) y sólo lo ampliamos en fundidos largos. Si
                        // la espera se come parte del fundido corto, mejor un fundido
                        // breve que un corte: las guardas de "el usuario navegó" y "A ya
                        // no es la actual" (más abajo) cubren que A llegue a su fin.
                        val warmupCap = maxOf(if (autoMix) 2600 else 1500, (crossfadeMs / 2).toInt())
                        var waited = 0
                        while (!tailAudioAdvancing && waited < warmupCap) {
                            delay(20)
                            waited += 20
                        }
                        // Right after start the position tracker overshoots by up
                        // to the AudioTrack buffer (~350ms): let it settle on the
                        // real audio clock before trusting any measurement.
                        delay(120)
                        // Measure how far the tail's CONTENT is from the main's
                        // and re-seek predicting the restart latency, so both A
                        // streams line up when the tail becomes audible.
                        var drift = player.currentPosition - tail.currentPosition
                        if (tailAudioAdvancing && kotlin.math.abs(drift) > 60) {
                            val restartLatency = if (drift > 0) drift + 40 else 120L
                            tailAudioAdvancing = false
                            tail.seekTo(player.currentPosition + restartLatency)
                            var rewait = 0
                            while (!tailAudioAdvancing && rewait < 600) {
                                delay(20)
                                rewait += 20
                            }
                            delay(120)
                            drift = player.currentPosition - tail.currentPosition
                        }
                        if (!tailAudioAdvancing || kotlin.math.abs(drift) > 250) {
                            // No real audio (cold pipeline) or still misaligned:
                            // a blind handover replays part of A. Skip this
                            // crossfade — plain gapless beats an audible glitch.
                            tail.stop()
                            tail.clearMediaItems()
                            tail.setPlaybackSpeed(1f)
                            armedForId = null
                            skipXfForId = curId
                            player.volume = gainFactor * fade
                            android.util.Log.w(
                                "Crossfade",
                                "overlap skipped advancing=$tailAudioAdvancing driftMs=$drift waitedMs=$waited",
                            )
                            touchedVolume = true
                            delay(100)
                            continue
                        }
                        // Si el usuario ha saltado de pista o ha buscado (siguiente /
                        // anterior / seek) mientras el tail calentaba, el principal ya
                        // no está en A. Seguir aquí fundiría el volumen de la pista
                        // equivocada y, peor, el seekToNextMediaItem de abajo avanzaría
                        // OTRA vez: un solo "siguiente" se comería una canción. Cortamos
                        // el fundido y dejamos que mande la navegación del usuario.
                        if (player.currentMediaItem?.mediaId != curId) {
                            tail.stop()
                            tail.clearMediaItems()
                            tail.setPlaybackSpeed(1f)
                            armedForId = null
                            player.volume = gainFactor * fade
                            touchedVolume = true
                            android.util.Log.d("Crossfade", "fire cancelado: el usuario navegó fuera de A")
                            delay(100)
                            continue
                        }
                        // Hand the OUTGOING track over from the main player to the
                        // tail player. BOTH are playing the SAME audio A here, so
                        // this sub-fade must be LINEAR-complementary (main down,
                        // tail up, sum held constant at gainA). The old hard jump to
                        // full tail while main ramped down doubled correlated A
                        // (~+5 dB) and clipped the mixer — that was the "pop".
                        delay(80)
                        for (step in 0..5) {
                            // El salto puede caer en estos ~120ms: si el principal
                            // deja de ser A, paramos de tocar SU volumen en el acto.
                            if (player.currentMediaItem?.mediaId != curId) break
                            val s = step / 5f
                            tail.volume = xfGainA * s * fade
                            player.volume = gainFactor * (1f - s) * fade
                            delay(20)
                        }
                        // Última comprobación antes de avanzar nosotros: si navegó
                        // durante el fundido, NO hacemos seekToNextMediaItem (saltaría
                        // una pista); restauramos el volumen y que siga su navegación.
                        if (player.currentMediaItem?.mediaId != curId) {
                            tail.stop()
                            tail.clearMediaItems()
                            tail.setPlaybackSpeed(1f)
                            armedForId = null
                            player.volume = gainFactor * fade
                            touchedVolume = true
                            android.util.Log.d("Crossfade", "fire cancelado durante el fundido: el usuario navegó fuera de A")
                            delay(100)
                            continue
                        }
                        tail.volume = xfGainA * fade
                        player.volume = 0f
                        player.seekToNextMediaItem()
                        gainFactor = armedGainB
                        gainForId = armedNextId
                        xfDurationMs = fadeLen
                        xfEndsAt = android.os.SystemClock.elapsedRealtime() + fadeLen
                        armedForId = null
                        android.util.Log.d(
                            "Crossfade",
                            "overlap start durMs=$fadeLen gainA=$xfGainA gainB=$gainFactor armed=$armedReady waitedMs=$waited driftMs=$drift",
                        )
                        touchedVolume = true
                        delay(100)
                        continue
                    }
                }

                player.volume = gainFactor * fade
                if (fade < 1f) android.util.Log.d("SleepFade", "normal fade=$fade mainVol=${player.volume}")
                touchedVolume = true
                delay(200)
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    // Swiping the app away from recents should stop the music; without this,
    // Media3 keeps the foreground service (and the playback) alive.
    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.run {
            stop()
            clearMediaItems()
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        tailPlayer?.release()
        tailPlayer = null
        castPlayer?.release()
        castPlayer = null
        com.aar.privatemusic.cast.CastState.castDeviceName.value = null
        runCatching { httpServer?.stop() }
        httpServer = null
        EqHolder.release()
        serviceScope.cancel()
        mainScope.cancel()
        super.onDestroy()
    }
}

private const val ROOT_ID = "root"
private const val ALL_ID = "all"
private const val FAVS_ID = "favs"
private const val RECENT_ID = "recent"
private const val PLAYLIST_PREFIX = "playlist/"

private class LibraryCallback(
    private val dao: MusicDao,
    private val scope: CoroutineScope,
) : MediaLibraryService.MediaLibrarySession.Callback {

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT_ID, "PrivateMusic"), params))

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val children: List<MediaItem> = when {
            parentId == ROOT_ID -> buildList {
                add(folder(ALL_ID, "Biblioteca"))
                add(folder(FAVS_ID, "Favoritas"))
                add(folder(RECENT_ID, "Recientes"))
                dao.playlistsOnce().forEach { add(folder("$PLAYLIST_PREFIX${it.id}", it.name)) }
            }
            parentId == ALL_ID -> dao.songsOnce().map { it.toBrowseItem() }
            parentId == FAVS_ID -> dao.favoritesOnce().map { it.toBrowseItem() }
            parentId == RECENT_ID -> dao.songsOnce().take(20).map { it.toBrowseItem() }
            parentId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                if (playlistId != null) dao.playlistSongsOnce(playlistId).map { it.toBrowseItem() }
                else emptyList()
            }
            else -> emptyList()
        }
        LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        dao.getSong(mediaId)?.let { LibraryResult.ofItem(it.toPlayableItem(), null) }
            ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
    }

    /** Controllers send bare mediaIds; resolve them to playable items with a real URI. */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = scope.future {
        mediaItems.map { item ->
            val requestUri = item.requestMetadata.mediaUri
            when {
                item.localConfiguration != null -> item
                // Karaoke & co.: an explicit file travels in requestMetadata.
                requestUri != null -> item.buildUpon().setUri(requestUri).build()
                else -> dao.getSong(item.mediaId)?.toPlayableItem() ?: item
            }
        }.toMutableList()
    }
}

private fun folder(id: String, title: String): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

private fun Song.toBrowseItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(baseMetadata(this).setIsBrowsable(false).setIsPlayable(true).build())
        .build()

private fun Song.toPlayableItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(Uri.fromFile(File(filePath)))
        .setMediaMetadata(baseMetadata(this).setIsBrowsable(false).setIsPlayable(true).build())
        .build()

private fun baseMetadata(song: Song): MediaMetadata.Builder =
    MediaMetadata.Builder()
        .setTitle(song.title)
        .setArtist(song.artist)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setArtworkUri(song.artPath?.let { Uri.fromFile(File(it)) })
