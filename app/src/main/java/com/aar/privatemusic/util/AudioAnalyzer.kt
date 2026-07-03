package com.aar.privatemusic.util

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class AnalysisResult(
    val bpm: Float?,
    val camelot: String?,
    val features: FloatArray, // sonic fingerprint for similarity
) {
    fun featuresJson(): String {
        val arr = JSONArray()
        features.forEach { arr.put(it.toDouble()) }
        return arr.toString()
    }

    companion object {
        fun parseFeatures(json: String): FloatArray? = runCatching {
            val arr = JSONArray(json)
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
        }.getOrNull()
    }
}

/**
 * On-device audio analysis without ML models:
 *  - tempo (BPM) via autocorrelation of the onset-energy envelope,
 *  - musical key via chromagram + Krumhansl profiles, reported in Camelot,
 *  - a compact sonic fingerprint (energy, brightness, chroma…) whose cosine
 *    distance drives "similar songs" radio and playlist reordering.
 * Analyzes up to 60s from the middle of the track to keep CPU/battery sane.
 */
object AudioAnalyzer {

    private const val FRAME = 2048
    private const val HOP = 1024
    private const val MAX_SECONDS = 60

    fun analyze(path: String, durationSec: Int): AnalysisResult? {
        val (pcm, sampleRate) = decodeMonoPcm(path, durationSec) ?: return null
        if (pcm.size < sampleRate) return null // under a second of audio

        val frameCount = (pcm.size - FRAME) / HOP
        if (frameCount < 8) return null

        val window = FloatArray(FRAME) { 0.5f - 0.5f * cos(2.0 * PI * it / FRAME).toFloat() }
        val energies = FloatArray(frameCount)
        val chroma = FloatArray(12)
        var centroidSum = 0.0
        var rolloffSum = 0.0
        var zcrSum = 0.0
        var rmsSum = 0.0

        val re = FloatArray(FRAME)
        val im = FloatArray(FRAME)
        // Analyze the spectrum on a subset of frames (every 4th) to save CPU.
        var spectralFrames = 0

        for (f in 0 until frameCount) {
            val offset = f * HOP
            var energy = 0.0
            var zc = 0
            for (i in 0 until FRAME) {
                val s = pcm[offset + i]
                energy += (s * s).toDouble()
                if (i > 0 && (s >= 0) != (pcm[offset + i - 1] >= 0)) zc++
            }
            energies[f] = energy.toFloat()
            rmsSum += sqrt(energy / FRAME)
            zcrSum += zc.toDouble() / FRAME

            if (f % 4 == 0) {
                for (i in 0 until FRAME) {
                    re[i] = pcm[offset + i] * window[i]
                    im[i] = 0f
                }
                fft(re, im)
                var magSum = 0.0
                var weighted = 0.0
                val mags = FloatArray(FRAME / 2)
                for (k in 1 until FRAME / 2) {
                    val mag = sqrt((re[k] * re[k] + im[k] * im[k]).toDouble()).toFloat()
                    mags[k] = mag
                    magSum += mag
                    weighted += mag * k
                }
                if (magSum > 0) {
                    centroidSum += weighted / magSum * sampleRate / FRAME
                    var cum = 0.0
                    var rolloffBin = 0
                    for (k in 1 until FRAME / 2) {
                        cum += mags[k]
                        if (cum >= 0.85 * magSum) {
                            rolloffBin = k
                            break
                        }
                    }
                    rolloffSum += rolloffBin.toDouble() * sampleRate / FRAME
                    // Chroma: fold bins onto 12 pitch classes (55 Hz - 4 kHz).
                    for (k in 1 until FRAME / 2) {
                        val freq = k.toDouble() * sampleRate / FRAME
                        if (freq < 55 || freq > 4000) continue
                        val pitchClass = ((12 * log2(freq / 440.0) + 69).roundToInt() % 12 + 12) % 12
                        chroma[pitchClass] += mags[k]
                    }
                }
                spectralFrames++
            }
        }

        val bpm = estimateBpm(energies, sampleRate)
        val camelot = estimateCamelot(chroma)

        // Fingerprint: normalized chroma (12) + tempo + energy + brightness + rolloff + zcr
        val chromaTotal = chroma.sum().takeIf { it > 0 } ?: 1f
        val features = FloatArray(17)
        for (i in 0 until 12) features[i] = chroma[i] / chromaTotal
        features[12] = ((bpm ?: 120f) - 60f) / 140f
        features[13] = (rmsSum / frameCount).toFloat() * 4f
        features[14] = (centroidSum / spectralFrames / 4000.0).toFloat()
        features[15] = (rolloffSum / spectralFrames / 8000.0).toFloat()
        features[16] = (zcrSum / frameCount).toFloat() * 4f

        return AnalysisResult(bpm, camelot, features)
    }

