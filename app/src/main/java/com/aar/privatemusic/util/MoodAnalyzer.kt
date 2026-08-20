package com.aar.privatemusic.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Probabilidades 0..1 de cada rasgo; `vocalness` alto = tiene voz. */
data class MoodResult(
    val happy: Float,
    val sad: Float,
    val aggressive: Float,
    val relaxed: Float,
    val danceability: Float,
    val vocalness: Float,
)

/**
 * Estado de ánimo, energía, bailabilidad y voz/instrumental de una canción con
 * los modelos de Essentia: el extractor Discogs-EffNet (18 MB) produce un
 * embedding de 1280 valores por parche de ~2 s, y seis cabezas diminutas
 * (0,5 MB cada una) lo convierten en probabilidades. Todo en el móvil, con el
 * mismo ONNX Runtime que ya usa el karaoke.
 *
 * La entrada tiene que ser exactamente la del entrenamiento
 * (`TensorflowInputMusiCNN` de Essentia): 16 kHz mono, ventana Hann de 512 sin
 * normalizar, salto 256, espectro de potencia, 96 bandas mel (escala Slaney,
 * triángulos de área unidad entre 0 y 8 kHz) y compresión log10(1 + 10000·x);
 * parches de 128 tramas con salto de 62. Una desviación aquí no da error: da
 * probabilidades sin sentido.
 */
object MoodAnalyzer {
    private const val TAG = "Mood"
    private const val BASE_URL = "https://github.com/aarweb/PrivateMusic/releases/download/models/"
    private const val EFFNET = "discogs-effnet-bsdynamic-1.onnx"
    private val HEADS = listOf(
        "mood_happy", "mood_sad", "mood_aggressive", "mood_relaxed", "danceability", "voice_instrumental",
    )
    /** Índice de la clase "positiva" en la salida softmax de cada cabeza (orden de `classes` en su JSON). */
    private val POSITIVE_INDEX = mapOf(
        "mood_happy" to 0, "mood_sad" to 1, "mood_aggressive" to 0,
        "mood_relaxed" to 1, "danceability" to 0, "voice_instrumental" to 1,
    )

    private const val SAMPLE_RATE = 16_000
    private const val FRAME = 512
    private const val HOP = 256
    private const val BANDS = 96
    private const val PATCH = 128
    private const val PATCH_HOP = 62
    private const val MAX_SECONDS = 60

    fun modelDir(context: Context): File = File(context.filesDir, "models/mood")
    private fun headFile(dir: File, head: String) = File(dir, "$head-discogs-effnet-1.onnx")

