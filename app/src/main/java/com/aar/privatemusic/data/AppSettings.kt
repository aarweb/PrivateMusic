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

    private val _autoMix = MutableStateFlow(prefs.getBoolean(KEY_AUTOMIX, false))
    val autoMix: StateFlow<Boolean> = _autoMix

    private val _listenBrainzToken = MutableStateFlow(prefs.getString(KEY_LISTENBRAINZ, "") ?: "")
    val listenBrainzToken: StateFlow<String> = _listenBrainzToken

    // --- Deezer (descarga directa FLAC/MP3 con la sesión del propio usuario) ---
    private val _deezerArl = MutableStateFlow(prefs.getString(KEY_DZ_ARL, "") ?: "")
    /** Cookie de sesión de Deezer del usuario; vacío = no autenticado. */
    val deezerArl: StateFlow<String> = _deezerArl

    private val _deezerUser = MutableStateFlow(prefs.getString(KEY_DZ_USER, "") ?: "")
    val deezerUser: StateFlow<String> = _deezerUser

    private val _deezerQuality = MutableStateFlow(prefs.getString(KEY_DZ_QUALITY, "FLAC") ?: "FLAC")
    /** "FLAC" | "MP3_320" | "MP3_128". */
    val deezerQuality: StateFlow<String> = _deezerQuality

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

    fun setAutoMix(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOMIX, value).apply()
        _autoMix.value = value
    }

    fun setListenBrainzToken(value: String) {
        prefs.edit().putString(KEY_LISTENBRAINZ, value.trim()).apply()
        _listenBrainzToken.value = value.trim()
    }

    /** Guarda (o limpia, con arl en blanco) la sesión de Deezer. */
    fun setDeezerSession(arl: String, user: String, country: String, hasFlac: Boolean, hasHq: Boolean) {
        prefs.edit()
            .putString(KEY_DZ_ARL, arl)
            .putString(KEY_DZ_USER, user)
            .putString(KEY_DZ_COUNTRY, country)
            .putBoolean(KEY_DZ_HAS_FLAC, hasFlac)
            .putBoolean(KEY_DZ_HAS_HQ, hasHq)
            .apply()
        _deezerArl.value = arl
        _deezerUser.value = user
        // Al bajar de plan, no dejes seleccionada una calidad que ya no tienes.
        if (!hasFlac && _deezerQuality.value == "FLAC") setDeezerQuality(if (hasHq) "MP3_320" else "MP3_128")
    }

    fun clearDeezerSession() = setDeezerSession("", "", "", hasFlac = false, hasHq = false)

    fun setDeezerQuality(value: String) {
        prefs.edit().putString(KEY_DZ_QUALITY, value).apply()
        _deezerQuality.value = value
    }

    val deezerCountry: String get() = prefs.getString(KEY_DZ_COUNTRY, "") ?: ""
    val deezerHasFlac: Boolean get() = prefs.getBoolean(KEY_DZ_HAS_FLAC, false)
    val deezerHasHq: Boolean get() = prefs.getBoolean(KEY_DZ_HAS_HQ, false)

    companion object {
        private const val KEY_CROSSFADE = "crossfade_sec"
        private const val KEY_NORMALIZE = "normalize_volume"
        private const val KEY_SPONSORBLOCK = "sponsorblock"
        private const val KEY_LISTENBRAINZ = "listenbrainz_token"
        private const val KEY_AUTOMIX = "automix"
        private const val KEY_DZ_ARL = "deezer_arl"
        private const val KEY_DZ_USER = "deezer_user"
        private const val KEY_DZ_QUALITY = "deezer_quality"
        private const val KEY_DZ_COUNTRY = "deezer_country"
        private const val KEY_DZ_HAS_FLAC = "deezer_has_flac"
        private const val KEY_DZ_HAS_HQ = "deezer_has_hq"

        fun readDeezerArl(context: Context): String =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(KEY_DZ_ARL, "") ?: ""

        fun readDeezerQuality(context: Context): String =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(KEY_DZ_QUALITY, "FLAC") ?: "FLAC"

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

        fun readAutoMix(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY_AUTOMIX, false)

        /** Spotify-style target loudness; louder tracks are attenuated down to it. */
        const val TARGET_LOUDNESS_DB = -14f
    }
}
