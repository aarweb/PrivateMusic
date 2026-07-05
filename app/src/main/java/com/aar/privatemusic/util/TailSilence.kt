package com.aar.privatemusic.util

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Measures how much of a track's ending is effectively silent (production
 * fade-outs, trailing silence). The crossfade anchors to the MUSICAL end:
 * overlapping a dying fade-out with the next song's ramp-in sounds like a
 * cut no matter how gapless the engine is.
 */
object TailSilence {

    private const val ANALYZE_LAST_SEC = 20
    private const val WINDOW_MS = 100

    /** Milliseconds of quiet tail at the end of the file (0 if none/unknown). */
    fun measureTailSilenceMs(path: String, durationSec: Int): Long? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(path)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            val startUs = ((durationSec - ANALYZE_LAST_SEC).coerceAtLeast(0)) * 1_000_000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                ?: return null
            val decoder = MediaCodec.createByCodecName(codecName).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val rmsWindows = ArrayList<Float>(256)
            var acc = 0.0
            var accCount = 0
            var windowSamples = sampleRate * WINDOW_MS / 1000 * channels

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        val buf = decoder.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (sb.hasRemaining()) {
                            val v = sb.get() / 32768f
                            acc += (v * v).toDouble()
                            accCount++
                            if (accCount >= windowSamples) {
                                rmsWindows.add(sqrt(acc / accCount).toFloat())
                                acc = 0.0; accCount = 0
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        windowSamples = sampleRate * WINDOW_MS / 1000 * channels
                    }
                }
            }
            if (rmsWindows.size < 10) return 0L

            // Reference: the loud part of this ending. Quiet = under ~-18dB of
            // it — aggressive on purpose: fading into a half-dead production
            // fade still reads as a dip, so we anchor where the music is alive.
            val sorted = rmsWindows.sorted()
            val ref = sorted[(sorted.size * 9) / 10]
            val threshold = (ref * 0.12f).coerceAtLeast(1e-4f)
            var quiet = 0
            for (i in rmsWindows.indices.reversed()) {
                if (rmsWindows[i] < threshold) quiet++ else break
            }
            (quiet.toLong() * WINDOW_MS).coerceAtMost((ANALYZE_LAST_SEC - 1) * 1000L)
        } catch (e: Exception) {
            android.util.Log.w("TailSilence", "measure failed", e)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
