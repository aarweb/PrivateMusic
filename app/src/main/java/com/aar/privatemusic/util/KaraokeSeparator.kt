package com.aar.privatemusic.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import com.aar.privatemusic.data.db.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Offline vocal separation for karaoke mode.
 *
 * Runs Open-Unmix (UMX-HQ, vocals target) exported to ONNX with a FIXED
 * input shape of (1, 2, 2049, 1280): magnitude STFT (n_fft 4096, hop 1024)
 * of ~30s stereo chunks at 44.1 kHz. The soft mask 1 - vocals/mix applied
 * to the complex mix spectrogram gives the instrumental, written as WAV.
 * Chunks overlap by N_FFT on each side; the edges are discarded because
 * the overlap-add window normalization is degenerate there.
 */
object KaraokeSeparator {

    private const val MODEL_URL =
        "https://github.com/aarweb/PrivateMusic/releases/download/models/umx_vocals.onnx"
    private const val SAMPLE_RATE = 44100
    private const val N_FFT = 4096
    private const val HOP = 1024
    private const val FRAMES = 1280
    private const val BINS = N_FFT / 2 + 1 // 2049
    private const val CHUNK = (FRAMES - 1) * HOP + N_FFT
    private const val EDGE = N_FFT
    private const val STEP = CHUNK - 2 * EDGE
    // Beyond this the in-RAM PCM (~21 MB/min) risks OOM even with largeHeap.
    const val MAX_DURATION_SEC = 15 * 60

    fun modelFile(context: Context): File = File(context.filesDir, "models/umx_vocals.onnx")

    fun isModelReady(context: Context): Boolean = modelFile(context).length() > 10_000_000

    fun instrumentalFile(musicDir: File, songId: String): File =
        File(musicDir, "$songId.karaoke.wav")

