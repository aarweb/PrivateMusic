package com.aar.privatemusic.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aar.privatemusic.data.db.Song
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class NowPlaying(
    val songId: String,
    val title: String,
    val artist: String,
    val artPath: String?,
    val durationMs: Long,
)

data class QueueItem(
    val mediaId: String,
    val title: String,
    val artist: String,
    val artPath: String?,
)

/** UI-side bridge to the MediaSessionService's player. */
class PlayerController(
    context: Context,
    private val onSongPlayed: (String) -> Unit = {},
) {

    private var controller: MediaController? = null
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastPlayedId: String? = null

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode

    // Sleep timer: remaining ms, or null when inactive.
    private val _sleepRemainingMs = MutableStateFlow<Long?>(null)
    val sleepRemainingMs: StateFlow<Long?> = _sleepRemainingMs

    private val _stopAfterTrack = MutableStateFlow(false)
    val stopAfterTrack: StateFlow<Boolean> = _stopAfterTrack

    private var sleepJob: Job? = null

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    _isPlaying.value = player.isPlaying
                    _shuffle.value = player.shuffleModeEnabled
                    _repeatMode.value = player.repeatMode
                    val item = player.currentMediaItem
                    _nowPlaying.value = item?.let {
                        NowPlaying(
                            songId = it.mediaId,
                            title = it.mediaMetadata.title?.toString() ?: "",
                            artist = it.mediaMetadata.artist?.toString() ?: "",
                            artPath = it.mediaMetadata.artworkUri?.path,
                            durationMs = player.duration.coerceAtLeast(0),
                        )
                    }
                    // Play history: record once per track start while playing.
                    val id = item?.mediaId
                    if (id != null && player.isPlaying && id != lastPlayedId) {
                        lastPlayedId = id
                        onSongPlayed(id)
                    }
                    // Mirror the player's timeline for the queue screen.
                    _queue.value = (0 until player.mediaItemCount).map { i ->
                        val mi = player.getMediaItemAt(i)
                        QueueItem(
                            mediaId = mi.mediaId,
                            title = mi.mediaMetadata.title?.toString() ?: "",
                            artist = mi.mediaMetadata.artist?.toString() ?: "",
                            artPath = mi.mediaMetadata.artworkUri?.path,
                        )
                    }
                    _currentIndex.value = player.currentMediaItemIndex
                }

                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    if (_stopAfterTrack.value && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        _stopAfterTrack.value = false
                        controller?.pause()
                    }
                }
            })
        }, MoreExecutors.directExecutor())
    }

    val positionMs: Long get() = controller?.currentPosition ?: 0L

    fun playQueue(songs: List<Song>, startIndex: Int) {
        val c = controller ?: return
        c.setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0L)
        c.prepare()
        c.play()
    }

    /** Inserts the song right after the current one. Starts playback if idle. */
    fun playNext(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            playQueue(listOf(song), 0)
        } else {
            c.addMediaItem(c.currentMediaItemIndex + 1, song.toMediaItem())
        }
    }

    /** Appends the song at the end of the queue. Starts playback if idle. */
    fun addToQueue(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            playQueue(listOf(song), 0)
        } else {
            c.addMediaItem(song.toMediaItem())
        }
    }

    fun playQueueItem(index: Int) {
        controller?.seekTo(index, 0L)
        controller?.play()
    }

    fun removeQueueItem(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun moveQueueItem(from: Int, to: Int) {
        val c = controller ?: return
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) {
            c.moveMediaItem(from, to)
        }
    }

    /** Re-shuffles everything after the current track (Spotify's Reshuffle). */
    fun reshuffleUpcoming() {
        val c = controller ?: return
        val n = c.mediaItemCount
        val start = c.currentMediaItemIndex + 1
        if (n - start < 2) return
        val rng = java.util.Random()
        for (i in start until n) {
            val j = start + rng.nextInt(n - start)
            if (i != j) c.moveMediaItem(i, j)
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = controller?.seekToNextMediaItem() ?: Unit
    fun previous() = controller?.seekToPreviousMediaItem() ?: Unit
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs) ?: Unit

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeatMode() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    /** Starts (or restarts) the sleep timer; fades out during the last 10 seconds. */
    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        controller?.volume = 1f
        sleepJob = mainScope.launch {
            val endAt = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
            while (true) {
                val remaining = endAt - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                _sleepRemainingMs.value = remaining
                if (remaining < 10_000) {
                    controller?.volume = (remaining / 10_000f).coerceIn(0f, 1f)
                }
                delay(250)
            }
            controller?.pause()
            controller?.volume = 1f
            _sleepRemainingMs.value = null
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemainingMs.value = null
        controller?.volume = 1f
    }

    /** Alternative sleep mode: stop when the current track finishes. */
    fun toggleStopAfterTrack() {
        _stopAfterTrack.value = !_stopAfterTrack.value
    }

    fun release() {
        cancelSleepTimer()
        controller?.release()
        controller = null
    }

    /** Plays [file] (e.g. an instrumental WAV) as if it were the song itself. */
    fun playKaraoke(song: Song, file: File) {
        val c = controller ?: return
        val uri = Uri.fromFile(file)
        val item = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("${song.title} · Karaoke")
                    .setArtist(song.artist)
                    .setArtworkUri(song.artPath?.let { Uri.fromFile(File(it)) })
                    .build()
            )
            .build()
        c.setMediaItems(listOf(item), 0, 0L)
        c.prepare()
        c.play()
    }

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.fromFile(File(filePath)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(artPath?.let { Uri.fromFile(File(it)) })
                    .build()
            )
            .build()
}
