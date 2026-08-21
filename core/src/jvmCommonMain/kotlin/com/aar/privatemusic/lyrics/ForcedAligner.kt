package com.aar.privatemusic.lyrics

import kotlin.math.abs
import kotlin.math.max

/**
 * Convierte una letra **plana** (sin tiempos) en una sincronizada, palabra a
 * palabra, para las canciones en las que LRCLIB sólo tiene el texto.
 *
 * Hay dos caminos, y los dos terminan en el mismo sitio (un [Lyrics] con
 * [LyricWord]s que se guarda como Enhanced LRC):
 *
 * 1. **[alignToVoice]** — por dónde canta la voz. No hace falta ningún modelo
 *    de reconocimiento: la voz ya se sabe separar (el motor del karaoke), y de
 *    ahí sale una curva de energía vocal; los tramos con voz se reparten entre
 *    los versos, y dentro de cada verso las palabras se reparten por sílabas.
 *    Es lo que se usa hoy, porque funciona en cualquier móvil y sin descargas.
 * 2. **[alignToTranscript]** — contra una transcripción con tiempos. Es más
 *    fino, pero necesita un motor de voz a texto. El algoritmo está aquí y
 *    probado, listo para enchufarle uno; ver la nota de abajo.
 *
 * **Por qué no hay Whisper (agosto 2026):** el AAR de whisper.cpp en Maven
 * Central (`dev.ffmpegkit-maintained:whisper-android`) sólo trae `arm64-v8a` en
 * su versión libre, así que no arranca en el emulador x86_64 donde se prueba
 * todo esto, ni en un móvil x86; encima devuelve tiempos **por frase**, no por
 * palabra, y su modelo son entre 42 y 75 MB más de descarga sobre los del
 * karaoke. Con la voz ya separada, el camino 1 da un resultado utilizable sin
 * nada de eso. Cuando haya un motor con timestamps por palabra que corra en
 * todas partes, se enchufa en [alignToTranscript] y ya está.
 */
object ForcedAligner {

    /** Un tramo en el que se oye cantar. */
    data class VoiceSegment(val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
    }

    /** Una palabra reconocida por un motor de voz a texto, con su tiempo. */
    data class TranscriptWord(val startMs: Long, val endMs: Long, val text: String)

    /** Debajo de esto no se considera que haya voz (fracción del pico). */
    private const val VOICE_THRESHOLD = 0.12f

    /** Silencios más cortos que esto no parten un tramo: son respiraciones. */
    private const val MIN_GAP_MS = 400L

    /** Tramos más cortos que esto son ruido del separador, no un verso. */
    private const val MIN_SEGMENT_MS = 350L

    // ---------------------------------------------------------------- energía

    /**
     * Saca los tramos con voz de una curva de energía (un valor por ventana de
     * [frameMs] milisegundos). El umbral es relativo al pico, así que da igual
     * cómo esté de alta la grabación.
     */
    fun segmentsFromEnergy(energy: FloatArray, frameMs: Double): List<VoiceSegment> {
        if (energy.isEmpty()) return emptyList()
        val peak = energy.max()
        if (peak <= 0f) return emptyList()
        val threshold = peak * VOICE_THRESHOLD

        val raw = mutableListOf<VoiceSegment>()
        var start = -1
        energy.forEachIndexed { i, v ->
            if (v >= threshold) {
                if (start < 0) start = i
            } else if (start >= 0) {
                raw += VoiceSegment((start * frameMs).toLong(), (i * frameMs).toLong())
                start = -1
            }
        }
        if (start >= 0) raw += VoiceSegment((start * frameMs).toLong(), (energy.size * frameMs).toLong())

        // Une lo que sólo separa una respiración y tira los tramos de nada.
        val merged = mutableListOf<VoiceSegment>()
        raw.forEach { seg ->
            val last = merged.lastOrNull()
            if (last != null && seg.startMs - last.endMs <= MIN_GAP_MS) {
                merged[merged.lastIndex] = last.copy(endMs = seg.endMs)
            } else {
                merged += seg
            }
        }
        return merged.filter { it.durationMs >= MIN_SEGMENT_MS }
    }

    // ------------------------------------------------------------ por la voz

