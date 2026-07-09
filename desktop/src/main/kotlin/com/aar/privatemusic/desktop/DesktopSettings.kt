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
        const val KEY_ARL = "deezer_arl"
        const val KEY_USER = "deezer_user"
        const val KEY_COUNTRY = "deezer_country"
        const val KEY_QUALITY = "deezer_quality"
        const val KEY_HAS_FLAC = "deezer_has_flac"
        const val KEY_HAS_HQ = "deezer_has_hq"
    }
}
