package com.aar.privatemusic.util

import android.content.Context
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.lyrics.ForcedAligner
import com.aar.privatemusic.lyrics.Lyrics
import com.aar.privatemusic.lyrics.LyricsFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sincroniza una letra plana con la canción: dice en qué milisegundo se canta
 * cada palabra y lo guarda como Enhanced LRC, que es lo que luego enciende la
 * letra sílaba a sílaba.
 *
 * La voz sale de donde ya sabemos sacarla: el motor del karaoke separa el
 * instrumental, y **la voz es lo que sobra** (original − instrumental). No hace
 * falta ningún modelo de reconocimiento de voz ni descarga nueva. De ahí sale
 * una curva de energía vocal, y [ForcedAligner] reparte los versos por ella.
 *
 * Se compara por **energía en el tiempo**, no muestra a muestra: las dos pistas
 * pueden venir a distinta frecuencia de muestreo (el instrumental se escribe a
 * 44,1 kHz y el original puede ser de 48), así que ambas curvas se calculan
 * sobre la misma rejilla de [FRAME_MS] milisegundos y así son comparables.
 */
object LyricsSync {

    /** Cada cuánto se mide la energía. 20 ms: fino para una sílaba, barato. */
    private const val FRAME_MS = 20.0

    /** Igual que el karaoke: más de esto no cabe en memoria con holgura. */
    const val MAX_DURATION_SEC = KaraokeSeparator.MAX_DURATION_SEC

    /**
     * Genera la letra sincronizada de [song] a partir de sus versos en
     * [plainLines] y del instrumental ya separado en [instrumental].
     * Devuelve null si no se puede (audio ilegible, sin voz detectable...).
     */
    suspend fun generate(
        song: Song,
        instrumental: File,
        plainLines: List<String>,
        musicDir: File,
    ): Lyrics? = withContext(Dispatchers.Default) {
        if (plainLines.none { it.isNotBlank() }) return@withContext null
        val energy = vocalEnergy(song.filePath, instrumental.absolutePath, song.durationSec)
            ?: return@withContext null
        currentCoroutineContext().ensureActive()

        val segments = ForcedAligner.segmentsFromEnergy(energy, FRAME_MS)
        if (segments.isEmpty()) return@withContext null
        val aligned = ForcedAligner.alignToVoice(
            lines = plainLines,
            segments = segments,
            totalMs = song.durationSec * 1000L,
        ) ?: return@withContext null

        // Se guarda como Enhanced LRC junto al audio, igual que una letra
        // descargada: a partir de aquí la app no distingue de dónde salió.
        LyricsFetcher.saveSynced(song.id, musicDir, aligned)
    }

    /**
     * Curva de energía de la voz: lo que suena en el original y no está en el
     * instrumental. Un valor cada [FRAME_MS] milisegundos.
     */
    private fun vocalEnergy(originalPath: String, instrumentalPath: String, durationSec: Int): FloatArray? {
        // Las dos curvas se calculan por separado y el PCM se suelta enseguida:
        // una canción de cinco minutos son ~50 MB por pista.
        val mix = envelope(originalPath, durationSec) ?: return null
        val instrumental = envelope(instrumentalPath, durationSec) ?: return null
        val n = minOf(mix.size, instrumental.size)
        if (n == 0) return null
        return FloatArray(n) { i -> max(0f, mix[i] - instrumental[i]) }
    }

    /** RMS por ventana de [FRAME_MS] ms, con la misma rejilla para cualquier fichero. */
    private fun envelope(path: String, durationSec: Int): FloatArray? {
        // maxSeconds por encima de la duración: así decodifica desde el
        // principio y no desde la mitad, que es lo que hace para analizar.
        val (pcm, sampleRate) = AudioAnalyzer.decodeMonoPcm(path, durationSec, durationSec + 20) ?: return null
        if (pcm.isEmpty() || sampleRate <= 0) return null
        val window = max(1, (sampleRate * FRAME_MS / 1000.0).toInt())
        val frames = pcm.size / window
        if (frames == 0) return null
        return FloatArray(frames) { f ->
            var sum = 0.0
            val from = f * window
            for (i in from until from + window) {
                val v = pcm[i].toDouble()
                sum += v * v
            }
            sqrt(sum / window).toFloat()
        }
    }
}
