package com.aar.privatemusic.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La comparación de versiones del actualizador.
 *
 * Equivocarse aquí tiene dos formas y las dos escuecen: o los móviles se quedan
 * sin actualizar para siempre, o descargan 90 MB y avisan de una versión que ya
 * tienen en cada arranque. La etiqueta de la release y el `versionName` no
 * siempre son idénticos (`v1.87` frente a `1.87`), así que conviene fijarlo.
 */
class AppUpdaterTest {

    private fun newer(remote: String, local: String) = AppUpdater.isNewer(remote, local)

    @Test
    fun `una version mayor es mas nueva`() {
        assertTrue(newer("1.88", "1.87"))
        assertTrue(newer("2.0", "1.99"))
        assertTrue(newer("1.90", "1.9"))
    }

    @Test
    fun `la misma version no es mas nueva`() {
        assertFalse(newer("1.87", "1.87"))
    }

    @Test
    fun `una version anterior no es mas nueva`() {
        assertFalse(newer("1.86", "1.87"))
        assertFalse(newer("1.9", "1.10"))
    }

    @Test
    fun `compara numero a numero, no como texto`() {
        // Como texto "1.9" > "1.10"; como versión, no.
        assertTrue(newer("1.10", "1.9"))
    }

    @Test
    fun `tolera distinto numero de partes`() {
        assertTrue(newer("1.87.1", "1.87"))
        assertFalse(newer("1.87", "1.87.1"))
        assertFalse(newer("1.87.0", "1.87"))
    }

    @Test
    fun `las etiquetas raras no cuentan como version nueva`() {
        // Una release llamada "models" o "v1.88-rc" no debe disparar la
        // actualización: los trozos no numéricos se descartan.
        assertFalse(newer("models", "1.87"))
        assertFalse(newer("", "1.87"))
    }

    @Test
    fun `normaliza bullets markdown como texto legible`() {
        assertEquals(
            "Novedades\n\n• Mejora la búsqueda\n• Corrige la cola\n• Añade soporte",
            AppUpdater.normalizeReleaseNotes(
                "# Novedades\n\n- Mejora la búsqueda\n* Corrige la cola\n+ Añade soporte",
            ),
        )
    }

    @Test
    fun `normaliza saltos windows y exceso de blancos`() {
        assertEquals(
            "Primera línea\n\nSegunda línea",
            AppUpdater.normalizeReleaseNotes("  Primera   línea  \r\n\r\n\r\n Segunda línea  \r\n"),
        )
    }

    @Test
    fun `un body vacio sigue vacio`() {
        assertEquals("", AppUpdater.normalizeReleaseNotes(" \r\n\t\n "))
    }

    @Test
    fun `trunca sin partir pares surrogate`() {
        val result = AppUpdater.normalizeReleaseNotes("1234🎵5678", maxLength = 6)
        assertEquals("1234…", result)
        assertTrue(result.length <= 6)
        assertFalse(result.any { Character.isSurrogate(it) })
    }
}
