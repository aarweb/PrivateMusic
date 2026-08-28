package com.aar.privatemusic.player

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import com.aar.privatemusic.data.AppSettings
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SpatialAudioStatus {
    UNSUPPORTED,
    DISABLED_BY_APP,
    UNAVAILABLE_ROUTE,
    DISABLED_BY_SYSTEM,
    WAITING_FOR_TRACK,
    INCOMPATIBLE_TRACK,
    ACTIVE,
}

data class SpatialAudioState(
    val status: SpatialAudioStatus,
    val headTracking: Boolean = false,
)

data class SpatialAudioInputs(
    val supported: Boolean,
    val enabledByApp: Boolean,
    val routeAvailable: Boolean,
    val enabledBySystem: Boolean,
    val formatKnown: Boolean,
    val canBeSpatialized: Boolean,
    val headTrackerAvailable: Boolean,
)

/** Resolución pura; el orden coincide con el motivo que debe ver el usuario. */
fun resolveSpatialAudioState(input: SpatialAudioInputs): SpatialAudioState {
    val status = when {
        !input.supported -> SpatialAudioStatus.UNSUPPORTED
        !input.enabledByApp -> SpatialAudioStatus.DISABLED_BY_APP
        !input.routeAvailable -> SpatialAudioStatus.UNAVAILABLE_ROUTE
        !input.enabledBySystem -> SpatialAudioStatus.DISABLED_BY_SYSTEM
        !input.formatKnown -> SpatialAudioStatus.WAITING_FOR_TRACK
        !input.canBeSpatialized -> SpatialAudioStatus.INCOMPATIBLE_TRACK
        else -> SpatialAudioStatus.ACTIVE
    }
    return SpatialAudioState(
        status = status,
        headTracking = status == SpatialAudioStatus.ACTIVE && input.headTrackerAvailable,
    )
}

object SpatialAudio {
    private val _state = MutableStateFlow(
        SpatialAudioState(
            if (Build.VERSION.SDK_INT >= 32) {
                SpatialAudioStatus.WAITING_FOR_TRACK
            } else {
                SpatialAudioStatus.UNSUPPORTED
            }
        )
    )
    val state: StateFlow<SpatialAudioState> = _state

    /** Atributos compartidos por los dos reproductores locales. */
    fun musicAudioAttributes(enabled: Boolean): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setSpatializationBehavior(
                if (enabled) C.SPATIALIZATION_BEHAVIOR_AUTO
                else C.SPATIALIZATION_BEHAVIOR_NEVER
            )
            .build()

    internal fun publish(state: SpatialAudioState) {
        _state.value = state
    }
}

