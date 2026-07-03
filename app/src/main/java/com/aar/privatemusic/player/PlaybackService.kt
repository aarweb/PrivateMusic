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
        // Crossfade must distinguish automatic track changes from manual skips:
        // fading in after a manual skip feels like broken volume.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                fadingIn = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                // The outgoing track may have been tempo-bent by AutoMix to land
                // on the incoming track's BPM; the incoming one plays natural.
                if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
            }
        })
        startVolumeLoop(player, dao)
    }

    /** True while the current track should ramp up (started via auto-transition). */
    private var fadingIn = false
    private var lastFadePhase = "full"

    /**
     * Drives the player volume for crossfade (fade-out/in at track edges)
     * and loudness normalization (attenuate loud tracks towards the target).
     * Leaves the volume alone when both features are off.
     */
    private fun startVolumeLoop(player: ExoPlayer, dao: MusicDao) {
        mainScope.launch {
            var gainFactor = 1f
            var gainForId: String? = null
            var touchedVolume = false
            // AutoMix: target speed ratio for the current transition, cached per track pair.
            var mixRatio = 1f
            var mixForIds: Pair<String, String>? = null
            while (isActive) {
                val crossfadeMs = AppSettings.readCrossfadeSec(this@PlaybackService) * 1000L
                val normalize = AppSettings.readNormalizeVolume(this@PlaybackService)
                val autoMix = AppSettings.readAutoMix(this@PlaybackService)

                if (crossfadeMs == 0L && !normalize) {
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
                    gainFactor = if (loudness != null) {
                        val gainDb = (AppSettings.TARGET_LOUDNESS_DB - loudness).coerceIn(-12f, 0f)
                        10.0.pow(gainDb / 20.0).toFloat()
                    } else 1f
                } else if (!normalize) {
                    gainFactor = 1f
                    gainForId = null
                }

                var fade = 1f
                var phase = "full"
                val duration = player.duration
                if (crossfadeMs > 0 && player.isPlaying &&
                    duration != androidx.media3.common.C.TIME_UNSET &&
                    duration > crossfadeMs * 2
                ) {
                    val position = player.currentPosition
                    val remaining = duration - position
                    val fadeInMs = crossfadeMs / 2
                    if (remaining in 1 until crossfadeMs && player.hasNextMediaItem()) {
                        // Equal-power fade-out, only when another track follows.
                        fade = kotlin.math.sqrt(remaining / crossfadeMs.toFloat()).coerceIn(0f, 1f)
                        phase = "out"
                        if (autoMix) {
                            // DJ-style beatmatch: bend the outgoing tempo to the incoming
                            // BPM so the handoff keeps a continuous pulse. Applied ONCE per
                            // transition: repeated speed changes flush the audio pipeline
                            // every tick and stall playback near the end of the stream.
                            val curId = player.currentMediaItem?.mediaId
                            val nextId = player.getMediaItemAt(player.nextMediaItemIndex).mediaId
                            if (curId != null && mixForIds != curId to nextId) {
                                mixForIds = curId to nextId
                                val (curBpm, nextBpm) = withContext(Dispatchers.IO) {
                                    runCatching { dao.getBpm(curId) to dao.getBpm(nextId) }
                                        .getOrDefault(null to null)
                                }
                                mixRatio = if (curBpm != null && nextBpm != null && curBpm > 0f)
                                    (nextBpm / curBpm).coerceIn(0.9f, 1.1f) else 1f
                                if (mixRatio != 1f) player.setPlaybackSpeed(mixRatio)
                                android.util.Log.d("AutoMix", "pair=$curId->$nextId ratio=$mixRatio")
                            }
                        }
                    } else if (fadingIn && position < fadeInMs) {
                        // Ramp-in only after an automatic transition, never after a skip.
                        fade = kotlin.math.sqrt((position + 150) / fadeInMs.toFloat()).coerceIn(0.1f, 1f)
                        phase = "in"
                    } else if (fadingIn) {
                        fadingIn = false
                    }
                }

                if (phase != "out") {
                    mixForIds = null
                    // Undo any leftover tempo bend (seek back out of the fade window, etc.).
                    if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
                }

                if (phase != lastFadePhase) {
                    lastFadePhase = phase
                    android.util.Log.d("Crossfade", "phase=$phase fade=$fade")
                }
                player.volume = gainFactor * fade
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
