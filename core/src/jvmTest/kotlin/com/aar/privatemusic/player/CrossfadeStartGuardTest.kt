package com.aar.privatemusic.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossfadeStartGuardTest {

    @Test
    fun `audio inmediato inicia con el restante actual`() {
        val decision = guard().evaluate(input(incomingAudioTrackId = "B", remainingTailWallMs = 4_321L))

        assertEquals(CrossfadeStartGuard.Decision.START(4_321L), decision)
    }

    @Test
    fun `varias esperas terminan en un fade acortado al llegar audio`() {
        val guard = guard(initialWallFadeMs = 4_000L)

        assertEquals(CrossfadeStartGuard.Decision.WAIT, guard.evaluate(input(elapsedWaitingMs = 0L)))
        assertEquals(CrossfadeStartGuard.Decision.WAIT, guard.evaluate(input(elapsedWaitingMs = 900L)))
        assertEquals(
            CrossfadeStartGuard.Decision.START(2_700L),
            guard.evaluate(
                input(
                    incomingAudioTrackId = "B",
                    elapsedWaitingMs = 1_300L,
                    remainingTailWallMs = 2_700L,
                ),
            ),
        )
    }

    @Test
    fun `timeout aborta en el limite y despues`() {
        val guard = guard(initialWallFadeMs = 10_000L)

        assertEquals(CrossfadeStartGuard.Decision.WAIT, guard.evaluate(input(elapsedWaitingMs = 2_499L)))
        assertEquals(CrossfadeStartGuard.Decision.ABORT, guard.evaluate(input(elapsedWaitingMs = 2_500L)))
        assertEquals(CrossfadeStartGuard.Decision.ABORT, guard.evaluate(input(elapsedWaitingMs = 9_000L)))
    }

    @Test
    fun `limite de tail distingue audio confirmado de espera`() {
        val guard = guard()

        assertEquals(
            CrossfadeStartGuard.Decision.START(500L),
            guard.evaluate(input(incomingAudioTrackId = "B", remainingTailWallMs = 500L)),
        )
        assertEquals(
            CrossfadeStartGuard.Decision.ABORT,
            guard.evaluate(input(remainingTailWallMs = 500L)),
        )
        assertEquals(
            CrossfadeStartGuard.Decision.WAIT,
            guard.evaluate(input(remainingTailWallMs = 501L)),
        )
    }

    @Test
    fun `pausa aborta incluso con audio confirmado`() {
        assertEquals(
            CrossfadeStartGuard.Decision.ABORT,
            guard().evaluate(input(playWhenReady = false, incomingAudioTrackId = "B")),
        )
    }

    @Test
    fun `identidad ausente o navegacion abortan`() {
        val guard = guard()

        assertEquals(
            CrossfadeStartGuard.Decision.ABORT,
            guard.evaluate(input(expectedMainTrackId = null, observedMainTrackId = "B")),
        )
        assertEquals(
            CrossfadeStartGuard.Decision.ABORT,
            guard.evaluate(input(observedMainTrackId = "C", incomingAudioTrackId = "B")),
        )
    }

    @Test
    fun `error del tail aborta incluso con audio confirmado`() {
        assertEquals(
            CrossfadeStartGuard.Decision.ABORT,
            guard().evaluate(input(incomingAudioTrackId = "B", tailError = true)),
        )
    }

    @Test
    fun `senal vieja de A o identidad no resoluble no confirman B`() {
        assertEquals(
            CrossfadeStartGuard.Decision.WAIT,
            guard().evaluate(input(incomingAudioTrackId = "A")),
        )
        assertEquals(
            CrossfadeStartGuard.Decision.WAIT,
            guard().evaluate(input(incomingAudioTrackId = null)),
        )
    }

    @Test
    fun `fade inicial corto reduce el presupuesto sin overflow`() {
        val guard = guard(initialWallFadeMs = 700L)

        assertEquals(200L, guard.waitBudgetMs)
        assertEquals(CrossfadeStartGuard.Decision.WAIT, guard.evaluate(input(elapsedWaitingMs = 199L)))
        assertEquals(CrossfadeStartGuard.Decision.ABORT, guard.evaluate(input(elapsedWaitingMs = 200L)))
        assertEquals(0L, guard(initialWallFadeMs = Long.MIN_VALUE).waitBudgetMs)
        assertEquals(2_500L, guard(initialWallFadeMs = Long.MAX_VALUE).waitBudgetMs)
    }

    @Test
    fun `invariantes exhaustivas de start y wait`() {
        val expectedIds = listOf<String?>(null, "B")
        val observedIds = listOf<String?>(null, "A", "B")
        val incomingIds = listOf<String?>(null, "A", "B")
        val booleans = listOf(false, true)
        val elapsedValues = listOf(-1L, 0L, 2_499L, 2_500L)
        val remainingValues = listOf(-1L, 499L, 500L, 501L, Long.MAX_VALUE)
        val guard = guard(initialWallFadeMs = Long.MAX_VALUE)

        expectedIds.forEach { expected ->
            observedIds.forEach { observed ->
                incomingIds.forEach { incoming ->
                    booleans.forEach { playWhenReady ->
                        booleans.forEach { tailError ->
                            elapsedValues.forEach { elapsed ->
                                remainingValues.forEach { remaining ->
                                    val input = input(
                                        expectedMainTrackId = expected,
                                        observedMainTrackId = observed,
                                        playWhenReady = playWhenReady,
                                        incomingAudioTrackId = incoming,
                                        tailError = tailError,
                                        elapsedWaitingMs = elapsed,
                                        remainingTailWallMs = remaining,
                                    )
                                    when (val decision = guard.evaluate(input)) {
                                        is CrossfadeStartGuard.Decision.START -> {
                                            assertTrue(expected != null)
                                            assertEquals(expected, observed)
                                            assertEquals(expected, incoming)
                                            assertTrue(playWhenReady)
                                            assertTrue(!tailError)
                                            assertTrue(decision.fadeMs >= 500L)
                                            assertEquals(remaining, decision.fadeMs)
                                        }

                                        CrossfadeStartGuard.Decision.WAIT -> {
                                            assertTrue(expected != null)
                                            assertEquals(expected, observed)
                                            assertTrue(playWhenReady)
                                            assertTrue(!tailError)
                                            assertTrue(incoming != expected)
                                            assertTrue(elapsed >= 0L)
                                            assertTrue(elapsed < guard.waitBudgetMs)
                                            assertTrue(remaining > 500L)
                                        }

                                        CrossfadeStartGuard.Decision.ABORT -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun guard(initialWallFadeMs: Long = 4_000L) = CrossfadeStartGuard(initialWallFadeMs)

    private fun input(
        expectedMainTrackId: String? = "B",
        observedMainTrackId: String? = "B",
        playWhenReady: Boolean = true,
        incomingAudioTrackId: String? = null,
        tailError: Boolean = false,
        elapsedWaitingMs: Long = 0L,
        remainingTailWallMs: Long = 4_000L,
    ) = CrossfadeStartGuard.Input(
        expectedMainTrackId = expectedMainTrackId,
        observedMainTrackId = observedMainTrackId,
        playWhenReady = playWhenReady,
        incomingAudioTrackId = incomingAudioTrackId,
        tailError = tailError,
        elapsedWaitingMs = elapsedWaitingMs,
        remainingTailWallMs = remainingTailWallMs,
    )
}
