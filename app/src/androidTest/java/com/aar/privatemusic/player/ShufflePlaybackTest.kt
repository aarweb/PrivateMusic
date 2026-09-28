package com.aar.privatemusic.player

import android.Manifest
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.rule.GrantPermissionRule
import com.aar.privatemusic.MainActivity
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.PlaylistSongCrossRef
import com.aar.privatemusic.data.db.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reproduce el fallo con los botones reales y el MediaController/ExoPlayer del emulador. */
class ShufflePlaybackTest {
    @get:Rule(order = 0)
    val notificationPermission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = compose.activity.application as PrivateMusicApp
    private val controller get() = app.playerController
    private lateinit var songs: List<Song>
    private lateinit var playlist: Playlist
    private lateinit var audio: File

    @Before
    fun seedPlaylist() = runBlocking {
        // Audio PCM local y largo: ni red, ni descargas, ni un final automático durante la prueba.
        audio = File(app.filesDir, "shuffle-test.wav")
        val pcmBytes = 8_000 * 2 * 120
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcmBytes).array()
        audio.outputStream().use { it.write(header); it.write(ByteArray(pcmBytes)) }
        songs = (1..4).map { index ->
            Song(
                id = "shuffle-test-$index", title = "Prueba aleatorio $index", artist = "Prueba local",
                durationSec = 120, filePath = audio.path, artPath = null, thumbnailUrl = null,
                addedAt = index.toLong(), metadataResolved = true,
            ).also { app.musicDao.insertSong(it) }
        }
        val draft = Playlist(name = "Prueba de aleatorio", createdAt = 0)
        playlist = draft.copy(id = app.musicDao.insertPlaylist(draft))
        songs.forEachIndexed { index, song ->
            app.musicDao.addToPlaylist(PlaylistSongCrossRef(playlist.id, song.id, index))
        }
    }

    @After
    fun cleanup() = runBlocking {
        compose.runOnIdle { if (controller.isPlaying.value) controller.togglePlayPause() }
        if (::playlist.isInitialized) {
            app.musicDao.clearPlaylist(playlist.id)
            app.musicDao.deletePlaylist(playlist)
        }
        if (::songs.isInitialized) songs.forEach { app.musicDao.deleteSong(it.id) }
        if (::audio.isInitialized) audio.delete()
    }

    private fun openPlayerAt(index: Int) {
        compose.onNodeWithText("Playlists").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(playlist.name))
        compose.onNodeWithText(playlist.name).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(songs[index].title))
        compose.onNodeWithText(songs[index].title).performClick()
        compose.waitUntil(15_000) {
            controller.nowPlaying.value?.songId == songs[index].id && controller.isPlaying.value
        }
        compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText(songs[index].title)) and
                hasAnyDescendant(hasContentDescription("Play/Pausa")),
            useUnmergedTree = true,
        ).performClick()
        compose.onNodeWithContentDescription("Aleatorio").assertExists()
    }

    @Test
    fun middleSongShuffleOffRestoresThePlaylistWhilePaused() {
        openPlayerAt(1)
        compose.onNodeWithContentDescription("Play/Pausa").performClick()
        compose.waitUntil(15_000) { !controller.isPlaying.value }
        compose.runOnIdle { controller.seekTo(12_000) }
        compose.onNodeWithContentDescription("Aleatorio").performClick()
        compose.waitUntil(15_000) { controller.currentIndex.value == 0 }
        assertEquals(songs[1].id, controller.queue.value.first().mediaId)
        assertEquals(songs.map { it.id }.sorted(), controller.queue.value.map { it.mediaId }.sorted())
        assertTrue(!controller.isPlaying.value)
        compose.runOnIdle { assertTrue(controller.positionMs >= 11_750) }

        compose.onNodeWithContentDescription("Aleatorio").performClick()
        compose.waitUntil(15_000) {
            !controller.shuffle.value && controller.currentIndex.value == 1 &&
                controller.queue.value.map { it.mediaId } == songs.map { it.id }
        }
        assertEquals(songs[1].id, controller.nowPlaying.value?.songId)
        assertTrue(!controller.isPlaying.value)
        compose.runOnIdle { assertTrue(controller.positionMs >= 11_750) }
    }

    @Test
    fun lastSongShuffleThenNextUsesTheWholePlaylist() {
        openPlayerAt(songs.lastIndex)
        assertEquals(songs.lastIndex, controller.currentIndex.value)
        compose.runOnIdle { controller.seekTo(12_000) }
        compose.onNodeWithContentDescription("Aleatorio").performClick()
        compose.waitUntil(15_000) { controller.shuffle.value }
        val evidenceDir = File(app.getExternalFilesDir(null), "shuffle-test").apply { mkdirs() }
        File(evidenceDir, "after-toggle.txt").writeText(
            "index=${controller.currentIndex.value}\n" +
                "playing=${controller.nowPlaying.value?.songId}\n" +
                "queue=${controller.queue.value.map { it.mediaId }}\n",
        )
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { screenshot ->
            File(evidenceDir, "after-toggle.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
        // En 2.36 fallaba aquí: seguía en el último índice y no había ninguna siguiente.
        compose.waitUntil(5_000) { controller.currentIndex.value == 0 }
        assertEquals(songs.last().id, controller.nowPlaying.value?.songId)
        assertEquals(songs.map { it.id }.sorted(), controller.queue.value.map { it.mediaId }.sorted())
        assertEquals(songs.last().id, controller.queue.value.first().mediaId)
        compose.runOnIdle { assertTrue(controller.positionMs >= 11_750) }

        val shuffledIds = controller.queue.value.map { it.mediaId }
        for (index in 1..songs.lastIndex) {
            compose.onNodeWithContentDescription("Siguiente").performClick()
            compose.waitUntil(15_000) { controller.nowPlaying.value?.songId == shuffledIds[index] }
            assertEquals(index, controller.currentIndex.value)
        }
    }
}
