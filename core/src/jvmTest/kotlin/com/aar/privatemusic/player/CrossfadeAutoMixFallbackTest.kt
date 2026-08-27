package com.aar.privatemusic.player

import kotlin.test.Test
import kotlin.test.assertEquals

class CrossfadeAutoMixFallbackTest {

    @Test
    fun `evalua exhaustivamente el producto cartesiano y acepta exactamente 24 casos`() {
        val eligiblePhases = setOf(
            CrossfadeLifecycle.Phase.PREARMING,
            CrossfadeLifecycle.Phase.ARMED,
            CrossfadeLifecycle.Phase.WARMING,
        )
        val eligibleReasons = setOf(
            CrossfadeLifecycle.CancelReason.PREPARATION_FAILED,
            CrossfadeLifecycle.CancelReason.TAIL_NO_AUDIO,
            CrossfadeLifecycle.CancelReason.EXCESSIVE_DRIFT,
            CrossfadeLifecycle.CancelReason.TAIL_ERROR,
        )
        val ratios = listOf(1f, 0.9f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)
        var evaluated = 0
        var accepted = 0

        CrossfadeLifecycle.Phase.entries.forEach { phase ->
            CrossfadeLifecycle.CancelReason.entries.forEach { reason ->
                listOf(false, true).forEach { autoMixApplied ->
                    listOf(false, true).forEach { retryAlreadyAttempted ->
                        ratios.forEach { ratio ->
                            val expected = phase in eligiblePhases &&
                                reason in eligibleReasons &&
                                autoMixApplied &&
                                ratio.isFinite() &&
                                ratio > 0f &&
                                ratio != 1f &&
                                !retryAlreadyAttempted
                            val actual = CrossfadeAutoMixFallback.shouldRetryNeutral(
                                phase = phase,
                                reason = reason,
                                autoMixApplied = autoMixApplied,
                                ratio = ratio,
                                retryAlreadyAttempted = retryAlreadyAttempted,
                            )

                            assertEquals(
                                expected,
                                actual,
                                "phase=$phase reason=$reason applied=$autoMixApplied " +
                                    "retry=$retryAlreadyAttempted ratio=$ratio",
                            )
                            evaluated++
                            if (actual) accepted++
                        }
                    }
                }
            }
        }

        assertEquals(1_764, evaluated)
        assertEquals(24, accepted)
    }
}
