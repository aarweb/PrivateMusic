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
import androidx.media3.common.PlaybackException
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
import kotlinx.coroutines.NonCancellable
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

/** MediaLibraryService: background playback + browse tree for Android Auto. */
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** ¿Suena algo ahora mismo? Lo usa el bucle de volumen para dormirse. */
    private val playing = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val playbackErrorRecovery = PlaybackErrorRecovery()
    private var recoveringPlaybackError = false
    private var pendingPlaybackError: PlaybackException? = null
    private var crossfadeResetGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
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
        localPlayer = player
        alignLocalOutput("main-created")
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
        val tail = ExoPlayer.Builder(this)
            .setRenderersFactory(AudioDsp.buildRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            .build()
        tailPlayer = tail
        alignLocalOutput("tail-created")
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
        // Igual que `tailAudioAdvancing`, este callback llega al looper del
        // reproductor (Main). Guardamos la identidad observada junto a la señal:
        // una notificación antigua de A nunca debe abrir el fade de B.
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioPositionAdvancing(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                playoutStartSystemTimeMs: Long,
            ) {
                val timeline = eventTime.timeline
                val mediaPeriodId = eventTime.mediaPeriodId
                incomingAudioTrackId = if (mediaPeriodId != null) {
                    val periodIndex = timeline.getIndexOfPeriod(mediaPeriodId.periodUid)
                    if (periodIndex != C.INDEX_UNSET) {
                        val period = timeline.getPeriod(
                            periodIndex,
                            androidx.media3.common.Timeline.Period(),
                        )
                        val windowIndex = period.windowIndex
                        if (windowIndex == eventTime.windowIndex &&
                            windowIndex in 0 until timeline.windowCount
                        ) {
                            timeline.getWindow(
                                windowIndex,
                                androidx.media3.common.Timeline.Window(),
                            ).mediaItem.mediaId.takeIf { it.isNotEmpty() }
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        })
        tailPlayer?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                tailPlayerErrorGeneration = if (tailPlayerErrorGeneration == Long.MAX_VALUE) {
                    Long.MIN_VALUE
                } else {
                    tailPlayerErrorGeneration + 1L
                }
                android.util.Log.w(
                    "Crossfade",
                    "tail player error code=${error.errorCode} " +
                        "track=${tailPlayer?.currentMediaItem?.mediaId} " +
                        "generation=$tailPlayerErrorGeneration",
                    error,
                )
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Stream previews use signed URLs that expire; a dead preview item
                // would otherwise fail silently forever from the notification.
                if (player.currentMediaItem?.mediaId?.startsWith("preview:") == true) {
                    player.clearMediaItems()
                    android.util.Log.w("Playback", "player error", error)
                    return
                }
                if (recoveringPlaybackError) {
                    android.util.Log.w(
                        "Playback",
                        "queued reentrant player error code=${error.errorCode}",
                        error,
                    )
                    pendingPlaybackError = error
                    return
                }
                recoveringPlaybackError = true
                try {
                    val maxErrors = maxOf(2, player.mediaItemCount + 2)
                    var currentError = error
                    var processed = 0
                    while (true) {
                        pendingPlaybackError = null
                        recoverLocalPlaybackError(player, currentError)
                        processed++
                        val pending = pendingPlaybackError ?: break
                        if (processed >= maxErrors) {
                            pendingPlaybackError = null
                            stopLocalAfterRecoveryDrainExceeded(player, pending, processed)
                            // stop() may itself synchronously report another error.
                            pendingPlaybackError = null
                            break
                        }
                        currentError = pending
                    }
                } finally {
                    pendingPlaybackError = null
                    recoveringPlaybackError = false
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing.value = isPlaying
            }
        })
        startVolumeLoop(player, dao)
        castDao = dao
        runCatching {
            audioManager.registerAudioDeviceCallback(
                audioDeviceCallback,
                android.os.Handler(mainLooper),
            )
            audioDeviceCallbackRegistered = true
        }.onFailure { android.util.Log.w("AudioRoute", "no se pudo registrar el callback", it) }
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
    private var incomingAudioTrackId: String? = null
    private var tailPlayerErrorGeneration = 0L
    private var localPlayer: ExoPlayer? = null
    private var castPlayer: androidx.media3.cast.CastPlayer? = null
    private var castContext: com.google.android.gms.cast.framework.CastContext? = null
    private var httpServer: com.aar.privatemusic.cast.MediaHttpServer? = null
    private var castDao: MusicDao? = null
    private val playbackHandoff = PlaybackHandoff()
    private val handoffServers = mutableSetOf<com.aar.privatemusic.cast.MediaHttpServer>()
    private lateinit var audioManager: android.media.AudioManager
    private var audioDeviceCallbackRegistered = false
    private var routeTargetInitialized = false
    private var lastRouteTarget: AudioRouteTarget? = null
    private var mainRouteApplied = false
    private var mainRouteTarget: AudioRouteTarget? = null
    private var tailRouteApplied = false
    private var tailRouteTarget: AudioRouteTarget? = null

    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
            alignLocalOutput("devices-added")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) {
            alignLocalOutput("devices-removed")
        }
    }

    private data class AudioRouteTarget(val id: Int, val type: Int)

    private fun recoverLocalPlaybackError(player: ExoPlayer, error: PlaybackException) {
        val trackId = player.currentMediaItem?.mediaId
        val errorCode = error.errorCode
        android.util.Log.w(
            "Playback",
            "player error code=$errorCode track=$trackId",
            error,
        )

        // This listener belongs to the local ExoPlayer. Never move its stale, silent queue while
        // the MediaSession is exposing Cast.
        if (mediaSession?.player !== player) {
            invalidateCrossfadeForRecovery()
            player.playWhenReady = false
            player.stop()
            android.util.Log.w(
                "Playback",
                "action=STOP_LOCAL_INACTIVE code=$errorCode track=$trackId",
            )
            return
        }

        // Player callbacks are delivered on Main, so snapshot all mutable player state here before
        // applying the owner-thread-only policy.
        val queueIds = localQueueIds(player)
        val currentIndex = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0L)
        val wasPlayWhenReady = player.playWhenReady
        val errorType = playbackErrorType(errorCode)
        val action = playbackErrorRecovery.onError(
            PlaybackErrorRecovery.Event(
                trackId = trackId,
                type = errorType,
                queueIds = queueIds,
                currentIndex = currentIndex,
                allowWrap = player.repeatMode == Player.REPEAT_MODE_ALL,
            ),
        )

        // Invalidate both the physical tail and volumeLoop's coroutine-local state before any
        // player operation can synchronously emit another error.
        invalidateCrossfadeForRecovery()

        when (action) {
            PlaybackErrorRecovery.Action.RETRY_CURRENT -> {
                android.util.Log.w(
                    "Playback",
                    "action=RETRY_CURRENT code=$errorCode type=$errorType track=$trackId " +
                        "index=$currentIndex position=$position",
                )
                player.seekTo(currentIndex, position)
                player.prepare()
                player.playWhenReady = wasPlayWhenReady
            }

            is PlaybackErrorRecovery.Action.SKIP_TO -> {
                android.util.Log.w(
                    "Playback",
                    "action=SKIP_TO index=${action.index} code=$errorCode " +
                        "type=$errorType track=$trackId",
                )
                player.seekTo(action.index, 0L)
                player.prepare()
                player.playWhenReady = wasPlayWhenReady
            }

            PlaybackErrorRecovery.Action.STOP -> {
                android.util.Log.w(
                    "Playback",
                    "action=STOP code=$errorCode type=$errorType track=$trackId",
                )
                player.playWhenReady = false
                player.stop()
            }
        }
    }

    private fun stopLocalAfterRecoveryDrainExceeded(
        player: ExoPlayer,
        error: PlaybackException,
        processed: Int,
    ) {
        val trackId = player.currentMediaItem?.mediaId
        invalidateCrossfadeForRecovery()
        player.playWhenReady = false
        player.stop()
        android.util.Log.e(
            "Playback",
            "action=STOP_RECOVERY_LIMIT processed=$processed code=${error.errorCode} " +
                "track=$trackId localActive=${mediaSession?.player === player}",
            error,
        )
    }

    private fun playbackErrorType(errorCode: Int): PlaybackErrorRecovery.ErrorType =
        when (errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
            -> PlaybackErrorRecovery.ErrorType.PERMANENT

            else -> PlaybackErrorRecovery.ErrorType.TRANSIENT
        }

    private fun clearTailCrossfadeForRecovery() {
        tailPlayer?.let {
            it.volume = 0f
            it.stop()
            it.clearMediaItems()
            it.setPlaybackSpeed(1f)
        }
        tailAudioAdvancing = false
        incomingAudioTrackId = null
    }

    private fun invalidateCrossfadeForRecovery() {
        clearTailCrossfadeForRecovery()
        crossfadeResetGeneration = if (crossfadeResetGeneration == Long.MAX_VALUE) {
            Long.MIN_VALUE
        } else {
            crossfadeResetGeneration + 1L
        }
    }

    private data class PreparedCast(
        val baseUrl: String,
        val entries: List<MediaItem?>,
    )

    private data class LocalHandoffFingerprint(
        val ids: List<String>,
        val index: Int,
        val position: Long,
        val playWhenReady: Boolean,
    )

    /** Chromecast: move the whole session (queue + position) to the TV. */
    private fun switchToCast() {
        mainScope.launch {
            val ticket = playbackHandoff.begin()
            val local = localPlayer ?: return@launch
            val cast = castPlayer ?: return@launch
            val dao = castDao ?: return@launch
            val session = mediaSession ?: return@launch
            val ids = (0 until local.mediaItemCount).map { local.getMediaItemAt(it).mediaId }
            if (ids.isEmpty()) {
                android.util.Log.w("Cast", "no se pasa a la TV: la cola local está vacía")
                return@launch
            }
            var candidate: com.aar.privatemusic.cast.MediaHttpServer? = null
            var previousServer: com.aar.privatemusic.cast.MediaHttpServer? = null
            var restorePlayWhenReady: Boolean? = null
            var commitAttempted = false
            var committed = false
            try {
                candidate = com.aar.privatemusic.cast.MediaHttpServer(dao)
                handoffServers += candidate
                val prepared = withContext(Dispatchers.IO) {
                    candidate.start()
                    val ip = com.aar.privatemusic.cast.MediaHttpServer.localIp()
                        ?: return@withContext null
                    val base = com.aar.privatemusic.cast.CastUrls.base(
                        ip,
                        com.aar.privatemusic.cast.MediaHttpServer.PORT,
                    )
                    PreparedCast(
                        baseUrl = base,
                        entries = ids.map { id -> dao.getSong(id)?.toCastItem(base) },
                    )
                }
                if (prepared == null) {
                    android.util.Log.w("Cast", "no se pasa a la TV: falta la red local")
                    return@launch
                }
                if (!playbackHandoff.isCurrent(ticket)) return@launch
                val activeCastSession = castContext?.sessionManager?.currentCastSession
                if (!cast.isCastSessionAvailable || activeCastSession == null) {
                    android.util.Log.w("Cast", "no se pasa a la TV: la sesión Cast ya no está disponible")
                    return@launch
                }
                if (session.player !== local) {
                    android.util.Log.d("Cast", "handoff descartado: la sesión ya no usa el reproductor local")
                    return@launch
                }
                val currentIds = localQueueIds(local)
                if (currentIds != ids) {
                    android.util.Log.d("Cast", "handoff descartado: la cola local cambió durante la preparación")
                    return@launch
                }
                val index = local.currentMediaItemIndex
                val position = local.currentPosition
                val wasPlayWhenReady = local.playWhenReady
                val queue = resolveHandoffQueue(prepared.entries, index)
                if (queue == null) {
                    android.util.Log.w("Cast", "no se pasa a la TV: la pista local actual no se resolvió")
                    return@launch
                }

                previousServer = httpServer
                restorePlayWhenReady = wasPlayWhenReady
                commitAttempted = true
                // El tail puede seguir sonando aunque el main se pause. Se cancela antes de que
                // Cast pueda hacerse audible, sin tocar la cola del reproductor local principal.
                invalidateCrossfadeForRecovery()
                local.pause()
                httpServer = candidate
                // Desde aquí, toda nueva canción debe convertirse en URL Cast.
                com.aar.privatemusic.cast.CastState.baseUrl = prepared.baseUrl
                // Media3 reads autoplay while constructing the receiver LOAD.
                cast.playWhenReady = wasPlayWhenReady
                cast.setMediaItems(queue.items, queue.currentIndex, position)
                session.player = cast
                com.aar.privatemusic.cast.CastState.castDeviceName.value =
                    activeCastSession.castDevice?.friendlyName ?: "TV"
                committed = true
                android.util.Log.d(
                    "Cast",
                    "session moved to cast: ${queue.items.size} items @$position " +
                        "playWhenReady=$wasPlayWhenReady",
                )

                if (committed && previousServer !== candidate) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { previousServer?.stop() }
                    }
                    previousServer?.let(handoffServers::remove)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (commitAttempted && !committed && playbackHandoff.isCurrent(ticket)) {
                    withContext(NonCancellable + Dispatchers.Main) {
                        rollbackCast(
                            ticket,
                            local,
                            cast,
                            session,
                            candidate,
                            previousServer,
                            checkNotNull(restorePlayWhenReady),
                        )
                    }
                }
                throw cancelled
            } catch (error: Throwable) {
                android.util.Log.e(
                    "Cast",
                    if (commitAttempted) "falló el commit del handoff" else "falló la preparación del handoff",
                    error,
                )
                if (commitAttempted) {
                    rollbackCast(
                        ticket,
                        local,
                        cast,
                        session,
                        candidate,
                        previousServer,
                        checkNotNull(restorePlayWhenReady),
                    )
                }
            } finally {
                if (!committed) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { candidate?.stop() }
                    }
                    candidate?.let(handoffServers::remove)
                }
            }
        }
    }

    private fun switchToLocal() {
        mainScope.launch {
            val ticket = playbackHandoff.begin()
            val local = localPlayer ?: return@launch
            val cast = castPlayer ?: return@launch
            val session = mediaSession ?: return@launch
            val castIds = (0 until cast.mediaItemCount).map { cast.getMediaItemAt(it).mediaId }
            val index = cast.currentMediaItemIndex
            val position = cast.currentPosition
            val wasPlayWhenReady = cast.playWhenReady
            // Capturamos primero la intención remota y mantenemos local mudo durante todo el
            // commit. Ninguna reconstrucción o fallback puede reactivarlo antes de enrutarlo.
            local.playWhenReady = false
            tailPlayer?.playWhenReady = false
            if (!playbackHandoff.isCurrent(ticket)) return@launch
            alignLocalOutput("cast-to-local", forceApply = true)
            if (!playbackHandoff.isCurrent(ticket)) return@launch
            runCatching { cast.stop() }
                .onFailure { android.util.Log.w("Cast", "no se pudo detener Cast", it) }
            if (!playbackHandoff.isCurrent(ticket)) return@launch
            val localExposed = runCatching { session.player = local }
                .onFailure { android.util.Log.e("Cast", "no se pudo devolver la sesión a local", it) }
                .isSuccess && session.player === local
            if (!localExposed) {
                local.playWhenReady = false
                tailPlayer?.playWhenReady = false
                android.util.Log.e("Cast", "handoff local abortado: la sesión no aceptó el reproductor local")
                return@launch
            }
            com.aar.privatemusic.cast.CastState.baseUrl = null
            com.aar.privatemusic.cast.CastState.castDeviceName.value = null
            val server = httpServer
            httpServer = null

            val localIds = localQueueIds(local)
            val dao = castDao
            val queueChanged = castIds.isNotEmpty() && castIds != localIds && dao != null
            var exposedFingerprint: LocalHandoffFingerprint? = null
            if (!queueChanged) {
                if (index in 0 until local.mediaItemCount) local.seekTo(index, position)
                local.playWhenReady = wasPlayWhenReady
            } else {
                exposedFingerprint = localFingerprint(local)
            }

            val resolved = try {
                withContext(Dispatchers.IO) {
                    runCatching { server?.stop() }
                    if (queueChanged) {
                        runCatching {
                            resolveHandoffQueue(
                                castIds.map { id -> dao?.getSong(id)?.toPlayableItem() },
                                index,
                            )
                        }.onFailure {
                            android.util.Log.e("Cast", "no se pudo resolver la cola local", it)
                        }.getOrNull()
                    } else {
                        null
                    }
                }
            } finally {
                server?.let(handoffServers::remove)
            }
            if (!playbackHandoff.isCurrent(ticket)) return@launch

            if (queueChanged) {
                val localUntouched = session.player === local &&
                    localFingerprint(local) == exposedFingerprint
                if (!localUntouched) {
                    android.util.Log.d("Cast", "resultado local descartado: el usuario cambió la reproducción")
                } else if (resolved == null) {
                    // Keep the previous local queue usable when the Cast item vanished.
                    local.playWhenReady = wasPlayWhenReady
                    android.util.Log.w(
                        "Cast",
                        "fallback a la cola local previa: falta la pista Cast actual",
                    )
                } else {
                    android.util.Log.d("Cast", "la cola cambió en la tele: se rehace en local (${castIds.size})")
                    local.setMediaItems(resolved.items, resolved.currentIndex, position)
                    local.prepare()
                    local.playWhenReady = wasPlayWhenReady
                }
            }
            android.util.Log.d("Cast", "session back to local @$position")
        }
    }

    private fun localQueueIds(local: ExoPlayer): List<String> =
        (0 until local.mediaItemCount).map { local.getMediaItemAt(it).mediaId }

    private fun localFingerprint(local: ExoPlayer) = LocalHandoffFingerprint(
        ids = localQueueIds(local),
        index = local.currentMediaItemIndex,
        position = local.currentPosition,
        playWhenReady = local.playWhenReady,
    )

    private suspend fun rollbackCast(
        ticket: PlaybackHandoff.Ticket,
        local: ExoPlayer,
        cast: androidx.media3.cast.CastPlayer,
        session: MediaLibrarySession,
        candidate: com.aar.privatemusic.cast.MediaHttpServer?,
        previousServer: com.aar.privatemusic.cast.MediaHttpServer?,
        wasPlayWhenReady: Boolean,
    ) {
        if (!playbackHandoff.isCurrent(ticket)) return
        local.playWhenReady = false
        tailPlayer?.playWhenReady = false
        alignLocalOutput("cast-rollback", forceApply = true)
        if (!playbackHandoff.isCurrent(ticket)) return
        runCatching { cast.stop() }
        val localExposed = runCatching { session.player = local }
            .onFailure { android.util.Log.e("Cast", "falló exponer local durante rollback", it) }
            .isSuccess && session.player === local
        if (!localExposed) {
            local.playWhenReady = false
            tailPlayer?.playWhenReady = false
            android.util.Log.e("Cast", "rollback abortado: la sesión no aceptó el reproductor local")
            return
        }
        com.aar.privatemusic.cast.CastState.baseUrl = null
        com.aar.privatemusic.cast.CastState.castDeviceName.value = null
        val publishedServer = httpServer
        httpServer = null
        runCatching { local.playWhenReady = wasPlayWhenReady }
        withContext(NonCancellable + Dispatchers.IO) {
            listOfNotNull(previousServer, publishedServer)
                .filter { it !== candidate }
                .distinct()
                .forEach { runCatching { it.stop() } }
        }
        previousServer?.let(handoffServers::remove)
        if (publishedServer !== candidate) publishedServer?.let(handoffServers::remove)
    }

    /**
     * Resuelve una sola salida física y aplica exactamente el mismo objeto (o null) a main/tail.
     * No usa el handoff de Cast: un hot-plug no debe invalidar una transición remota en curso.
     */
    private fun alignLocalOutput(reason: String, forceApply: Boolean = false) {
        val main = localPlayer
        val tail = tailPlayer
        val target = resolveLocalOutput()
        val targetIdentity = target?.let { AudioRouteTarget(it.id, it.type) }
        val routeChanged = !routeTargetInitialized || lastRouteTarget != targetIdentity
        val plan = AudioRoutePlan.evaluate(
            AudioRoutePlan.Input(
                localActive = mediaSession?.player === main,
                hasMain = main != null,
                hasTail = tail != null,
                mainAlreadyOnTarget = mainRouteApplied && mainRouteTarget == targetIdentity,
                tailAlreadyOnTarget = tailRouteApplied && tailRouteTarget == targetIdentity,
                routeChanged = routeChanged,
                forceApply = forceApply,
            ),
        )

        routeTargetInitialized = true
        lastRouteTarget = targetIdentity
        if (plan.invalidateCrossfade) {
            // Primero silencia y vacía el tail; retirar auriculares nunca debe dejarlo sonando.
            invalidateCrossfadeForRecovery()
        }
        if (plan.applyMain && main != null) {
            runCatching { main.setPreferredAudioDevice(target) }
                .onSuccess {
                    mainRouteApplied = true
                    mainRouteTarget = targetIdentity
                }
                .onFailure {
                    mainRouteApplied = false
                    android.util.Log.w("AudioRoute", "falló ruta main reason=$reason", it)
                }
        }
        if (plan.applyTail && tail != null) {
            runCatching { tail.setPreferredAudioDevice(target) }
                .onSuccess {
                    tailRouteApplied = true
                    tailRouteTarget = targetIdentity
                }
                .onFailure {
                    tailRouteApplied = false
                    android.util.Log.w("AudioRoute", "falló ruta tail reason=$reason", it)
                }
        }
        android.util.Log.d(
            "AudioRoute",
            "reason=$reason changed=$routeChanged active=${mediaSession?.player === main} " +
                "target=${target?.productName ?: "default"} id=${target?.id} type=${target?.type} " +
                "force=$forceApply apply=${plan.applyMain}/${plan.applyTail} " +
                "invalidate=${plan.invalidateCrossfade}",
        )
    }

    private fun resolveLocalOutput(): android.media.AudioDeviceInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val mediaAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val attributedExternal = runCatching {
                audioManager.getAudioDevicesForAttributes(mediaAttributes)
                    .filter { it.isSink && it.isSupportedExternalOutput() }
            }.onFailure {
                android.util.Log.w("AudioRoute", "falló getAudioDevicesForAttributes", it)
            }.getOrDefault(emptyList())
            if (attributedExternal.size == 1) return attributedExternal.single()
        }

        // getDevices conserva el orden que entrega el sistema: la primera externa soportada
        // hace determinista el fallback sin consultar dispositivos sólo emparejados.
        return runCatching {
            audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.isSink && it.isSupportedExternalOutput() }
        }.onFailure {
            android.util.Log.w("AudioRoute", "falló fallback getDevices; se usa default", it)
        }.getOrNull()
    }

    private fun android.media.AudioDeviceInfo.isSupportedExternalOutput(): Boolean =
        type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == android.media.AudioDeviceInfo.TYPE_HEARING_AID

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
                        incomingAudioTrackId = null
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
            var gainKey: PlaybackGain.ConfigKey? = null
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
            var armedTailErrorGeneration = tailPlayerErrorGeneration
            var armedAtPos = 0L
            var armedGainA = 1f
            var armedGainB = 1f
            var armedNextId: String? = null
            var armedLoudnessA: Float? = null
            var armedReplayGainA: Float? = null
            var armedLoudnessB: Float? = null
            var armedReplayGainB: Float? = null
            var armedConfig: PlaybackGain.ConfigKey? = null
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
            val lifecycle = CrossfadeLifecycle()
            var observedCrossfadeResetGeneration = crossfadeResetGeneration

            fun currentGainConfig(trackId: String? = player.currentMediaItem?.mediaId) =
                PlaybackGain.configKey(
                    trackId = trackId,
                    enabled = AppSettings.readNormalizeVolume(this@PlaybackService),
                    mode = AppSettings.readNormalizeMode(this@PlaybackService),
                )

            fun currentNextId(): String? {
                val index = player.nextMediaItemIndex
                return if (index != C.INDEX_UNSET && index in 0 until player.mediaItemCount) {
                    player.getMediaItemAt(index).mediaId
                } else {
                    null
                }
            }

            fun gainFor(
                loudness: Float?,
                replayGain: Float?,
                config: PlaybackGain.ConfigKey,
            ): Float = PlaybackGain.gain(
                loudnessDb = loudness,
                replayGainDb = replayGain,
                enabled = config.enabled,
                mode = when (config.mode) {
                    PlaybackGain.Mode.RMS -> "rms"
                    PlaybackGain.Mode.REPLAY_GAIN -> "replaygain"
                },
            )

            fun clearArmedNormalization() {
                armedLoudnessA = null
                armedReplayGainA = null
                armedLoudnessB = null
                armedReplayGainB = null
                armedConfig = null
            }

            fun refreshArmedGains(config: PlaybackGain.ConfigKey) {
                armedGainA = gainFor(armedLoudnessA, armedReplayGainA, config)
                armedGainB = gainFor(armedLoudnessB, armedReplayGainB, config)
                armedConfig = config
            }

            fun clearCrossfadeOperation(fade: Float, gain: Float) {
                tailPlayer?.let {
                    it.volume = 0f
                    it.stop()
                    it.clearMediaItems()
                    it.setPlaybackSpeed(1f)
                }
                xfEndsAt = 0L
                xfDurationMs = 1L
                xfGainA = 1f
                xfMainId = null
                armedForId = null
                armedAtPos = 0L
                armedGainA = 1f
                armedGainB = 1f
                armedNextId = null
                armedRatio = 1f
                armedEffDur = 0L
                clearArmedNormalization()
                tailAudioAdvancing = false
                incomingAudioTrackId = null
                player.volume = gain * fade
                touchedVolume = true
            }

            fun applyCrossfadeResetIfNeeded(fade: Float, gain: Float): Boolean {
                val requestedGeneration = crossfadeResetGeneration
                if (requestedGeneration == observedCrossfadeResetGeneration) return false
                observedCrossfadeResetGeneration = requestedGeneration
                clearCrossfadeOperation(fade, gain)
                lifecycle.reset()
                android.util.Log.d(
                    "Crossfade",
                    "recovery reset generation=$requestedGeneration",
                )
                return true
            }

            fun acceptOrRecover(
                transition: CrossfadeLifecycle.Transition,
                fade: Float,
                gain: Float,
            ): Boolean {
                if (transition.accepted) return true
                android.util.Log.w(
                    "Crossfade",
                    "transición ilegal event=${transition.event} phase=${transition.before.phase} " +
                        "track=${transition.before.trackId} next=${transition.before.nextId}; reset",
                )
                clearCrossfadeOperation(fade, gain)
                lifecycle.reset()
                return false
            }

            fun cancelCrossfade(
                reason: CrossfadeLifecycle.CancelReason,
                detail: String,
                fade: Float,
                gain: Float,
            ) {
                val transition = lifecycle.cancel(reason)
                clearCrossfadeOperation(fade, gain)
                if (!transition.accepted) {
                    android.util.Log.w(
                        "Crossfade",
                        "cancelación ilegal reason=$reason phase=${transition.before.phase}; reset",
                    )
                    lifecycle.reset()
                }
                android.util.Log.d("Crossfade", "cancel reason=$reason $detail")
            }

            fun tailFailedForCurrentOperation(): Boolean =
                tailPlayerErrorGeneration != armedTailErrorGeneration

            fun cancelIfTailFailed(checkpoint: String, fade: Float, gain: Float): Boolean {
                if (!tailFailedForCurrentOperation()) return false
                val failedTrackId = lifecycle.trackId ?: armedForId
                skipXfForId = failedTrackId
                cancelCrossfade(
                    CrossfadeLifecycle.CancelReason.TAIL_ERROR,
                    "checkpoint=$checkpoint track=$failedTrackId " +
                        "generation=$armedTailErrorGeneration/$tailPlayerErrorGeneration",
                    fade,
                    gain,
                )
                return true
            }

            fun invalidateCrossfade(detail: String, fade: Float, gain: Float) {
                clearCrossfadeOperation(fade, gain)
                lifecycle.reset()
                android.util.Log.d("Crossfade", "invalidated $detail")
            }

            // Ajuste máximo de tempo del AutoMix, releído cada iteración.
            var maxStretch = 0.10f

            volumeLoop@ while (isActive) {
                if (applyCrossfadeResetIfNeeded(SleepFade.level, gainFactor)) {
                    continue@volumeLoop
                }
                val observedId = player.currentMediaItem?.mediaId
                if (lifecycle.phase != CrossfadeLifecycle.Phase.IDLE &&
                    lifecycle.phase != CrossfadeLifecycle.Phase.CANCELLED &&
                    cancelIfTailFailed(
                        "phase=${lifecycle.phase}",
                        SleepFade.level,
                        gainFactor,
                    )
                ) {
                    delay(100)
                    continue@volumeLoop
                }
                if (lifecycle.phase == CrossfadeLifecycle.Phase.CANCELLED &&
                    observedId != lifecycle.trackId
                ) {
                    acceptOrRecover(lifecycle.trackChanged(observedId), SleepFade.level, gainFactor)
                } else if (lifecycle.phase != CrossfadeLifecycle.Phase.IDLE &&
                    lifecycle.phase != CrossfadeLifecycle.Phase.CANCELLED &&
                    observedId != lifecycle.expectedMainTrackId
                ) {
                    cancelCrossfade(
                        CrossfadeLifecycle.CancelReason.NAVIGATION,
                        "track=${lifecycle.trackId} observed=$observedId phase=${lifecycle.phase}",
                        SleepFade.level,
                        gainFactor,
                    )
                }
                // En pausa no hay volumen que gestionar, y el servicio sigue vivo
                // mucho después de pausar. Sin esta guarda el bucle ticaba dos
                // veces por segundo para siempre: medido en el Pixel, 0,75% de un
                // núcleo con la pantalla apagada y la música parada.
                //
                // El tope de 5 s es una red: si alguna vez se perdiera el evento
                // de "ha empezado a sonar", el bucle despierta solo en vez de
                // quedarse dormido con la música puesta.
                if (!player.isPlaying && xfEndsAt == 0L) {
                    if (lifecycle.phase != CrossfadeLifecycle.Phase.IDLE &&
                        lifecycle.phase != CrossfadeLifecycle.Phase.CANCELLED
                    ) {
                        cancelCrossfade(
                            CrossfadeLifecycle.CancelReason.PAUSED,
                            "phase=${lifecycle.phase}",
                            SleepFade.level,
                            gainFactor,
                        )
                    }
                    kotlinx.coroutines.withTimeoutOrNull(5_000) {
                        playing.first { it }
                    }
                    continue
                }
                val tail = tailPlayer
                val crossfadeMs = AppSettings.readCrossfadeSec(this@PlaybackService) * 1000L
                val normalize = AppSettings.readNormalizeVolume(this@PlaybackService)
                val normalizeMode = AppSettings.readNormalizeMode(this@PlaybackService)
                maxStretch = AppSettings.readAutoMixMaxStretch(this@PlaybackService)
                val autoMix = AppSettings.readAutoMix(this@PlaybackService)
                val xfActive = xfEndsAt != 0L
                // Fundido del temporizador de apagado (0..1): multiplicador global
                // que se aplica a TODA escritura de volumen, para que no se pelee con
                // el crossfade/normalización escribiendo controller.volume por fuera.
                val fade = SleepFade.level
                val id = player.currentMediaItem?.mediaId
                val requestedGainKey = PlaybackGain.configKey(id, normalize, normalizeMode)

                if (requestedGainKey != gainKey) {
                    // Never let the previous track/configuration leak while Room is
                    // resolving the new one.
                    gainFactor = 1f
                    player.volume = fade
                    touchedVolume = true
                    gainKey = null

                    if (!requestedGainKey.enabled || requestedGainKey.trackId == null) {
                        gainKey = requestedGainKey
                    } else {
                        val requestedTrackId = requireNotNull(requestedGainKey.trackId)
                        val lookup: Result<Pair<Float?, Float?>> = withContext(Dispatchers.IO) {
                            runCatching {
                                dao.getLoudness(requestedTrackId) to
                                    dao.getReplayGain(requestedTrackId)
                            }
                        }
                        // Track and settings may have changed while Room was running.
                        // Cache/apply only the exact request that is still current.
                        val currentKey = currentGainConfig()
                        if (currentKey == requestedGainKey) {
                            lookup.fold(
                                onSuccess = { (loudness, replayGain) ->
                                    gainFactor = gainFor(loudness, replayGain, requestedGainKey)
                                    gainKey = requestedGainKey
                                },
                                onFailure = {
                                    // Unity stays temporary and uncached, so a
                                    // transient Room failure is retried next tick.
                                    android.util.Log.w(
                                        "Playback",
                                        "normalization lookup failed track=${requestedGainKey.trackId}",
                                        it,
                                    )
                                },
                            )
                        }
                    }
                }

                // The Room lookup above suspends on IO. A player error can be recovered on Main
                // while it is in flight, so observe the reset again before driving any crossfade
                // state or volume curve with the old coroutine-local snapshot.
                if (applyCrossfadeResetIfNeeded(SleepFade.level, gainFactor)) continue

                if (crossfadeMs == 0L &&
                    lifecycle.phase != CrossfadeLifecycle.Phase.IDLE &&
                    lifecycle.phase != CrossfadeLifecycle.Phase.CANCELLED
                ) {
                    cancelCrossfade(
                        CrossfadeLifecycle.CancelReason.WINDOW_CLOSED,
                        "crossfade desactivado",
                        fade,
                        gainFactor,
                    )
                }

                // Con el fade activo (fade < 1) NO hacemos el early-return: hay que
                // seguir aplicando el multiplicador al volumen aunque no haya crossfade
                // ni normalización.
                if (crossfadeMs == 0L && !normalize && !xfActive && fade >= 1f) {
                    if (touchedVolume) {
                        player.volume = 1f
                        touchedVolume = false
                    }
                    delay(500)
                    continue
                }

                // ---- Overlap in progress: drive both volume curves. ----
                if (xfActive && tail != null) {
                    if (cancelIfTailFailed("overlap", fade, gainFactor)) {
                        // B recupera el volumen completo en este mismo tick; el
                        // listener del tail nunca toca la cola del principal.
                        touchedVolume = true
                        delay(100)
                        continue@volumeLoop
                    }
                    val remainingXf = xfEndsAt - android.os.SystemClock.elapsedRealtime()
                    // NOT isPlaying: right after seekToNextMediaItem the main player
                    // dips into BUFFERING for a tick (much more likely with the screen
                    // off / CPU throttled), and aborting there cut track A dead.
                    // playWhenReady only drops when the user actually pauses.
                    val aborted = player.currentMediaItem?.mediaId != xfMainId || !player.playWhenReady
                    if (remainingXf <= 0 || aborted) {
                        if (aborted) {
                            val reason = if (!player.playWhenReady) {
                                CrossfadeLifecycle.CancelReason.PAUSED
                            } else {
                                CrossfadeLifecycle.CancelReason.NAVIGATION
                            }
                            cancelCrossfade(reason, "durante overlap", fade, gainFactor)
                        } else if (acceptOrRecover(lifecycle.complete(), fade, gainFactor)) {
                            clearCrossfadeOperation(fade, gainFactor)
                            android.util.Log.d("Crossfade", "overlap completo")
                        }
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
                val duration = com.aar.privatemusic.player.CrossfadeGate.effectiveDurationMs(
                    playerDurationMs = player.duration,
                    metadataDurationMs = player.currentMediaItem?.mediaMetadata?.durationMs,
                    timeUnset = androidx.media3.common.C.TIME_UNSET,
                )
                if (tail != null && com.aar.privatemusic.player.CrossfadeGate.windowOpen(
                        crossfadeMs = crossfadeMs,
                        durationMs = duration,
                        isPlaying = player.isPlaying,
                        hasNext = player.hasNextMediaItem(),
                        repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE,
                        timeUnset = androidx.media3.common.C.TIME_UNSET,
                    )
                ) {
                    val curItem = player.currentMediaItem
                    val curUri = curItem?.localConfiguration?.uri
                    val curId = curItem?.mediaId
                    skipXfForId = com.aar.privatemusic.player.CrossfadeGate.keepSkipMark(skipXfForId, curId)
                    // `curId` se rescata aparte para que el compilador siga
                    // sabiendo que no es nulo dentro del bloque.
                    val eligible = curId != null &&
                        com.aar.privatemusic.player.CrossfadeGate.trackEligible(
                            hasUri = curUri != null,
                            mediaId = curId,
                            skipXfForId = skipXfForId,
                        )
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
                    if (eligible && lifecycle.phase == CrossfadeLifecycle.Phase.IDLE &&
                        armedForId != curId &&
                        remaining < crossfadeMs + 25000 &&
                        nextIdx != androidx.media3.common.C.INDEX_UNSET
                    ) {
                        val nextId = player.getMediaItemAt(nextIdx).mediaId
                        val prearmConfig = PlaybackGain.configKey(curId, normalize, normalizeMode)
                        if (!acceptOrRecover(lifecycle.prearm(curId, nextId), fade, gainFactor)) {
                            delay(100)
                            continue
                        }
                        // Baseline por operación: un fallo antiguo queda absorbido
                        // aquí y sólo los cambios posteriores invalidan este prearm.
                        armedTailErrorGeneration = tailPlayerErrorGeneration
                        val prearmLookup = withContext(Dispatchers.IO) {
                            runCatching {
                                val la = dao.getLoudness(curId)
                                val lb = dao.getLoudness(nextId)
                                val rga = dao.getReplayGain(curId)
                                val rgb = dao.getReplayGain(nextId)
                                val ba = dao.getBpm(curId)
                                val bb = dao.getBpm(nextId)
                                val ts = dao.getTailSilence(curId) ?: 0L
                                val decision = decideTempoMatch(ba, bb, maxStretch)
                                MixInfoHolder.publish(MixInfo(ba, bb, decision, autoMix))
                                Triple(listOf(la, lb, rga, rgb), decision.appliedRatio, ts)
                            }
                        }
                        if (applyCrossfadeResetIfNeeded(fade, gainFactor)) continue@volumeLoop
                        if (cancelIfTailFailed("prearm lookup", fade, gainFactor)) {
                            delay(100)
                            continue@volumeLoop
                        }
                        val observedNextId = currentNextId()
                        val observedConfig = currentGainConfig(curId)
                        if (player.currentMediaItem?.mediaId != curId ||
                            observedNextId != nextId ||
                            observedConfig != prearmConfig
                        ) {
                            invalidateCrossfade(
                                "durante prearm track=$curId observed=${player.currentMediaItem?.mediaId} " +
                                    "next=$nextId/$observedNextId config=$prearmConfig/$observedConfig",
                                fade,
                                gainFactor,
                            )
                            delay(100)
                            continue
                        }
                        val prearmFailure = prearmLookup.exceptionOrNull()
                        if (prearmFailure != null) {
                            android.util.Log.w(
                                "Crossfade",
                                "prearm lookup failed track=$curId",
                                prearmFailure,
                            )
                            cancelCrossfade(
                                CrossfadeLifecycle.CancelReason.PREPARATION_FAILED,
                                "Room lookup track=$curId next=$nextId",
                                fade,
                                gainFactor,
                            )
                            delay(100)
                            continue
                        }
                        val prearmData = prearmLookup.getOrThrow()
                        val (gains, ratio, tailSilence) = prearmData
                        if (player.currentMediaItem?.mediaId == curId) {
                            armedForId = curId
                            // Trim the silent/fading tail, but never more than half the window.
                            armedEffDur = (duration - tailSilence)
                                .coerceAtLeast(player.currentPosition + 500)
                            armedAtPos = armedEffDur - crossfadeMs
                            armedLoudnessA = gains[0]
                            armedLoudnessB = gains[1]
                            armedReplayGainA = gains[2]
                            armedReplayGainB = gains[3]
                            refreshArmedGains(prearmConfig)
                            armedNextId = nextId
                            armedRatio = ratio
                            if (armedAtPos > player.currentPosition + 100) {
                                tail.playWhenReady = false
                                tail.volume = 0f
                                tail.setPlaybackSpeed(if (autoMix) ratio else 1f)
                                tail.setMediaItem(
                                    MediaItem.Builder().setMediaId(curId).setUri(curUri).build(),
                                    armedAtPos,
                                )
                                tail.prepare()
                            }
                            if (!acceptOrRecover(lifecycle.armed(), fade, gainFactor)) {
                                delay(100)
                                continue
                            }
                            val mix = MixInfoHolder.info.value
                            android.util.Log.d(
                                "Crossfade",
                                "pre-armed at $armedAtPos ratio=$ratio requiredRatio=${mix?.requiredRatio} " +
                                    "autoMix=$autoMix bpm=${mix?.bpmOut}->${mix?.bpmIn} " +
                                "tailSilenceMs=$tailSilence",
                            )
                        } else {
                            cancelCrossfade(
                                CrossfadeLifecycle.CancelReason.NAVIGATION,
                                "durante prearm track=$curId observed=${player.currentMediaItem?.mediaId}",
                                fade,
                                gainFactor,
                            )
                            delay(100)
                            continue
                        }
                    }

                    if (eligible && armedForId == curId && remaining < crossfadeMs &&
                        nextIdx != androidx.media3.common.C.INDEX_UNSET
                    ) {
                        val firingNextId = currentNextId()
                        if (firingNextId == null || firingNextId != armedNextId) {
                            cancelCrossfade(
                                CrossfadeLifecycle.CancelReason.NAVIGATION,
                                "antes de firing track=$curId next=$armedNextId/$firingNextId",
                                fade,
                                gainFactor,
                            )
                            delay(100)
                            continue
                        }
                        val firingConfig = currentGainConfig(curId)
                        if (firingConfig != armedConfig) refreshArmedGains(firingConfig)
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
                                MediaItem.Builder().setMediaId(curId).setUri(curUri).build(),
                                player.currentPosition,
                            )
                            tail.prepare()
                            armedForId = curId
                            armedAtPos = player.currentPosition
                            var prep = 0
                            while (tail.playbackState != Player.STATE_READY && prep < 1200) {
                                if (applyCrossfadeResetIfNeeded(fade, gainFactor)) {
                                    continue@volumeLoop
                                }
                                if (cancelIfTailFailed("fallback prepare", fade, gainFactor)) {
                                    continue@volumeLoop
                                }
                                delay(20)
                                if (applyCrossfadeResetIfNeeded(fade, gainFactor)) {
                                    continue@volumeLoop
                                }
                                if (cancelIfTailFailed("fallback prepare", fade, gainFactor)) {
                                    continue@volumeLoop
                                }
                                prep += 20
                            }
                            if (applyCrossfadeResetIfNeeded(fade, gainFactor)) continue@volumeLoop
                            if (cancelIfTailFailed("fallback prepared", fade, gainFactor)) {
                                continue@volumeLoop
                            }
                            if (tail.playbackState != Player.STATE_READY) {
                                skipXfForId = curId
                                cancelCrossfade(
                                    CrossfadeLifecycle.CancelReason.PREPARATION_FAILED,
                                    "track=$curId waitedMs=$prep",
                                    fade,
                                    gainFactor,
                                )
                                delay(100)
                                continue
                            }
                        }
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
                                if (applyCrossfadeResetIfNeeded(fade, gainFactor)) {
                                    continue@volumeLoop
                                }
                                if (cancelIfTailFailed("resync", fade, gainFactor)) {
                                    continue@volumeLoop
                                }
                                delay(20)
                                if (applyCrossfadeResetIfNeeded(fade, gainFactor)) {
                                    continue@volumeLoop
                                }
                                if (cancelIfTailFailed("resync", fade, gainFactor)) {
                                    continue@volumeLoop
                                }
                                resync += 20
                            }
                        }
                        if (applyCrossfadeResetIfNeeded(fade, gainFactor)) continue@volumeLoop
                        if (cancelIfTailFailed("resync complete", fade, gainFactor)) {
                            continue@volumeLoop
                        }
                        // Spin the tail up MUTED: its AudioTrack takes 100-800ms
                        // (longer with the AutoMix tempo bend: Sonic lengthens the
                        // pipeline) to actually push samples, and during that
                        // warm-up the main player keeps advancing — an audible
                        // tail would replay that stretch of A. Muted, waiting
                        // longer costs nothing: A keeps sounding on the main.
                        tail.volume = 0f
                        tailAudioAdvancing = false
                        // Punto de referencia para descontar el tempo del AutoMix
                        // del cálculo de deriva (ver `realDrift`): aún parado, la
                        // posición del tail ES el destino del último seek.
                        var refTailPos = tail.currentPosition
                        if (!acceptOrRecover(lifecycle.warming(), fade, gainFactor)) {
                            delay(100)
                            continue
                        }
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
                            if (applyCrossfadeResetIfNeeded(fade, gainFactor)) {
                                continue@volumeLoop
                            }
                            if (cancelIfTailFailed("warming", fade, gainFactor)) {
                                continue@volumeLoop
                            }
                            delay(20)
                            if (applyCrossfadeResetIfNeeded(fade, gainFactor)) {
                                continue@volumeLoop
                            }
                            if (cancelIfTailFailed("warming", fade, gainFactor)) {
                                continue@volumeLoop
                            }
                            waited += 20
                        }
                        if (applyCrossfadeResetIfNeeded(fade, gainFactor)) continue@volumeLoop
                        if (cancelIfTailFailed("warming complete", fade, gainFactor)) {
                            continue@volumeLoop
                        }
                        // Right after start the position tracker overshoots by up
                        // to the AudioTrack buffer (~350ms): let it settle on the
                        // real audio clock before trusting any measurement.
                        if (applyCrossfadeResetIfNeeded(fade, gainFactor)) continue@volumeLoop
                        delay(120)
                        if (applyCrossfadeResetIfNeeded(fade, gainFactor)) continue@volumeLoop
                        if (cancelIfTailFailed("warming settle", fade, gainFactor)) {
                            continue@volumeLoop
                        }
                        // Measure how far the tail's CONTENT is from the main's
                        // and re-seek predicting the restart latency, so both A
                        // streams line up when the tail becomes audible.
                        // El tail corre al tempo del AutoMix, así que se separa del
                        // principal a propósito: con ratio 1.1 y 2.6s de calentamiento
                        // son 260ms de "deriva" que no es tal, y que bastaban para
                        // saltarse el fundido (activar AutoMix hacía MENOS probable
                        // que hubiera fundido). Lo que el tail ha avanzado de más por
                        // el tempo es exactamente `avance * (1 - 1/ratio)`: se descuenta
                        // para decidir si el relevo está sano. El seek, en cambio, sigue
                        // usando la deriva REAL: hay que alinearlos de verdad.
                        val speed = if (autoMix) armedRatio else 1f
                        fun realDrift(rawDrift: Long, tailPos: Long): Long {
                            if (speed == 1f) return rawDrift
                            val advanced = (tailPos - refTailPos).coerceAtLeast(0L)
                            return rawDrift + (advanced * (1f - 1f / speed)).toLong()
                        }
                        var tailPos = tail.currentPosition
                        var drift = player.currentPosition - tailPos
                        if (tailAudioAdvancing && kotlin.math.abs(drift) > 60) {
                            val restartLatency = if (drift > 0) drift + 40 else 120L
                            tailAudioAdvancing = false
                            val target = player.currentPosition + restartLatency
                            tail.seekTo(target)
                            refTailPos = target
                            var rewait = 0
                            while (!tailAudioAdvancing && rewait < 600) {
                                if (applyCrossfadeResetIfNeeded(fade, gainFactor)) {
                                    continue@volumeLoop
                                }
                                if (cancelIfTailFailed("warming reseek", fade, gainFactor)) {
                                    continue@volumeLoop
                                }
                                delay(20)
                                if (applyCrossfadeResetIfNeeded(fade, gainFactor)) {
                                    continue@volumeLoop
                                }
                                if (cancelIfTailFailed("warming reseek", fade, gainFactor)) {
                                    continue@volumeLoop
                                }
                                rewait += 20
                            }
                            if (applyCrossfadeResetIfNeeded(fade, gainFactor)) continue@volumeLoop
                            if (cancelIfTailFailed("warming reseek complete", fade, gainFactor)) {
                                continue@volumeLoop
                            }
                            if (applyCrossfadeResetIfNeeded(fade, gainFactor)) continue@volumeLoop
                            delay(120)
                            if (applyCrossfadeResetIfNeeded(fade, gainFactor)) continue@volumeLoop
                            if (cancelIfTailFailed("warming reseek settle", fade, gainFactor)) {
                                continue@volumeLoop
                            }
                            tailPos = tail.currentPosition
                            drift = player.currentPosition - tailPos
                        }
                        if (!player.playWhenReady) {
                            cancelCrossfade(
                                CrossfadeLifecycle.CancelReason.PAUSED,
                                "durante warming track=$curId",
                                fade,
                                gainFactor,
                            )
                            delay(100)
                            continue
                        }
                        if (!tailAudioAdvancing) {
                            // No real audio (cold pipeline) or still misaligned:
                            // a blind handover replays part of A. Skip this
                            // crossfade — plain gapless beats an audible glitch.
                            skipXfForId = curId
                            cancelCrossfade(
                                CrossfadeLifecycle.CancelReason.TAIL_NO_AUDIO,
                                "driftMs=$drift realDriftMs=${realDrift(drift, tailPos)} waitedMs=$waited",
                                fade,
                                gainFactor,
                            )
                            delay(100)
                            continue
                        }
                        if (kotlin.math.abs(realDrift(drift, tailPos)) > 250) {
                            skipXfForId = curId
                            cancelCrossfade(
                                CrossfadeLifecycle.CancelReason.EXCESSIVE_DRIFT,
                                "driftMs=$drift realDriftMs=${realDrift(drift, tailPos)} waitedMs=$waited",
                                fade,
                                gainFactor,
                            )
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
                            cancelCrossfade(
                                CrossfadeLifecycle.CancelReason.NAVIGATION,
                                "durante warming track=$curId observed=${player.currentMediaItem?.mediaId}",
                                fade,
                                gainFactor,
                            )
                            delay(100)
                            continue
                        }
                        val warmedNextId = currentNextId()
                        if (warmedNextId == null || warmedNextId != armedNextId) {
                            cancelCrossfade(
                                CrossfadeLifecycle.CancelReason.NAVIGATION,
                                "tras warming track=$curId next=$armedNextId/$warmedNextId",
                                fade,
                                gainFactor,
                            )
                            delay(100)
                            continue
                        }
                        val warmedConfig = currentGainConfig(curId)
                        if (warmedConfig != armedConfig) refreshArmedGains(warmedConfig)
                        gainFactor = armedGainA
                        gainKey = warmedConfig
                        xfGainA = armedGainA
                        xfMainId = warmedNextId
                        if (!acceptOrRecover(lifecycle.handover(), fade, gainFactor)) {
                            delay(100)
                            continue
                        }
                        // Hand the OUTGOING track over from the main player to the
                        // tail player. BOTH are playing the SAME audio A here, so
                        // this sub-fade must be LINEAR-complementary (main down,
                        // tail up, sum held constant at gainA). The old hard jump to
                        // full tail while main ramped down doubled correlated A
                        // (~+5 dB) and clipped the mixer — that was the "pop".
                        if (applyCrossfadeResetIfNeeded(fade, gainFactor)) continue@volumeLoop
                        delay(80)
                        if (applyCrossfadeResetIfNeeded(fade, gainFactor)) continue@volumeLoop
                        if (cancelIfTailFailed("handover settle", SleepFade.level, gainFactor)) {
                            continue@volumeLoop
                        }
                        for (step in 0..5) {
                            if (applyCrossfadeResetIfNeeded(SleepFade.level, gainFactor)) {
                                continue@volumeLoop
                            }
                            if (cancelIfTailFailed("handover subfade", SleepFade.level, gainFactor)) {
                                continue@volumeLoop
                            }
                            // El salto puede caer en estos ~120ms: si el principal
                            // deja de ser A, paramos de tocar SU volumen en el acto.
                            if (player.currentMediaItem?.mediaId != curId ||
                                !player.playWhenReady
                            ) {
                                break
                            }
                            val s = step / 5f
                            val handoverFade = SleepFade.level
                            tail.volume = xfGainA * s * handoverFade
                            player.volume = gainFactor * (1f - s) * handoverFade
                            delay(20)
                            if (applyCrossfadeResetIfNeeded(SleepFade.level, gainFactor)) {
                                continue@volumeLoop
                            }
                            if (cancelIfTailFailed("handover subfade", SleepFade.level, gainFactor)) {
                                continue@volumeLoop
                            }
                        }
                        if (applyCrossfadeResetIfNeeded(SleepFade.level, gainFactor)) {
                            continue@volumeLoop
                        }
                        if (cancelIfTailFailed("handover complete", SleepFade.level, gainFactor)) {
                            continue@volumeLoop
                        }
                        // Última comprobación antes de avanzar nosotros: si navegó
                        // durante el fundido, NO hacemos seekToNextMediaItem (saltaría
                        // una pista); restauramos el volumen y que siga su navegación.
                        val handoverNextId = currentNextId()
                        if (player.currentMediaItem?.mediaId != curId ||
                            !player.playWhenReady ||
                            handoverNextId == null ||
                            handoverNextId != armedNextId
                        ) {
                            val reason = if (!player.playWhenReady) {
                                CrossfadeLifecycle.CancelReason.PAUSED
                            } else {
                                CrossfadeLifecycle.CancelReason.NAVIGATION
                            }
                            cancelCrossfade(reason, "durante handover track=$curId", fade, gainFactor)
                            delay(100)
                            continue
                        }
                        // Settings can change during warm-up or the linear A->tail
                        // handover. Recompute both gains from the pre-armed raw data
                        // and pin the exact configuration used for B.
                        val handoverConfig = currentGainConfig(curId)
                        if (handoverConfig != armedConfig) refreshArmedGains(handoverConfig)
                        gainFactor = armedGainA
                        gainKey = handoverConfig
                        xfGainA = armedGainA
                        xfMainId = handoverNextId
                        // A queda audible en el tail mientras B salta/prepara a
                        // volumen cero. El reloj del overlap todavía NO existe:
                        // se publicará sólo cuando el callback confirme audio de B.
                        val initialRemainingContentMs = (armedEffDur - tail.currentPosition)
                            .coerceAtMost(crossfadeMs)
                        val initialWallFadeMs = contentDurationToWallMs(
                            initialRemainingContentMs,
                            speed,
                        )
                        val startGuard = CrossfadeStartGuard(initialWallFadeMs)
                        tail.volume = xfGainA * SleepFade.level
                        player.volume = 0f
                        incomingAudioTrackId = null
                        player.seekToNextMediaItem()
                        gainFactor = armedGainB
                        gainKey = PlaybackGain.ConfigKey(
                            trackId = handoverNextId,
                            enabled = handoverConfig.enabled,
                            mode = handoverConfig.mode,
                        )
                        xfMainId = handoverNextId
                        if (applyCrossfadeResetIfNeeded(SleepFade.level, gainFactor)) {
                            continue@volumeLoop
                        }
                        val waitStartedAt = android.os.SystemClock.elapsedRealtime()
                        android.util.Log.d(
                            "Crossfade",
                            "incoming wait initialContentMs=$initialRemainingContentMs " +
                                "initialWallMs=$initialWallFadeMs budgetMs=${startGuard.waitBudgetMs} " +
                                "gainA=$xfGainA gainB=$gainFactor driftMs=$drift",
                        )

                        while (true) {
                            if (applyCrossfadeResetIfNeeded(SleepFade.level, gainFactor)) {
                                continue@volumeLoop
                            }
                            val now = android.os.SystemClock.elapsedRealtime()
                            val elapsedWaitingMs = (now - waitStartedAt).coerceAtLeast(0L)
                            val remainingContentMs = (armedEffDur - tail.currentPosition)
                                .coerceAtMost(crossfadeMs)
                            val remainingTailWallMs = contentDurationToWallMs(
                                remainingContentMs,
                                speed,
                            )
                            val observedMainTrackId = player.currentMediaItem?.mediaId
                            val tailError = tailFailedForCurrentOperation()
                            when (
                                val decision = startGuard.evaluate(
                                    CrossfadeStartGuard.Input(
                                        expectedMainTrackId = handoverNextId,
                                        observedMainTrackId = observedMainTrackId,
                                        playWhenReady = player.playWhenReady,
                                        incomingAudioTrackId = incomingAudioTrackId,
                                        tailError = tailError,
                                        elapsedWaitingMs = elapsedWaitingMs,
                                        remainingTailWallMs = remainingTailWallMs,
                                    ),
                                )
                            ) {
                                CrossfadeStartGuard.Decision.WAIT -> {
                                    val waitFade = SleepFade.level
                                    tail.volume = xfGainA * waitFade
                                    player.volume = 0f
                                    touchedVolume = true
                                    if (applyCrossfadeResetIfNeeded(waitFade, gainFactor)) {
                                        continue@volumeLoop
                                    }
                                    delay(20)
                                    if (applyCrossfadeResetIfNeeded(SleepFade.level, gainFactor)) {
                                        continue@volumeLoop
                                    }
                                }

                                is CrossfadeStartGuard.Decision.START -> {
                                    if (!acceptOrRecover(
                                            lifecycle.overlapping(),
                                            SleepFade.level,
                                            gainFactor,
                                        )
                                    ) {
                                        continue@volumeLoop
                                    }
                                    val overlapStartedAt = android.os.SystemClock.elapsedRealtime()
                                    xfDurationMs = decision.fadeMs
                                    xfEndsAt = overlapStartedAt + decision.fadeMs
                                    val overlapFade = SleepFade.level
                                    tail.volume = xfGainA * overlapFade
                                    player.volume = 0f
                                    armedForId = null
                                    armedAtPos = 0L
                                    armedGainA = 1f
                                    armedGainB = 1f
                                    armedNextId = null
                                    armedRatio = 1f
                                    armedEffDur = 0L
                                    clearArmedNormalization()
                                    android.util.Log.d(
                                        "Crossfade",
                                        "overlap start initialWallMs=$initialWallFadeMs " +
                                            "remainingWallMs=${decision.fadeMs} " +
                                            "waitMs=$elapsedWaitingMs gainA=$xfGainA " +
                                            "gainB=$gainFactor driftMs=$drift",
                                    )
                                    touchedVolume = true
                                    if (applyCrossfadeResetIfNeeded(overlapFade, gainFactor)) {
                                        continue@volumeLoop
                                    }
                                    delay(100)
                                    if (applyCrossfadeResetIfNeeded(SleepFade.level, gainFactor)) {
                                        continue@volumeLoop
                                    }
                                    continue@volumeLoop
                                }

                                CrossfadeStartGuard.Decision.ABORT -> {
                                    val reason = when {
                                        tailError -> CrossfadeLifecycle.CancelReason.TAIL_ERROR
                                        !player.playWhenReady -> CrossfadeLifecycle.CancelReason.PAUSED
                                        lifecycle.nextId == null ||
                                            observedMainTrackId != lifecycle.nextId ->
                                            CrossfadeLifecycle.CancelReason.NAVIGATION
                                        else -> CrossfadeLifecycle.CancelReason.INCOMING_NO_AUDIO
                                    }
                                    if (reason == CrossfadeLifecycle.CancelReason.TAIL_ERROR ||
                                        reason == CrossfadeLifecycle.CancelReason.INCOMING_NO_AUDIO
                                    ) {
                                        skipXfForId = curId
                                    }
                                    cancelCrossfade(
                                        reason,
                                        "post-jump track=$curId expected=$handoverNextId " +
                                            "observed=$observedMainTrackId audio=$incomingAudioTrackId " +
                                            "initialWallMs=$initialWallFadeMs " +
                                            "remainingWallMs=$remainingTailWallMs " +
                                            "waitMs=$elapsedWaitingMs gainA=$xfGainA " +
                                            "gainB=$gainFactor driftMs=$drift " +
                                            "generation=$armedTailErrorGeneration/" +
                                            tailPlayerErrorGeneration,
                                        SleepFade.level,
                                        gainFactor,
                                    )
                                    touchedVolume = true
                                    continue@volumeLoop
                                }
                            }
                        }
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
        // Invalidate first: no suspended handoff may commit during teardown.
        playbackHandoff.begin()
        if (audioDeviceCallbackRegistered) {
            runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
                .onFailure {
                    android.util.Log.w("AudioRoute", "no se pudo desregistrar el callback", it)
                }
            audioDeviceCallbackRegistered = false
        }
        playbackErrorRecovery.reset()
        recoveringPlaybackError = false
        pendingPlaybackError = null
        mainScope.cancel()
        val servers = (handoffServers + listOfNotNull(httpServer)).distinct()
        handoffServers.clear()
        httpServer = null
        kotlinx.coroutines.runBlocking {
            withContext(Dispatchers.IO) {
                servers.forEach { runCatching { it.stop() } }
            }
        }
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
        com.aar.privatemusic.cast.CastState.baseUrl = null
        EqHolder.release()
        serviceScope.cancel()
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

    /**
     * Controllers send bare mediaIds; resolve them to playable items with a real URI.
     *
     * Es el embudo por el que pasa TODO lo que la app pone a sonar: elegir una
     * canción, cambiar de playlist, encolar, restaurar la sesión... Por eso es
     * aquí donde se decide si la pista viaja como fichero local o como URL: si
     * está sonando en la tele, un `file://` del móvil no lo alcanza nadie y la
     * tele se queda muda sin decir nada (era justo lo que pasaba al cambiar de
     * canción mientras se comparte).
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = scope.future {
        val base = com.aar.privatemusic.cast.CastState.baseUrl
        mediaItems.map { item ->
            val requestUri = item.requestMetadata.mediaUri
            when {
                // Sonando en la tele: la canción de la biblioteca se sirve por HTTP.
                base != null && requestUri == null && !item.mediaId.startsWith("preview:") ->
                    dao.getSong(item.mediaId)?.toCastItem(base) ?: item.also {
                        android.util.Log.w(
                            "Cast",
                            "\"${it.mediaId}\" no está en la biblioteca: la tele no puede reproducirla",
                        )
                    }
                // Karaoke: el instrumental es un fichero del móvil que la tele no
                // alcanza. Se deja tal cual (sonará en el móvil, no en la tele).
                base != null && requestUri != null -> item.buildUpon().setUri(requestUri).build().also {
                    android.util.Log.w("Cast", "pista local (karaoke/preescucha): no viaja a la tele")
                }
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

/**
 * La misma canción, pero como la tiene que ver la tele: servida por HTTP.
 *
 * Un `file://` del móvil no lo alcanza nadie desde el salón, así que audio y
 * carátula salen por el servidor local. Lo usan los dos caminos que mandan
 * música a la tele —empezar a compartir y cambiar de canción o de playlist
 * mientras suena— para que no vuelvan a divergir.
 */
private fun Song.toCastItem(base: String): MediaItem {
    val artUri = when {
        artPath?.let { File(it).canRead() } == true -> com.aar.privatemusic.cast.CastUrls.art(base, id)
        !thumbnailUrl.isNullOrBlank() -> thumbnailUrl
        else -> null
    }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(com.aar.privatemusic.cast.CastUrls.song(base, id))
        .setMimeType(com.aar.privatemusic.cast.CastUrls.mimeFor(filePath))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                // Sin tipo, el receptor por defecto asume vídeo y pinta la ficha
                // de la canción como si fuera una película.
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setDurationMs(durationSec * 1000L)
                .setArtworkUri(artUri?.let { Uri.parse(it) })
                .build()
        )
        .build()
}

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
