package com.aar.privatemusic.player

import androidx.media3.common.Format
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Estado observado de Media3, compartido con Ajustes sin retener Player ni Context. */
object NativeAudioState {
    private var input: AudioInputInfo? = null
    private var track: AudioTrackInfo? = null
    private val _state = MutableStateFlow(AudioOutputInfo())
    val state: StateFlow<AudioOutputInfo> = _state

    @Synchronized
    fun onInputFormat(format: Format) {
        input = AudioInputInfo(
            mimeType = format.sampleMimeType,
            channelCount = format.channelCount.takeUnless { it == Format.NO_VALUE },
            sampleRate = format.sampleRate.takeUnless { it == Format.NO_VALUE },
            bitrate = format.bitrate.takeUnless { it == Format.NO_VALUE },
        )
        publish()
    }

    @Synchronized
    fun onAudioTrackInitialized(config: AudioSink.AudioTrackConfig) {
        track = AudioTrackInfo(
            encoding = config.encoding,
            channelConfig = config.channelConfig,
            offload = config.offload,
            pcmEncoding = Util.isEncodingLinearPcm(config.encoding),
        )
        publish()
    }

    @Synchronized
    fun onAudioTrackReleased(config: AudioSink.AudioTrackConfig) {
        if (track?.encoding == config.encoding && track?.offload == config.offload) {
            track = null
            publish()
        }
    }

    @Synchronized
    fun clear() {
        input = null
        track = null
        publish()
    }

    private fun publish() {
        _state.value = AudioOutputClassifier.resolve(input, track)
    }
}
