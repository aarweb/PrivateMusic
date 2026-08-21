package com.aar.privatemusic.lyrics

/**
 * Formatos de letra con tiempos **por palabra**, para que el karaoke resalte la
 * sílaba que se está cantando y no la línea entera.
 *
 * Se leen dos:
 *
 * - **Enhanced LRC** (extensión A2, la que usan Kugou/QQ/NetEase/AIMP): el LRC
 *   de siempre con marcas `<mm:ss.xx>` intercaladas dentro de la línea.
 *   `[00:12.00] <00:12.00>Never <00:12.40>gonna <00:12.90>give`
 *   Un LRC normal lo lee igual: las marcas van dentro del texto, así que un
 *   parser viejo sólo ve la línea. Por eso es el formato que generamos.
 * - **TTML** (el estilo de Apple Music): `<p begin=".." end="..">` con `<span>`
 *   por palabra. Sólo se lee; nadie lo genera aquí.
 *
 * LRCLIB **no sirve tiempos por palabra** (agosto 2026: 0 de 100 resultados de
 * cinco canciones populares traían marcas `<...>`); sí sirve, en su campo
 * `lyricsfile`, el **fin de cada línea**, que es lo que permite el barrido
 * suave cuando no hay palabras. Ver [LyricsFetcher].
 */

/** Una palabra (o sílaba) con su tramo dentro de la línea. */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

internal object LyricFormats {

    /** `<mm:ss.xx>` o `<mm:ss:xx>`, las marcas de palabra del Enhanced LRC. */
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")

    /** `[mm:ss.xx]`, la marca de línea de cualquier LRC. */
    private val LINE_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun toMs(min: String, sec: String, frac: String): Long {
        val f = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return min.toLong() * 60_000 + sec.toLong() * 1_000 + f
    }

    /**
     * Saca las palabras con tiempo de una línea de Enhanced LRC, ya sin su marca
     * `[mm:ss.xx]` inicial. Devuelve lista vacía si la línea no lleva marcas de
     * palabra: el llamante se queda entonces con el resaltado por línea.
     *
     * El final de cada palabra es el principio de la siguiente; la última se
     * cierra con [lineEndMs] si se conoce, y si no con un margen razonable.
     */
    fun parseWords(content: String, lineEndMs: Long?): List<LyricWord> {
        val tags = WORD_TAG.findAll(content).toList()
        if (tags.isEmpty()) return emptyList()
        val words = mutableListOf<LyricWord>()
        tags.forEachIndexed { i, m ->
            val start = toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val from = m.range.last + 1
            val to = if (i + 1 < tags.size) tags[i + 1].range.first else content.length
            val text = content.substring(from, to)
            // El espacio que separa dos palabras suele ir pegado al texto de la
            // anterior; se recorta al guardar y se repone al pintar.
            if (text.isNotBlank()) words += LyricWord(start, start, text.trim())
        }
        if (words.isEmpty()) return emptyList()
        // Cierra cada palabra con el arranque de la siguiente. Un fichero con
        // tiempos desordenados dejaría tramos negativos: se ordena antes.
        val sorted = words.sortedBy { it.startMs }
        return sorted.mapIndexed { i, w ->
            val end = if (i + 1 < sorted.size) sorted[i + 1].startMs else {
                lineEndMs?.takeIf { it > w.startMs } ?: (w.startMs + DEFAULT_WORD_MS)
            }
            w.copy(endMs = maxOf(end, w.startMs))
        }
    }

    /** Sin nada que diga cuándo acaba la última palabra, medio segundo. */
    private const val DEFAULT_WORD_MS = 500L

    /** El texto de la línea, ya limpio de marcas de palabra. */
    fun stripWordTags(content: String): String =
        WORD_TAG.replace(content, "").replace(Regex("""\s+"""), " ").trim()

    fun lineTags(raw: String): List<MatchResult> = LINE_TAG.findAll(raw).toList()

