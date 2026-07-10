package com.aar.privatemusic.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Properties

/**
 * Preferencias del escritorio, en un `.properties` junto a la biblioteca. No hay
 * `SharedPreferences` aquí y no hace falta nada más: son cuatro claves y las
 * escribe el hilo de la interfaz de una en una.
 *
 * El ARL de Deezer es la sesión del usuario. Se guarda con permisos 600 y nunca
 * se muestra: la interfaz sólo enseña el nombre de la cuenta.
 */
class DesktopSettings(dataDir: File = DesktopStorage.dataDir) {

    private val file = File(dataDir, "settings.properties")
    private val props = Properties().apply {
        if (file.exists()) file.inputStream().use(::load)
    }

    // --- Reproducción ----------------------------------------------------

    /** Segundos de fundido entre canciones. 0 = desactivado. */
    private val _crossfadeSec = MutableStateFlow(props.getProperty(KEY_CROSSFADE, "0").toIntOrNull() ?: 0)
    val crossfadeSec: StateFlow<Int> = _crossfadeSec

    private val _normalizeVolume = MutableStateFlow(props.getProperty(KEY_NORMALIZE, "false").toBoolean())
    val normalizeVolume: StateFlow<Boolean> = _normalizeVolume

    /** Descargar sola la versión nueva y aplicarla al cerrar. */
    private val _autoUpdate = MutableStateFlow(props.getProperty(KEY_AUTO_UPDATE, "true").toBoolean())
    val autoUpdate: StateFlow<Boolean> = _autoUpdate

    /** Iguala el tempo de la saliente al de la entrante durante el fundido. */
    private val _autoMix = MutableStateFlow(props.getProperty(KEY_AUTOMIX, "false").toBoolean())
    val autoMix: StateFlow<Boolean> = _autoMix

    /** Alto de fila de las tablas: "COMPACTA" | "NORMAL" | "GRANDE". */
    private val _rowDensity = MutableStateFlow(props.getProperty(KEY_DENSITY, "NORMAL"))
    val rowDensity: StateFlow<String> = _rowDensity

    fun setRowDensity(value: String) = put(KEY_DENSITY, value) { _rowDensity.value = value }

    private val _eqEnabled = MutableStateFlow(props.getProperty(KEY_EQ_ENABLED, "false").toBoolean())
    val eqEnabled: StateFlow<Boolean> = _eqEnabled

    private val _eqPreamp = MutableStateFlow(props.getProperty(KEY_EQ_PREAMP, "0").toFloatOrNull() ?: 0f)
    val eqPreamp: StateFlow<Float> = _eqPreamp

    /** Amplificación en dB por banda; lista vacía = nunca se ha tocado. */
    private val _eqAmps = MutableStateFlow(
        props.getProperty(KEY_EQ_AMPS, "").split(",").mapNotNull { it.toFloatOrNull() },
    )
    val eqAmps: StateFlow<List<Float>> = _eqAmps

    fun setCrossfadeSec(value: Int) = put(KEY_CROSSFADE, value.toString()) { _crossfadeSec.value = value }

    fun setNormalizeVolume(value: Boolean) = put(KEY_NORMALIZE, value.toString()) { _normalizeVolume.value = value }

    fun setAutoMix(value: Boolean) = put(KEY_AUTOMIX, value.toString()) { _autoMix.value = value }

    fun setAutoUpdate(value: Boolean) = put(KEY_AUTO_UPDATE, value.toString()) { _autoUpdate.value = value }

    fun setEqEnabled(value: Boolean) = put(KEY_EQ_ENABLED, value.toString()) { _eqEnabled.value = value }

    fun setEqPreamp(value: Float) = put(KEY_EQ_PREAMP, value.toString()) { _eqPreamp.value = value }

    fun setEqAmps(value: List<Float>) =
        put(KEY_EQ_AMPS, value.joinToString(",")) { _eqAmps.value = value }

    private inline fun put(key: String, value: String, update: () -> Unit) {
        props.setProperty(key, value)
        update()
        save()
    }

    // --- Deezer ----------------------------------------------------------

    private val _deezerArl = MutableStateFlow(props.getProperty(KEY_ARL, ""))
    val deezerArl: StateFlow<String> = _deezerArl

    private val _deezerUser = MutableStateFlow(props.getProperty(KEY_USER, ""))
    val deezerUser: StateFlow<String> = _deezerUser

    /** "FLAC" | "MP3_320" | "MP3_128" — las mismas etiquetas que en el móvil. */
    private val _deezerQuality = MutableStateFlow(props.getProperty(KEY_QUALITY, "FLAC"))
    val deezerQuality: StateFlow<String> = _deezerQuality

    val deezerHasFlac: Boolean get() = props.getProperty(KEY_HAS_FLAC, "false").toBoolean()
    val deezerHasHq: Boolean get() = props.getProperty(KEY_HAS_HQ, "false").toBoolean()

    fun setDeezerSession(arl: String, user: String, country: String, hasFlac: Boolean, hasHq: Boolean) {
        props.setProperty(KEY_ARL, arl)
        props.setProperty(KEY_USER, user)
        props.setProperty(KEY_COUNTRY, country)
        props.setProperty(KEY_HAS_FLAC, hasFlac.toString())
        props.setProperty(KEY_HAS_HQ, hasHq.toString())
        _deezerArl.value = arl
        _deezerUser.value = user
        // El plan manda: pedir FLAC sin cuenta HiFi devuelve un error críptico.
        if (!hasFlac && _deezerQuality.value == "FLAC") {
            setDeezerQuality(if (hasHq) "MP3_320" else "MP3_128")
        }
        save()
    }

    fun clearDeezerSession() {
        listOf(KEY_ARL, KEY_USER, KEY_COUNTRY, KEY_HAS_FLAC, KEY_HAS_HQ).forEach(props::remove)
        _deezerArl.value = ""
        _deezerUser.value = ""
        save()
    }

    fun setDeezerQuality(value: String) {
        props.setProperty(KEY_QUALITY, value)
        _deezerQuality.value = value
        save()
    }

    private fun save() {
        file.outputStream().use { props.store(it, "PrivateMusic") }
        runCatching {
            val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            java.nio.file.Files.setPosixFilePermissions(file.toPath(), perms)
        }
    }

    private companion object {
        const val KEY_CROSSFADE = "crossfade_sec"
        const val KEY_NORMALIZE = "normalize_volume"
        const val KEY_AUTOMIX = "automix"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_DENSITY = "row_density"
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_EQ_PREAMP = "eq_preamp"
        const val KEY_EQ_AMPS = "eq_amps"
        const val KEY_ARL = "deezer_arl"
        const val KEY_USER = "deezer_user"
        const val KEY_COUNTRY = "deezer_country"
        const val KEY_QUALITY = "deezer_quality"
        const val KEY_HAS_FLAC = "deezer_has_flac"
        const val KEY_HAS_HQ = "deezer_has_hq"
    }
}
