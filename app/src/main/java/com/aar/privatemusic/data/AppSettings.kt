package com.aar.privatemusic.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

/**
 * [BLACK] es negro puro, no "muy oscuro": en una pantalla OLED el píxel negro
 * está apagado, así que se nota en la batería y de noche no deslumbra.
 */
enum class ThemeMode(val label: String) {
    SYSTEM("Como el sistema"),
    LIGHT("Claro"),
    DARK("Oscuro"),
    BLACK("Negro puro (OLED)"),
}

/** Cómo se pinta la letra en el reproductor. */
enum class LyricStyle(val label: String) {
    CLASSIC("Clásico"),
    FOCUS("Foco"),
    VIDEOCLIP("Videoclip"),
    MINIMAL("Minimalista"),
}

/** App-wide playback preferences; read from both the UI and the playback service. */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _crossfadeSec = MutableStateFlow(prefs.getInt(KEY_CROSSFADE, 0))
    val crossfadeSec: StateFlow<Int> = _crossfadeSec

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun setThemeMode(value: ThemeMode) {
        prefs.edit().putString(KEY_THEME, value.name).apply()
        _themeMode.value = value
    }

    private val _normalizeVolume = MutableStateFlow(prefs.getBoolean(KEY_NORMALIZE, false))
    val normalizeVolume: StateFlow<Boolean> = _normalizeVolume

    private val _sponsorBlock = MutableStateFlow(prefs.getBoolean(KEY_SPONSORBLOCK, true))
    val sponsorBlock: StateFlow<Boolean> = _sponsorBlock

    private val _autoDownloadVideo = MutableStateFlow(prefs.getBoolean(KEY_AUTO_VIDEO, false))
    /** Bajar solo el videoclip de fondo tras cada descarga. Gasta datos: por defecto off. */
    val autoDownloadVideo: StateFlow<Boolean> = _autoDownloadVideo

    private val _videoOnMetered = MutableStateFlow(prefs.getBoolean(KEY_VIDEO_METERED, false))
    /** Permitir bajar los vídeos también con datos móviles (por defecto solo WiFi). */
    val videoOnMetered: StateFlow<Boolean> = _videoOnMetered

    private val _autoMix = MutableStateFlow(prefs.getBoolean(KEY_AUTOMIX, false))
    val autoMix: StateFlow<Boolean> = _autoMix

    /** Ajuste máximo de tempo del AutoMix, en porcentaje (10, 15 o 20). */
    private val _autoMixMaxPct = MutableStateFlow(prefs.getInt(KEY_AUTOMIX_MAX, 20))
    val autoMixMaxPct: StateFlow<Int> = _autoMixMaxPct

    /** Publicar la biblioteca en la red local para que el PC la lea. */
    private val _shareWithPc = MutableStateFlow(prefs.getBoolean(KEY_SHARE_PC, false))
    val shareWithPc: StateFlow<Boolean> = _shareWithPc

    /** Clave privada que autoriza al reproductor de escritorio en la red local. */
    val syncToken: String
        get() = synchronized(SYNC_TOKEN_LOCK) {
            prefs.getString(KEY_SYNC_TOKEN, null)?.takeIf { it.length == 32 }
                ?: newSyncToken().also { prefs.edit().putString(KEY_SYNC_TOKEN, it).commit() }
        }

    fun regenerateSyncToken(): String = synchronized(SYNC_TOKEN_LOCK) {
        newSyncToken().also { prefs.edit().putString(KEY_SYNC_TOKEN, it).commit() }
    }

    /** Voz del DJ (TTS del sistema). Off por defecto: el DJ funciona con texto. */
    private val _djVoice = MutableStateFlow(prefs.getBoolean(KEY_DJ_VOICE, false))
    val djVoice: StateFlow<Boolean> = _djVoice

    /** Mostrar las letras en otros alfabetos romanizadas (karaoke de K-pop/J-pop). */
    private val _romanizeLyrics = MutableStateFlow(prefs.getBoolean(KEY_ROMANIZE, false))
    val romanizeLyrics: StateFlow<Boolean> = _romanizeLyrics

    fun setRomanizeLyrics(value: Boolean) {
        prefs.edit().putBoolean(KEY_ROMANIZE, value).apply()
        _romanizeLyrics.value = value
    }

    // Fondo animado (por mood/BPM) en el reproductor cuando la canción no tiene vídeo.
    private val _animatedBackground = MutableStateFlow(prefs.getBoolean(KEY_ANIM_BG, true))
    val animatedBackground: StateFlow<Boolean> = _animatedBackground

    fun setAnimatedBackground(value: Boolean) {
        prefs.edit().putBoolean(KEY_ANIM_BG, value).apply()
        _animatedBackground.value = value
    }

    private val _lyricStyle = MutableStateFlow(
        runCatching { LyricStyle.valueOf(prefs.getString(KEY_LYRIC_STYLE, null) ?: "") }
            .getOrDefault(LyricStyle.CLASSIC)
    )
    /** Estilo elegido para ver la letra; se recuerda entre sesiones. */
    val lyricStyle: StateFlow<LyricStyle> = _lyricStyle

    fun setLyricStyle(value: LyricStyle) {
        prefs.edit().putString(KEY_LYRIC_STYLE, value.name).apply()
        _lyricStyle.value = value
    }

    // "" = clientes por defecto de yt-dlp; si no, cadena para player_client.
    private val _youtubeClient = MutableStateFlow(prefs.getString(KEY_YT_CLIENT, "") ?: "")
    val youtubeClient: StateFlow<String> = _youtubeClient

    fun setYoutubeClient(value: String) {
        prefs.edit().putString(KEY_YT_CLIENT, value).apply()
        _youtubeClient.value = value
    }

    // --- Servidor de música propio (Subsonic / Navidrome / Jellyfin) ---
    private val _subsonicUrl = MutableStateFlow(prefs.getString(KEY_SUB_URL, "") ?: "")
    val subsonicUrl: StateFlow<String> = _subsonicUrl
    private val _subsonicUser = MutableStateFlow(prefs.getString(KEY_SUB_USER, "") ?: "")
    val subsonicUser: StateFlow<String> = _subsonicUser
    private val _subsonicPass = MutableStateFlow(SecretStore.read(prefs, KEY_SUB_PASS))
    val subsonicPass: StateFlow<String> = _subsonicPass

    fun setSubsonicServer(url: String, user: String, pass: String) {
        prefs.edit()
            .putString(KEY_SUB_URL, url.trim())
            .putString(KEY_SUB_USER, user.trim())
            .apply()
        SecretStore.write(prefs, KEY_SUB_PASS, pass)
        _subsonicUrl.value = url.trim()
        _subsonicUser.value = user.trim()
        _subsonicPass.value = pass
    }

    /** Config actual del servidor, o null si no está configurado. */
    fun subsonicConfig(): com.aar.privatemusic.downloader.SubsonicConfig? {
        val url = _subsonicUrl.value
        val user = _subsonicUser.value
        return if (url.isNotBlank() && user.isNotBlank())
            com.aar.privatemusic.downloader.SubsonicConfig(url, user, _subsonicPass.value)
        else null
    }

    private val _listenBrainzToken = MutableStateFlow(SecretStore.read(prefs, KEY_LISTENBRAINZ))
    val listenBrainzToken: StateFlow<String> = _listenBrainzToken

    // --- Deezer (descarga directa FLAC/MP3 con la sesión del propio usuario) ---
    private val _deezerArl = MutableStateFlow(SecretStore.read(prefs, KEY_DZ_ARL))
    /** Cookie de sesión de Deezer del usuario; vacío = no autenticado. */
    val deezerArl: StateFlow<String> = _deezerArl

    private val _deezerUser = MutableStateFlow(prefs.getString(KEY_DZ_USER, "") ?: "")
    val deezerUser: StateFlow<String> = _deezerUser

    private val _deezerQuality = MutableStateFlow(prefs.getString(KEY_DZ_QUALITY, "FLAC") ?: "FLAC")

    /** Deezer ya no reconoce el ARL guardado; hay que volver a entrar. */
    private val _deezerArlExpired = MutableStateFlow(prefs.getBoolean(KEY_DZ_EXPIRED, false))
    val deezerArlExpired: StateFlow<Boolean> = _deezerArlExpired

    /** El aviso de Inicio se puede apartar hasta el siguiente arranque. */
    private val _deezerExpiredDismissed = MutableStateFlow(false)
    val deezerExpiredDismissed: StateFlow<Boolean> = _deezerExpiredDismissed
    fun dismissDeezerExpired() { _deezerExpiredDismissed.value = true }

    fun setDeezerArlExpired(expired: Boolean) {
        prefs.edit().putBoolean(KEY_DZ_EXPIRED, expired).apply()
        _deezerArlExpired.value = expired
        if (!expired) _deezerExpiredDismissed.value = false
    }
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

    fun setAutoDownloadVideo(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_VIDEO, value).apply()
        _autoDownloadVideo.value = value
    }

    fun setVideoOnMetered(value: Boolean) {
        prefs.edit().putBoolean(KEY_VIDEO_METERED, value).apply()
        _videoOnMetered.value = value
    }

    fun setAutoMix(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOMIX, value).apply()
        _autoMix.value = value
    }

    fun setAutoMixMaxPct(value: Int) {
        prefs.edit().putInt(KEY_AUTOMIX_MAX, value).apply()
        _autoMixMaxPct.value = value
    }

    fun setShareWithPc(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHARE_PC, value).apply()
        _shareWithPc.value = value
    }

    fun setDjVoice(value: Boolean) {
        prefs.edit().putBoolean(KEY_DJ_VOICE, value).apply()
        _djVoice.value = value
    }

    fun setListenBrainzToken(value: String) {
        SecretStore.write(prefs, KEY_LISTENBRAINZ, value.trim())
        _listenBrainzToken.value = value.trim()
    }

    /** Guarda (o limpia, con arl en blanco) la sesión de Deezer. */
    fun setDeezerSession(arl: String, user: String, country: String, hasFlac: Boolean, hasHq: Boolean) {
        prefs.edit()
            .putString(KEY_DZ_USER, user)
            .putString(KEY_DZ_COUNTRY, country)
            .putBoolean(KEY_DZ_HAS_FLAC, hasFlac)
            .putBoolean(KEY_DZ_HAS_HQ, hasHq)
            .putBoolean(KEY_DZ_EXPIRED, false)
            .apply()
        SecretStore.write(prefs, KEY_DZ_ARL, arl)
        _deezerArl.value = arl
        _deezerUser.value = user
        _deezerArlExpired.value = false
        _deezerExpiredDismissed.value = false
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
        private const val KEY_THEME = "theme_mode"
        private const val KEY_NORMALIZE = "normalize_volume"
        private const val KEY_SPONSORBLOCK = "sponsorblock"
        private const val KEY_AUTO_VIDEO = "auto_download_video"
        private const val KEY_VIDEO_METERED = "video_on_metered"
        private const val KEY_LISTENBRAINZ = "listenbrainz_token"
        private const val KEY_AUTOMIX = "automix"
        private const val KEY_AUTOMIX_MAX = "automix_max_pct"
        private const val KEY_SHARE_PC = "share_with_pc"
        private const val KEY_SYNC_TOKEN = "sync_token"
        private const val KEY_DJ_VOICE = "dj_voice"
        private const val KEY_ROMANIZE = "romanize_lyrics"
        private const val KEY_ANIM_BG = "animated_background"
        private const val KEY_LYRIC_STYLE = "lyric_style"
        private const val KEY_YT_CLIENT = "youtube_client"
        private const val KEY_SUB_URL = "subsonic_url"
        private const val KEY_SUB_USER = "subsonic_user"
        private const val KEY_SUB_PASS = "subsonic_pass"
        private const val KEY_DZ_ARL = "deezer_arl"
        private const val KEY_DZ_USER = "deezer_user"
        private const val KEY_DZ_QUALITY = "deezer_quality"
        private const val KEY_DZ_COUNTRY = "deezer_country"
        private const val KEY_DZ_HAS_FLAC = "deezer_has_flac"
        private const val KEY_DZ_HAS_HQ = "deezer_has_hq"
        private const val KEY_DZ_EXPIRED = "deezer_arl_expired"
        private val SYNC_TOKEN_LOCK = Any()

        private fun newSyncToken(): String = ByteArray(16).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02X".format(it) }

        fun readDeezerArl(context: Context): String =
            SecretStore.read(context.getSharedPreferences("settings", Context.MODE_PRIVATE), KEY_DZ_ARL)

        fun readDeezerQuality(context: Context): String =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(KEY_DZ_QUALITY, "FLAC") ?: "FLAC"

        fun readYoutubeClient(context: Context): String =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(KEY_YT_CLIENT, "") ?: ""

        fun readSponsorBlock(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_SPONSORBLOCK, true)

        fun readAutoDownloadVideo(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_VIDEO, false)

        fun readVideoOnMetered(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_VIDEO_METERED, false)

        fun readListenBrainzToken(context: Context): String =
            SecretStore.read(context.getSharedPreferences("settings", Context.MODE_PRIVATE), KEY_LISTENBRAINZ)

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

        /**
         * Ajuste máximo de tempo del AutoMix, como fracción (0,20 = ±20%).
         *
         * En Android el time-stretch de Sonic conserva el tono, así que se puede
         * estirar bastante más que en el escritorio, donde cambiar la velocidad
         * arrastra el tono y por eso allí el tope sigue siendo del 10%.
         */
        fun readAutoMixMaxStretch(context: Context): Float =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt(KEY_AUTOMIX_MAX, 20).coerceIn(5, 30) / 100f

        // --- EQ paramétrico / DSP propio ---
        fun readEqMode(context: Context): String =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("eq_mode", "graphic") ?: "graphic"

        fun writeEqMode(context: Context, mode: String) =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putString("eq_mode", mode).apply()

        fun readEqPreamp(context: Context): Float =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getFloat("eq_param_preamp", 0f)

        fun writeEqPreamp(context: Context, value: Float) =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putFloat("eq_param_preamp", value).apply()

        fun readEqFilters(context: Context): String? =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("eq_param_filters", null)

        fun writeEqFilters(context: Context, json: String) =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putString("eq_param_filters", json).apply()

        fun readCrossfeedLevel(context: Context): Int =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt("crossfeed_level", 0)

        fun writeCrossfeedLevel(context: Context, level: Int) =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putInt("crossfeed_level", level).apply()

        /** Modo de normalización: "rms" (medida propia) o "replaygain" (tag). */
        fun readNormalizeMode(context: Context): String =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("normalize_mode", "rms") ?: "rms"

        fun writeNormalizeMode(context: Context, mode: String) =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putString("normalize_mode", mode).apply()

        /** Spotify-style target loudness; louder tracks are attenuated down to it. */
        const val TARGET_LOUDNESS_DB = -14f
    }
}
