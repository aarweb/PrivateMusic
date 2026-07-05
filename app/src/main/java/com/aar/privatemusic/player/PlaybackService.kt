package com.aar.privatemusic.player

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
import kotlinx.coroutines.delay
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

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val dao = MusicDatabase.get(this).musicDao()
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
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Stream previews use signed URLs that expire; a dead preview item
                // would otherwise fail silently forever from the notification.
                if (player.currentMediaItem?.mediaId?.startsWith("preview:") == true) {
                    player.clearMediaItems()
                }
                android.util.Log.w("Playback", "player error", error)
            }
        })
        startVolumeLoop(player, dao)
    }

    private var tailPlayer: ExoPlayer? = null

    /**
     * Drives volume normalization and the REAL crossfade: when the current
     * track enters its last N seconds, its tail keeps playing on [tailPlayer]
     * while the main player jumps to the next track — both songs overlap with
     * crossed equal-power curves (and AutoMix tempo-bends the outgoing tail).
     */
    private fun startVolumeLoop(player: ExoPlayer, dao: MusicDao) {
        mainScope.launch {
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
            // hole while the decoder spins up (audible micro-cut).
            var armedForId: String? = null
            var armedAtPos = 0L

            fun gainOf(loudness: Float?, normalize: Boolean): Float =
                if (normalize && loudness != null) {
                    val gainDb = (AppSettings.TARGET_LOUDNESS_DB - loudness).coerceIn(-12f, 0f)
                    10.0.pow(gainDb / 20.0).toFloat()
                } else 1f

            while (isActive) {
                val tail = tailPlayer
                val crossfadeMs = AppSettings.readCrossfadeSec(this@PlaybackService) * 1000L
                val normalize = AppSettings.readNormalizeVolume(this@PlaybackService)
                val autoMix = AppSettings.readAutoMix(this@PlaybackService)
                val xfActive = xfEndsAt != 0L

                if (crossfadeMs == 0L && !normalize && !xfActive) {
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
                    val loudness = withContext(Dispatchers.IO) { runCatching { dao.getLoudness(id) }.getOrNull() }
                    gainFactor = gainOf(loudness, true)
                } else if (!normalize) {
                    gainFactor = 1f
                    gainForId = null
                }

                // ---- Overlap in progress: drive both volume curves. ----
                if (xfActive && tail != null) {
                    val remainingXf = xfEndsAt - android.os.SystemClock.elapsedRealtime()
                    val aborted = player.currentMediaItem?.mediaId != xfMainId || !player.isPlaying
                    if (remainingXf <= 0 || aborted) {
                        tail.volume = 0f
                        tail.stop()
                        tail.clearMediaItems()
                        tail.setPlaybackSpeed(1f)
                        xfEndsAt = 0L
                        player.volume = gainFactor
                        android.util.Log.d("Crossfade", "overlap end aborted=$aborted")
                    } else {
                        val t = (1f - remainingXf.toFloat() / xfDurationMs).coerceIn(0f, 1f)
                        player.volume = gainFactor * kotlin.math.sqrt(t)
                        tail.volume = xfGainA * kotlin.math.sqrt(1f - t)
                        android.util.Log.d("Crossfade", "overlap t=$t mainVol=${player.volume} tailVol=${tail.volume}")
                    }
                    touchedVolume = true
                    delay(100)
                    continue
                }

                // ---- Pre-arm & fire the overlap around the crossfade window. ----
                val duration = player.duration
                if (crossfadeMs > 0 && tail != null && player.isPlaying &&
                    duration != androidx.media3.common.C.TIME_UNSET &&
                    duration > crossfadeMs * 2 &&
                    player.hasNextMediaItem() &&
                    // Repeat-one loops the same track: no crossfade there.
                    player.repeatMode != Player.REPEAT_MODE_ONE
                ) {
                    val remaining = duration - player.currentPosition
                    val curItem = player.currentMediaItem
                    val curUri = curItem?.localConfiguration?.uri
                    val curId = curItem?.mediaId
                    val eligible = curUri != null && curId != null && !curId.startsWith("preview:")

                    // Pre-arm: prepare the tail (paused, muted) at the exact spot
                    // where the window opens, so firing needs no decoder warm-up.
                    if (eligible && armedForId != curId &&
                        remaining in crossfadeMs until crossfadeMs + 2500
                    ) {
                        armedForId = curId
                        armedAtPos = duration - crossfadeMs
                        tail.playWhenReady = false
                        tail.volume = 0f
                        tail.setMediaItem(MediaItem.Builder().setUri(curUri).build(), armedAtPos)
                        tail.prepare()
                        android.util.Log.d("Crossfade", "pre-armed at $armedAtPos")
                    }

                    if (eligible && remaining in 1 until crossfadeMs) {
                        val nextId = player.getMediaItemAt(player.nextMediaItemIndex).mediaId
                        // Gather gains + AutoMix ratio off the main thread.
                        val (loudA, loudB, ratio) = withContext(Dispatchers.IO) {
                            runCatching {
                                val la = dao.getLoudness(curId)
                                val lb = dao.getLoudness(nextId)
                                val ba = dao.getBpm(curId)
                                val bb = dao.getBpm(nextId)
                                val r = if (ba != null && bb != null && ba > 0f)
                                    (bb / ba).coerceIn(0.9f, 1.1f) else 1f
                                Triple(la, lb, r)
                            }.getOrDefault(Triple(null, null, 1f))
                        }
                        // Revalidate: a manual skip may have happened during the query.
                        if (player.currentMediaItem?.mediaId == curId && player.isPlaying) {
                            val pos = player.currentPosition
                            val fadeLen = (duration - pos).coerceIn(500L, crossfadeMs)
                            xfGainA = gainOf(loudA, normalize)
                            xfMainId = nextId
                            val armedReady = armedForId == curId &&
                                tail.playbackState == Player.STATE_READY
                            if (!armedReady) {
                                // Fallback (seek into the window, odd file): prepare now.
                                tail.setMediaItem(MediaItem.Builder().setUri(curUri).build(), pos)
                                tail.prepare()
                            } else if (kotlin.math.abs(pos - armedAtPos) > 150) {
                                tail.seekTo(pos + 60)
                            }
                            if (autoMix && ratio != 1f) tail.setPlaybackSpeed(ratio)
                            tail.volume = xfGainA
                            tail.play()
                            // Keep A audible on the MAIN player until the tail is
                            // really producing sound — this is what kills the cut.
                            var waited = 0
                            while (!tail.isPlaying && waited < 500) {
                                delay(20)
                                waited += 20
                            }
                            player.volume = 0f
                            player.seekToNextMediaItem()
                            gainFactor = gainOf(loudB, normalize)
                            gainForId = nextId
                            xfDurationMs = fadeLen
                            xfEndsAt = android.os.SystemClock.elapsedRealtime() + fadeLen
                            armedForId = null
                            android.util.Log.d(
                                "Crossfade",
                                "overlap start durMs=$fadeLen gainA=$xfGainA gainB=$gainFactor automixRatio=$ratio armed=$armedReady waitedMs=$waited",
                            )
                            touchedVolume = true
                            delay(100)
                            continue
                        }
                    }
                }

                player.volume = gainFactor
                touchedVolume = true
                delay(200)
            }
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
