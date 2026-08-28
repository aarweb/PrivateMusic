package com.aar.privatemusic.player

import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class AudioRouteKind {
    BUILTIN,
    BLUETOOTH,
    WIRED,
    USB,
    HEARING_AID,
    OTHER,
}

data class AudioRouteDescriptor(
    val kind: AudioRouteKind,
    val label: String,
    val address: String? = null,
) {
    /** Identificador estable que nunca contiene el nombre ni la dirección en claro. */
    val key: String
        get() = AudioDeviceProfiles.routeKey(this)

    val displayLabel: String
        get() = label.trim().takeIf { it.isNotEmpty() } ?: when (kind) {
            AudioRouteKind.BUILTIN -> "Altavoz del dispositivo"
            AudioRouteKind.BLUETOOTH -> "Dispositivo Bluetooth"
            AudioRouteKind.WIRED -> "Auriculares con cable"
            AudioRouteKind.USB -> "Dispositivo USB"
            AudioRouteKind.HEARING_AID -> "Audífono"
            AudioRouteKind.OTHER -> "Salida de audio"
        }
}

data class AudioProfileSettings(
    val crossfadeSec: Int = 0,
    val normalizeVolume: Boolean = false,
    val normalizeMode: String = "rms",
    val autoMix: Boolean = false,
    val autoMixMaxPct: Int = 20,
    val spatialAudioAuto: Boolean = true,
    val stereoSurroundLevel: Int = 0,
    val crossfeedLevel: Int = 0,
    val eqEnabled: Boolean = false,
    val eqMode: String = "graphic",
    val eqBands: String? = null,
    val eqPreamp: Float = 0f,
    val eqFilters: String? = null,
)

data class SavedAudioDeviceProfile(
    val key: String,
    val label: String,
    val settings: AudioProfileSettings,
    val updatedAt: Long,
)

/** Reglas puras para identificar, validar y mantener perfiles por salida de audio. */
object AudioDeviceProfiles {
    const val BUILTIN_KEY = "audio-route:builtin"
    const val MAX_PROFILES = 32

    fun routeKey(route: AudioRouteDescriptor): String {
        if (route.kind == AudioRouteKind.BUILTIN) return BUILTIN_KEY
        val identity = route.address.cleanIdentity()
            ?: normalizeLabel(route.label).takeIf { it.isNotEmpty() }
            ?: "unknown"
        return "audio-route:${route.kind.name.lowercase(Locale.ROOT)}:${sha256(identity)}"
    }

    fun sanitize(settings: AudioProfileSettings): AudioProfileSettings {
        val surround = settings.stereoSurroundLevel.coerceIn(0, 3)
        return settings.copy(
            crossfadeSec = settings.crossfadeSec.coerceIn(0, 12),
            normalizeMode = settings.normalizeMode.choiceOr("rms", "rms", "replaygain"),
            autoMixMaxPct = settings.autoMixMaxPct.coerceIn(5, 30),
            stereoSurroundLevel = surround,
            crossfeedLevel = if (surround > 0) 0 else settings.crossfeedLevel.coerceIn(0, 3),
            eqMode = settings.eqMode.choiceOr("graphic", "graphic", "parametric"),
            eqBands = settings.eqBands.cleanOptional(),
            eqPreamp = settings.eqPreamp.takeIf(Float::isFinite)?.coerceIn(-12f, 12f) ?: 0f,
            eqFilters = settings.eqFilters.cleanOptional(),
        )
    }

    fun sanitize(profile: SavedAudioDeviceProfile): SavedAudioDeviceProfile? {
        val key = profile.key.trim()
        val label = profile.label.trim()
        if (key.isEmpty() || label.isEmpty()) return null
        return profile.copy(key = key, label = label, settings = sanitize(profile.settings))
    }

