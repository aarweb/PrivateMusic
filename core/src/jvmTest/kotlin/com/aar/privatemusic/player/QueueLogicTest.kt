package com.aar.privatemusic.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueueLogicTest {

    // ---- resumeStartMs ----

    @Test
    fun `una pista corta empieza siempre por el principio`() {
        assertEquals(0L, QueueLogic.resumeStartMs(durationSec = 200, savedMs = 120_000L))
    }

    @Test
    fun `una pista larga reanuda donde se dejo`() {
        assertEquals(1_200_000L, QueueLogic.resumeStartMs(durationSec = 3600, savedMs = 1_200_000L))
    }

    @Test
    fun `menos de treinta segundos no merece reanudar`() {
        assertEquals(0L, QueueLogic.resumeStartMs(durationSec = 3600, savedMs = 20_000L))
    }

    @Test
    fun `reanudar a diez segundos del final seria acabarla`() {
        // 20 min = 1_200_000 ms; el limite esta en (1200 - 10) * 1000.
        assertEquals(0L, QueueLogic.resumeStartMs(durationSec = 1200, savedMs = 1_195_000L))
    }

    // ---- restoreIndex / restorePositionMs ----

    @Test
    fun `sin borrados el indice no se mueve`() {
        val present = listOf(true, true, true, true)
        assertEquals(2, QueueLogic.restoreIndex(present, savedIndex = 2))
    }

    @Test
    fun `una cancion borrada por delante corre el indice hacia atras`() {
        // [A, B(borrada), C]; sonaba C (indice 2) -> ahora es el 1.
        val present = listOf(true, false, true)
        assertEquals(1, QueueLogic.restoreIndex(present, savedIndex = 2))
    }

    @Test
    fun `una cancion borrada por detras no toca el indice`() {
        val present = listOf(true, true, false)
        assertEquals(1, QueueLogic.restoreIndex(present, savedIndex = 1))
    }

    @Test
    fun `si la que sonaba se borro suena la siguiente que quede`() {
        // [A, B(borrada), C]; sonaba B -> debe caer en C, que ahora es el 1.
        val present = listOf(true, false, true)
        assertEquals(1, QueueLogic.restoreIndex(present, savedIndex = 1))
        assertEquals(0L, QueueLogic.restorePositionMs(present, savedIndex = 1, savedPositionMs = 90_000L))
    }

    @Test
    fun `si la ultima se borro se cae en la ultima que queda`() {
        val present = listOf(true, true, false)
        assertEquals(1, QueueLogic.restoreIndex(present, savedIndex = 2))
    }

    @Test
    fun `la posicion solo se hereda si la pista sigue ahi`() {
        val present = listOf(true, true, true)
        assertEquals(90_000L, QueueLogic.restorePositionMs(present, savedIndex = 1, savedPositionMs = 90_000L))
    }

    @Test
    fun `con todo borrado el indice es cero`() {
        assertEquals(0, QueueLogic.restoreIndex(listOf(false, false), savedIndex = 1))
    }

    /**
     * Buscar por id se equivocaría aquí: la cola tiene la misma canción dos
     * veces y sonaba la segunda copia.
     */
    @Test
    fun `con la misma cancion repetida se elige la copia correcta`() {
        // [X, Y(borrada), X]; sonaba la segunda X (indice 2) -> queda [X, X], indice 1.
        val present = listOf(true, false, true)
        assertEquals(1, QueueLogic.restoreIndex(present, savedIndex = 2))
    }

    /**
     * El invariante que importa: el índice devuelto siempre cae dentro de la
     * lista filtrada, y si la pista sobrevive apunta exactamente a ella.
     */
    @Test
    fun `fuerza bruta hasta seis elementos`() {
        for (n in 1..6) {
            for (mask in 0 until (1 shl n)) {
                val present = (0 until n).map { (mask shr it) and 1 == 1 }
                if (present.none { it }) continue
                val survivors = present.count { it }
                for (saved in 0 until n) {
                    val index = QueueLogic.restoreIndex(present, saved)
                    assertTrue(index in 0 until survivors, "indice fuera de rango: $present, $saved")
                    if (present[saved]) {
                        // La posición de la pista superviviente en la lista filtrada.
                        val expected = present.take(saved).count { it }
                        assertEquals(expected, index, "no apunta a la pista que sonaba: $present, $saved")
                    }
                }
            }
        }
    }
}
