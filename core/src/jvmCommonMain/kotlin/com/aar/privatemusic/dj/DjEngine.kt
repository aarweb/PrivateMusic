package com.aar.privatemusic.dj

import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.player.decideTempoMatch
import kotlin.math.abs

/**
 * El "cerebro" del AI DJ local: arma una sesión continua con una **curva de
 * energía** (arranque medio → subida → pico → bajada), dividida en **bloques**
 * encadenadas dentro de cada bloque por cercanía de
 * tonalidad (Camelot) y BPM para que el AutoMix mezcle limpio. Mezcla lo
 * conocido (favoritas / muy escuchadas) con el descubrimiento (nunca sonadas)
 * y nunca pone más de dos seguidas del mismo artista.
 *
 * Es puro y determinista dado un `seed`: sin red, sin modelo, testeable.
 */
object DjEngine {

    /** Por qué está este bloque en la sesión; alimenta la locución. */
    enum class BlockKind { WARMUP, BUILD, PEAK, COOLDOWN }

    data class Block(
        val kind: BlockKind,
        val songs: List<Song>,
        /** Energía media objetivo del bloque, 0..1. */
        val targetEnergy: Float,
    )

    data class Session(
        val tracks: List<Song>,
        /** Índice de la primera canción de cada bloque, para saber cuándo cambia. */
        val blocks: List<Block>,
    ) {
        /** El bloque al que pertenece la canción en la posición [index] de la cola. */
        fun blockAt(index: Int): Block? {
            if (index !in tracks.indices) return null
            var acc = 0
            for (b in blocks) {
                if (index < acc + b.songs.size) return b
                acc += b.songs.size
            }
            return null
        }

        /** True si [index] es la primera canción de su bloque (cambio de bloque). */
        fun isBlockStart(index: Int): Boolean {
            var acc = 0
            for (b in blocks) {
                if (index == acc) return true
                acc += b.songs.size
            }
            return false
        }

        /**
         * Sustituye todo lo posterior a [currentIndex] por [rest]. Un índice
         * fuera de la sesión no cambia nada; una lista vacía trunca el futuro.
         */
        fun replaceFuture(
            currentIndex: Int,
            rest: List<Song>,
            kind: BlockKind = BlockKind.BUILD,
            targetEnergy: Float = 0.6f,
        ): Session {
            if (currentIndex !in tracks.indices) return this

            val keptCount = currentIndex + 1
            var remaining = keptCount
            val newBlocks = mutableListOf<Block>()
            for (block in blocks) {
                if (remaining <= 0) break
                val keptSongs = block.songs.take(remaining)
                if (keptSongs.isNotEmpty()) newBlocks.add(block.copy(songs = keptSongs))
                remaining -= keptSongs.size
            }
            if (rest.isNotEmpty()) newBlocks.add(Block(kind, rest, targetEnergy))

            val newTracks = tracks.take(keptCount) + rest
            return Session(newTracks, newBlocks)
        }
    }

    /**
     * Energía percibida de una canción (0..1). Combina bailabilidad, agresividad
     * (energía alta), BPM y calma. Si faltan los moods, cae al BPM normalizado.
     */
    fun energyOf(song: Song): Float {
        val dance = song.danceability
        val aggr = song.moodAggressive
        val relax = song.moodRelaxed
        val bpmNorm = song.bpm?.let { ((it - 70f) / 90f).coerceIn(0f, 1f) }
        return if (dance != null || aggr != null || relax != null) {
            val d = dance ?: 0.5f
            val a = aggr ?: 0f
            val r = relax ?: 0f
            (0.45f * d + 0.30f * a + 0.25f * (bpmNorm ?: 0.5f) - 0.30f * r + 0.15f)
                .coerceIn(0f, 1f)
        } else {
            bpmNorm ?: 0.5f
        }
    }

    /** Distancia en la rueda de Camelot (0 = misma, 3 = lejana). Réplica pura. */
    fun camelotDistance(a: String?, b: String?): Int {
        if (a == null || b == null) return 2
        val numA = a.dropLast(1).toIntOrNull() ?: return 2
        val numB = b.dropLast(1).toIntOrNull() ?: return 2
        val sameLetter = a.last() == b.last()
        val wheel = minOf((numA - numB + 12) % 12, (numB - numA + 12) % 12)
        return when {
            wheel == 0 && sameLetter -> 0
            wheel == 0 || (wheel == 1 && sameLetter) -> 1
            wheel == 1 -> 2
            else -> 3
        }
    }

    private fun transitionCost(a: Song, b: Song, maxStretch: Float): Float {
        val key = camelotDistance(a.camelot, b.camelot).toFloat()
        val decision = decideTempoMatch(a.bpm, b.bpm, maxStretch)
        val requiredStretch = abs(decision.requiredRatio - 1f)
        val stretch = when {
            decision.comparableTargetBpm == null -> 1.5f
            maxStretch > 0f -> requiredStretch / maxStretch
            requiredStretch == 0f -> 0f
            else -> 4f
        }
        val residual = (decision.residualErrorPct ?: 0f) / 3f
        val rejected = if (decision.discardedReason != null && requiredStretch > 0f) 2f else 0f
        val bpm = stretch + residual + rejected
        return key + bpm
    }

