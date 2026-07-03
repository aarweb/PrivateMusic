package com.aar.privatemusic.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide playback preferences; read from both the UI and the playback service. */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _crossfadeSec = MutableStateFlow(prefs.getInt(KEY_CROSSFADE, 0))
    val crossfadeSec: StateFlow<Int> = _crossfadeSec

    private val _normalizeVolume = MutableStateFlow(prefs.getBoolean(KEY_NORMALIZE, false))
    val normalizeVolume: StateFlow<Boolean> = _normalizeVolume

    private val _sponsorBlock = MutableStateFlow(prefs.getBoolean(KEY_SPONSORBLOCK, true))
    val sponsorBlock: StateFlow<Boolean> = _sponsorBlock

    private val _listenBrainzToken = MutableStateFlow(prefs.getString(KEY_LISTENBRAINZ, "") ?: "")
    val listenBrainzToken: StateFlow<String> = _listenBrainzToken

    fun setCrossfadeSec(value: Int) {
        prefs.edit().putInt(KEY_CROSSFADE, value).apply()
        _crossfadeSec.value = value
    }

    fun setNormalizeVolume(value: Boolean) {
        prefs.edit().putBoolean(KEY_NORMALIZE, value).apply()
        _normalizeVolume.value = value
    }

    fun setSponsorBlock(value: Boolean) {
        prefs.edit().putBoolean(KEY_SPONSORBLOCK, value).apply()
        _sponsorBlock.value = value
    }

    fun setListenBrainzToken(value: String) {
        prefs.edit().putString(KEY_LISTENBRAINZ, value.trim()).apply()
        _listenBrainzToken.value = value.trim()
    }

    companion object {
        private const val KEY_CROSSFADE = "crossfade_sec"
        private const val KEY_NORMALIZE = "normalize_volume"
        private const val KEY_SPONSORBLOCK = "sponsorblock"
        private const val KEY_LISTENBRAINZ = "listenbrainz_token"

        fun readSponsorBlock(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_SPONSORBLOCK, true)

        fun readListenBrainzToken(context: Context): String =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(KEY_LISTENBRAINZ, "") ?: ""

        fun readEqEnabled(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("eq_enabled", false)

        fun writeEqEnabled(context: Context, value: Boolean) =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putBoolean("eq_enabled", value).apply()

        fun readEqBands(context: Context): List<Short>? =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("eq_bands", null)
                ?.split(",")?.mapNotNull { it.toShortOrNull() }

        fun writeEqBands(context: Context, levels: List<Short>) =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putString("eq_bands", levels.joinToString(",")).apply()

        /** Static readers used by the playback service without holding an instance. */
        fun readCrossfadeSec(context: Context): Int =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt(KEY_CROSSFADE, 0)

        fun readNormalizeVolume(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY_NORMALIZE, false)

        /** Spotify-style target loudness; louder tracks are attenuated down to it. */
        const val TARGET_LOUDNESS_DB = -14f
    }
}
