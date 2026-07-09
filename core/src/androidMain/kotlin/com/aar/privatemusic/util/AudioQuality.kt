package com.aar.privatemusic.util

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File



actual fun readAudioQuality(path: String, durationSec: Int): AudioQuality? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(path)
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                format = f
                break
            }
        }
        val f = format ?: return null
        val mime = f.getString(MediaFormat.KEY_MIME) ?: return null
        val codec = when {
            mime.contains("opus") -> "OPUS"
            mime.contains("mp4a") || mime.contains("aac") -> "AAC"
            mime.contains("vorbis") -> "VORBIS"
            mime.contains("flac") -> "FLAC"
            mime.contains("mpeg") -> "MP3"
            else -> mime.substringAfter("audio/").uppercase()
        }
        val sampleRate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else null
        val bitrate = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) {
            f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
        } else {
            // Containers like WebM often omit the tag: estimate from file size.
            val bytes = File(path).length()
            if (durationSec > 0) ((bytes * 8) / durationSec / 1000).toInt() else null
        }
        AudioQuality(codec, bitrate, sampleRate)
    } catch (e: Exception) {
        null
    } finally {
        extractor.release()
    }
}
