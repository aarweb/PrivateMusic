package com.aar.privatemusic.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialAudioTest {

    private val active = SpatialAudioInputs(
        supported = true,
        enabledByApp = true,
        routeAvailable = true,
        enabledBySystem = true,
        formatKnown = true,
        canBeSpatialized = true,
        headTrackerAvailable = false,
    )

    @Test
    fun `unsupported has first precedence`() {
        assertStatus(
            SpatialAudioStatus.UNSUPPORTED,
            active.copy(
                supported = false,
                enabledByApp = false,
                routeAvailable = false,
                enabledBySystem = false,
                formatKnown = false,
                canBeSpatialized = false,
            ),
        )
    }

    @Test
    fun `disabled by app precedes platform and track states`() {
        assertStatus(
            SpatialAudioStatus.DISABLED_BY_APP,
            active.copy(
                enabledByApp = false,
                routeAvailable = false,
                enabledBySystem = false,
                formatKnown = false,
                canBeSpatialized = false,
            ),
        )
    }

    @Test
    fun `unavailable route precedes system and track states`() {
        assertStatus(
            SpatialAudioStatus.UNAVAILABLE_ROUTE,
            active.copy(
                routeAvailable = false,
                enabledBySystem = false,
                formatKnown = false,
                canBeSpatialized = false,
            ),
        )
    }

    @Test
    fun `disabled by system precedes track states`() {
        assertStatus(
            SpatialAudioStatus.DISABLED_BY_SYSTEM,
            active.copy(
                enabledBySystem = false,
                formatKnown = false,
                canBeSpatialized = false,
            ),
        )
    }

    @Test
    fun `unknown format waits before compatibility is evaluated`() {
        assertStatus(
            SpatialAudioStatus.WAITING_FOR_TRACK,
            active.copy(formatKnown = false, canBeSpatialized = false),
        )
    }

    @Test
    fun `known incompatible format is reported`() {
        assertStatus(
            SpatialAudioStatus.INCOMPATIBLE_TRACK,
            active.copy(canBeSpatialized = false),
        )
    }

    @Test
    fun `active requires every condition`() {
        assertStatus(SpatialAudioStatus.ACTIVE, active)
    }

    @Test
    fun `head tracking is exposed only while spatial audio is active`() {
        val withTracker = resolveSpatialAudioState(active.copy(headTrackerAvailable = true))
        assertEquals(SpatialAudioStatus.ACTIVE, withTracker.status)
        assertTrue(withTracker.headTracking)

        val inactive = resolveSpatialAudioState(
            active.copy(enabledBySystem = false, headTrackerAvailable = true)
        )
        assertEquals(SpatialAudioStatus.DISABLED_BY_SYSTEM, inactive.status)
        assertFalse(inactive.headTracking)
    }

    private fun assertStatus(expected: SpatialAudioStatus, input: SpatialAudioInputs) {
        val state = resolveSpatialAudioState(input)
        assertEquals(expected, state.status)
        assertFalse(state.headTracking)
    }
}