    // ---- tempo ----

    private fun estimateBpm(energies: FloatArray, sampleRate: Int): Float? {
        val n = energies.size
        // Onset strength: positive energy increments.
        val onset = FloatArray(n)
        for (i in 1 until n) onset[i] = maxOf(0f, energies[i] - energies[i - 1])
        val mean = onset.average().toFloat()
        for (i in onset.indices) onset[i] -= mean

        val framesPerSec = sampleRate.toFloat() / HOP
        val minLag = (framesPerSec * 60f / 180f).toInt().coerceAtLeast(1) // 180 BPM
        val maxLag = (framesPerSec * 60f / 60f).toInt().coerceAtMost(n / 2) // 60 BPM
        if (maxLag <= minLag) return null

        var bestLag = 0
        var bestScore = 0f
        for (lag in minLag..maxLag) {
            var score = 0f
            for (i in 0 until n - lag) score += onset[i] * onset[i + lag]
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag == 0 || bestScore <= 0f) return null
        var bpm = 60f * framesPerSec / bestLag
        while (bpm < 70f) bpm *= 2f
        while (bpm > 180f) bpm /= 2f
        return (bpm * 10).roundToInt() / 10f
    }

    // ---- key ----

    private val MAJOR_PROFILE = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
    private val MINOR_PROFILE = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)
    private val MAJOR_CAMELOT = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1) // C..B

    private fun estimateCamelot(chroma: FloatArray): String? {
        if (chroma.sum() <= 0f) return null
        var bestScore = Float.NEGATIVE_INFINITY
        var bestRoot = 0
        var bestMinor = false
        for (root in 0 until 12) {
            var maj = 0f
            var min = 0f
            for (i in 0 until 12) {
                maj += chroma[(root + i) % 12] * MAJOR_PROFILE[i]
                min += chroma[(root + i) % 12] * MINOR_PROFILE[i]
            }
            if (maj > bestScore) {
                bestScore = maj; bestRoot = root; bestMinor = false
            }
            if (min > bestScore) {
                bestScore = min; bestRoot = root; bestMinor = true
            }
        }
        return if (bestMinor) "${MAJOR_CAMELOT[(bestRoot + 3) % 12]}A"
        else "${MAJOR_CAMELOT[bestRoot]}B"
    }

    /** Distance between two Camelot codes for DJ-style compatibility (0=perfect). */
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

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 0f || nb <= 0f) return 0f
        return dot / (sqrt(na) * sqrt(nb))
    }

    // ---- decoding ----

    /** Decodes up to MAX_SECONDS of mono float PCM from the middle of the file. */
    private fun decodeMonoPcm(path: String, durationSec: Int): Pair<FloatArray, Int>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    extractor.selectTrack(i)
                    format = f
                    break
                }
            }
            val fmt = format ?: return null
            val sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // Seek to the middle so we analyze the body of the song, not the intro.
            if (durationSec > MAX_SECONDS + 10) {
                val startUs = (durationSec - MAX_SECONDS) / 2 * 1_000_000L
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(fmt)
                ?: return null
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val maxSamples = sampleRate * MAX_SECONDS
            val pcm = FloatArray(maxSamples)
            var written = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            while (!outputDone && written < maxSamples) {
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
                    val out = codec.getOutputBuffer(outIndex)!!
                    val shorts = out.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    var i = 0
                    while (i + channels <= shorts.limit() && written < maxSamples) {
                        var sum = 0f
                        for (c in 0 until channels) sum += shorts.get(i + c) / 32768f
                        pcm[written++] = sum / channels
                        i += channels
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            return pcm.copyOf(written) to sampleRate
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    // ---- FFT (iterative radix-2) ----

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curR = 1f
                var curI = 0f
                for (k in 0 until len / 2) {
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val vR = re[i + k + len / 2] * curR - im[i + k + len / 2] * curI
                    val vI = re[i + k + len / 2] * curI + im[i + k + len / 2] * curR
                    re[i + k] = uR + vR
                    im[i + k] = uI + vI
                    re[i + k + len / 2] = uR - vR
                    im[i + k + len / 2] = uI - vI
                    val nR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