    /** Orden "de DJ" dentro de un bloque: cada canción sigue a la más cercana. */
    fun sonicOrder(songs: List<Song>, maxStretch: Float = 0.20f): List<Song> {
        if (songs.size < 3) return songs
        val safeMaxStretch = maxStretch.takeIf { it.isFinite() && it >= 0f } ?: 0.20f
        val remaining = songs.toMutableList()
        val ordered = mutableListOf(remaining.removeAt(0))
        while (remaining.isNotEmpty()) {
            val last = ordered.last()
            val next = remaining.minByOrNull { transitionCost(last, it, safeMaxStretch) }!!
            remaining.remove(next)
            ordered.add(next)
        }
        return ordered
    }

    /**
     * Construye la sesión. [candidates] es la biblioteca reproducible (sin snooze).
     * [playCounts] y [favoriteIds] deciden conocido vs descubrimiento; [seed]
     * hace la selección reproducible (para tests y para "renovar").
     */
    fun buildSession(
        candidates: List<Song>,
        playCounts: Map<String, Int>,
        favoriteIds: Set<String>,
        seed: Long,
        size: Int = 24,
    ): Session {
        val pool = candidates.filter { it.durationSec > 0 }.distinctBy { it.id }
        val requested = size.coerceAtLeast(0).coerceAtMost(pool.size)
        if (requested == 0) return Session(emptyList(), emptyList())
        val rng = DeterministicRng(seed)

        // Curva de energía por bloque: medio → sube → pico → baja.
        val plan = listOf(
            BlockKind.WARMUP to 0.45f,
            BlockKind.BUILD to 0.65f,
            BlockKind.PEAK to 0.85f,
            BlockKind.BUILD to 0.70f,
            BlockKind.COOLDOWN to 0.35f,
        )
        val basePerBlock = requested / plan.size
        val extraBlocks = requested % plan.size

        val used = HashSet<String>()
        val known = pool.filter { favoriteIds.contains(it.id) || (playCounts[it.id] ?: 0) >= 3 }.toMutableList()
        val fresh = pool.filter { (playCounts[it.id] ?: 0) == 0 }.toMutableList()
        known.shuffleWith(rng)
        fresh.shuffleWith(rng)

        val blocks = mutableListOf<Block>()
        for ((planIndex, item) in plan.withIndex()) {
            val (kind, target) = item
            val blockSize = basePerBlock + if (planIndex < extraBlocks) 1 else 0
            if (blockSize == 0) continue
            // Cada bloque intenta cuadrar con su energía objetivo, alternando
            // canciones conocidas y descubrimientos, sin repetir.
            val bucket = mutableListOf<Song>()
            var wantFresh = kind == BlockKind.BUILD || kind == BlockKind.WARMUP
            var guard = 0
            while (bucket.size < blockSize && guard++ < pool.size * 2) {
                val from = if (wantFresh && fresh.any { it.id !in used }) fresh else known
                val tail = bucket.takeLast(2).map { it.artist.lowercase() }
                val cand = nearestEnergy(from, target, used, tail)
                    ?: nearestEnergy(pool, target, used, tail)
                    ?: nearestEnergy(pool, target, used, emptyList()) // sin alternativa: acepta
                    ?: break
                bucket.add(cand); used.add(cand.id)
                wantFresh = !wantFresh
            }
            if (bucket.isEmpty()) continue
            blocks.add(Block(kind, sonicOrder(bucket), target))
        }

        // Reordena dentro de cada bloque, teniendo en cuenta la costura anterior.
        val orderedTracks = mutableListOf<Song>()
        val alignedBlocks = blocks.map { block ->
            val songs = noTripleArtist(block.songs, orderedTracks.takeLast(2))
            orderedTracks.addAll(songs)
            block.copy(songs = songs)
        }
        return Session(orderedTracks, alignedBlocks)
    }

    private fun nearestEnergy(
        from: List<Song>, target: Float, used: Set<String>, avoidArtists: List<String> = emptyList(),
    ): Song? = from
        .filter { it.id !in used }
        .filter { avoidArtists.size < 2 || avoidArtists.any { a -> a != it.artist.lowercase() } }
        .minByOrNull { abs(energyOf(it) - target) }

    /** Evita tres seguidas del mismo artista siempre que quede una alternativa. */
    private fun noTripleArtist(songs: List<Song>, prefix: List<Song> = emptyList()): List<Song> {
        val out = prefix.takeLast(2).toMutableList()
        val rest = songs.toMutableList()
        while (rest.isNotEmpty()) {
            val lastTwo = out.takeLast(2).map { it.artist.lowercase() }
            val idx = rest.indexOfFirst { c -> lastTwo.size < 2 || lastTwo.any { it != c.artist.lowercase() } }
            out.add(rest.removeAt(if (idx >= 0) idx else 0))
        }
        return out.drop(prefix.takeLast(2).size)
    }
}

/** RNG determinista y multiplataforma (no depende de java.util.Random del host). */
class DeterministicRng(seed: Long) {
    private var state = if (seed == 0L) 0x9E3779B97F4A7C15uL else seed.toULong()
    fun nextULong(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }
    fun nextInt(bound: Int): Int = if (bound <= 0) 0 else (nextULong() % bound.toULong()).toInt()
}

fun <T> MutableList<T>.shuffleWith(rng: DeterministicRng) {
    for (i in size - 1 downTo 1) {
        val j = rng.nextInt(i + 1)
        val tmp = this[i]; this[i] = this[j]; this[j] = tmp
    }
}
