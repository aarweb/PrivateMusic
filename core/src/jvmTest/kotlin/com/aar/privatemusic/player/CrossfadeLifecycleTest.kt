package com.aar.privatemusic.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossfadeLifecycleTest {

    @Test
    fun `recorre el ciclo completo y conserva sus pistas`() {
        val lifecycle = CrossfadeLifecycle()

        assertTrue(lifecycle.prearm("A", "B").accepted)
        assertEquals(CrossfadeLifecycle.Phase.PREARMING, lifecycle.phase)
        assertEquals("A", lifecycle.trackId)
        assertEquals("B", lifecycle.nextId)
        assertTrue(lifecycle.armed().accepted)
        assertTrue(lifecycle.warming().accepted)
        assertTrue(lifecycle.handover().accepted)
        assertEquals("A", lifecycle.expectedMainTrackId)
        assertTrue(lifecycle.overlapping().accepted)
        assertEquals("B", lifecycle.expectedMainTrackId)
        assertTrue(lifecycle.complete().accepted)
        assertIdle(lifecycle)
    }

    @Test
    fun `cada motivo cancela desde una fase activa sin perder diagnostico`() {
        val cases = listOf(
            CrossfadeLifecycle.CancelReason.PREPARATION_FAILED to CrossfadeLifecycle.Phase.PREARMING,
            CrossfadeLifecycle.CancelReason.TAIL_NO_AUDIO to CrossfadeLifecycle.Phase.WARMING,
            CrossfadeLifecycle.CancelReason.EXCESSIVE_DRIFT to CrossfadeLifecycle.Phase.WARMING,
            CrossfadeLifecycle.CancelReason.PAUSED to CrossfadeLifecycle.Phase.ARMED,
            CrossfadeLifecycle.CancelReason.NAVIGATION to CrossfadeLifecycle.Phase.HANDOVER,
            CrossfadeLifecycle.CancelReason.NAVIGATION to CrossfadeLifecycle.Phase.OVERLAPPING,
            CrossfadeLifecycle.CancelReason.INSUFFICIENT_CONTENT to CrossfadeLifecycle.Phase.HANDOVER,
            CrossfadeLifecycle.CancelReason.WINDOW_CLOSED to CrossfadeLifecycle.Phase.ARMED,
        )

        cases.forEach { (reason, phase) ->
            val lifecycle = lifecycleAt(phase)
            val result = lifecycle.cancel(reason)

            assertTrue(result.accepted, "$reason desde $phase")
            assertEquals(CrossfadeLifecycle.Phase.CANCELLED, lifecycle.phase)
            assertEquals(reason, lifecycle.cancelReason)
            assertEquals("A", lifecycle.trackId)
            assertEquals("B", lifecycle.nextId)
        }
    }

    @Test
    fun `pausa tambien puede cancelar warmup handover y overlap`() {
        listOf(
            CrossfadeLifecycle.Phase.WARMING,
            CrossfadeLifecycle.Phase.HANDOVER,
            CrossfadeLifecycle.Phase.OVERLAPPING,
        ).forEach { phase ->
            val lifecycle = lifecycleAt(phase)
            assertTrue(lifecycle.cancel(CrossfadeLifecycle.CancelReason.PAUSED).accepted)
            assertEquals(CrossfadeLifecycle.CancelReason.PAUSED, lifecycle.cancelReason)
        }
    }

    @Test
    fun `navegacion puede cancelar warming handover y overlap`() {
        listOf(
            CrossfadeLifecycle.Phase.WARMING,
            CrossfadeLifecycle.Phase.HANDOVER,
            CrossfadeLifecycle.Phase.OVERLAPPING,
        ).forEach { phase ->
            val lifecycle = lifecycleAt(phase)
            assertTrue(lifecycle.cancel(CrossfadeLifecycle.CancelReason.NAVIGATION).accepted)
            assertEquals(CrossfadeLifecycle.CancelReason.NAVIGATION, lifecycle.cancelReason)
        }
    }

    @Test
    fun `un cambio de pista limpia la cancelacion pero la misma pista no`() {
        val lifecycle = lifecycleAt(CrossfadeLifecycle.Phase.WARMING)
        lifecycle.cancel(CrossfadeLifecycle.CancelReason.TAIL_NO_AUDIO)

        val same = lifecycle.trackChanged("A")
        assertFalse(same.accepted)
        assertEquals(CrossfadeLifecycle.Phase.CANCELLED, lifecycle.phase)
        assertEquals(CrossfadeLifecycle.CancelReason.TAIL_NO_AUDIO, lifecycle.cancelReason)

        assertTrue(lifecycle.trackChanged("B").accepted)
        assertIdle(lifecycle)
    }

    @Test
    fun `transiciones ilegales se rechazan sin corromper el estado`() {
        val lifecycle = CrossfadeLifecycle()
        val initial = lifecycle.current

        listOf(
            lifecycle.armed(),
            lifecycle.warming(),
            lifecycle.handover(),
            lifecycle.overlapping(),
            lifecycle.complete(),
            lifecycle.cancel(CrossfadeLifecycle.CancelReason.PAUSED),
        ).forEach { result ->
            assertFalse(result.accepted)
            assertEquals(initial, result.before)
            assertEquals(initial, result.after)
            assertEquals(initial, lifecycle.current)
        }

        lifecycle.prearm("A", "B")
        val prearming = lifecycle.current
        assertFalse(lifecycle.prearm("X", "Y").accepted)
        assertFalse(lifecycle.warming().accepted)
        assertFalse(lifecycle.complete().accepted)
        assertEquals(prearming, lifecycle.current)
    }

    @Test
    fun `reset es seguro desde cualquier fase`() {
        CrossfadeLifecycle.Phase.entries.forEach { phase ->
            val lifecycle = lifecycleAt(phase)
            assertTrue(lifecycle.reset().accepted)
            assertIdle(lifecycle)
            assertTrue(lifecycle.reset().accepted)
            assertIdle(lifecycle)
        }
    }

    private fun lifecycleAt(phase: CrossfadeLifecycle.Phase): CrossfadeLifecycle =
        CrossfadeLifecycle().apply {
            if (phase == CrossfadeLifecycle.Phase.IDLE) return@apply
            prearm("A", "B")
            if (phase == CrossfadeLifecycle.Phase.PREARMING) return@apply
            armed()
            if (phase == CrossfadeLifecycle.Phase.ARMED) return@apply
            warming()
            if (phase == CrossfadeLifecycle.Phase.WARMING) return@apply
            handover()
            if (phase == CrossfadeLifecycle.Phase.HANDOVER) return@apply
            overlapping()
            if (phase == CrossfadeLifecycle.Phase.OVERLAPPING) return@apply
            cancel(CrossfadeLifecycle.CancelReason.NAVIGATION)
        }

    private fun assertIdle(lifecycle: CrossfadeLifecycle) {
        assertEquals(CrossfadeLifecycle.Phase.IDLE, lifecycle.phase)
        assertNull(lifecycle.trackId)
        assertNull(lifecycle.nextId)
        assertNull(lifecycle.cancelReason)
    }
}
