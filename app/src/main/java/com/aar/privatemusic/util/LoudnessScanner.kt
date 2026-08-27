package com.aar.privatemusic.util

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import com.aar.privatemusic.dsp.PcmRmsAccumulator
import java.nio.ByteOrder

/**
 * Decodes an audio file to PCM and measures its RMS loudness in dBFS.
 * Runs once per song after download; the result drives volume normalization
 * (attenuate loud tracks towards the target, never boost).
 */
object LoudnessScanner {

    fun measureRmsDb(path: String): Float? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                ?: return null
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, 0)
            codec.start()

            val accumulator = PcmRmsAccumulator()
            var channelCount = runCatching {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }.getOrDefault(0)
            var pcmSamples = ShortArray(0)
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val out = codec.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0 && channelCount > 0) {
                        val visibleLimit = out.limit().coerceIn(0, out.capacity())
                        val start = info.offset.coerceIn(0, visibleLimit)
                        val requestedEnd = info.offset.toLong() + info.size.toLong()
                        val end = requestedEnd.coerceIn(start.toLong(), visibleLimit.toLong()).toInt()
                        val sampleLength = (end - start) / Short.SIZE_BYTES
                        if (sampleLength > 0) {
                            if (pcmSamples.size < sampleLength) pcmSamples = ShortArray(sampleLength)
                            val pcm = out.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
                                position(start)
                                limit(end)
                            }.asShortBuffer()
                            pcm.get(pcmSamples, 0, sampleLength)
                            accumulator.add(
                                samples = pcmSamples,
                                length = sampleLength,
                                channelCount = channelCount,
                                frameStride = 4,
                            )
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    channelCount = runCatching {
                        codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }.getOrDefault(channelCount)
                }
            }

            return accumulator.rmsDb()
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
