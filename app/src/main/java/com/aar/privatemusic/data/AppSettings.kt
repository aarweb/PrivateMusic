package com.aar.privatemusic.data

import android.content.Context
import android.content.SharedPreferences
import com.aar.privatemusic.player.AudioDeviceProfileCodec
import com.aar.privatemusic.player.AudioDeviceProfiles
import com.aar.privatemusic.player.AudioProfileSettings
import com.aar.privatemusic.player.AudioRouteDescriptor
import com.aar.privatemusic.player.SavedAudioDeviceProfile
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

/** MIX prioriza DSP/fundidos; NATIVE entrega el formato al dispositivo si puede. */
enum class AudioOutputProfile { MIX, NATIVE }

/** App-wide playback preferences; read from both the UI and the playback service. */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _crossfadeSec = MutableStateFlow(prefs.getInt(KEY_CROSSFADE, 0))
    val crossfadeSec: StateFlow<Int> = _crossfadeSec

    private val _activeAudioDeviceProfileLabel = MutableStateFlow(
        prefs.getString(KEY_ACTIVE_AUDIO_DEVICE_PROFILE_LABEL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_AUDIO_DEVICE_PROFILE_LABEL,
    )
    val activeAudioDeviceProfileLabel: StateFlow<String> = _activeAudioDeviceProfileLabel

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun setThemeMode(value: ThemeMode) {
        prefs.edit().putString(KEY_THEME, value.name).apply()
        _themeMode.value = value
    }

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(KEY_KEEP_SCREEN_ON, false))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn

    fun setKeepScreenOn(value: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
        _keepScreenOn.value = value
    }

    private val _normalizeVolume = MutableStateFlow(prefs.getBoolean(KEY_NORMALIZE, false))
    val normalizeVolume: StateFlow<Boolean> = _normalizeVolume

    private val _audioOutputProfile = MutableStateFlow(readAudioOutputProfile(context))
    val audioOutputProfile: StateFlow<AudioOutputProfile> = _audioOutputProfile

    private val _sponsorBlock = MutableStateFlow(prefs.getBoolean(KEY_SPONSORBLOCK, true))
    val sponsorBlock: StateFlow<Boolean> = _sponsorBlock

    private val _autoDownloadVideo = MutableStateFlow(prefs.getBoolean(KEY_AUTO_VIDEO, false))
    /** Bajar solo el videoclip de fondo tras cada descarga. Gasta datos: por defecto off. */
    val autoDownloadVideo: StateFlow<Boolean> = _autoDownloadVideo

    private val _videoOnMetered = MutableStateFlow(prefs.getBoolean(KEY_VIDEO_METERED, false))
    /** Permitir bajar los vídeos también con datos móviles (por defecto solo WiFi). */
    val videoOnMetered: StateFlow<Boolean> = _videoOnMetered

    private val _videoOnlyWhileCharging = MutableStateFlow(
        prefs.getBoolean(KEY_VIDEO_ONLY_WHILE_CHARGING, false)
    )
    /** Limitar las descargas automáticas y masivas de vídeo a cuando el móvil carga. */
    val videoOnlyWhileCharging: StateFlow<Boolean> = _videoOnlyWhileCharging

    private val _canvasQuality = MutableStateFlow(prefs.getInt(KEY_CANVAS_QUALITY, 720))
    /** Altura máxima solicitada a YouTube para los Canvas: 480, 720 o 1080. */
    val canvasQuality: StateFlow<Int> = _canvasQuality

    private val _autoMix = MutableStateFlow(prefs.getBoolean(KEY_AUTOMIX, false))
    val autoMix: StateFlow<Boolean> = _autoMix

    private val _spatialAudioAuto = MutableStateFlow(
        prefs.getBoolean(KEY_SPATIAL_AUDIO_AUTO, true)
    )
    val spatialAudioAuto: StateFlow<Boolean> = _spatialAudioAuto

    private val _stereoSurroundLevel = MutableStateFlow(
        prefs.getInt(KEY_STEREO_SURROUND_LEVEL, 0).coerceIn(0, 3)
    )
    val stereoSurroundLevel: StateFlow<Int> = _stereoSurroundLevel

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
        profiledWrite(prefs) { putInt(KEY_CROSSFADE, value) }
        _crossfadeSec.value = value
    }

    fun setNormalizeVolume(value: Boolean) {
        profiledWrite(prefs) { putBoolean(KEY_NORMALIZE, value) }
        _normalizeVolume.value = value
    }

    fun setAudioOutputProfile(value: AudioOutputProfile) {
        prefs.edit().putString(KEY_AUDIO_OUTPUT_PROFILE, value.name).apply()
        _audioOutputProfile.value = value
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

    fun setVideoOnlyWhileCharging(value: Boolean) {
        prefs.edit().putBoolean(KEY_VIDEO_ONLY_WHILE_CHARGING, value).apply()
        _videoOnlyWhileCharging.value = value
    }

    fun setCanvasQuality(value: Int) {
        val safe = value.takeIf { it in setOf(480, 720, 1080) } ?: 720
        prefs.edit().putInt(KEY_CANVAS_QUALITY, safe).apply()
        _canvasQuality.value = safe
    }

    fun setAutoMix(value: Boolean) {
        profiledWrite(prefs) { putBoolean(KEY_AUTOMIX, value) }
        _autoMix.value = value
    }

    fun setSpatialAudioAuto(value: Boolean) {
        profiledWrite(prefs) { putBoolean(KEY_SPATIAL_AUDIO_AUTO, value) }
        _spatialAudioAuto.value = value
    }

    fun setStereoSurroundLevel(value: Int) {
        val safe = value.coerceIn(0, 3)
        profiledWrite(prefs) {
            putInt(KEY_STEREO_SURROUND_LEVEL, safe)
            if (safe > 0) putInt(KEY_CROSSFEED_LEVEL, 0)
        }
        _stereoSurroundLevel.value = safe
    }

    fun setCrossfeedLevel(value: Int) {
        val safe = value.coerceIn(0, 3)
        profiledWrite(prefs) {
            putInt(KEY_CROSSFEED_LEVEL, safe)
            if (safe > 0) putInt(KEY_STEREO_SURROUND_LEVEL, 0)
        }
        if (safe > 0) _stereoSurroundLevel.value = 0
    }

    fun setAutoMixMaxPct(value: Int) {
        profiledWrite(prefs) { putInt(KEY_AUTOMIX_MAX, value) }
        _autoMixMaxPct.value = value
    }

    fun savedAudioDeviceProfiles(): List<SavedAudioDeviceProfile> = synchronized(PROFILE_LOCK) {
        readAudioDeviceProfiles(prefs)
    }

    fun activeAudioDeviceProfileKey(): String? = synchronized(PROFILE_LOCK) {
        readActiveAudioDeviceProfileKey(prefs)
    }

    fun activateAudioDeviceProfile(route: AudioRouteDescriptor): Boolean =
        synchronized(PROFILE_LOCK) {
            val key = route.key
            val label = route.displayLabel
            val activeKey = readActiveAudioDeviceProfileKey(prefs)
            val originalProfiles = readAudioDeviceProfiles(prefs)
            val currentSettings = snapshotProfileSettings(prefs)
            val now = System.currentTimeMillis()

            if (key == activeKey) {
                val profiles = AudioDeviceProfiles.upsert(
                    originalProfiles,
                    SavedAudioDeviceProfile(key, label, currentSettings, now),
                )
                prefs.edit()
                    .putString(KEY_AUDIO_DEVICE_PROFILES_JSON, AudioDeviceProfileCodec.encode(profiles))
                    .putString(KEY_ACTIVE_AUDIO_DEVICE_PROFILE_KEY, key)
                    .putString(KEY_ACTIVE_AUDIO_DEVICE_PROFILE_LABEL, label)
                    .commit()
                _activeAudioDeviceProfileLabel.value = label
                return@synchronized false
            }

            // La referencia se conserva antes del upsert saliente para que el límite no la expulse.
            val incomingProfile = originalProfiles.firstOrNull { it.key == key }
            var profiles = originalProfiles
            if (activeKey != null) {
                val activeLabel = readActiveAudioDeviceProfileLabel(prefs)
                profiles = AudioDeviceProfiles.upsert(
                    profiles,
                    SavedAudioDeviceProfile(activeKey, activeLabel, currentSettings, now),
                )
            }

            val incomingSettings = incomingProfile?.settings
                ?.let(AudioDeviceProfiles::sanitize)
                ?: currentSettings
            val applySettings = incomingProfile != null && incomingSettings != currentSettings
            profiles = AudioDeviceProfiles.upsert(
                profiles,
                SavedAudioDeviceProfile(key, label, incomingSettings, now),
            )

            prefs.edit().also { editor ->
                if (applySettings) writeProfileSettings(editor, incomingSettings)
                editor
                    .putString(KEY_AUDIO_DEVICE_PROFILES_JSON, AudioDeviceProfileCodec.encode(profiles))
                    .putString(KEY_ACTIVE_AUDIO_DEVICE_PROFILE_KEY, key)
                    .putString(KEY_ACTIVE_AUDIO_DEVICE_PROFILE_LABEL, label)
                    .commit()
            }

            if (applySettings) {
                _crossfadeSec.value = incomingSettings.crossfadeSec
                _normalizeVolume.value = incomingSettings.normalizeVolume
                _autoMix.value = incomingSettings.autoMix
                _autoMixMaxPct.value = incomingSettings.autoMixMaxPct
                _spatialAudioAuto.value = incomingSettings.spatialAudioAuto
                _stereoSurroundLevel.value = incomingSettings.stereoSurroundLevel
            }
            _activeAudioDeviceProfileLabel.value = label
            applySettings
        }

    fun removeAudioDeviceProfile(key: String): Boolean = synchronized(PROFILE_LOCK) {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty() || normalizedKey == readActiveAudioDeviceProfileKey(prefs)) {
            return@synchronized false
        }
        val profiles = readAudioDeviceProfiles(prefs)
        if (profiles.none { it.key == normalizedKey }) return@synchronized false
        val remaining = AudioDeviceProfiles.remove(profiles, normalizedKey)
        prefs.edit()
            .putString(KEY_AUDIO_DEVICE_PROFILES_JSON, AudioDeviceProfileCodec.encode(remaining))
            .apply()
        true
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
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_NORMALIZE = "normalize_volume"
        private const val KEY_AUDIO_OUTPUT_PROFILE = "audio_output_profile"
        private const val KEY_SPONSORBLOCK = "sponsorblock"
        private const val KEY_AUTO_VIDEO = "auto_download_video"
        private const val KEY_VIDEO_METERED = "video_on_metered"
        private const val KEY_VIDEO_ONLY_WHILE_CHARGING = "video_only_while_charging"
        private const val KEY_CANVAS_QUALITY = "canvas_quality"
        private const val KEY_LISTENBRAINZ = "listenbrainz_token"
        private const val KEY_AUTOMIX = "automix"
        internal const val KEY_SPATIAL_AUDIO_AUTO = "spatial_audio_auto"
        private const val KEY_STEREO_SURROUND_LEVEL = "stereo_surround_level"
        private const val KEY_CROSSFEED_LEVEL = "crossfeed_level"
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
        private const val KEY_AUDIO_DEVICE_PROFILES_JSON = "audio_device_profiles_json"
        private const val KEY_ACTIVE_AUDIO_DEVICE_PROFILE_KEY = "active_audio_device_profile_key"
        private const val KEY_ACTIVE_AUDIO_DEVICE_PROFILE_LABEL = "active_audio_device_profile_label"
        private const val DEFAULT_AUDIO_DEVICE_PROFILE_LABEL = "Salida actual"
        private val SYNC_TOKEN_LOCK = Any()
        private val PROFILE_LOCK = Any()

        private fun readAudioDeviceProfiles(prefs: SharedPreferences): List<SavedAudioDeviceProfile> =
            AudioDeviceProfileCodec.decode(prefs.getString(KEY_AUDIO_DEVICE_PROFILES_JSON, null))

        private fun readActiveAudioDeviceProfileKey(prefs: SharedPreferences): String? =
            prefs.getString(KEY_ACTIVE_AUDIO_DEVICE_PROFILE_KEY, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        private fun readActiveAudioDeviceProfileLabel(prefs: SharedPreferences): String =
            prefs.getString(KEY_ACTIVE_AUDIO_DEVICE_PROFILE_LABEL, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_AUDIO_DEVICE_PROFILE_LABEL

        private fun snapshotProfileSettings(prefs: SharedPreferences): AudioProfileSettings =
            AudioDeviceProfiles.sanitize(
                AudioProfileSettings(
                    crossfadeSec = prefs.getInt(KEY_CROSSFADE, 0),
                    normalizeVolume = prefs.getBoolean(KEY_NORMALIZE, false),
                    normalizeMode = prefs.getString("normalize_mode", "rms") ?: "rms",
                    autoMix = prefs.getBoolean(KEY_AUTOMIX, false),
                    autoMixMaxPct = prefs.getInt(KEY_AUTOMIX_MAX, 20),
                    spatialAudioAuto = prefs.getBoolean(KEY_SPATIAL_AUDIO_AUTO, true),
                    stereoSurroundLevel = prefs.getInt(KEY_STEREO_SURROUND_LEVEL, 0),
                    crossfeedLevel = prefs.getInt(KEY_CROSSFEED_LEVEL, 0),
                    eqEnabled = prefs.getBoolean("eq_enabled", false),
                    eqMode = prefs.getString("eq_mode", "graphic") ?: "graphic",
                    eqBands = prefs.getString("eq_bands", null),
                    eqPreamp = prefs.getFloat("eq_param_preamp", 0f),
                    eqFilters = prefs.getString("eq_param_filters", null),
                ),
            )

        private fun writeProfileSettings(
            editor: SharedPreferences.Editor,
            settings: AudioProfileSettings,
        ) {
            editor
                .putInt(KEY_CROSSFADE, settings.crossfadeSec)
                .putBoolean(KEY_NORMALIZE, settings.normalizeVolume)
                .putString("normalize_mode", settings.normalizeMode)
                .putBoolean(KEY_AUTOMIX, settings.autoMix)
                .putInt(KEY_AUTOMIX_MAX, settings.autoMixMaxPct)
                .putBoolean(KEY_SPATIAL_AUDIO_AUTO, settings.spatialAudioAuto)
                .putInt(KEY_STEREO_SURROUND_LEVEL, settings.stereoSurroundLevel)
                .putInt(KEY_CROSSFEED_LEVEL, settings.crossfeedLevel)
                .putBoolean("eq_enabled", settings.eqEnabled)
                .putString("eq_mode", settings.eqMode)
                .putFloat("eq_param_preamp", settings.eqPreamp)
            settings.eqBands?.let { editor.putString("eq_bands", it) }
                ?: editor.remove("eq_bands")
            settings.eqFilters?.let { editor.putString("eq_param_filters", it) }
                ?: editor.remove("eq_param_filters")
        }

        private fun updateActiveProfileLocked(prefs: SharedPreferences) {
            val activeKey = readActiveAudioDeviceProfileKey(prefs) ?: return
            val profile = SavedAudioDeviceProfile(
                key = activeKey,
                label = readActiveAudioDeviceProfileLabel(prefs),
                settings = snapshotProfileSettings(prefs),
                updatedAt = System.currentTimeMillis(),
            )
            val profiles = AudioDeviceProfiles.upsert(readAudioDeviceProfiles(prefs), profile)
            prefs.edit()
                .putString(KEY_AUDIO_DEVICE_PROFILES_JSON, AudioDeviceProfileCodec.encode(profiles))
                .apply()
        }

        private inline fun profiledWrite(
            prefs: SharedPreferences,
            update: SharedPreferences.Editor.() -> Unit,
        ) = synchronized(PROFILE_LOCK) {
            prefs.edit().apply(update).apply()
            updateActiveProfileLocked(prefs)
        }

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

        fun readCanvasQuality(context: Context): Int =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt(KEY_CANVAS_QUALITY, 720)
                .takeIf { it in setOf(480, 720, 1080) } ?: 720

        fun readListenBrainzToken(context: Context): String =
            SecretStore.read(context.getSharedPreferences("settings", Context.MODE_PRIVATE), KEY_LISTENBRAINZ)

        fun readEqEnabled(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("eq_enabled", false)

        fun writeEqEnabled(context: Context, value: Boolean) = profiledWrite(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE),
        ) { putBoolean("eq_enabled", value) }

        fun readEqBands(context: Context): List<Short>? =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("eq_bands", null)
                ?.split(",")?.mapNotNull { it.toShortOrNull() }

        fun writeEqBands(context: Context, levels: List<Short>) = profiledWrite(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE),
        ) { putString("eq_bands", levels.joinToString(",")) }

        /** Static readers used by the playback service without holding an instance. */
        fun readCrossfadeSec(context: Context): Int =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt(KEY_CROSSFADE, 0)

        fun readNormalizeVolume(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY_NORMALIZE, false)

        fun readAudioOutputProfile(context: Context): AudioOutputProfile =
            runCatching {
                AudioOutputProfile.valueOf(
                    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .getString(KEY_AUDIO_OUTPUT_PROFILE, null) ?: "",
                )
            }.getOrDefault(AudioOutputProfile.MIX)

        fun readAutoMix(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY_AUTOMIX, false)

        fun readSpatialAudioAuto(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_SPATIAL_AUDIO_AUTO, true)

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

        fun writeEqMode(context: Context, mode: String) = profiledWrite(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE),
        ) { putString("eq_mode", mode) }

        fun readEqPreamp(context: Context): Float =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getFloat("eq_param_preamp", 0f)

        fun writeEqPreamp(context: Context, value: Float) = profiledWrite(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE),
        ) { putFloat("eq_param_preamp", value) }

        fun readEqFilters(context: Context): String? =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("eq_param_filters", null)

        fun writeEqFilters(context: Context, json: String) = profiledWrite(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE),
        ) { putString("eq_param_filters", json) }

        fun readCrossfeedLevel(context: Context): Int =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt(KEY_CROSSFEED_LEVEL, 0)
                .coerceIn(0, 3)

        fun writeCrossfeedLevel(context: Context, level: Int) = profiledWrite(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE),
        ) {
            val safe = level.coerceIn(0, 3)
            putInt(KEY_CROSSFEED_LEVEL, safe)
            if (safe > 0) putInt(KEY_STEREO_SURROUND_LEVEL, 0)
        }

        fun readStereoSurroundLevel(context: Context): Int =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt(KEY_STEREO_SURROUND_LEVEL, 0)
                .coerceIn(0, 3)

        fun writeStereoSurroundLevel(context: Context, level: Int) = profiledWrite(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE),
        ) {
            val safe = level.coerceIn(0, 3)
            putInt(KEY_STEREO_SURROUND_LEVEL, safe)
            if (safe > 0) putInt(KEY_CROSSFEED_LEVEL, 0)
        }

        /** Modo de normalización: "rms" (medida propia) o "replaygain" (tag). */
        fun readNormalizeMode(context: Context): String =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("normalize_mode", "rms") ?: "rms"

        fun writeNormalizeMode(context: Context, mode: String) = profiledWrite(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE),
        ) { putString("normalize_mode", mode) }

        /** Spotify-style target loudness; louder tracks are attenuated down to it. */
        const val TARGET_LOUDNESS_DB = com.aar.privatemusic.player.PlaybackGain.TARGET_LOUDNESS_DB
    }
}
