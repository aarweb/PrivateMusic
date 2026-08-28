package com.aar.privatemusic.lyrics

import java.text.Normalizer

data class LyricSearchDocument(
    val songId: String,
    val originalLines: List<String>,
    val translatedLines: List<String> = emptyList(),
)

data class LyricSearchHit(
    val songId: String,
    val snippet: String,
    val translated: Boolean,
    val score: Int,
)

/** Motor puro de búsqueda: no conoce Android, disco, red ni base de datos. */
object LyricSearch {
    private val combiningMarks = Regex("\\p{M}+")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val html = Regex("<[^>]*>")
    private val lrcTag = Regex("\\[(?:\\d{1,3}:)?\\d{1,2}(?:[.:]\\d{1,3})?]")
    private val spaces = Regex("\\s+")

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(combiningMarks, "")
        .lowercase()
        .replace(punctuation, " ")
        .trim()
        .replace(spaces, " ")

    fun search(
        documents: Collection<LyricSearchDocument>,
        query: String,
        limit: Int = 100,
    ): List<LyricSearchHit> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.length < 3 || limit <= 0) return emptyList()
        val queryTokens = normalizedQuery.split(' ').filter { it.isNotBlank() }.distinct()

        return documents.mapNotNull { document ->
            val original = bestInLines(document.songId, document.originalLines, normalizedQuery, queryTokens, false)
            val translated = bestInLines(document.songId, document.translatedLines, normalizedQuery, queryTokens, true)
            listOfNotNull(original, translated).maxWithOrNull(hitComparator)
        }.sortedWith(hitComparator.reversed()).take(limit)
    }

    private fun bestInLines(
        songId: String,
        rawLines: List<String>,
        query: String,
        queryTokens: List<String>,
        translated: Boolean,
    ): LyricSearchHit? {
        val lines = rawLines.map(::cleanSnippetLine).filter { it.isNotBlank() }
        var best: LyricSearchHit? = null
        lines.indices.forEach { index ->
            val candidates = buildList {
                add(lines[index])
                if (index < lines.lastIndex) add("${lines[index]} · ${lines[index + 1]}")
            }
            candidates.forEach { snippet ->
                val normalized = normalize(snippet)
                val exact = normalized.contains(query)
                val tokenMatch = !exact && queryTokens.isNotEmpty() &&
                    normalized.split(' ').toHashSet().let { words -> queryTokens.all(words::contains) }
                if (!exact && !tokenMatch) return@forEach
                val score = (if (exact) 10_000 else 1_000) + query.length
                val hit = LyricSearchHit(songId, snippet, translated, score)
                if (best == null || hitComparator.compare(hit, best!!) > 0) best = hit
            }
        }
        return best
    }

    private fun cleanSnippetLine(value: String): String = value
        .replace(html, " ")
        .replace(lrcTag, " ")
        .replace(spaces, " ")
        .trim()

    /** Mayor score, original antes que traducción y finalmente id estable. */
    private val hitComparator = compareBy<LyricSearchHit> { it.score }
        .thenBy { !it.translated }
        .thenByDescending { it.songId }
}
