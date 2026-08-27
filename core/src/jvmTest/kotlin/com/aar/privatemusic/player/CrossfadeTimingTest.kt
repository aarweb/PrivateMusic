package com.aar.privatemusic.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossfadeTimingTest {

    @Test
    fun `tabla exacta de reservas y triggers`() {
        val cases = listOf(
            ReserveCase(1_000L, autoMix = false, expectedReserveMs = 2_000L),
            ReserveCase(1_000L, autoMix = true, expectedReserveMs = 2_000L),
            ReserveCase(2_000L, autoMix = false, expectedReserveMs = 4_000L),
            ReserveCase(2_000L, autoMix = true, expectedReserveMs = 4_000L),
            ReserveCase(4_000L, autoMix = false, expectedReserveMs = 7_240L),
            ReserveCase(4_000L, autoMix = true, expectedReserveMs = 7_840L),
            ReserveCase(8_000L, autoMix = false, expectedReserveMs = 9_240L),
            ReserveCase(8_000L, autoMix = true, expectedReserveMs = 9_240L),
            ReserveCase(12_000L, autoMix = false, expectedReserveMs = 11_240L),
            ReserveCase(12_000L, autoMix = true, expectedReserveMs = 11_240L),
        )

        cases.forEach { case ->
            assertEquals(
                case.expectedReserveMs,
                CrossfadeTiming.startupReserveMs(case.configuredMs, case.autoMix),
                case.toString(),
            )
            assertEquals(
                case.configuredMs + case.expectedReserveMs,
                CrossfadeTiming.technicalTriggerMs(case.configuredMs, case.autoMix),
                case.toString(),
            )
        }
    }

    @Test
    fun `startup instantaneo espera desde el trigger y conserva la ventana completa`() {
        val configuredMs = 4_000L
        val triggerMs = CrossfadeTiming.technicalTriggerMs(configuredMs, autoMix = false)

        assertEquals(
            CrossfadeTiming.AudibleStartDecision.WAIT,
            CrossfadeTiming.decideAudibleStart(configuredMs, triggerMs),
        )
        assertEquals(
            CrossfadeTiming.AudibleStartDecision.START(configuredMs),
            CrossfadeTiming.decideAudibleStart(configuredMs, configuredMs),
        )
    }

    @Test
    fun `startup lento dentro de reserva espera y conserva el objetivo`() {
        val configuredMs = 8_000L
        val reserveMs = CrossfadeTiming.startupReserveMs(configuredMs, autoMix = true)
        val triggerMs = CrossfadeTiming.technicalTriggerMs(configuredMs, autoMix = true)
        val startupLatencyMs = reserveMs - 1_000L

        assertEquals(
            CrossfadeTiming.AudibleStartDecision.WAIT,
            CrossfadeTiming.decideAudibleStart(configuredMs, triggerMs - startupLatencyMs),
        )
        assertEquals(
            CrossfadeTiming.AudibleStartDecision.START(configuredMs),
            CrossfadeTiming.decideAudibleStart(configuredMs, triggerMs - reserveMs),
        )
    }

    @Test
    fun `startup mas lento que la reserva se degrada al restante real`() {
        val configuredMs = 8_000L
        val reserveMs = CrossfadeTiming.startupReserveMs(configuredMs, autoMix = false)
        val triggerMs = CrossfadeTiming.technicalTriggerMs(configuredMs, autoMix = false)
        val excessLatencyMs = 750L

        assertEquals(
            CrossfadeTiming.AudibleStartDecision.START(configuredMs - excessLatencyMs),
            CrossfadeTiming.decideAudibleStart(
                configuredMs,
                triggerMs - reserveMs - excessLatencyMs,
            ),
        )
    }

    @Test
    fun `regresion de ocho segundos no consume la ventana audible`() {
        val configuredMs = 8_000L
        val triggerMs = CrossfadeTiming.technicalTriggerMs(configuredMs, autoMix = false)

        assertEquals(17_240L, triggerMs)
        assertEquals(
            CrossfadeTiming.AudibleStartDecision.WAIT,
            CrossfadeTiming.decideAudibleStart(configuredMs, triggerMs - 4_000L),
        )
        assertEquals(
            CrossfadeTiming.AudibleStartDecision.START(8_000L),
            CrossfadeTiming.decideAudibleStart(configuredMs, 8_000L),
        )
    }

    @Test
    fun `plan de final musical limita silencio y mantiene duracion positiva`() {
        assertEquals(
            CrossfadeTiming.MusicalEndPlan(10_000L, 0L),
            CrossfadeTiming.planMusicalEnd(10_000L, 0L, 4_000L),
        )
        assertEquals(
            CrossfadeTiming.MusicalEndPlan(10_000L, 0L),
            CrossfadeTiming.planMusicalEnd(10_000L, -1L, 4_000L),
        )
        assertEquals(
            CrossfadeTiming.MusicalEndPlan(8_000L, 2_000L),
            CrossfadeTiming.planMusicalEnd(10_000L, 2_000L, 4_000L),
        )
        assertEquals(
            CrossfadeTiming.MusicalEndPlan(8_000L, 2_000L),
            CrossfadeTiming.planMusicalEnd(10_000L, 9_000L, 4_000L),
        )
        assertEquals(
            CrossfadeTiming.MusicalEndPlan(1L, 0L),
            CrossfadeTiming.planMusicalEnd(1L, Long.MAX_VALUE, Long.MAX_VALUE),
        )
        assertEquals(
            CrossfadeTiming.MusicalEndPlan(1L, 1L),
            CrossfadeTiming.planMusicalEnd(2L, Long.MAX_VALUE, Long.MAX_VALUE),
        )
        assertEquals(
            CrossfadeTiming.MusicalEndPlan(4_611_686_018_427_387_904L, 4_611_686_018_427_387_903L),
            CrossfadeTiming.planMusicalEnd(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
        )
        assertEquals(
            CrossfadeTiming.MusicalEndPlan(0L, 0L),
            CrossfadeTiming.planMusicalEnd(Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
        )
        assertEquals(
            CrossfadeTiming.MusicalEndPlan(10_000L, 0L),
            CrossfadeTiming.planMusicalEnd(10_000L, Long.MAX_VALUE, 0L),
        )
    }

    @Test
    fun `invariantes exhaustivas no desbordan ni salen de la ventana`() {
        val values = listOf(
            Long.MIN_VALUE,
            -1L,
            0L,
            1L,
            2L,
            499L,
            500L,
            1_000L,
            4_000L,
            Long.MAX_VALUE / 2L,
            Long.MAX_VALUE - 1L,
            Long.MAX_VALUE,
        )

        values.forEach { configured ->
            val normalizedConfigured = configured.coerceAtLeast(0L)
            listOf(false, true).forEach { autoMix ->
                val reserveMs = CrossfadeTiming.startupReserveMs(configured, autoMix)
                val doubledConfigured = if (normalizedConfigured > Long.MAX_VALUE / 2L) {
                    Long.MAX_VALUE
                } else {
                    normalizedConfigured * 2L
                }
                val reserveLimitMs = minOf(12_000L, doubledConfigured)
                val triggerMs = CrossfadeTiming.technicalTriggerMs(configured, autoMix)

                assertTrue(reserveMs >= 0L)
                assertTrue(reserveMs <= reserveLimitMs)
                assertTrue(triggerMs >= normalizedConfigured)
                assertEquals(saturatedAdd(normalizedConfigured, reserveMs), triggerMs)
            }

            values.forEach { remaining ->
                when (
                    val decision = CrossfadeTiming.decideAudibleStart(configured, remaining)
                ) {
                    is CrossfadeTiming.AudibleStartDecision.START -> {
                        assertTrue(decision.durationMs > 0L)
                        assertTrue(decision.durationMs <= normalizedConfigured)
                        assertTrue(decision.durationMs <= remaining)
                        assertTrue(remaining <= normalizedConfigured)
                    }

                    CrossfadeTiming.AudibleStartDecision.WAIT -> {
                        assertTrue(normalizedConfigured > 0L)
                        assertTrue(remaining > normalizedConfigured)
                    }

                    CrossfadeTiming.AudibleStartDecision.ABORT -> {
                        assertTrue(normalizedConfigured == 0L || remaining <= 0L)
                    }
                }
            }
        }
    }

    @Test
    fun `invariantes exhaustivas del final musical`() {
        val values = listOf(
            Long.MIN_VALUE,
            -1L,
            0L,
            1L,
            2L,
            4_000L,
            Long.MAX_VALUE / 2L,
            Long.MAX_VALUE,
        )

        values.forEach { duration ->
            values.forEach { tailSilence ->
                values.forEach { configured ->
                    val plan = CrossfadeTiming.planMusicalEnd(duration, tailSilence, configured)
                    val normalizedDuration = duration.coerceAtLeast(0L)
                    val normalizedTail = tailSilence.coerceAtLeast(0L)
                    val normalizedConfigured = configured.coerceAtLeast(0L)

                    assertTrue(plan.effectiveDurationMs >= 0L)
                    assertTrue(plan.effectiveDurationMs <= normalizedDuration)
                    assertTrue(plan.appliedTailSilenceMs >= 0L)
                    assertTrue(plan.appliedTailSilenceMs <= normalizedTail)
                    assertTrue(plan.appliedTailSilenceMs <= normalizedConfigured / 2L)
                    assertEquals(
                        normalizedDuration,
                        plan.effectiveDurationMs + plan.appliedTailSilenceMs,
                    )
                    if (normalizedDuration > 0L) {
                        assertTrue(plan.effectiveDurationMs >= 1L)
                        assertTrue(plan.appliedTailSilenceMs <= normalizedDuration - 1L)
                    }
                }
            }
        }
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private data class ReserveCase(
        val configuredMs: Long,
        val autoMix: Boolean,
        val expectedReserveMs: Long,
    )
}
