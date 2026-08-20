package com.aar.privatemusic.data

import java.text.Normalizer

/**
 * "Mix a medida": convierte una frase en español ("algo tranquilo de los 90 sin
 * voz que no haya sonado en un mes") en reglas de playlist inteligente sobre
 * los atributos que ya calcula la app (BPM, tonalidad, año, ánimo, historial).
 * Es determinista: sin modelo de lenguaje, sin red, sin sorpresas; lo que no
 * entiende, lo ignora y lo devuelve en [Parsed.ignored] para enseñarlo.
 */
object MixPromptParser {

    data class Parsed(
        val rules: SmartRules,
        /** Enlazar por tonalidad (rueda de Camelot) al ordenar la cola. */
        val sortForMixing: Boolean = false,
        /** "parecido a X": semilla para ordenar por cercanía sónica. */
        val similarTo: String? = null,
        /** Palabras que no se han podido traducir a ninguna regla. */
        val ignored: List<String> = emptyList(),
        /** Resumen legible de lo entendido, para confirmarlo en pantalla. */
        val summary: List<String> = emptyList(),
    )

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private class Builder {
        val conditions = mutableListOf<Condition>()
        val summary = mutableListOf<String>()
        var sortForMixing = false
        var similarTo: String? = null
        var sort: RuleSort = RuleSort.RANDOM
        var descending = false
        fun add(c: Condition, label: String) { conditions += c; summary += label }
    }

    private val STOP = setOf(
        "algo", "alguna", "algunas", "algunos", "un", "una", "unas", "unos", "de", "del", "la", "el", "los", "las",
        "que", "y", "o", "con", "para", "por", "me", "pon", "ponme", "quiero", "dame", "musica", "canciones",
        "cancion", "temas", "tema", "mix", "mezcla", "lista", "playlist", "a", "en", "mas", "muy", "bien", "poco",
        "hoy", "ahora", "esta", "este", "mi", "mis", "tu", "sonido", "rollo", "tipo", "estilo", "cosas", "cosa",
        "haya", "hayan", "he", "sonado", "escuchado", "oido", "puesto", "ultimo", "ultima", "ultimos", "ultimas",
        "no", "sin", "ni", "hace", "desde", "dias", "dia", "semana", "semanas", "mes", "meses", "ano", "anos",
        "year", "years", "the", "of", "and", "some", "songs", "music",
    )