    /**
     * TTML sencillo (estilo Apple Music): `<p begin end>` con `<span>` opcional
     * por palabra. No es un parser completo de TTML —no hay regiones, estilos ni
     * anidamiento profundo—, sólo lo justo para leer una letra.
     */
    fun parseTtml(xml: String): Lyrics? {
        val pTag = Regex("""<p\b([^>]*)>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val spanTag = Regex("""<span\b([^>]*)>(.*?)</span>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val lines = mutableListOf<LyricLine>()
        pTag.findAll(xml).forEach { p ->
            val begin = attr(p.groupValues[1], "begin")?.let(::ttmlTime) ?: return@forEach
            val end = attr(p.groupValues[1], "end")?.let(::ttmlTime)
            val body = p.groupValues[2]
            val spans = spanTag.findAll(body).toList()
            val words = spans.mapNotNull { s ->
                val ws = attr(s.groupValues[1], "begin")?.let(::ttmlTime) ?: return@mapNotNull null
                val we = attr(s.groupValues[1], "end")?.let(::ttmlTime) ?: ws
                val text = unescape(stripTags(s.groupValues[2])).trim()
                if (text.isBlank()) null else LyricWord(ws, maxOf(we, ws), text)
            }.sortedBy { it.startMs }
            val text = if (words.isNotEmpty()) {
                words.joinToString(" ") { it.text }
            } else {
                unescape(stripTags(body)).replace(Regex("""\s+"""), " ").trim()
            }
            if (text.isNotBlank()) lines += LyricLine(begin, text, end, words)
        }
        return if (lines.isEmpty()) null else Lyrics(true, lines.sortedBy { it.timeMs })
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.get(1)

    private fun stripTags(s: String) = s.replace(Regex("""<[^>]*>"""), "")

    private fun unescape(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    /**
     * Tiempos de TTML: `hh:mm:ss.mmm`, `mm:ss.mmm`, `12.5s`, `500ms` o segundos
     * pelados. Devuelve null si no se entiende.
     */
    fun ttmlTime(raw: String): Long? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        Regex("""^(\d+(?:\.\d+)?)(h|m|s|ms|f|t)$""", RegexOption.IGNORE_CASE).find(v)?.let { m ->
            val n = m.groupValues[1].toDouble()
            return when (m.groupValues[2].lowercase()) {
                "h" -> (n * 3_600_000).toLong()
                "m" -> (n * 60_000).toLong()
                "s" -> (n * 1_000).toLong()
                "ms" -> n.toLong()
                else -> return null // frames/ticks: harían falta los metadatos del documento
            }
        }
        val parts = v.split(":")
        if (parts.size !in 2..3) return v.toDoubleOrNull()?.let { (it * 1000).toLong() }
        return runCatching {
            val secs = parts.last().toDouble()
            val mins = parts[parts.size - 2].toLong()
            val hours = if (parts.size == 3) parts[0].toLong() else 0L
            hours * 3_600_000 + mins * 60_000 + (secs * 1000).toLong()
        }.getOrNull()
    }

    /**
     * Escribe Enhanced LRC. Es lo que genera la alineación automática, y lo lee
     * cualquier reproductor con LRC normal (las marcas `<>` se ven como texto,
     * pero la línea sigue en su sitio... por eso también se guarda el texto
     * limpio detrás de la última marca no: va intercalado, tal cual manda A2).
     */
    fun toEnhancedLrc(lyrics: Lyrics): String = buildString {
        lyrics.lines.forEach { line ->
            append(stamp(line.timeMs, '[', ']'))
            if (line.words.isEmpty()) {
                append(' ').append(line.text)
            } else {
                line.words.forEach { w ->
                    append(' ').append(stamp(w.startMs, '<', '>')).append(w.text)
                }
            }
            append('\n')
        }
    }

    private fun stamp(ms: Long, open: Char, close: Char): String {
        val totalCs = ms / 10
        val cs = totalCs % 100
        val totalSec = totalCs / 100
        val sec = totalSec % 60
        val min = totalSec / 60
        return "$open%02d:%02d.%02d$close".format(min, sec, cs)
    }
}