    /**
     * Reparte [lines] entre los [segments] en los que se canta.
     *
     * Si hay tantos tramos como versos, cada verso cae en el suyo. Si no
     * cuadran —lo normal: el separador parte un verso en dos, o junta dos—, se
     * reparte todo el tiempo cantado en proporción a lo largo que es cada
     * verso, contado en sílabas, que es lo que de verdad se tarda en cantarlo.
     */
    fun alignToVoice(lines: List<String>, segments: List<VoiceSegment>, totalMs: Long): Lyrics? {
        val clean = lines.map { it.trim() }.filter { it.isNotBlank() }
        if (clean.isEmpty()) return null
        val voiced = segments.filter { it.durationMs > 0 }
        if (voiced.isEmpty()) return null

        val spans: List<VoiceSegment> = if (voiced.size == clean.size) {
            voiced
        } else {
            // Un solo "carril" continuo con todo lo cantado, troceado por peso.
            val weights = clean.map { syllables(it).toDouble() }
            val totalWeight = weights.sum().takeIf { it > 0 } ?: return null
            val sungMs = voiced.sumOf { it.durationMs }
            var acc = 0.0
            clean.indices.map { i ->
                val from = acc
                acc += weights[i] / totalWeight * sungMs
                VoiceSegment(sungTimeToReal(from, voiced), sungTimeToReal(acc, voiced))
            }
        }

        val result = clean.mapIndexed { i, text ->
            val span = spans[i]
            val end = if (i == clean.lastIndex) max(span.endMs, span.startMs) else spans[i + 1].startMs
            LyricLine(
                timeMs = span.startMs,
                text = text,
                endMs = end.coerceAtMost(if (totalMs > 0) totalMs else end),
                words = spreadWords(text, span.startMs, max(span.endMs, span.startMs + 1)),
            )
        }
        return Lyrics(true, result)
    }

    /**
     * Pasa de "milisegundo N de lo cantado" al milisegundo real de la canción,
     * saltándose los huecos instrumentales.
     */
    private fun sungTimeToReal(sungMs: Double, voiced: List<VoiceSegment>): Long {
        var remaining = sungMs
        voiced.forEach { seg ->
            if (remaining <= seg.durationMs) return seg.startMs + remaining.toLong()
            remaining -= seg.durationMs
        }
        return voiced.last().endMs
    }

    /** Reparte las palabras de un verso dentro de su tramo, por sílabas. */
    private fun spreadWords(text: String, startMs: Long, endMs: Long): List<LyricWord> {
        val words = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val weights = words.map { syllables(it).toDouble() }
        val total = weights.sum().takeIf { it > 0 } ?: return emptyList()
        val span = (endMs - startMs).toDouble()
        var acc = 0.0
        return words.mapIndexed { i, w ->
            val from = startMs + (acc / total * span).toLong()
            acc += weights[i]
            val to = startMs + (acc / total * span).toLong()
            LyricWord(from, max(to, from), w)
        }
    }

    // --------------------------------------------------- contra transcripción

    /**
     * Alinea el texto que ya conocemos contra lo que un motor de voz a texto ha
     * oído, con sus tiempos. El motor se equivoca, se salta palabras y se
     * inventa otras, así que no vale con emparejar por orden: se resuelve con
     * el alineamiento clásico de secuencias (Needleman-Wunsch), que permite
     * huecos a los dos lados y elige el encaje que menos cambios necesita.
     *
     * Las palabras que el motor no oyó se reparten entre sus vecinas, para que
     * ninguna se quede sin tiempo.
     */
    fun alignToTranscript(lines: List<String>, transcript: List<TranscriptWord>): Lyrics? {
        val clean = lines.map { it.trim() }.filter { it.isNotBlank() }
        if (clean.isEmpty() || transcript.isEmpty()) return null

        // Texto conocido aplanado, recordando de qué verso viene cada palabra.
        val known = mutableListOf<Pair<Int, String>>()
        clean.forEachIndexed { li, line ->
            line.split(Regex("""\s+""")).filter { it.isNotBlank() }.forEach { known += li to it }
        }
        if (known.isEmpty()) return null

        val a = known.map { normalize(it.second) }
        val b = transcript.map { normalize(it.text) }
        val pairs = needlemanWunsch(a, b)

        // Tiempo de cada palabra conocida: el de la palabra que le tocó.
        val starts = arrayOfNulls<Long>(known.size)
        val ends = arrayOfNulls<Long>(known.size)
        pairs.forEach { (i, j) ->
            if (i >= 0 && j >= 0) {
                starts[i] = transcript[j].startMs
                ends[i] = transcript[j].endMs
            }
        }
        if (starts.all { it == null }) return null
        interpolateGaps(starts, ends)

        // De vuelta a versos.
        val byLine = known.indices.groupBy { known[it].first }
        val result = byLine.entries.sortedBy { it.key }.mapNotNull { (lineIdx, idxs) ->
            val words = idxs.mapNotNull { i ->
                val s = starts[i] ?: return@mapNotNull null
                LyricWord(s, max(ends[i] ?: s, s), known[i].second)
            }
            if (words.isEmpty()) return@mapNotNull null
            LyricLine(
                timeMs = words.first().startMs,
                text = clean[lineIdx],
                endMs = words.last().endMs,
                words = words,
            )
        }.sortedBy { it.timeMs }
        return if (result.isEmpty()) null else Lyrics(true, result)
    }

