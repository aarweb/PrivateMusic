package com.aar.privatemusic.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioFocusPolicyTest {

    @Test
    fun `play requests focus once and starts only after grant`() {
        val requested = step(AudioFocusPolicy.State(), AudioFocusPolicy.Event.Play)

        assertEquals(AudioFocusPolicy.FocusState.REQUESTING, requested.state.focus)
        assertTrue(requested.state.wantsPlayback)
        assertTrue(requested.decision.requestFocus)
        assertFalse(requested.decision.resumeMain)

        val duplicate = step(requested.state, AudioFocusPolicy.Event.Play)
        assertNoDecision(duplicate.decision)

        val granted = step(duplicate.state, AudioFocusPolicy.Event.FocusRequestGranted)
        assertEquals(AudioFocusPolicy.FocusState.HELD, granted.state.focus)
        assertTrue(granted.decision.resumeMain)

        val duplicateGrant = step(granted.state, AudioFocusPolicy.Event.FocusRequestGranted)
        assertNoDecision(duplicateGrant.decision)
    }

    @Test
    fun `denied focus drops the play intent and never starts`() {
        val requested = step(AudioFocusPolicy.State(), AudioFocusPolicy.Event.Play)
        val denied = step(requested.state, AudioFocusPolicy.Event.FocusRequestDenied)

        assertEquals(AudioFocusPolicy.State(), denied.state)
        assertNoDecision(denied.decision)
        assertNoDecision(step(denied.state, AudioFocusPolicy.Event.FocusGain).decision)
    }

    @Test
    fun `duck affects both-player multiplier without pausing and gain restores it`() {
        val playing = focusedPlaying()
        val ducked = step(playing, AudioFocusPolicy.Event.FocusDuck)

        assertEquals(AudioFocusPolicy.FocusState.DUCKED, ducked.state.focus)
        assertEquals(AudioFocusPolicy.DUCK_VOLUME, ducked.state.duckMultiplier)
        assertEquals(AudioFocusPolicy.DUCK_VOLUME, ducked.decision.duckMultiplier)
        assertFalse(ducked.decision.pausePlayers)

        val duplicateDuck = step(ducked.state, AudioFocusPolicy.Event.FocusDuck)
        assertNoDecision(duplicateDuck.decision)

        val gained = step(duplicateDuck.state, AudioFocusPolicy.Event.FocusGain)
        assertEquals(AudioFocusPolicy.FocusState.HELD, gained.state.focus)
        assertEquals(AudioFocusPolicy.FULL_VOLUME, gained.decision.duckMultiplier)
        assertFalse(gained.decision.resumeMain)
        assertNoDecision(step(gained.state, AudioFocusPolicy.Event.FocusGain).decision)
    }

    @Test
    fun `transient loss pauses active playback and gain resumes once`() {
        val lost = step(focusedPlaying(), AudioFocusPolicy.Event.FocusLossTransient)

        assertEquals(AudioFocusPolicy.FocusState.TRANSIENT_LOSS, lost.state.focus)
        assertTrue(lost.state.resumeOnGain)
        assertTrue(lost.decision.pausePlayers)
        assertTrue(step(lost.state, AudioFocusPolicy.Event.FocusGain).decision.resumeMain)
        assertFalse(lost.decision.abandonFocus)

        val duplicateLoss = step(lost.state, AudioFocusPolicy.Event.FocusLossTransient)
        assertNoDecision(duplicateLoss.decision)

        val gained = step(duplicateLoss.state, AudioFocusPolicy.Event.FocusGain)
        assertEquals(AudioFocusPolicy.FocusState.HELD, gained.state.focus)
        assertTrue(gained.decision.resumeMain)
        assertNoDecision(step(gained.state, AudioFocusPolicy.Event.FocusGain).decision)
    }

    @Test
    fun `transient loss remembers intent while main is buffering`() {
        val buffering = focusedPlaying().copy(isPlaying = false)
        val lost = step(buffering, AudioFocusPolicy.Event.FocusLossTransient)

        assertTrue(lost.decision.pausePlayers)
        assertTrue(lost.state.resumeOnGain)
        assertTrue(step(lost.state, AudioFocusPolicy.Event.FocusGain).decision.resumeMain)
    }

    @Test
    fun `transient loss also remembers active playback without recorded intent`() {
        val active = AudioFocusPolicy.State(
            focus = AudioFocusPolicy.FocusState.HELD,
            wantsPlayback = false,
            isPlaying = true,
        )
        val lost = step(active, AudioFocusPolicy.Event.FocusLossTransient)

        assertTrue(lost.state.resumeOnGain)
        assertTrue(lost.decision.pausePlayers)
    }

    @Test
    fun `user pause during transient loss cancels pending resume and abandons`() {
        val transient = step(
            focusedPlaying(),
            AudioFocusPolicy.Event.FocusLossTransient,
        ).state
        val paused = step(transient, AudioFocusPolicy.Event.Pause)

        assertEquals(AudioFocusPolicy.State(), paused.state)
        assertTrue(paused.decision.pausePlayers)
        assertTrue(paused.decision.abandonFocus)
        assertNoDecision(step(paused.state, AudioFocusPolicy.Event.FocusGain).decision)
        assertNoDecision(step(paused.state, AudioFocusPolicy.Event.Pause).decision)
    }

    @Test
    fun `play requested during transient loss waits for gain`() {
        val idleHeld = AudioFocusPolicy.State(focus = AudioFocusPolicy.FocusState.HELD)
        val transient = step(idleHeld, AudioFocusPolicy.Event.FocusLossTransient)
        assertFalse(transient.decision.pausePlayers)

        val pending = step(transient.state, AudioFocusPolicy.Event.Play)
        assertTrue(pending.state.wantsPlayback)
        assertTrue(pending.state.resumeOnGain)
        assertNoDecision(pending.decision)

        val gained = step(pending.state, AudioFocusPolicy.Event.FocusGain)
        assertTrue(gained.decision.resumeMain)
        assertFalse(gained.decision.requestFocus)
    }

    @Test
    fun `permanent loss pauses abandons and stale gain never resumes`() {
        val lost = step(focusedPlaying(), AudioFocusPolicy.Event.FocusLoss)

        assertEquals(AudioFocusPolicy.FocusState.PERMANENT_LOSS, lost.state.focus)
        assertTrue(lost.decision.pausePlayers)
        assertTrue(lost.decision.abandonFocus)
        assertFalse(lost.state.wantsPlayback)

        assertNoDecision(step(lost.state, AudioFocusPolicy.Event.FocusGain).decision)
        assertNoDecision(step(lost.state, AudioFocusPolicy.Event.FocusLoss).decision)
    }

    @Test
    fun `permanent loss after duck restores full volume`() {
        val ducked = step(focusedPlaying(), AudioFocusPolicy.Event.FocusDuck)
        val lost = step(ducked.state, AudioFocusPolicy.Event.FocusLoss)

        assertTrue(lost.decision.pausePlayers)
        assertTrue(lost.decision.abandonFocus)
        assertEquals(AudioFocusPolicy.FULL_VOLUME, lost.decision.duckMultiplier)
        assertEquals(AudioFocusPolicy.FULL_VOLUME, lost.state.duckMultiplier)
    }

    @Test
    fun `permanent loss after transient does not issue a duplicate pause`() {
        val transient = step(
            focusedPlaying(),
            AudioFocusPolicy.Event.FocusLossTransient,
        )
        val lost = step(transient.state, AudioFocusPolicy.Event.FocusLoss)

        assertFalse(lost.decision.pausePlayers)
        assertTrue(lost.decision.abandonFocus)
        assertEquals(AudioFocusPolicy.FocusState.PERMANENT_LOSS, lost.state.focus)
        assertNoDecision(step(lost.state, AudioFocusPolicy.Event.FocusGain).decision)
    }

    @Test
    fun `transient loss after duck resets multiplier before gain`() {
        val ducked = step(focusedPlaying(), AudioFocusPolicy.Event.FocusDuck)
        val transient = step(ducked.state, AudioFocusPolicy.Event.FocusLossTransient)

        assertTrue(transient.decision.pausePlayers)
        assertEquals(AudioFocusPolicy.FULL_VOLUME, transient.decision.duckMultiplier)
        assertEquals(AudioFocusPolicy.FULL_VOLUME, transient.state.duckMultiplier)
        assertTrue(step(transient.state, AudioFocusPolicy.Event.FocusGain).decision.resumeMain)
    }

    @Test
    fun `definitive pause and stop abandon held focus idempotently`() {
        val paused = step(focusedPlaying(), AudioFocusPolicy.Event.Pause)
        assertTrue(paused.decision.pausePlayers)
        assertTrue(paused.decision.abandonFocus)
        assertEquals(AudioFocusPolicy.State(), paused.state)

        assertNoDecision(step(paused.state, AudioFocusPolicy.Event.Stop).decision)

        val ducked = step(focusedPlaying(), AudioFocusPolicy.Event.FocusDuck).state
        val stopped = step(ducked, AudioFocusPolicy.Event.Stop)
        assertTrue(stopped.decision.pausePlayers)
        assertTrue(stopped.decision.abandonFocus)
        assertEquals(AudioFocusPolicy.FULL_VOLUME, stopped.decision.duckMultiplier)
    }

    @Test
    fun `playback events only update observed playback`() {
        val held = AudioFocusPolicy.State(
            focus = AudioFocusPolicy.FocusState.HELD,
            wantsPlayback = true,
        )
        val started = step(held, AudioFocusPolicy.Event.PlaybackStarted)
        assertTrue(started.state.isPlaying)
        assertNoDecision(started.decision)

        val stopped = step(started.state, AudioFocusPolicy.Event.PlaybackStopped)
        assertFalse(stopped.state.isPlaying)
        assertTrue(stopped.state.wantsPlayback)
        assertNoDecision(stopped.decision)
    }

    @Test
    fun `new explicit play may request focus after permanent loss`() {
        val lost = step(focusedPlaying(), AudioFocusPolicy.Event.FocusLoss).state
        val replay = step(lost, AudioFocusPolicy.Event.Play)

        assertEquals(AudioFocusPolicy.FocusState.REQUESTING, replay.state.focus)
        assertTrue(replay.decision.requestFocus)
        assertFalse(replay.decision.resumeMain)
    }

    private fun focusedPlaying(): AudioFocusPolicy.State {
        val requested = step(AudioFocusPolicy.State(), AudioFocusPolicy.Event.Play)
        return step(requested.state, AudioFocusPolicy.Event.FocusRequestGranted).state
    }

    private fun step(
        state: AudioFocusPolicy.State,
        event: AudioFocusPolicy.Event,
    ): AudioFocusPolicy.Transition = AudioFocusPolicy.reduce(state, event)

    private fun assertNoDecision(decision: AudioFocusPolicy.Decision) {
        assertFalse(decision.requestFocus)
        assertFalse(decision.abandonFocus)
        assertFalse(decision.pausePlayers)
        assertFalse(decision.resumeMain)
        assertNull(decision.duckMultiplier)
    }
}