    fun parse(text: String): Parsed {
        val b = Builder()
        var t = " " + norm(text).replace(Regex("[¿?¡!.,;:]"), " ").replace(Regex("\\s+"), " ").trim() + " "

        // ---- Años sueltos ("antes de 1975", "desde 2022"): antes que el artista, que si no se los come.
        Regex(" (antes de|anteriores a|hasta) (el )?(\\d{4}) ").find(t)?.let { m ->
            b.add(Condition(RuleField.YEAR, RuleOp.LT, value = m.groupValues[3].toDouble()), "antes de ${m.groupValues[3]}")
            t = t.replace(m.value, " ")
        }
        Regex(" (despues de|posteriores a|desde) (el )?(\\d{4}) ").find(t)?.let { m ->
            b.add(Condition(RuleField.YEAR, RuleOp.GT, value = m.groupValues[3].toDouble() - 1), "desde ${m.groupValues[3]}")
            t = t.replace(m.value, " ")
        }

        // ---- "parecido a X" / "de X" (artista): expresiones con cola libre
        Regex(" (parecido|parecida|parecidas|parecidos|similar|similares) a (.+?)(?= (que|y|pero|sin|con|de los|de las|para)\\b|$)").find(t)?.let { m ->
            b.similarTo = m.groupValues[2].trim()
            b.summary += "parecido a «${b.similarTo}»"
            t = t.replace(m.value, " ")
        }
        Regex(" de (?!los |las |la |el |hoy|ahora|fiesta|correr|estudiar|relax|dormir|baile|bailar|gimnasio|gym|antes|siempre|siempre )([a-z0-9][a-z0-9 .&'\\-]{1,40}?)(?= (que|y|pero|sin|con|para|de los|de las|rapid|lent|tranquil|alegr|trist|bailabl|instrumental|favorit)|$)").find(t)?.let { m ->
            val artist = m.groupValues[1].trim()
            if (artist.isNotBlank() && !artist.all { it.isDigit() || it == ' ' } && artist.split(' ').none { it in DECADE_WORDS }) {
                b.add(Condition(RuleField.ARTIST, RuleOp.CONTAINS, text = artist), "de $artist")
                t = t.replace(m.value, " ")
            }
        }

        // ---- Décadas y años
        decade(t)?.let { (from, to, label) ->
            b.add(Condition(RuleField.YEAR, RuleOp.BETWEEN, value = from.toDouble(), value2 = to.toDouble()), label)
        }

        // ---- Historial: "que no haya sonado en N días/semanas/meses", "nuevas", "las más escuchadas"
        Regex(" (no (haya|hayan|he|las haya|las he) (sonado|escuchado|oido|puesto)|sin escuchar|olvidadas|que no suene[n]?)( (en|desde hace|hace) (el ultimo |la ultima |los ultimos |las ultimas )?((\\d+|un|una|dos|tres|seis) )?(dia|dias|semana|semanas|mes|meses|ano|anos))?").find(t)?.let { m ->
            val n = m.groupValues.getOrNull(8)?.trim().orEmpty()
            val unit = m.groupValues.getOrNull(9)?.trim().orEmpty()
            val days = spanDays(n, unit) ?: 30
            b.add(Condition(RuleField.LAST_PLAYED, RuleOp.NOT_IN_LAST_DAYS, value = days.toDouble()), "sin sonar en $days días")
        }
        if (Regex(" (nunca (escuchad|oid|puest)|nunca (las )?(haya|he) (escuchado|oido|puesto)|sin estrenar|por descubrir|no (haya|he) escuchado nunca)").containsMatchIn(t)) {
            b.add(Condition(RuleField.PLAY_COUNT, RuleOp.EQ, value = 0.0), "nunca escuchadas")
        }
        if (Regex(" (mas escuchad|favoritas de siempre|las de siempre|top|mas puestas|mas oidas)").containsMatchIn(t)) {
            b.add(Condition(RuleField.PLAY_COUNT, RuleOp.GT, value = 2.0), "de las más escuchadas")
            b.sort = RuleSort.PLAY_COUNT; b.descending = true
        }
        if (Regex(" (nuevas|recientes|recien (anadidas|bajadas|descargadas)|anadidas hace poco|lo ultimo)").containsMatchIn(t)) {
            b.add(Condition(RuleField.ADDED, RuleOp.IN_LAST_DAYS, value = 30.0), "añadidas en el último mes")
            b.sort = RuleSort.ADDED; b.descending = true
        }
        if (Regex(" (favoritas|favs|las que me gustan|con corazon|marcadas)").containsMatchIn(t)) {
            b.add(Condition(RuleField.FAVORITE, RuleOp.IS_TRUE), "favoritas")
        }

        // ---- Tempo
        Regex(" (\\d{2,3}) ?bpm").find(t)?.let { m ->
            val bpm = m.groupValues[1].toDouble()
            b.add(Condition(RuleField.BPM, RuleOp.BETWEEN, value = bpm - 8, value2 = bpm + 8), "~${bpm.toInt()} BPM")
        } ?: run {
            if (Regex(" (rapid|acelerad|a tope|para correr|de correr|running|cardio|a toda|movid|marchos|con marcha|frenetic)").containsMatchIn(t)) {
                b.add(Condition(RuleField.BPM, RuleOp.GT, value = 128.0), "rápidas (más de 128 BPM)")
            } else if (Regex(" (lent|pausad|despacio|baladas?|slow|para dormir|de dormir|tumbad)").containsMatchIn(t)) {
                b.add(Condition(RuleField.BPM, RuleOp.LT, value = 95.0), "lentas (menos de 95 BPM)")
            } else if (Regex(" (medio tiempo|mid ?tempo|ritmo medio)").containsMatchIn(t)) {
                b.add(Condition(RuleField.BPM, RuleOp.BETWEEN, value = 95.0, value2 = 128.0), "ritmo medio")
            }
        }

        // ---- Ánimo / energía
        val mood = 60.0
        if (Regex(" (tranquil|relaj|chill|suave|calmad|sereno|serena|para estudiar|de estudiar|de fondo|para leer|relax|para dormir|de dormir|apacible)").containsMatchIn(t)) {
            b.add(Condition(RuleField.RELAXED, RuleOp.GT, value = mood), "tranquilas")
        }
        if (Regex(" (energ|caner|canya|cana |fiesta|fiester|con fuerza|potent|intens|agresiv|duras|dura |heavy|para el gym|de gym|gimnasio|para entrenar|de entrenar|a tope|que pegue)").containsMatchIn(t)) {
            b.add(Condition(RuleField.ENERGY, RuleOp.GT, value = mood), "enérgicas")
        }
        if (Regex(" (bailabl|para bailar|de bailar|bailon|baile|dance|discoteca|marcha|para la fiesta)").containsMatchIn(t)) {
            b.add(Condition(RuleField.DANCEABILITY, RuleOp.GT, value = mood), "bailables")
        }
        if (Regex(" (alegr|feliz|felices|contenta|contento|optimist|animad|buen rollo|positiv|luminos|de verano|veraniega|veraniegas)").containsMatchIn(t)) {
            b.add(Condition(RuleField.HAPPY, RuleOp.GT, value = mood), "alegres")
        }
        if (Regex(" (trist|melanc|nostalg|para llorar|de llorar|oscur|sombri|bajon|depre)").containsMatchIn(t)) {
            b.add(Condition(RuleField.SAD, RuleOp.GT, value = mood), "tristes")
        }
        if (Regex(" (sin voz|sin voces|sin cantar|instrumental|solo musica|sin letra)").containsMatchIn(t)) {
            b.add(Condition(RuleField.VOCALNESS, RuleOp.LT, value = 40.0), "instrumentales")
        } else if (Regex(" (con voz|con voces|cantad|para cantar|de cantar|con letra|karaoke)").containsMatchIn(t)) {
            b.add(Condition(RuleField.VOCALNESS, RuleOp.GT, value = 60.0), "con voz")
        }

        // ---- Orden / duración
        if (Regex(" (misma tonalidad|para mezclar|que encajen|enlazad|armonic|dj|sin saltos de tono|en la misma clave)").containsMatchIn(t)) {
            b.sortForMixing = true
            b.summary += "ordenadas para mezclar"
        }
        if (Regex(" (cortas|breves|de menos de (tres|3) minutos)").containsMatchIn(t)) {
            b.add(Condition(RuleField.DURATION, RuleOp.LT, value = 200.0), "cortas")
        }
        if (Regex(" (largas|de mas de (cinco|5) minutos|extendidas|sets?|mixes)").containsMatchIn(t)) {
            b.add(Condition(RuleField.DURATION, RuleOp.GT, value = 360.0), "largas")
        }

        // ---- Lo que no se ha entendido: palabras "con contenido" que no encajan en nada.
        val understood = b.summary.joinToString(" ").let { norm(it) }
        val ignored = t.trim().split(' ')
            .filter { w -> w.length > 2 && w !in STOP && !understood.contains(w) && !MATCHED_HINTS.any { w.startsWith(it) } }
            .distinct()

        return Parsed(
            rules = SmartRules(
                root = RuleGroup(matchAll = true, conditions = b.conditions),
                sort = b.sort,
                descending = b.descending,
                limit = 0,
            ),
            sortForMixing = b.sortForMixing,
            similarTo = b.similarTo,
            ignored = ignored,
            summary = b.summary,
        )
    }

