package com.aar.privatemusic.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoRetryPolicyTest {

    @Test
    fun `expone los intervalos y el lote acordados`() {
        assertEquals(86_400_000L, VideoRetryPolicy.ENTRY_CHECK_INTERVAL_MS)
        assertEquals(604_800_000L, VideoRetryPolicy.MARKER_RETRY_INTERVAL_MS)
        assertEquals(3, VideoRetryPolicy.MAX_BATCH_SIZE)
    }

    @Test
    fun `comprobacion de entrada nunca hecha o invalida esta debida`() {
        val now = 2_000_000_000L
        assertTrue(VideoRetryPolicy.isEntryCheckDue(0L, now))
        assertTrue(VideoRetryPolicy.isEntryCheckDue(-1L, now))
        assertTrue(VideoRetryPolicy.isEntryCheckDue(Long.MIN_VALUE, now))
    }

    @Test
    fun `comprobacion de entrada respeta exactamente el umbral`() {
        val checkedAt = 1_000_000_000L
        val threshold = checkedAt + VideoRetryPolicy.ENTRY_CHECK_INTERVAL_MS
        assertFalse(VideoRetryPolicy.isEntryCheckDue(checkedAt, threshold - 1L))
        assertTrue(VideoRetryPolicy.isEntryCheckDue(checkedAt, threshold))
        assertTrue(VideoRetryPolicy.isEntryCheckDue(checkedAt, threshold + 1L))
    }

    @Test
    fun `comprobacion futura o con reloj retrocedido esta debida`() {
        assertTrue(VideoRetryPolicy.isEntryCheckDue(2_000L, 1_999L))
        assertTrue(VideoRetryPolicy.isEntryCheckDue(1L, 0L))
        assertTrue(VideoRetryPolicy.isEntryCheckDue(1L, Long.MIN_VALUE))
        assertTrue(VideoRetryPolicy.isEntryCheckDue(Long.MAX_VALUE, Long.MAX_VALUE - 1L))
    }

    @Test
    fun `comprobacion maneja extremos sin desbordar`() {
        assertTrue(VideoRetryPolicy.isEntryCheckDue(1L, Long.MAX_VALUE))
        assertFalse(VideoRetryPolicy.isEntryCheckDue(Long.MAX_VALUE, Long.MAX_VALUE))
        assertTrue(VideoRetryPolicy.isEntryCheckDue(Long.MIN_VALUE, Long.MAX_VALUE))
        assertTrue(VideoRetryPolicy.isEntryCheckDue(Long.MIN_VALUE, Long.MIN_VALUE))
    }

    @Test
    fun `marca nunca fechada o invalida puede reintentarse`() {
        val now = 2_000_000_000L
        assertTrue(VideoRetryPolicy.canRetryMarker(0L, now))
        assertTrue(VideoRetryPolicy.canRetryMarker(-1L, now))
        assertTrue(VideoRetryPolicy.canRetryMarker(Long.MIN_VALUE, now))
    }

    @Test
    fun `marca respeta exactamente los siete dias`() {
        val markedAt = 1_000_000_000L
        val threshold = markedAt + VideoRetryPolicy.MARKER_RETRY_INTERVAL_MS
        assertFalse(VideoRetryPolicy.canRetryMarker(markedAt, threshold - 1L))
        assertTrue(VideoRetryPolicy.canRetryMarker(markedAt, threshold))
        assertTrue(VideoRetryPolicy.canRetryMarker(markedAt, threshold + 1L))
    }

    @Test
    fun `marca futura o con reloj retrocedido puede reintentarse`() {
        assertTrue(VideoRetryPolicy.canRetryMarker(2_000L, 1_999L))
        assertTrue(VideoRetryPolicy.canRetryMarker(1L, 0L))
        assertTrue(VideoRetryPolicy.canRetryMarker(1L, Long.MIN_VALUE))
        assertTrue(VideoRetryPolicy.canRetryMarker(Long.MAX_VALUE, Long.MAX_VALUE - 1L))
    }

    @Test
    fun `marca maneja extremos sin desbordar`() {
        assertTrue(VideoRetryPolicy.canRetryMarker(1L, Long.MAX_VALUE))
        assertFalse(VideoRetryPolicy.canRetryMarker(Long.MAX_VALUE, Long.MAX_VALUE))
        assertTrue(VideoRetryPolicy.canRetryMarker(Long.MIN_VALUE, Long.MAX_VALUE))
        assertTrue(VideoRetryPolicy.canRetryMarker(Long.MIN_VALUE, Long.MIN_VALUE))
    }
}
