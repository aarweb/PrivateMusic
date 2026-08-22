package com.aar.privatemusic.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Prepara el vídeo "de fondo" de una canción. El vídeo suena mudo y en bucle en
 * la pantalla del reproductor, así que no interesa arrastrar su pista de audio:
 * se copia sólo el vídeo a `<id>.mp4` con [MediaMuxer] (sin recodificar, sin
 * ffmpeg). Si el contenedor no encaja en un mp4 (p. ej. VP9/webm), se guarda el
 * fichero tal cual —ExoPlayer lo reproduce igual en local— para no perderlo.
 */
object VideoImport {
    private const val TAG = "VideoImport"

    /** Copia [uri] a [dest] quedándose sólo con la pista de vídeo. Devuelve el fichero o null. */
    fun stripAudioTo(context: Context, uri: Uri, dest: File): File? {
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            val ok = runCatching { muxVideoOnly(context, uri, tmp) }.getOrDefault(false)
            if (ok && tmp.length() > 0) {
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
                dest
            } else {
                // El muxer no pudo (códec no-mp4): copia cruda, ExoPlayer la lee.
                tmp.delete()
                copyRaw(context, uri, dest)
            }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo importar el vídeo", e)
            tmp.delete()
            null
        }
    }

    /** Copia [source] (fichero ya en disco, p. ej. una descarga) a `<id>.mp4` sin audio. */
    fun stripAudioFromFile(source: File, dest: File): File? = try {
        val extractor = MediaExtractor().apply { setDataSource(source.absolutePath) }
        val out = muxFromExtractor(extractor, dest)
        if (out) dest else source.copyTo(dest, overwrite = true)
    } catch (e: Exception) {
        Log.w(TAG, "strip desde fichero falló, copia cruda", e)
        runCatching { source.copyTo(dest, overwrite = true) }.getOrNull()
    }

    private fun muxVideoOnly(context: Context, uri: Uri, dest: File): Boolean {
        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
            pfd ?: return false
            extractor.setDataSource(pfd.fileDescriptor)
            return muxFromExtractor(extractor, dest)
        }
    }

    /** Copia la primera pista de vídeo a un mp4. Cierra el extractor. Devuelve si lo logró. */
    private fun muxFromExtractor(extractor: MediaExtractor, dest: File): Boolean {
        try {
            var videoTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrack = i; format = f; break
                }
            }
            if (videoTrack < 0 || format == null) return false
            extractor.selectTrack(videoTrack)

            val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            muxer.release()
            return true
        } finally {
            extractor.release()
        }
    }

    private fun copyRaw(context: Context, uri: Uri, dest: File): File? =
        context.contentResolver.openInputStream(uri)?.use { input ->
            if (dest.exists()) dest.delete()
            dest.outputStream().use { input.copyTo(it) }
            dest
        }
}
