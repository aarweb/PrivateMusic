package com.aar.privatemusic.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioRoutePlanTest {

    @Test
    fun `sin reproductores no aplica ni invalida`() {
        assertEquals(output(), plan(hasMain = false, hasTail = false, routeChanged = true))
    }

    @Test
    fun `solo main aplica main desalineado`() {
        assertEquals(output(applyMain = true), plan(hasMain = true, hasTail = false))
    }

    @Test
    fun `solo tail aplica tail desalineado`() {
        assertEquals(output(applyTail = true), plan(hasMain = false, hasTail = true))
    }

    @Test
    fun `ambos ya alineados no requieren escritura`() {
        assertEquals(
            output(),
            plan(mainAlreadyOnTarget = true, tailAlreadyOnTarget = true),
        )
    }

    @Test
    fun `force reaplica ambos aunque ya esten alineados`() {
        assertEquals(
            output(applyMain = true, applyTail = true),
            plan(
                mainAlreadyOnTarget = true,
                tailAlreadyOnTarget = true,
                forceApply = true,
            ),
        )
    }

    @Test
    fun `force no invalida si la ruta no cambio`() {
        assertFalse(
            plan(
                mainAlreadyOnTarget = true,
                tailAlreadyOnTarget = true,
                routeChanged = false,
                forceApply = true,
            ).invalidateCrossfade,
        )
    }

    @Test
    fun `solo main desalineado aplica main`() {
        assertEquals(
            output(applyMain = true),
            plan(mainAlreadyOnTarget = false, tailAlreadyOnTarget = true),
        )
    }

    @Test
    fun `solo tail desalineado aplica tail`() {
        assertEquals(
            output(applyTail = true),
            plan(mainAlreadyOnTarget = true, tailAlreadyOnTarget = false),
        )
    }

    @Test
    fun `ambos desalineados aplican el mismo objetivo`() {
        assertEquals(output(applyMain = true, applyTail = true), plan())
    }

    @Test
    fun `cambio de ruta local activo invalida crossfade`() {
        assertTrue(plan(routeChanged = true).invalidateCrossfade)
    }

    @Test
    fun `cambio de ruta durante Cast alinea e invalida sin autoplay`() {
        assertEquals(
            output(applyMain = true, applyTail = true, invalidateCrossfade = true),
            plan(localActive = false, routeChanged = true),
        )
    }

    @Test
    fun `conexion sin cambio de ruta sólo alinea y no expresa accion play`() {
        val result = plan(
            localActive = false,
            mainAlreadyOnTarget = true,
            tailAlreadyOnTarget = false,
            routeChanged = false,
        )

        assertEquals(output(applyTail = true), result)
        assertFalse(result.invalidateCrossfade)
    }

    @Test
    fun `matriz exhaustiva cumple invariantes`() {
        val booleans = listOf(false, true)

        booleans.forEach { localActive ->
            booleans.forEach { hasMain ->
                booleans.forEach { hasTail ->
                    booleans.forEach { mainAligned ->
                        booleans.forEach { tailAligned ->
                            booleans.forEach { routeChanged ->
                                booleans.forEach { forceApply ->
                                    val result = plan(
                                        localActive = localActive,
                                        hasMain = hasMain,
                                        hasTail = hasTail,
                                        mainAlreadyOnTarget = mainAligned,
                                        tailAlreadyOnTarget = tailAligned,
                                        routeChanged = routeChanged,
                                        forceApply = forceApply,
                                    )

                                    assertEquals(
                                        hasMain && (forceApply || !mainAligned),
                                        result.applyMain,
                                    )
                                    assertEquals(
                                        hasTail && (forceApply || !tailAligned),
                                        result.applyTail,
                                    )
                                    assertEquals(
                                        routeChanged && (hasMain || hasTail),
                                        result.invalidateCrossfade,
                                    )
                                    if (result.applyMain) assertTrue(hasMain)
                                    if (result.applyTail) assertTrue(hasTail)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun plan(
        localActive: Boolean = true,
        hasMain: Boolean = true,
        hasTail: Boolean = true,
        mainAlreadyOnTarget: Boolean = false,
        tailAlreadyOnTarget: Boolean = false,
        routeChanged: Boolean = false,
        forceApply: Boolean = false,
    ) = AudioRoutePlan.evaluate(
        AudioRoutePlan.Input(
            localActive = localActive,
            hasMain = hasMain,
            hasTail = hasTail,
            mainAlreadyOnTarget = mainAlreadyOnTarget,
            tailAlreadyOnTarget = tailAlreadyOnTarget,
            routeChanged = routeChanged,
            forceApply = forceApply,
        ),
    )

    private fun output(
        applyMain: Boolean = false,
        applyTail: Boolean = false,
        invalidateCrossfade: Boolean = false,
    ) = AudioRoutePlan.Output(applyMain, applyTail, invalidateCrossfade)
}
