package com.aar.privatemusic.player

/** Búsqueda pequeña y determinista para Android Auto y los comandos de voz. */
object CarMediaSearch {
    const val DEFAULT_LIMIT = 50

    data class Candidate<T>(
        val value: T,
        val title: String,
        val artist: String,
        val album: String?,
    )

    /**
     * Conserva el orden original para desempatar. Una consulta vacía devuelve
     * el principio de la entrada, lo que permite reutilizarlo con recientes.
     */
    fun <T> rank(
        candidates: List<Candidate<T>>,
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<T> {
        if (limit <= 0) return emptyList()
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return candidates.take(limit).map { it.value }
        val queryTokens = normalizedQuery.split(' ')

        return candidates.asSequence()
            .mapIndexedNotNull { index, candidate ->
                score(candidate, normalizedQuery, queryTokens)?.let { score ->
                    Ranked(candidate.value, score, index)
                }
            }
            .sortedWith(compareBy<Ranked<T>> { it.score }.thenBy { it.inputIndex })
            .take(limit)
            .map { it.value }
            .toList()
    }

    private fun <T> score(
        candidate: Candidate<T>,
        query: String,
        queryTokens: List<String>,
    ): Int? {
        val title = normalize(candidate.title)
        val artist = normalize(candidate.artist)
        val album = normalize(candidate.album.orEmpty())
        val titleTokens = title.split(' ')
        return when {
            title == query -> 0
            title.startsWith(query) -> 1
            queryTokens.all { queryToken ->
                titleTokens.any { titleToken -> titleToken.startsWith(queryToken) }
            } -> 2
            artist.contains(query) -> 3
            album.contains(query) -> 4
            else -> null
        }
    }

    /** Lowercase, espacios estables y equivalencia de tildes españolas. */
    fun normalize(value: String): String = buildString(value.length) {
        var pendingSpace = false
        value.lowercase().forEach { character ->
            // También admite Unicode descompuesto ("o" + tilde combinada).
            if (character in '\u0300'..'\u036f') return@forEach
            val normalized = when (character) {
                'á', 'à', 'ä', 'â', 'ã' -> 'a'
                'é', 'è', 'ë', 'ê' -> 'e'
                'í', 'ì', 'ï', 'î' -> 'i'
                'ó', 'ò', 'ö', 'ô', 'õ' -> 'o'
                'ú', 'ù', 'ü', 'û' -> 'u'
                'ñ' -> 'n'
                'ç' -> 'c'
                else -> character
            }
            if (normalized.isLetterOrDigit()) {
                if (pendingSpace && isNotEmpty()) append(' ')
                append(normalized)
                pendingSpace = false
            } else {
                pendingSpace = isNotEmpty()
            }
        }
    }

    private data class Ranked<T>(val value: T, val score: Int, val inputIndex: Int)
}
