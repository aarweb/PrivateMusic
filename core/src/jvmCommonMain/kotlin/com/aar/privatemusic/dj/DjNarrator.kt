package com.aar.privatemusic.dj

import com.aar.privatemusic.data.db.Song
import kotlin.math.roundToInt

/**
 * El "locutor" del AI DJ: convierte datos REALES (BPM, tonalidad, ánimo,
 * historial, racha de artista, bloque) en una frase corta de presentación.
 * Con plantillas + memoria anti-repetición; sin modelo de lenguaje, sin red.
 * Si falta el dato, cae a una frase neutra: NUNCA inventa cifras.
 */
class DjNarrator(seed: Long = 1) {

    private val rng = DeterministicRng(seed)
    private val recent = ArrayDeque<String>()

    /** Datos de la canción/bloque que entra, y de la que salía. */
    data class Cue(
        val block: DjEngine.Block,
        val incoming: Song,
        val outgoing: Song?,
        /** Reproducciones de la canción entrante (0 = nunca sonó). */
        val incomingPlays: Int,
        /** Hace cuántos días sonó por última vez, o null si nunca. */
        val incomingLastPlayedDays: Int?,
        /** Cuántas seguidas del mismo artista lleva contando la entrante. */
        val artistStreak: Int,
    )

    /** Frase para presentar el arranque de un bloque. */
    fun narrate(cue: Cue): String {
        val options = buildList {
            // Racha de artista (lo más llamativo primero).
            if (cue.artistStreak >= 3) add(pick(artistStreak(cue.incoming.artist, cue.artistStreak)))
            // Cambio de energía por BPM.
            bpmLine(cue)?.let { add(it) }
            // Historial (un descubrimiento o un clásico pesa más que la tonalidad).
            historyLine(cue)?.let { add(it) }
            // Tonalidad (mezcla limpia).
            camelotLine(cue)?.let { add(it) }
            // Ánimo del bloque.
            add(pick(moodLines(cue.block)))
        }
        // Elige la primera no repetida recientemente; si todas se repiten, la primera.
        val chosen = options.firstOrNull { it !in recent } ?: options.firstOrNull() ?: NEUTRAL
        remember(chosen)
        return chosen
    }

    /** Comentario cuando el usuario pide algo ("más animado"). */
    fun narrateRequest(summary: List<String>): String {
        val what = summary.firstOrNull()
        val base = if (what.isNullOrBlank()) pick(REQUEST_GENERIC) else pick(REQUEST_WITH) 
        return if (what.isNullOrBlank()) base else base.replace("{q}", what)
    }

    private fun bpmLine(cue: Cue): String? {
        val out = cue.outgoing?.bpm?.roundToInt() ?: return null
        val inn = cue.incoming.bpm?.roundToInt() ?: return null
        if (out <= 0 || inn <= 0) return null
        val diff = inn - out
        return when {
            diff >= 12 -> pick(listOf(
                "Subimos el ritmo, de $out a $inn",
                "Aceleramos: $out a $inn pulsaciones",
                "Más caña, nos vamos a $inn",
            ))
            diff <= -12 -> pick(listOf(
                "Bajamos revoluciones, de $out a $inn",
                "Aflojamos: de $out a $inn",
                "Nos calmamos, $inn de tempo",
            ))
            else -> null
        }
    }

    private fun camelotLine(cue: Cue): String? {
        val a = cue.outgoing?.camelot ?: return null
        val b = cue.incoming.camelot ?: return null
        return when (DjEngine.camelotDistance(a, b)) {
            0, 1 -> pick(listOf(
                "Misma onda de tonalidad, mezcla limpia",
                "Encaje armónico, esto liga solo",
                "Tonalidad vecina, suena redondo",
            ))
            else -> null
        }
    }

    private fun historyLine(cue: Cue): String? = when {
        cue.incomingPlays == 0 -> pick(listOf(
            "Esta no la habías puesto nunca: a ver qué tal",
            "Un descubrimiento de tu propia biblioteca",
            "Rescatada del fondo del baúl",
        ))
        cue.incomingPlays >= 15 -> pick(listOf(
            "De las que tenías en bucle",
            "Un clásico tuyo",
            "Esta te la sabes de memoria",
        ))
        cue.incomingLastPlayedDays != null && cue.incomingLastPlayedDays >= 120 -> pick(listOf(
            "Hacía tiempo que no sonaba",
            "Del baúl de los recuerdos",
            "Volvemos a esta después de meses",
        ))
        else -> null
    }

    private fun moodLines(block: DjEngine.Block): List<String> = when (block.kind) {
        DjEngine.BlockKind.WARMUP -> listOf("Vamos entrando en calor", "Arrancamos suave", "Calentando motores")
        DjEngine.BlockKind.BUILD -> listOf("Subiendo la intensidad", "Vamos a más", "Cogiendo carrerilla")
        DjEngine.BlockKind.PEAK -> listOf("Modo fiesta", "Aquí llega lo gordo", "A todo trapo")
        DjEngine.BlockKind.COOLDOWN -> listOf("Bajamos el telón", "Algo más tranquilo para cerrar", "Modo relax")
    }

    private fun artistStreak(artist: String, n: Int) = listOf(
        "$n seguidas de $artist",
        "Nos quedamos con $artist un rato",
        "Sesión $artist",
    )

    private fun pick(list: List<String>): String = list[rng.nextInt(list.size)]

    private fun remember(s: String) {
        recent.addLast(s)
        while (recent.size > 6) recent.removeFirst()
    }

    companion object {
        const val NEUTRAL = "Seguimos"
        private val REQUEST_GENERIC = listOf("Marchando", "Cambiamos el rumbo", "Vamos con otra cosa")
        private val REQUEST_WITH = listOf("Marchando: {q}", "Vale, {q}", "Ajustado: {q}")
    }
}
