package com.aar.privatemusic.util

import com.aar.privatemusic.dsp.ReplayGain
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Lee y escribe el ReplayGain de track en los tags del fichero con jaudiotagger
 * (funciona en Android y escritorio para tags de texto). Lee
 * `REPLAYGAIN_TRACK_GAIN` (Vorbis/FLAC/ID3) y, si no, `R128_TRACK_GAIN` (opus,
 * Q7.8). La escritura sólo funciona en contenedores con comentarios Vorbis
 * (FLAC/OGG/Opus); en otros devuelve false sin romper nada.
 */
object ReplayGainTags {
    private const val KEY = "REPLAYGAIN_TRACK_GAIN"
    private val silenced: Unit by lazy { Logger.getLogger("org.jaudiotagger").level = Level.OFF }

    fun read(path: String): Float? {
        silenced
        val file = File(path)
        if (!file.canRead()) return null
        val tag = runCatching { AudioFileIO.read(file).tag }.getOrNull() ?: return null
        fun first(key: String): String? =
            runCatching { tag.getFirst(key) }.getOrNull()?.takeIf { it.isNotBlank() }
        ReplayGain.parseGainDb(first(KEY))?.let { return it }
        first("R128_TRACK_GAIN")?.trim()?.toIntOrNull()?.let { return ReplayGain.r128ToDb(it) }
        return null
    }

    /** Escribe `REPLAYGAIN_TRACK_GAIN` (dB). true si lo consiguió. Nunca lanza. */
    fun write(path: String, gainDb: Float): Boolean {
        silenced
        val file = File(path)
        if (!file.canWrite()) return false
        return runCatching {
            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            // setField(String, String) existe en las etiquetas Vorbis (FLAC/OGG/Opus);
            // en ID3/MP4 no, y entonces getMethod lanza y se captura -> false.
            val method = tag.javaClass.getMethod("setField", String::class.java, String::class.java)
            method.invoke(tag, KEY, ReplayGain.formatGainDb(gainDb))
            audio.commit()
            true
        }.getOrDefault(false)
    }
}