    fun modelsReady(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, EFFNET).length() > 17_000_000 &&
            HEADS.all { headFile(dir, it).length() > 400_000 }
    }

    /** Baja los que falten (21 MB en total, una vez). Devuelve true si están todos. */
    fun ensureModels(context: Context): Boolean {
        val dir = modelDir(context).apply { mkdirs() }
        val wanted = listOf(EFFNET to 17_000_000L) + HEADS.map { "$it-discogs-effnet-1.onnx" to 400_000L }
        wanted.forEach { (name, minSize) ->
            val target = File(dir, name)
            if (target.length() > minSize) return@forEach
            if (!download(BASE_URL + name, target)) return false
        }
        return modelsReady(context)
    }

    private fun download(url: String, target: File): Boolean {
        val tmp = File(target.parentFile, target.name + ".part")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                }
            } }
            if (total > 0 && done != total) { tmp.delete(); false } else tmp.renameTo(target)
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "model download failed: $url", e)
            false
        }
    }

    // --------------------------------------------------------------- sesiones

    private val lock = Any()
    private var effnet: OrtSession? = null
    private val heads = HashMap<String, OrtSession>()

    private fun sessions(context: Context): Pair<OrtSession, Map<String, OrtSession>> = synchronized(lock) {
        val env = OrtEnvironment.getEnvironment()
        val dir = modelDir(context)
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        val e = effnet ?: env.createSession(File(dir, EFFNET).absolutePath, opts).also { effnet = it }
        HEADS.forEach { h ->
            if (h !in heads) heads[h] = env.createSession(headFile(dir, h).absolutePath, opts)
        }
        e to heads
    }

    /** Libera las sesiones (tras un backfill largo, para devolver memoria). */
    fun release() = synchronized(lock) {
        runCatching { effnet?.close() }
        effnet = null
        heads.values.forEach { runCatching { it.close() } }
        heads.clear()
    }

    // --------------------------------------------------------------- análisis

    fun analyze(context: Context, path: String, durationSec: Int): MoodResult? {
        if (!modelsReady(context)) return null
        val (pcmNative, nativeRate) = AudioAnalyzer.decodeMonoPcm(path, durationSec, MAX_SECONDS) ?: return null
        val pcm = resampleTo16k(pcmNative, nativeRate)
        val mel = logMel(pcm)
        if (mel.size < PATCH) return null
        val patchCount = (mel.size - PATCH) / PATCH_HOP + 1
        val input = FloatArray(patchCount * PATCH * BANDS)
        var o = 0
        for (p in 0 until patchCount) {
            val start = p * PATCH_HOP
            for (f in 0 until PATCH) {
                System.arraycopy(mel[start + f], 0, input, o, BANDS)
                o += BANDS
            }
        }
        return try {
            val (eff, hs) = sessions(context)
            val env = OrtEnvironment.getEnvironment()
            val shape = longArrayOf(patchCount.toLong(), PATCH.toLong(), BANDS.toLong())
            val embeddings: Array<FloatArray> = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                eff.run(mapOf(eff.inputNames.first() to tensor)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    (out.get("embeddings").get().value as Array<FloatArray>)
                }
            }
            val flat = FloatArray(embeddings.size * 1280)
            embeddings.forEachIndexed { i, row -> System.arraycopy(row, 0, flat, i * 1280, 1280) }
            val embShape = longArrayOf(embeddings.size.toLong(), 1280L)
            val scores = HashMap<String, Float>()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), embShape).use { tensor ->
                hs.forEach { (name, session) ->
                    session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val act = out.get(0).value as Array<FloatArray>
                        val idx = POSITIVE_INDEX.getValue(name)
                        // Media de los parches: la canción entera, no un segundo de ella.
                        scores[name] = act.map { it[idx] }.average().toFloat()
                    }
                }
            }
            MoodResult(
                happy = scores.getValue("mood_happy"),
                sad = scores.getValue("mood_sad"),
                aggressive = scores.getValue("mood_aggressive"),
                relaxed = scores.getValue("mood_relaxed"),
                danceability = scores.getValue("danceability"),
                vocalness = scores.getValue("voice_instrumental"),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "inference failed for $path", e)
            null
        }
    }

    // ------------------------------------------------------------------- DSP

    /**
     * Remuestreo por convolución con una sinc enventanada (16 taps por lado,
     * corte en 7,2 kHz): sin el filtro, los agudos de 44,1 kHz se plegarían
     * sobre las bandas mel y el modelo vería un espectro que no existe.
     */
    internal fun resampleTo16k(x: FloatArray, rate: Int): FloatArray {
        if (rate == SAMPLE_RATE) return x
        val ratio = rate.toDouble() / SAMPLE_RATE
        val outLen = (x.size / ratio).toInt()
        val out = FloatArray(outLen)
        val half = 16
        val fc = 0.45 / ratio.coerceAtLeast(1.0) // normalizado a la tasa de entrada
        for (n in 0 until outLen) {
            val center = n * ratio
            val k0 = (center - half).toInt().coerceAtLeast(0)
            val k1 = (center + half).toInt().coerceAtMost(x.size - 1)
            var acc = 0.0
            var wsum = 0.0
            for (k in k0..k1) {
                val d = k - center
                val w = 0.5 + 0.5 * cos(PI * d / (half + 1)) // Hann
                val s = if (d == 0.0) 2 * fc else sin(2 * PI * fc * d) / (PI * d)
                acc += x[k] * s * w
                wsum += s * w
            }
            out[n] = if (wsum != 0.0) (acc / wsum).toFloat() else 0f
        }
        return out
    }

    private val hann = FloatArray(FRAME) { (0.5 - 0.5 * cos(2.0 * PI * it / (FRAME - 1))).toFloat() }

    /** Banco de filtros mel (Slaney, triángulos de área unidad), 96 × 257. */
    private val melFilters: Array<FloatArray> by lazy {
        val bins = FRAME / 2 + 1
        val binHz = SAMPLE_RATE.toDouble() / FRAME
        val melLo = hzToMel(0.0)
        val melHi = hzToMel(SAMPLE_RATE / 2.0)
        val edges = DoubleArray(BANDS + 2) { melToHz(melLo + (melHi - melLo) * it / (BANDS + 1)) }
        Array(BANDS) { b ->
            val lo = edges[b]; val c = edges[b + 1]; val hi = edges[b + 2]
            val norm = 2.0 / (hi - lo)
            FloatArray(bins) { k ->
                val f = k * binHz
                val w = when {
                    f < lo || f > hi -> 0.0
                    f <= c -> if (c > lo) (f - lo) / (c - lo) else 1.0
                    else -> if (hi > c) (hi - f) / (hi - c) else 1.0
                }
                (w * norm).toFloat()
            }
        }
    }

    private fun hzToMel(f: Double): Double =
        if (f < 1000.0) f / (200.0 / 3) else 15.0 + ln(f / 1000.0) / ln(6.4) * 27.0

    private fun melToHz(m: Double): Double =
        if (m < 15.0) m * (200.0 / 3) else 1000.0 * 6.4.pow((m - 15.0) / 27.0)

    /** Tramas × 96 bandas, ya comprimidas con log10(1 + 10000·x). */
    internal fun logMel(pcm: FloatArray): Array<FloatArray> {
        val frames = if (pcm.size < FRAME) 0 else (pcm.size - FRAME) / HOP + 1
        val re = FloatArray(FRAME)
        val im = FloatArray(FRAME)
        val power = FloatArray(FRAME / 2 + 1)
        return Array(frames) { t ->
            val off = t * HOP
            for (i in 0 until FRAME) { re[i] = pcm[off + i] * hann[i]; im[i] = 0f }
            AudioAnalyzer.fft(re, im)
            for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
            FloatArray(BANDS) { b ->
                val filt = melFilters[b]
                var acc = 0.0
                for (k in power.indices) acc += power[k] * filt[k]
                log10(1.0 + 10_000.0 * acc).toFloat()
            }
        }
    }

    /** Para pruebas/diagnóstico: energía media por banda de una señal. */
    @Suppress("unused")
    internal fun rms(x: FloatArray): Float = sqrt(x.fold(0.0) { a, v -> a + v * v } / x.size.coerceAtLeast(1)).toFloat()
}