/** Vive únicamente en el servicio y no conserva ninguna Activity. */
class SpatialAudioController(
    context: Context,
    private val mainPlayer: ExoPlayer,
    private val tailPlayer: ExoPlayer,
) : SharedPreferences.OnSharedPreferenceChangeListener, AutoCloseable {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var decodedFormat: AudioFormat? = null
    private var closed = false
    private val platform: SpatialAudioPlatform? = if (Build.VERSION.SDK_INT >= 32) {
        runCatching { Api32SpatialAudioPlatform(appContext, ::updateState) }.getOrNull()
    } else {
        null
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
        applyPreference()
    }

    fun onAudioInputFormatChanged(format: Format) {
        runOnMain {
            decodedFormat = decodedPcmFormat(format)
            updateState()
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == AppSettings.KEY_SPATIAL_AUDIO_AUTO) runOnMain(::applyPreference)
    }

    private fun applyPreference() {
        if (closed) return
        val enabled = AppSettings.readSpatialAudioAuto(appContext)
        val attributes = SpatialAudio.musicAudioAttributes(enabled)
        // El foco se gestiona aparte y ambos reproductores deben ser idénticos.
        mainPlayer.setAudioAttributes(attributes, /* handleAudioFocus = */ false)
        tailPlayer.setAudioAttributes(attributes, /* handleAudioFocus = */ false)
        updateState()
    }

    private fun updateState() {
        if (closed) return
        val enabled = AppSettings.readSpatialAudioAuto(appContext)
        val inputs = platform?.snapshot(enabled, decodedFormat) ?: SpatialAudioInputs(
            supported = false,
            enabledByApp = enabled,
            routeAvailable = false,
            enabledBySystem = false,
            formatKnown = decodedFormat != null,
            canBeSpatialized = false,
            headTrackerAvailable = false,
        )
        val state = resolveSpatialAudioState(inputs)
        AudioDsp.setSystemSpatialAudioActive(state.status == SpatialAudioStatus.ACTIVE)
        SpatialAudio.publish(state)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun close() {
        runOnMain {
            if (closed) return@runOnMain
            decodedFormat = null
            updateState()
            closed = true
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            platform?.close()
            AudioDsp.setSystemSpatialAudioActive(false)
        }
    }
}

private interface SpatialAudioPlatform : AutoCloseable {
    fun snapshot(enabledByApp: Boolean, format: AudioFormat?): SpatialAudioInputs
}

@RequiresApi(32)
private class Api32SpatialAudioPlatform(
    context: Context,
    private val onChanged: () -> Unit,
) : SpatialAudioPlatform {

    private val spatializer =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).spatializer
    private val executor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
    private var stateListenerRegistered = false
    private var headTrackerListenerRegistered = false
    private val stateListener = object : Spatializer.OnSpatializerStateChangedListener {
        override fun onSpatializerAvailableChanged(spatializer: Spatializer, available: Boolean) {
            onChanged()
        }

        override fun onSpatializerEnabledChanged(spatializer: Spatializer, enabled: Boolean) {
            onChanged()
        }
    }
    private var headTrackerRegistration: HeadTrackerRegistration? = null

    init {
        runCatching {
            spatializer.addOnSpatializerStateChangedListener(executor, stateListener)
            stateListenerRegistered = true
        }
        if (Build.VERSION.SDK_INT >= 33) {
            headTrackerRegistration = runCatching {
                Api33.registerHeadTracker(spatializer, executor, onChanged)
            }.getOrNull()
            headTrackerListenerRegistered = headTrackerRegistration != null
        }
    }

    override fun snapshot(enabledByApp: Boolean, format: AudioFormat?): SpatialAudioInputs {
        val supported = runCatching {
            spatializer.immersiveAudioLevel != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
        }.getOrDefault(false)
        val available = runCatching { spatializer.isAvailable }.getOrDefault(false)
        val systemEnabled = runCatching { spatializer.isEnabled }.getOrDefault(false)
        val canBeSpatialized = format?.let {
            runCatching {
                spatializer.canBeSpatialized(platformMusicAttributes(enabledByApp), it)
            }.getOrDefault(false)
        } ?: false
        val headTrackerAvailable = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { headTrackerRegistration?.isAvailable() == true }.getOrDefault(false)
        } else {
            false
        }
        return SpatialAudioInputs(
            supported = supported,
            enabledByApp = enabledByApp,
            routeAvailable = available,
            enabledBySystem = systemEnabled,
            formatKnown = format != null,
            canBeSpatialized = canBeSpatialized,
            headTrackerAvailable = headTrackerAvailable,
        )
    }

    override fun close() {
        if (stateListenerRegistered) {
            runCatching { spatializer.removeOnSpatializerStateChangedListener(stateListener) }
            stateListenerRegistered = false
        }
        if (headTrackerListenerRegistered) {
            runCatching { headTrackerRegistration?.close() }
            headTrackerRegistration = null
            headTrackerListenerRegistered = false
        }
    }

    private fun platformMusicAttributes(enabled: Boolean): android.media.AudioAttributes =
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .setSpatializationBehavior(
                if (enabled) android.media.AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO
                else android.media.AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER
            )
            .build()
}

private interface HeadTrackerRegistration : AutoCloseable {
    fun isAvailable(): Boolean
}

@RequiresApi(33)
private object Api33 {
    fun registerHeadTracker(
        spatializer: Spatializer,
        executor: Executor,
        onChanged: () -> Unit,
    ): HeadTrackerRegistration {
        val listener = Spatializer.OnHeadTrackerAvailableListener { _, _ -> onChanged() }
        spatializer.addOnHeadTrackerAvailableListener(executor, listener)
        return object : HeadTrackerRegistration {
            override fun isAvailable(): Boolean = spatializer.isHeadTrackerAvailable

            override fun close() {
                spatializer.removeOnHeadTrackerAvailableListener(listener)
            }
        }
    }
}

private fun decodedPcmFormat(format: Format): AudioFormat? {
    val channelMask = channelMask(format.channelCount) ?: return null
    if (format.sampleRate <= 0) return null
    return runCatching {
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(format.sampleRate)
            .setChannelMask(channelMask)
            .build()
    }.getOrNull()
}

private fun channelMask(channelCount: Int): Int? = when (channelCount) {
    1 -> AudioFormat.CHANNEL_OUT_MONO
    2 -> AudioFormat.CHANNEL_OUT_STEREO
    3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
    4 -> AudioFormat.CHANNEL_OUT_QUAD
    5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
    6 -> AudioFormat.CHANNEL_OUT_5POINT1
    7 -> AudioFormat.CHANNEL_OUT_6POINT1
    8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
    else -> null
}