    suspend fun downloadModel(context: Context, onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val target = modelFile(context)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.part")
            try {
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done * 100 / total).toInt())
                        }
                    }
                }
                // A quietly-truncated file would crash createSession on every use.
                if (total > 0 && done != total) {
                    tmp.delete()
                    false
                } else {
                    tmp.renameTo(target)
                }
            } catch (e: Exception) {
                tmp.delete()
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("Karaoke", "model download failed", e)
                false
            }
        }

    /** Separates the vocals out of [song]; returns the instrumental WAV or null. */
    suspend fun separate(
        context: Context,
        song: Song,
        musicDir: File,
        onProgress: (Int) -> Unit,
    ): File? = withContext(Dispatchers.Default) {
        val out = instrumentalFile(musicDir, song.id)
        if (out.length() > 1000) return@withContext out
        // Whole-song PCM lives in RAM: cap the duration to keep the heap sane.
        if (song.durationSec > MAX_DURATION_SEC) return@withContext null
        val (left, right) = decodeStereo(song.filePath, song.durationSec) ?: return@withContext null
        onProgress(10)

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        val session = try {
            env.createSession(modelFile(context).absolutePath, opts)
        } catch (e: Exception) {
            // Corrupt/truncated model: delete it so the next attempt re-downloads
            // instead of crashing forever.
            android.util.Log.e("Karaoke", "bad model, deleting", e)
            modelFile(context).delete()
            return@withContext null
        }
        val tmp = File(musicDir, "${song.id}.karaoke.part")
        var wav: RandomAccessFile? = null
        try {
            val w = RandomAccessFile(tmp, "rw").also { wav = it }
            w.write(ByteArray(44)) // header patched at the end

            val total = left.size
            val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }
            // Reused buffers: complex spectrogram of both channels + model input.
            val specRe = Array(2) { FloatArray(BINS * FRAMES) }
            val specIm = Array(2) { FloatArray(BINS * FRAMES) }
            val mag = FloatArray(2 * BINS * FRAMES)
            val chunkL = FloatArray(CHUNK)
            val chunkR = FloatArray(CHUNK)
            val outL = FloatArray(CHUNK)
            val outR = FloatArray(CHUNK)
            val wsum = FloatArray(CHUNK)
            val fft = Fft(N_FFT)

            var pos = 0
            var written = 0L
            while (pos < total) {
                currentCoroutineContext().ensureActive()
                fill(left, pos, chunkL)
                fill(right, pos, chunkR)
                stft(chunkL, window, fft, specRe[0], specIm[0])
                stft(chunkR, window, fft, specRe[1], specIm[1])
                for (ch in 0 until 2) {
                    val re = specRe[ch]; val im = specIm[ch]
                    val base = ch * BINS * FRAMES
                    for (i in 0 until BINS * FRAMES) {
                        mag[base + i] = kotlin.math.sqrt(re[i] * re[i] + im[i] * im[i])
                    }
                }
                currentCoroutineContext().ensureActive()
                val input = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(mag), longArrayOf(1, 2, BINS.toLong(), FRAMES.toLong()),
                )
                val vocals = input.use { session.run(mapOf("mix_mag" to it)) }.use { result ->
                    val t = result[0] as OnnxTensor
                    val arr = FloatArray(2 * BINS * FRAMES)
                    t.floatBuffer.get(arr)
                    arr
                }
                // Soft mask: keep 1 - vocals/mix of each complex bin.
                for (ch in 0 until 2) {
                    val re = specRe[ch]; val im = specIm[ch]
                    val base = ch * BINS * FRAMES
                    for (i in 0 until BINS * FRAMES) {
                        val m = mag[base + i]
                        val ratio = if (m > 1e-9f) (vocals[base + i] / m).coerceIn(0f, 1f) else 0f
                        val keep = 1f - ratio
                        re[i] *= keep
                        im[i] *= keep
                    }
                }
                currentCoroutineContext().ensureActive()
                istft(specRe[0], specIm[0], window, fft, outL, wsum)
                istft(specRe[1], specIm[1], window, fft, outR, wsum)

                // Discard chunk edges (degenerate normalization + no LSTM context).
                val from = if (pos == 0) 0 else EDGE
                val to = min(CHUNK - EDGE, total - pos).let {
                    if (pos + CHUNK >= total) min(CHUNK, total - pos) else it
                }
                val bytes = ByteBuffer.allocate((to - from) * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in from until to) {
                    bytes.putShort((outL[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                    bytes.putShort((outR[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                }
                w.write(bytes.array())
                written += (to - from)
                onProgress(10 + (pos.toLong() * 88 / total).toInt())
                if (pos + CHUNK >= total) break // tail already written up to the end
                pos += STEP
            }

            patchWavHeader(w, written)
            if (!tmp.renameTo(out)) throw java.io.IOException("renameTo failed")
            onProgress(100)
            out
        } catch (e: Exception) {
            tmp.delete()
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("Karaoke", "separation failed", e)
            null
        } finally {
            runCatching { wav?.close() }
            session.close()
        }
    }

    private fun fill(src: FloatArray, pos: Int, dst: FloatArray) {
        val n = min(dst.size, src.size - pos)
        if (n > 0) System.arraycopy(src, pos, dst, 0, n)
        if (n < dst.size) dst.fill(0f, n.coerceAtLeast(0), dst.size)
    }

    /** STFT without centering; results stored as [bin * FRAMES + frame] (model layout). */
    private fun stft(x: FloatArray, window: FloatArray, fft: Fft, outRe: FloatArray, outIm: FloatArray) {
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        for (f in 0 until FRAMES) {
            val base = f * HOP
            for (i in 0 until N_FFT) {
                re[i] = x[base + i] * window[i]
                im[i] = 0f
            }
            fft.forward(re, im)
            for (b in 0 until BINS) {
                outRe[b * FRAMES + f] = re[b]
                outIm[b * FRAMES + f] = im[b]
            }
        }
    }

    private fun istft(
        specRe: FloatArray, specIm: FloatArray, window: FloatArray, fft: Fft,
        out: FloatArray, wsum: FloatArray,
    ) {
        out.fill(0f)
        wsum.fill(0f)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        for (f in 0 until FRAMES) {
            for (b in 0 until BINS) {
                re[b] = specRe[b * FRAMES + f]
                im[b] = specIm[b * FRAMES + f]
            }
            // Rebuild the conjugate-symmetric upper half of the real signal's spectrum.
            for (b in 1 until N_FFT / 2) {
                re[N_FFT - b] = re[b]
                im[N_FFT - b] = -im[b]
            }
            fft.inverse(re, im)
            val base = f * HOP
            for (i in 0 until N_FFT) {
                out[base + i] += re[i] * window[i]
                wsum[base + i] += window[i] * window[i]
            }
        }
        for (i in out.indices) {
            out[i] /= wsum[i].coerceAtLeast(1e-4f)
        }
    }

    private fun patchWavHeader(wav: RandomAccessFile, frames: Long) {
        val dataBytes = frames * 4 // 16-bit stereo
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(2) // stereo
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 4)
        header.putShort(4)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataBytes.toInt())
        wav.seek(0)
        wav.write(header.array())
    }

    /** Decodes the whole file to stereo float PCM resampled to 44.1 kHz. */
    private fun decodeStereo(path: String, durationSec: Int): Pair<FloatArray, FloatArray>? {
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
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                ?: return null
            val decoder = MediaCodec.createByCodecName(codecName).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()

            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // Preallocate from the known duration: avoids doubling spikes near OOM.
            val expected = (durationSec.coerceAtLeast(1) + 5) * SAMPLE_RATE
            val left = FloatGrow(expected)
            val right = FloatGrow(expected)
            // Stateful linear resampler position (in source samples).
            var t = 0.0
            var prevL = 0f
            var prevR = 0f
            var havePrev = false

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
                        val shorts = ShortArray(info.size / 2)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        val frames = shorts.size / srcChannels
                        val ratio = srcRate.toDouble() / SAMPLE_RATE
                        var i = 0
                        while (i < frames) {
                            val l = shorts[i * srcChannels] / 32768f
                            val r = if (srcChannels > 1) shorts[i * srcChannels + 1] / 32768f else l
                            if (!havePrev) { prevL = l; prevR = r; havePrev = true }
                            // Emit output samples that fall between prev and this source sample.
                            while (t < 1.0) {
                                val a = t.toFloat()
                                left.add(prevL + (l - prevL) * a)
                                right.add(prevR + (r - prevR) * a)
                                t += ratio
                            }
                            t -= 1.0
                            prevL = l; prevR = r
                            i++
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        srcChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            if (left.size < SAMPLE_RATE) null else left.toArray() to right.toArray()
        } catch (e: Exception) {
            android.util.Log.e("Karaoke", "decode failed", e)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Minimal growable float array to avoid boxing while decoding. */
    private class FloatGrow(initialCapacity: Int) {
        var data = FloatArray(initialCapacity.coerceAtLeast(1 shl 16))
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    /** Iterative radix-2 FFT with precomputed tables. */
    private class Fft(private val n: Int) {
        private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
        private val sinT = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
        private val rev = IntArray(n).also { rev ->
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j or bit
                rev[i] = j
            }
        }

        fun forward(re: FloatArray, im: FloatArray) {
            for (i in 0 until n) {
                val r = rev[i]
                if (i < r) {
                    var tmp = re[i]; re[i] = re[r]; re[r] = tmp
                    tmp = im[i]; im[i] = im[r]; im[r] = tmp
                }
            }
            var len = 2
            while (len <= n) {
                val half = len / 2
                val tableStep = n / len
                var i = 0
                while (i < n) {
                    var k = 0
                    for (j in i until i + half) {
                        val c = cosT[k]; val s = -sinT[k]
                        val tr = re[j + half] * c - im[j + half] * s
                        val ti = re[j + half] * s + im[j + half] * c
                        re[j + half] = re[j] - tr
                        im[j + half] = im[j] - ti
                        re[j] += tr
                        im[j] += ti
                        k += tableStep
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        fun inverse(re: FloatArray, im: FloatArray) {
            for (i in 0 until n) im[i] = -im[i]
            forward(re, im)
            val inv = 1f / n
            for (i in 0 until n) {
                re[i] *= inv
                im[i] = -im[i] * inv
            }
        }
    }
}