    /**
     * Alineamiento de dos secuencias con huecos. Devuelve las parejas
     * `(indiceConocido, indiceTranscripcion)`; -1 en un lado significa hueco.
     */
    private fun needlemanWunsch(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val gap = -1
        val match = 2
        val mismatch = -1
        val score = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) score[i][0] = i * gap
        for (j in 0..b.size) score[0][j] = j * gap
        for (i in 1..a.size) {
            for (j in 1..b.size) {
                val diag = score[i - 1][j - 1] + if (similar(a[i - 1], b[j - 1])) match else mismatch
                score[i][j] = maxOf(diag, score[i - 1][j] + gap, score[i][j - 1] + gap)
            }
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = a.size
        var j = b.size
        while (i > 0 || j > 0) {
            val diag = if (i > 0 && j > 0) {
                score[i - 1][j - 1] + if (similar(a[i - 1], b[j - 1])) match else mismatch
            } else Int.MIN_VALUE
            when {
                i > 0 && j > 0 && score[i][j] == diag -> {
                    // Sólo cuenta como emparejada si de verdad se parecen: si no,
                    // la palabra conocida se queda sin tiempo y se interpola.
                    pairs += if (similar(a[i - 1], b[j - 1])) (i - 1) to (j - 1) else (i - 1) to -1
                    i--; j--
                }
                i > 0 && score[i][j] == score[i - 1][j] + gap -> { pairs += (i - 1) to -1; i-- }
                else -> { pairs += -1 to (j - 1); j-- }
            }
        }
        return pairs.reversed()
    }

    /** Reparte el tiempo de las palabras que el motor no oyó. */
    private fun interpolateGaps(starts: Array<Long?>, ends: Array<Long?>) {
        val n = starts.size
        var i = 0
        while (i < n) {
            if (starts[i] != null) { i++; continue }
            var j = i
            while (j < n && starts[j] == null) j++
            // Extremos del hueco: lo que acabó antes y lo que empieza después.
            val before = ends.getOrNull(i - 1)?.let { it } ?: starts.getOrNull(i - 1)
            val after = starts.getOrNull(j)
            val from = before ?: (after ?: 0L)
            val to = after ?: (before ?: 0L)
            val count = j - i
            val step = if (to > from) (to - from) / (count + 1) else 0L
            for (k in 0 until count) {
                starts[i + k] = from + step * (k + 1)
                ends[i + k] = from + step * (k + 2)
            }
            i = j
        }
    }

    // ----------------------------------------------------------------- texto

    /** Dos palabras cuentan como la misma si coinciden o casi (una errata). */
    internal fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (abs(a.length - b.length) > 1) return false
        return a.length >= 4 && b.length >= 4 && editDistanceAtMostOne(a, b)
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    /** Deja la palabra comparable: sin mayúsculas, tildes ni puntuación. */
    internal fun normalize(word: String): String = buildString {
        word.lowercase().forEach { c ->
            val plain = when (c) {
                'á', 'à', 'ä', 'â', 'ã' -> 'a'
                'é', 'è', 'ë', 'ê' -> 'e'
                'í', 'ì', 'ï', 'î' -> 'i'
                'ó', 'ò', 'ö', 'ô', 'õ' -> 'o'
                'ú', 'ù', 'ü', 'û' -> 'u'
                'ñ' -> 'n'
                'ç' -> 'c'
                else -> c
            }
            if (plain.isLetterOrDigit()) append(plain)
        }
    }

    /**
     * Sílabas a ojo: grupos de vocales. No es exacto —ni falta— pero reparte
     * mucho mejor que contar letras: "amanecer" tarda más que "sol".
     */
    internal fun syllables(word: String): Int {
        if (word.isBlank()) return 0
        val n = normalize(word)
        // Algo que se canta pero no tiene letras ("¡¡¡", "1") pesa como una
        // sílaba: con peso cero se quedaría sin tiempo, o dejaría el verso
        // entero a cero si fuese lo único que hay.
        if (n.isEmpty()) return 1
        var count = 0
        var inVowel = false
        n.forEach { c ->
            val vowel = c in "aeiouy"
            if (vowel && !inVowel) count++
            inVowel = vowel
        }
        return max(1, count)
    }
}
