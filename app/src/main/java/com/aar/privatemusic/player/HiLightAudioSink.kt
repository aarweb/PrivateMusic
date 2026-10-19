package com.aar.privatemusic.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.aar.privatemusic.hilight.BeatDetector
import com.aar.privatemusic.hilight.HiLightController
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.aar.privatemusic.hilight.BeatQueue

/** Observes decoded PCM unchanged. All scheduling stays on the renderer's playback looper. */
internal class HiLightAudioSink(sink: AudioSink, private val lights: HiLightController) : ForwardingAudioSink(sink) {
    private var detector: BeatDetector? = null
    private var rate = 0
    private var channels = 0
    private var encoding = 0
    private var lastBuffer: ByteBuffer? = null
    private var handler: Handler? = null
    private var running = false
    private var scheduled = false
    private var analyzing = false
    private var volume = 1f
    private val beats = BeatQueue()
    private val tick = object : Runnable {
        override fun run() {
            scheduled = false
            if (!running) return
            if (!lights.wantsAudioBeats()) beats.clear()
            val position = getCurrentPositionUs(false)
            if (position != AudioSink.CURRENT_POSITION_NOT_SET) {
                if (beats.takeDue(position) && volume > 0.02f) lights.onAudioBeat()
            }
            schedule()
        }
    }

    override fun configure(config: AudioSink.AudioSinkConfig) {
        clear()
        super.configure(config)
        val format = config.format
        rate = format.sampleRate
        channels = format.channelCount
        encoding = format.pcmEncoding
        detector = if (rate > 0 && channels > 0 && encoding in
            setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) BeatDetector(rate, channels) else null
        handler = Looper.myLooper()?.let { Handler(it) }
        schedule()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (buffer !== lastBuffer) {
            lastBuffer = buffer
            if (lights.wantsAudioBeats()) {
                analyzing = true
                analyze(buffer, presentationTimeUs)
            } else if (analyzing) {
                clear()
                lastBuffer = buffer
                analyzing = false
            }
        }
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (consumed) lastBuffer = null
        schedule()
        return consumed
    }

    private fun analyze(buffer: ByteBuffer, timeUs: Long) {
        val analyser = detector ?: return
        val pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytes = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        var frame = 0L
        while (pcm.remaining() >= channels * bytes) {
            val timestamp = timeUs + frame * 1_000_000 / rate
            for (channel in 0 until channels) {
                val value = if (bytes == 4) pcm.float.toDouble() else pcm.short / 32768.0
                if (analyser.sample(value, channel, timestamp)) {
                    beats.add(timestamp)
                }
            }
            frame++
        }
    }

    override fun play() { super.play(); running = true; schedule() }
    override fun pause() { running = false; handler?.removeCallbacks(tick); scheduled = false; super.pause() }
    override fun setVolume(volume: Float) { this.volume = volume; super.setVolume(volume) }
    override fun handleDiscontinuity() { clear(); super.handleDiscontinuity(); schedule() }
    override fun flush() { clear(); super.flush(); schedule() }
    override fun reset() { running = false; clear(); super.reset() }
    override fun release() { running = false; clear(); super.release() }
    private fun schedule() {
        if (running && beats.isNotEmpty && !scheduled && handler != null) {
            scheduled = true
            handler?.postDelayed(tick, 10)
        }
    }

    private fun clear() {
        scheduled = false
        handler?.removeCallbacks(tick)
        beats.clear()
        lastBuffer = null
        if (detector != null) detector = BeatDetector(rate, channels)
    }
}
