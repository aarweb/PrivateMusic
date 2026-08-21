package com.aar.privatemusic.player

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.aar.privatemusic.data.AppSettings
import com.aar.privatemusic.dsp.Crossfeed
import com.aar.privatemusic.dsp.EqFilter
import com.aar.privatemusic.dsp.EqFilterType
import com.aar.privatemusic.dsp.ParametricEq
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * DSP propio insertado en la cadena de audio de Media3 (antes del sink), así
 * suena también en la pista saliente durante el fundido. Un único
 * [snapshot] inmutable compartido lo leen los procesadores de ambos
 * reproductores; [reload] lo actualiza desde Ajustes y sube [version] para que
 * los procesadores reconstruyan sus filtros en el siguiente buffer.
 *
 * El paramétrico corre aquí; el ecualizador "gráfico" (del sistema, en
 * [EqHolder]) va enganchado al audioSessionId. Sólo uno está activo a la vez
 * (lo garantiza la UI) para no encadenar dos EQ.
 */
object AudioDsp {
    data class Snapshot(
        val eqEnabled: Boolean,
        val preampDb: Double,
        val filters: List<EqFilter>,
        val crossfeedLevel: Int,
    )

    @Volatile
    var snapshot: Snapshot = Snapshot(false, 0.0, emptyList(), 0)
        private set

    val version = AtomicInteger(0)

    fun reload(context: Context) {
        snapshot = Snapshot(
            eqEnabled = AppSettings.readEqMode(context) == "parametric",
            preampDb = AppSettings.readEqPreamp(context).toDouble(),
            filters = decodeFilters(AppSettings.readEqFilters(context)),
            crossfeedLevel = AppSettings.readCrossfeedLevel(context),
        )
        version.incrementAndGet()
    }

    fun buildRenderersFactory(context: Context): RenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(EqAudioProcessor(), CrossfeedAudioProcessor()))
                    .build()
        }

    // --- (de)serialización de los filtros paramétricos ---

    fun encodeFilters(preampDb: Float, filters: List<EqFilter>): String {
        val arr = JSONArray()
        filters.forEach {
            arr.put(
                JSONObject()
                    .put("t", it.type.name)
                    .put("f", it.freqHz)
                    .put("g", it.gainDb)
                    .put("q", it.q),
            )
        }
        return JSONObject().put("preamp", preampDb.toDouble()).put("filters", arr).toString()
    }

    fun decodeFilters(json: String?): List<EqFilter> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONObject(json).optJSONArray("filters") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = runCatching { EqFilterType.valueOf(o.optString("t")) }.getOrNull()
                    ?: return@mapNotNull null
                EqFilter(type, o.optDouble("f"), o.optDouble("g"), o.optDouble("q").coerceAtLeast(0.1))
            }
        }.getOrDefault(emptyList())
    }

    fun decodePreamp(json: String?): Float {
        if (json.isNullOrBlank()) return 0f
        return runCatching { JSONObject(json).optDouble("preamp", 0.0).toFloat() }.getOrDefault(0f)
    }
}

/** Ecualizador paramétrico como AudioProcessor de Media3 (PCM 16-bit). */
private class EqAudioProcessor : BaseAudioProcessor() {
    private val eq = ParametricEq()
    private var appliedVersion = -1
    private var sampleRate = 0
    private var channels = 0
    private var scratch = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_16BIT) {
            sampleRate = inputAudioFormat.sampleRate
            channels = inputAudioFormat.channelCount
            appliedVersion = -1
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush() {
        eq.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val shortCount = remaining / 2
        if (scratch.size < shortCount) scratch = ShortArray(shortCount)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(scratch, 0, shortCount)
        inputBuffer.position(inputBuffer.limit())

        val snap = AudioDsp.snapshot
        val v = AudioDsp.version.get()
        if (v != appliedVersion) {
            if (snap.eqEnabled) {
                eq.configure(sampleRate, channels, snap.preampDb, snap.filters)
            } else {
                eq.configure(sampleRate, channels, 0.0, emptyList())
            }
            appliedVersion = v
        }
        if (eq.isActive()) eq.process(scratch, shortCount)

        val out = replaceOutputBuffer(remaining)
        out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(scratch, 0, shortCount)
        out.position(remaining)
        out.flip()
    }
}

/** Crossfeed como AudioProcessor de Media3 (PCM 16-bit estéreo). */
private class CrossfeedAudioProcessor : BaseAudioProcessor() {
    private val crossfeed = Crossfeed()
    private var appliedVersion = -1
    private var sampleRate = 0
    private var channels = 0
    private var scratch = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_16BIT) {
            sampleRate = inputAudioFormat.sampleRate
            channels = inputAudioFormat.channelCount
            appliedVersion = -1
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush() {
        crossfeed.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val shortCount = remaining / 2
        if (scratch.size < shortCount) scratch = ShortArray(shortCount)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(scratch, 0, shortCount)
        inputBuffer.position(inputBuffer.limit())

        val v = AudioDsp.version.get()
        if (v != appliedVersion) {
            crossfeed.configure(sampleRate, channels, AudioDsp.snapshot.crossfeedLevel)
            appliedVersion = v
        }
        if (crossfeed.isActive()) crossfeed.process(scratch, shortCount)

        val out = replaceOutputBuffer(remaining)
        out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(scratch, 0, shortCount)
        out.position(remaining)
        out.flip()
    }
}
