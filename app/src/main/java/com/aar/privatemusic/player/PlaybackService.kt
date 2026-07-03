package com.aar.privatemusic.player

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback(dao, serviceScope))
            .build()
        EqHolder.init(this, player.audioSessionId)
        startVolumeLoop(player, dao)
    }

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
            while (isActive) {
                val crossfadeMs = AppSettings.readCrossfadeSec(this@PlaybackService) * 1000L
                val normalize = AppSettings.readNormalizeVolume(this@PlaybackService)

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
                if (crossfadeMs > 0 && player.isPlaying) {
                    val duration = player.duration
                    val position = player.currentPosition
                    if (duration > crossfadeMs * 2) {
                        val remaining = duration - position
                        fade = minOf(
                            1f,
                            remaining / crossfadeMs.toFloat(),
                            (position + 400) / crossfadeMs.toFloat(),
                        ).coerceIn(0f, 1f)
                    }
                }

                player.volume = gainFactor * fade
                touchedVolume = true
                delay(200)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

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
            if (item.localConfiguration != null) item
            else dao.getSong(item.mediaId)?.toPlayableItem() ?: item
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
