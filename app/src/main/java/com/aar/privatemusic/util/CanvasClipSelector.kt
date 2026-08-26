package com.aar.privatemusic.util

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/** Elige una ventana continua con imagen legible y movimiento moderado. */
object CanvasClipSelector {
    private const val CLIP_SECONDS = 8
    private const val MIN_DURATION_MS = 6_000L

    // Movimiento mínimo conservador: evita aceptar una portada fija por ruido de
    // compresión, sin exigir cortes o desplazamientos rápidos a un videoclip lento.
    private const val APPRECIABLE_PIXEL_DELTA = 8.0
    private const val MIN_MEAN_PAIR_DELTA = 1.5
    private const val MIN_MEAN_CHANGED_RATIO = 0.015

    internal enum class TitleKind {
        REJECTED,
        PREFERRED_OFFICIAL,
        NORMAL,
    }

    /** Clasificación pura basada en palabras completas y frases normalizadas. */
    internal fun classifyTitle(title: String): TitleKind {
        val tokens = Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)

        val rejectedWord = tokens.any {
            it in setOf(
                "lyric",
                "lyrics",
                "letra",
                "letras",
                "karaoke",
                "visualizer",
                "visualiser",
                "topic",
            )
        }
        val rejectedPhrase = listOf(
            listOf("official", "audio"),
            listOf("audio", "only"),
            listOf("static", "image"),
            listOf("still", "image"),
            listOf("cover", "audio"),
        ).any { tokens.containsPhrase(it) }
        if (rejectedWord || rejectedPhrase) return TitleKind.REJECTED

        val preferred = tokens.contains("videoclip") || listOf(
            listOf("official", "music", "video"),
            listOf("official", "video"),
            listOf("video", "oficial"),
            listOf("video", "clip"),
        ).any { tokens.containsPhrase(it) }
        return if (preferred) TitleKind.PREFERRED_OFFICIAL else TitleKind.NORMAL
    }

    /** Primer oficial admisible; si no existe, primer resultado normal. */
    internal fun selectTitleIndex(titles: List<String>): Int? {
        var firstNormal: Int? = null
        titles.forEachIndexed { index, title ->
            when (classifyTitle(title)) {
                TitleKind.REJECTED -> Unit
                TitleKind.PREFERRED_OFFICIAL -> return index
                TitleKind.NORMAL -> if (firstNormal == null) firstNormal = index
            }
        }
        return firstNormal
    }

    /** Tres posiciones reproducibles, alejadas de introducción y créditos. */
    fun candidateStarts(durationSec: Double, seed: String): List<Int> {
        if (durationSec <= CLIP_SECONDS + 2) return listOf(0)
        val first = (durationSec * 0.25).toInt()
        val last = (durationSec * 0.75 - CLIP_SECONDS).toInt().coerceAtLeast(first)
        if (last == first) return listOf(first)
        val random = Random(seed.hashCode())
        return buildSet {
            while (size < 3 && size < last - first + 1) add(random.nextInt(first, last + 1))
        }.sorted()
    }

    fun choose(files: List<File>): File? = files
        .mapNotNull { file -> visualScore(file)?.let { score -> file to score } }
        .maxByOrNull { (_, score) -> score }
        ?.first

    private fun visualScore(file: File): Double? = runCatching {
        if (!file.isFile || file.length() <= 0L) return@runCatching null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: return@runCatching null
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: return@runCatching null
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: return@runCatching null
            if (durationMs < MIN_DURATION_MS || width <= 0 || height <= 0) {
                return@runCatching null
            }

            val frames = SAMPLE_US.mapNotNull { time ->
                retriever.getScaledFrameAtTime(
                    time,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    48,
                    27,
                )?.let(::luma)
            }.filter { it.isNotEmpty() }
            if (frames.size < 4) return@runCatching null

            val brightness = frames.flatMap { it.asIterable() }.average()
            val pairMetrics = frames.zipWithNext { a, b ->
                val pixelCount = min(a.size, b.size)
                var deltaSum = 0.0
                var appreciablePixels = 0
                repeat(pixelCount) { index ->
                    val delta = abs(a[index] - b[index])
                    deltaSum += delta
                    if (delta >= APPRECIABLE_PIXEL_DELTA) appreciablePixels++
                }
                Pair(
                    deltaSum / pixelCount,
                    appreciablePixels.toDouble() / pixelCount,
                )
            }
            val motion = pairMetrics.map { it.first }.average()
            val changedRatio = pairMetrics.map { it.second }.average()
            if (motion < MIN_MEAN_PAIR_DELTA || changedRatio < MIN_MEAN_CHANGED_RATIO) {
                return@runCatching null
            }
            val hardestCut = pairMetrics.maxOfOrNull { it.first } ?: return@runCatching null

            // Un Canvas agradable tiene detalle visible y movimiento, pero no
            // fundidos negros ni saltos violentos entre planos.
            val exposurePenalty = when {
                brightness < 30.0 -> (30.0 - brightness) * 3
                brightness > 225.0 -> (brightness - 225.0) * 2
                else -> 0.0
            }
            val motionPenalty = abs(motion - 22.0)
            val cutPenalty = (hardestCut - 65.0).coerceAtLeast(0.0) * 2
            100.0 - exposurePenalty - motionPenalty - cutPenalty
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun luma(bitmap: Bitmap): DoubleArray {
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return DoubleArray(pixels.size) { i ->
                val color = pixels[i]
                val r = color shr 16 and 0xff
                val g = color shr 8 and 0xff
                val b = color and 0xff
                0.2126 * r + 0.7152 * g + 0.0722 * b
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun List<String>.containsPhrase(phrase: List<String>): Boolean =
        phrase.isNotEmpty() && windowed(phrase.size).any { it == phrase }

    private val SAMPLE_US = listOf(500_000L, 2_250_000L, 4_000_000L, 5_750_000L, 7_500_000L)
}
