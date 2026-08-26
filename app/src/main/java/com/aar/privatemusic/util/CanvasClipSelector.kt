package com.aar.privatemusic.util

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/** Elige una ventana continua con imagen legible y movimiento moderado. */
object CanvasClipSelector {
    private const val CLIP_SECONDS = 8

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

    fun choose(files: List<File>): File? = files.maxByOrNull(::visualScore)

    private fun visualScore(file: File): Double = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frames = SAMPLE_US.mapNotNull { time ->
                retriever.getScaledFrameAtTime(
                    time,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    48,
                    27,
                )?.let(::luma)
            }
            if (frames.size < 3) return@runCatching Double.NEGATIVE_INFINITY

            val brightness = frames.flatMap { it.asIterable() }.average()
            val differences = frames.zipWithNext { a, b ->
                a.indices.sumOf { abs(a[it] - b[it]) } / a.size
            }
            val motion = differences.average()
            val hardestCut = differences.maxOrNull() ?: 0.0

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
    }.getOrDefault(Double.NEGATIVE_INFINITY)

    private fun luma(bitmap: Bitmap): DoubleArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return DoubleArray(pixels.size) { i ->
            val color = pixels[i]
            val r = color shr 16 and 0xff
            val g = color shr 8 and 0xff
            val b = color and 0xff
            0.2126 * r + 0.7152 * g + 0.0722 * b
        }
    }

    private val SAMPLE_US = listOf(500_000L, 2_250_000L, 4_000_000L, 5_750_000L, 7_500_000L)
}
