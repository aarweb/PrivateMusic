package com.aar.privatemusic.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixPromptParserTest {

    private fun conds(text: String) = MixPromptParser.parse(text).rules.root.conditions

    private fun has(text: String, field: RuleField, op: RuleOp, value: Double? = null, value2: Double? = null): Boolean =
        conds(text).any { c ->
            c.field == field && c.op == op && (value == null || c.value == value) && (value2 == null || c.value2 == value2)
        }

    @Test
    fun `tranquilo sin voz que no haya sonado en un mes`() {
        val t = "algo tranquilo sin voz que no haya sonado en un mes"
        assertTrue(has(t, RuleField.RELAXED, RuleOp.GT, 60.0))
        assertTrue(has(t, RuleField.VOCALNESS, RuleOp.LT, 40.0))
        assertTrue(has(t, RuleField.LAST_PLAYED, RuleOp.NOT_IN_LAST_DAYS, 30.0))
        assertEquals(3, conds(t).size)
        assertTrue(MixPromptParser.parse(t).ignored.isEmpty(), "nada sin entender: ${MixPromptParser.parse(t).ignored}")
    }

    @Test
    fun `decadas y anos`() {
        assertTrue(has("rock de los 90", RuleField.YEAR, RuleOp.BETWEEN, 1990.0, 1999.0))
        assertTrue(has("canciones de los ochenta", RuleField.YEAR, RuleOp.BETWEEN, 1980.0, 1989.0))
        assertTrue(has("de los 2000", RuleField.YEAR, RuleOp.BETWEEN, 2000.0, 2009.0))
        assertTrue(has("temas de los 2010s", RuleField.YEAR, RuleOp.BETWEEN, 2010.0, 2019.0))
        assertTrue(has("algo antes de 1975", RuleField.YEAR, RuleOp.LT, 1975.0))
        assertTrue(has("cosas desde 2022", RuleField.YEAR, RuleOp.GT, 2021.0))
    }

    @Test
    fun `tempo y energia`() {
        assertTrue(has("algo rápido para correr", RuleField.BPM, RuleOp.GT, 128.0))
        assertTrue(has("baladas lentas", RuleField.BPM, RuleOp.LT, 95.0))
        assertTrue(has("a 120 bpm", RuleField.BPM, RuleOp.BETWEEN, 112.0, 128.0))
        assertTrue(has("cañero para el gym", RuleField.ENERGY, RuleOp.GT, 60.0))
        assertTrue(has("bailable de fiesta", RuleField.DANCEABILITY, RuleOp.GT, 60.0))
        assertTrue(has("algo alegre de verano", RuleField.HAPPY, RuleOp.GT, 60.0))
        assertTrue(has("triste para llorar", RuleField.SAD, RuleOp.GT, 60.0))
    }

    @Test
    fun `historial, favoritas y artista`() {
        assertTrue(has("mis favoritas que no haya escuchado en dos semanas", RuleField.FAVORITE, RuleOp.IS_TRUE))
        assertTrue(has("mis favoritas que no haya escuchado en dos semanas", RuleField.LAST_PLAYED, RuleOp.NOT_IN_LAST_DAYS, 14.0))
        assertTrue(has("canciones que nunca he escuchado", RuleField.PLAY_COUNT, RuleOp.EQ, 0.0))
        val artist = conds("algo tranquilo de Radiohead").first { it.field == RuleField.ARTIST }
        assertEquals("radiohead", artist.text)
        assertEquals(RuleOp.CONTAINS, artist.op)
        val p = MixPromptParser.parse("las más escuchadas")
        assertEquals(RuleSort.PLAY_COUNT, p.rules.sort)
    }

    @Test
    fun `parecido a y ordenar para mezclar`() {
        val p = MixPromptParser.parse("algo parecido a Blinding Lights para mezclar")
        assertEquals("blinding lights", p.similarTo)
        assertTrue(p.sortForMixing)
        val q = MixPromptParser.parse("rápidas en la misma tonalidad")
        assertTrue(q.sortForMixing)
        assertTrue(q.rules.root.conditions.any { it.field == RuleField.BPM })
    }

    @Test
    fun `lo que no entiende lo devuelve sin inventar reglas`() {
        val p = MixPromptParser.parse("ponme cosas moradas")
        assertTrue(p.rules.root.isEmpty)
        assertEquals(listOf("moradas"), p.ignored)
    }
}
