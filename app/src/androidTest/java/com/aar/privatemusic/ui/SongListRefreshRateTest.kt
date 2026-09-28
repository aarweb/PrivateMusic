package com.aar.privatemusic.ui

import android.Manifest
import android.os.Build
import android.view.Window
import androidx.compose.ui.test.hasScrollAction
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import com.aar.privatemusic.MainActivity
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.db.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises the app's real library list and its Window attributes; no physical fps assertion. */
class SongListRefreshRateTest {
    @get:Rule(order = 0)
    val notificationPermission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = compose.activity.application as PrivateMusicApp
    private val ids = (1..50).map { "refresh-test-$it" }
    private lateinit var canvas: File
    private lateinit var testedWindow: Window

    private fun preferredRate(): Float {
        var rate = Float.NaN
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            rate = testedWindow.attributes.preferredRefreshRate
        }
        return rate
    }

    private fun supportedMaximum(): Float {
        var maximum = Float.NaN
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val display = requireNotNull(testedWindow.decorView.display)
            val mode = display.mode
            maximum = display.supportedModes
                .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
                .flatMap { candidate ->
                    listOf(candidate.refreshRate) +
                        (if (Build.VERSION.SDK_INT >= 31) candidate.alternativeRefreshRates.toList() else emptyList())
                }
                .filter { it.isFinite() && it > 0f }
                .maxOrNull() ?: error("No valid refresh mode")
        }
        return maximum
    }

    @Before fun seed() = runBlocking {
        testedWindow = compose.activity.window
        canvas = File(app.filesDir, "refresh-test-canvas.mp4")
        canvas.writeBytes(byteArrayOf(0, 1, 2))
        ids.forEachIndexed { index, id ->
            app.musicDao.insertSong(Song(
                id = id, title = "Fluidez prueba ${index + 1}", artist = "Prueba refresco",
                durationSec = 180, filePath = "/missing/$id.mp3", artPath = null,
                thumbnailUrl = null, addedAt = index.toLong(), metadataResolved = true,
                videoPath = when (index) {
                    49 -> canvas.absolutePath
                    48 -> File(app.filesDir, "refresh-missing.mp4").absolutePath
                    else -> null
                },
            ))
        }
    }

    @After fun cleanup() = runBlocking {
        ids.forEach { app.musicDao.deleteSong(it) }
        if (::canvas.isInitialized) canvas.delete()
    }

    @Test fun canvasIndicatorRequiresReadableFile() {
        compose.onNodeWithText("Biblioteca").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Canvas disponible").fetchSemanticsNodes().size == 1
        }
        assertEquals(1, compose.onAllNodesWithContentDescription("Canvas disponible").fetchSemanticsNodes().size)
        runBlocking {
            app.musicDao.updateSongVideo(ids.last(), File(app.filesDir, "refresh-new-missing.mp4").absolutePath)
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Canvas disponible").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun libraryScrollRequestsSupportedMaximumAndRestoresPreviousPreference() {
        compose.onNodeWithText("Biblioteca").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Fluidez prueba 50").fetchSemanticsNodes().isNotEmpty()
        }
        val original = preferredRate()
        val expected = supportedMaximum()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNode(hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performTouchInput { swipeUp(durationMillis = 70) }
            compose.mainClock.advanceTimeByFrame()
            assertEquals(expected, preferredRate())
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(original, preferredRate())
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(expected, preferredRate())
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(10_000) { preferredRate() == original }
        compose.onNodeWithText("Ajustes").performClick()
        assertEquals(original, preferredRate())
    }
}