    private val DECADE_WORDS_LIST = listOf(
        "50s", "60s", "70s", "80s", "90s", "2000s", "2010s", "2020s", "cincuenta", "sesenta", "setenta", "ochenta",
        "noventa", "dosmil", "dos", "mil", "actuales", "actual", "clasic", "viej", "antigu",
    )
    private val DECADE_WORDS = DECADE_WORDS_LIST.toSet()

    private val MATCHED_HINTS = listOf(
        "tranquil", "relaj", "chill", "suav", "calm", "energ", "caner", "fiest", "bail", "alegr", "feliz",
        "trist", "melanc", "nostalg", "instrumental", "voz", "voces", "cantar", "rapid", "lent", "balad", "bpm",
        "favorit", "nuev", "recient", "nunca", "olvidad", "escuchad", "sonad", "parecid", "similar", "mezclar",
        "tonalidad", "corta", "larga", "antes", "despues", "desde", "hasta", "anos", "decada", "gym", "gimnasio",
        "entrenar", "correr", "estudiar", "dormir", "leer", "fondo", "verano", "llorar", "marcha", "dance", "heavy",
        "potent", "intens", "agresiv", "dura", "movid", "acelerad", "pausad", "despacio", "slow", "cardio", "running",
        "tope", "pegue", "medio", "tempo", "ritmo", "karaoke", "letra", "top", "discotec", "armonic", "enlazad",
        "encajen", "clave", "saltos", "sets", "mixes", "extendid", "breves", "minutos", "tres", "cinco", "buen",
        "rollo", "positiv", "luminos", "animad", "optimist", "oscur", "sombri", "bajon", "depre", "sereno", "serena",
        "apacible", "fuerza", "cana", "canya", "frenetic", "marchos", "toda", "tumbad", "estrenar", "descubrir",
        "puestas", "oidas", "siempre", "recien", "anadid", "bajad", "descargad", "ultimo", "corazon", "marcadas",
        "gustan", "favs",
    ) + DECADE_WORDS_LIST


