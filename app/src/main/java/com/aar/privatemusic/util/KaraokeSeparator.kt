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
    private const val MDX_MODEL_URL =
        "https://github.com/aarweb/PrivateMusic/releases/download/models/mdx_inst_hq3.onnx"
    // MDX-Net (UVR Inst HQ3): complex STFT segments, n_fft 6144 (= 3 * 2048).
    private const val MDX_NFFT = 6144
    private const val MDX_HOP = 1024
    private const val MDX_DIMF = 3072
    private const val MDX_DIMT = 256
    private const val MDX_SEG = (MDX_DIMT - 1) * MDX_HOP // 261120
    private const val MDX_EDGE = MDX_NFFT
    private const val MDX_STEP = MDX_SEG - 2 * MDX_EDGE
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

    fun mdxModelFile(context: Context): File = File(context.filesDir, "models/mdx_inst_hq3.onnx")

    fun engine(context: Context): String =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("karaoke_engine", "umx") ?: "umx"

    fun isModelReady(context: Context): Boolean =
        if (engine(context) == "mdx") mdxModelFile(context).length() > 50_000_000
        else modelFile(context).length() > 10_000_000

    fun instrumentalFile(musicDir: File, songId: String): File = File(musicDir, "$songId.karaoke.wav")

    fun instrumentalFileFor(context: Context, musicDir: File, songId: String): File =
        if (engine(context) == "mdx") File(musicDir, "$songId.karaoke_hq.wav")
        else File(musicDir, "$songId.karaoke.wav")

    suspend fun downloadModel(context: Context, onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val mdx = engine(context) == "mdx"
            val target = if (mdx) mdxModelFile(context) else modelFile(context)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.part")
            try {
                val conn = URL(if (mdx) MDX_MODEL_URL else MODEL_URL)
                    .openConnection() as HttpURLConnection
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
        val mdx = engine(context) == "mdx"
        val out = instrumentalFileFor(context, musicDir, song.id)
        if (out.length() > 1000) return@withContext out
        // Whole-song PCM lives in RAM: cap the duration to keep the heap sane.
        if (song.durationSec > MAX_DURATION_SEC) return@withContext null
        val (left, right) = decodeStereo(song.filePath, song.durationSec) ?: return@withContext null
        onProgress(10)

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        val activeModel = if (mdx) mdxModelFile(context) else modelFile(context)
        val session = try {
            env.createSession(activeModel.absolutePath, opts)
        } catch (e: Exception) {
            // Corrupt/truncated model: delete it so the next attempt re-downloads
            // instead of crashing forever.
            android.util.Log.e("Karaoke", "bad model, deleting", e)
            activeModel.delete()
            return@withContext null
        }
        if (mdx) {
            return@withContext try {
                separateMdx(env, session, left, right, musicDir, song.id, out, onProgress)
            } finally {
                session.close()
            }
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


    /**
     * MDX-Net engine (UVR Inst HQ3): the model maps the complex STFT of the
     * mix straight to the instrumental's complex STFT — much cleaner vocals
     * removal than the UMX mask. Center-padded per segment; segment edges
     * are discarded and segments overlap so seams never reach the output.
     */
    private suspend fun separateMdx(
        env: OrtEnvironment,
        session: OrtSession,
        left: FloatArray,
        right: FloatArray,
        musicDir: File,
        songId: String,
        out: File,
        onProgress: (Int) -> Unit,
    ): File? {
        val tmp = File(musicDir, "$songId.karaoke_hq.part")
        var wav: RandomAccessFile? = null
        return try {
            val w = RandomAccessFile(tmp, "rw").also { wav = it }
            w.write(ByteArray(44))

            val total = left.size
            val half = MDX_NFFT / 2
            val win = FloatArray(MDX_NFFT) { (0.5 - 0.5 * cos(2.0 * PI * it / MDX_NFFT)).toFloat() }
            val fft = Fft3x2048()
            val bins = MDX_NFFT / 2 + 1 // 3073
            // input/output layout: [reL, reR, imL, imR] × (3072 × 256), bin-major.
            val inBuf = FloatArray(4 * MDX_DIMF * MDX_DIMT)
            val segPadded = FloatArray(MDX_SEG + MDX_NFFT) // center pad both sides
            val re = FloatArray(MDX_NFFT)
            val im = FloatArray(MDX_NFFT)
            val specRe = Array(2) { FloatArray(bins * MDX_DIMT) }
            val specIm = Array(2) { FloatArray(bins * MDX_DIMT) }
            val outSeg = Array(2) { FloatArray(MDX_SEG + MDX_NFFT) }
            val wsum = FloatArray(MDX_SEG + MDX_NFFT)

            fun segmentToSpec(src: FloatArray, pos: Int, ch: Int) {
                // Fill centered segment with reflect padding at song edges.
                for (i in segPadded.indices) {
                    var idx = pos + i - half
                    if (idx < 0) idx = -idx
                    if (idx >= src.size) idx = 2 * src.size - 2 - idx
                    segPadded[i] = src[idx.coerceIn(0, src.size - 1)]
                }
                for (t in 0 until MDX_DIMT) {
                    val base = t * MDX_HOP
                    for (i in 0 until MDX_NFFT) {
                        re[i] = segPadded[base + i] * win[i]
                        im[i] = 0f
                    }
                    fft.forward(re, im)
                    for (b in 0 until bins) {
                        specRe[ch][b * MDX_DIMT + t] = re[b]
                        specIm[ch][b * MDX_DIMT + t] = im[b]
                    }
                }
            }

            fun specToSegment(ch: Int) {
                val dst = outSeg[ch]
                java.util.Arrays.fill(dst, 0f)
                if (ch == 0) java.util.Arrays.fill(wsum, 0f)
                for (t in 0 until MDX_DIMT) {
                    for (b in 0 until bins) {
                        re[b] = specRe[ch][b * MDX_DIMT + t]
                        im[b] = specIm[ch][b * MDX_DIMT + t]
                    }
                    for (b in 1 until MDX_NFFT / 2) {
                        re[MDX_NFFT - b] = re[b]
                        im[MDX_NFFT - b] = -im[b]
                    }
                    fft.inverse(re, im)
                    val base = t * MDX_HOP
                    for (i in 0 until MDX_NFFT) {
                        dst[base + i] += re[i] * win[i]
                        if (ch == 0) wsum[base + i] += win[i] * win[i]
                    }
                }
                for (i in dst.indices) dst[i] /= wsum[i].coerceAtLeast(1e-6f)
            }

            var pos = 0
            var written = 0L
            while (pos < total) {
                currentCoroutineContext().ensureActive()
                segmentToSpec(left, pos, 0)
                segmentToSpec(right, pos, 1)
                // Pack model input: channels [reL, reR, imL, imR], 3072 bins.
                var o = 0
                for (b in 0 until MDX_DIMF) for (t in 0 until MDX_DIMT) inBuf[o++] = specRe[0][b * MDX_DIMT + t]
                for (b in 0 until MDX_DIMF) for (t in 0 until MDX_DIMT) inBuf[o++] = specRe[1][b * MDX_DIMT + t]
                for (b in 0 until MDX_DIMF) for (t in 0 until MDX_DIMT) inBuf[o++] = specIm[0][b * MDX_DIMT + t]
                for (b in 0 until MDX_DIMF) for (t in 0 until MDX_DIMT) inBuf[o++] = specIm[1][b * MDX_DIMT + t]

                currentCoroutineContext().ensureActive()
                val input = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(inBuf),
                    longArrayOf(1, 4, MDX_DIMF.toLong(), MDX_DIMT.toLong()),
                )
                val res = input.use { session.run(mapOf(session.inputNames.first() to it)) }
                res.use { r ->
                    val t0 = r[0] as OnnxTensor
                    val fb = t0.floatBuffer
                    // Unpack straight into spec arrays (Nyquist bin stays zero).
                    var i0 = 0
                    for (b in 0 until MDX_DIMF) for (t in 0 until MDX_DIMT) specRe[0][b * MDX_DIMT + t] = fb.get(i0++)
                    for (b in 0 until MDX_DIMF) for (t in 0 until MDX_DIMT) specRe[1][b * MDX_DIMT + t] = fb.get(i0++)
                    for (b in 0 until MDX_DIMF) for (t in 0 until MDX_DIMT) specIm[0][b * MDX_DIMT + t] = fb.get(i0++)
                    for (b in 0 until MDX_DIMF) for (t in 0 until MDX_DIMT) specIm[1][b * MDX_DIMT + t] = fb.get(i0++)
                    for (ch in 0 until 2) {
                        for (t in 0 until MDX_DIMT) {
                            specRe[ch][(bins - 1) * MDX_DIMT + t] = 0f
                            specIm[ch][(bins - 1) * MDX_DIMT + t] = 0f
                        }
                    }
                }
                specToSegment(0)
                specToSegment(1)

                // Write the valid center (segment minus center-pad and edges).
                val from = if (pos == 0) half else half + MDX_EDGE
                val avail = min(MDX_SEG, total - pos)
                val to = if (pos + MDX_SEG >= total) half + avail else half + MDX_SEG - MDX_EDGE
                val bytes = ByteBuffer.allocate((to - from) * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in from until to) {
                    bytes.putShort((outSeg[0][i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                    bytes.putShort((outSeg[1][i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                }
                w.write(bytes.array())
                written += (to - from)
                onProgress(10 + (pos.toLong() * 88 / total).toInt())
                if (pos + MDX_SEG >= total) break
                pos += MDX_STEP
            }

            patchWavHeader(w, written)
            if (!tmp.renameTo(out)) throw java.io.IOException("renameTo failed")
            onProgress(100)
            out
        } catch (e: Exception) {
            tmp.delete()
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("Karaoke", "mdx separation failed", e)
            null
        } finally {
            runCatching { wav?.close() }
        }
    }

    /** Radix-3 decimation over the radix-2 core: N = 6144 = 3 × 2048. */
    private class Fft3x2048 {
        private val m = 2048
        private val n = 6144
        private val sub = Fft(m)
        private val ar = FloatArray(m); private val ai = FloatArray(m)
        private val br = FloatArray(m); private val bi = FloatArray(m)
        private val cr = FloatArray(m); private val ci = FloatArray(m)
        private val wr = FloatArray(n) { cos(-2.0 * PI * it / n).toFloat() }
        private val wi = FloatArray(n) { sin(-2.0 * PI * it / n).toFloat() }

        fun forward(re: FloatArray, im: FloatArray) {
            for (i in 0 until m) {
                ar[i] = re[3 * i];     ai[i] = im[3 * i]
                br[i] = re[3 * i + 1]; bi[i] = im[3 * i + 1]
                cr[i] = re[3 * i + 2]; ci[i] = im[3 * i + 2]
            }
            sub.forward(ar, ai); sub.forward(br, bi); sub.forward(cr, ci)
            for (k in 0 until n) {
                val t = k % m
                val w1r = wr[k]; val w1i = wi[k]
                val k2 = (2 * k) % n
                val w2r = wr[k2]; val w2i = wi[k2]
                val bR = br[t] * w1r - bi[t] * w1i
                val bI = br[t] * w1i + bi[t] * w1r
                val cR = cr[t] * w2r - ci[t] * w2i
                val cI = cr[t] * w2i + ci[t] * w2r
                re[k] = ar[t] + bR + cR
                im[k] = ai[t] + bI + cI
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