    fun canonical(profiles: Iterable<SavedAudioDeviceProfile>): List<SavedAudioDeviceProfile> {
        val byKey = LinkedHashMap<String, SavedAudioDeviceProfile>()
        profiles.forEach { raw ->
            val profile = sanitize(raw) ?: return@forEach
            val previous = byKey[profile.key]
            if (previous == null || profile.updatedAt >= previous.updatedAt) byKey[profile.key] = profile
        }
        return byKey.values
            .sortedWith(compareByDescending<SavedAudioDeviceProfile> { it.updatedAt }.thenBy { it.key })
            .take(MAX_PROFILES)
    }

    fun upsert(
        profiles: Iterable<SavedAudioDeviceProfile>,
        profile: SavedAudioDeviceProfile,
    ): List<SavedAudioDeviceProfile> = canonical(profiles + profile)

    fun remove(
        profiles: Iterable<SavedAudioDeviceProfile>,
        key: String,
    ): List<SavedAudioDeviceProfile> = canonical(profiles.filterNot { it.key == key.trim() })

    private fun String?.cleanIdentity(): String? = this?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    private fun normalizeLabel(value: String): String = value.trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.choiceOr(fallback: String, vararg allowed: String): String {
        val normalized = trim().lowercase(Locale.ROOT)
        return normalized.takeIf { it in allowed } ?: fallback
    }

    private fun String?.cleanOptional(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

}

/** JSON tolerante para persistir la colección sin propagar errores de datos externos. */
object AudioDeviceProfileCodec {
    fun encode(profiles: Iterable<SavedAudioDeviceProfile>): String = runCatching {
        JSONArray().apply {
            AudioDeviceProfiles.canonical(profiles).forEach { put(it.toJson()) }
        }.toString()
    }.getOrDefault("[]")

    fun decode(value: String?): List<SavedAudioDeviceProfile> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toProfile()?.let(::add)
                }
            }.let(AudioDeviceProfiles::canonical)
        }.getOrDefault(emptyList())
    }

    private fun SavedAudioDeviceProfile.toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("label", label)
        .put("updatedAt", updatedAt)
        .put("settings", settings.toJson())

    private fun AudioProfileSettings.toJson(): JSONObject = JSONObject()
        .put("crossfadeSec", crossfadeSec)
        .put("normalizeVolume", normalizeVolume)
        .put("normalizeMode", normalizeMode)
        .put("autoMix", autoMix)
        .put("autoMixMaxPct", autoMixMaxPct)
        .put("spatialAudioAuto", spatialAudioAuto)
        .put("stereoSurroundLevel", stereoSurroundLevel)
        .put("crossfeedLevel", crossfeedLevel)
        .put("eqEnabled", eqEnabled)
        .put("eqMode", eqMode)
        .put("eqBands", eqBands ?: JSONObject.NULL)
        .put("eqPreamp", eqPreamp.toDouble())
        .put("eqFilters", eqFilters ?: JSONObject.NULL)

    private fun JSONObject.toProfile(): SavedAudioDeviceProfile? = runCatching {
        val key = optString("key", "")
        val label = optString("label", "")
        val settingsJson = optJSONObject("settings") ?: JSONObject()
        SavedAudioDeviceProfile(
            key = key,
            label = label,
            settings = settingsJson.toSettings(),
            updatedAt = optLong("updatedAt", 0L),
        )
    }.getOrNull()

    private fun JSONObject.toSettings(): AudioProfileSettings = AudioDeviceProfiles.sanitize(
        AudioProfileSettings(
            crossfadeSec = optInt("crossfadeSec", 0),
            normalizeVolume = optBoolean("normalizeVolume", false),
            normalizeMode = optString("normalizeMode", "rms"),
            autoMix = optBoolean("autoMix", false),
            autoMixMaxPct = optInt("autoMixMaxPct", 20),
            spatialAudioAuto = optBoolean("spatialAudioAuto", true),
            stereoSurroundLevel = optInt("stereoSurroundLevel", 0),
            crossfeedLevel = optInt("crossfeedLevel", 0),
            eqEnabled = optBoolean("eqEnabled", false),
            eqMode = optString("eqMode", "graphic"),
            eqBands = optNullableString("eqBands"),
            eqPreamp = optDouble("eqPreamp", 0.0).toFloat(),
            eqFilters = optNullableString("eqFilters"),
        ),
    )

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key, null)
}