    /** (desde, hasta, etiqueta) de la década mencionada, si hay. */
    private fun decade(t: String): Triple<Int, Int, String>? {
        val m = Regex(" (de )?(los )?(anos )?(19|20)?(50|60|70|80|90|00|10|20)s? ").find(t)
            ?: Regex(" (de )?(los )?(anos )?(cincuenta|sesenta|setenta|ochenta|noventa|dos ?mil(?! (diez|veinte))|dos ?mil diez|dos ?mil veinte) ").find(t)
        if (m != null) {
            val raw = m.value.trim()
            val start = when {
                raw.contains("cincuenta") || raw.endsWith("50") || raw.endsWith("50s") -> 1950
                raw.contains("sesenta") || raw.endsWith("60") || raw.endsWith("60s") -> 1960
                raw.contains("setenta") || raw.endsWith("70") || raw.endsWith("70s") -> 1970
                raw.contains("ochenta") || raw.endsWith("80") || raw.endsWith("80s") -> 1980
                raw.contains("noventa") || raw.endsWith("90") || raw.endsWith("90s") -> 1990
                raw.contains("veinte") || raw.endsWith("2020") || raw.endsWith("20s") || raw.endsWith(" 20") -> 2020
                raw.contains("diez") || raw.endsWith("2010") || raw.endsWith("10s") || raw.endsWith(" 10") -> 2010
                else -> 2000
            }
            return Triple(start, start + 9, "de los ${if (start < 2000) start % 100 else start}")
        }
        if (Regex(" (actuales?|de ahora|modern[ao]s?|de este ano|recientes de verdad) ").containsMatchIn(t)) {
            return Triple(2020, 2099, "actuales")
        }
        if (Regex(" (clasic[ao]s?|viej[ao]s?|antigu[ao]s?|de antes|de toda la vida) ").containsMatchIn(t)) {
            return Triple(1900, 1999, "clásicas (antes de 2000)")
        }
        return null
    }

    private fun spanDays(n: String, unit: String): Int? {
        if (unit.isBlank()) return null
        val count = when (n) { "", "un", "una" -> 1; "dos" -> 2; "tres" -> 3; "seis" -> 6; else -> n.toIntOrNull() ?: 1 }
        return when {
            unit.startsWith("dia") -> count
            unit.startsWith("semana") -> count * 7
            unit.startsWith("mes") -> count * 30
            unit.startsWith("ano") -> count * 365
            else -> null
        }
    }
}
