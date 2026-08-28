package com.aar.privatemusic.player

/** Snapshot portable de la pista elegida por el reproductor. */
data class AudioInputInfo(
    val mimeType: String?,
    val channelCount: Int?,
    val sampleRate: Int?,
    val bitrate: Int?,
)

/** Snapshot portable del AudioTrack que Android ha abierto realmente. */
data class AudioTrackInfo(
    val encoding: Int,
    val channelConfig: Int,
    val offload: Boolean,
    val pcmEncoding: Boolean,
)

enum class AudioOutputKind { UNKNOWN, PCM, PASSTHROUGH, OFFLOAD }

data class AudioOutputInfo(
    val input: AudioInputInfo? = null,
    val track: AudioTrackInfo? = null,
    val outputKind: AudioOutputKind = AudioOutputKind.UNKNOWN,
    val atmos: Boolean = false,
)

/** Clasifica hechos observados; nunca deduce Atmos de AC-4, canales o marketing. */
object AudioOutputClassifier {
    const val E_AC3_JOC_MIME = "audio/eac3-joc"

    fun resolve(input: AudioInputInfo?, track: AudioTrackInfo?): AudioOutputInfo =
        AudioOutputInfo(
            input = input,
            track = track,
            outputKind = when {
                track == null -> AudioOutputKind.UNKNOWN
                track.encoding <= 0 -> AudioOutputKind.UNKNOWN
                track.offload -> AudioOutputKind.OFFLOAD
                track.pcmEncoding -> AudioOutputKind.PCM
                else -> AudioOutputKind.PASSTHROUGH
            },
            atmos = input?.mimeType == E_AC3_JOC_MIME,
        )
}
