package com.aar.privatemusic.player

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioDeviceProfilesTest {
    @Test
    fun `builtin tiene clave constante y etiqueta legible`() {
        val route = AudioRouteDescriptor(AudioRouteKind.BUILTIN, "  ", "ignorada")

        assertEquals(AudioDeviceProfiles.BUILTIN_KEY, route.key)
        assertEquals("Altavoz del dispositivo", route.displayLabel)
    }

    @Test
    fun `claves priorizan address y son estables ante mayusculas y espacios`() {
        val first = AudioRouteDescriptor(AudioRouteKind.BLUETOOTH, "Coche", " AA:BB:CC ")
        val renamed = AudioRouteDescriptor(AudioRouteKind.BLUETOOTH, "Otro nombre", "aa:bb:cc")

        assertEquals(first.key, renamed.key)
        assertEquals("audio-route:bluetooth:${sha256("aa:bb:cc")}", first.key)
        assertEquals(64, first.key.substringAfterLast(':').length)
    }

    @Test
    fun `fallback usa kind y label normalizada sin filtrar datos privados`() {
        val route = AudioRouteDescriptor(AudioRouteKind.USB, "  DAC   Salón  ")
        val equivalent = AudioRouteDescriptor(AudioRouteKind.USB, "dac salón")
        val otherKind = AudioRouteDescriptor(AudioRouteKind.WIRED, "dac salón")

        assertEquals(route.key, equivalent.key)
        assertNotEquals(route.key, otherKind.key)
        assertFalse(route.key.contains("DAC", ignoreCase = true))
        assertFalse(route.key.contains("Salón", ignoreCase = true))
        assertFalse(route.key.contains("aa:bb", ignoreCase = true))
    }

    @Test
    fun `cada kind sin nombre produce clave privada y etiqueta fallback`() {
        AudioRouteKind.entries.filterNot { it == AudioRouteKind.BUILTIN }.forEach { kind ->
            val route = AudioRouteDescriptor(kind, " \t")
            assertTrue(route.key.startsWith("audio-route:${kind.name.lowercase()}"))
            assertFalse(route.key.endsWith("unknown"))
            assertTrue(route.displayLabel.isNotBlank())
        }
    }

    @Test
    fun `sanitiza rangos modos opcionales y efectos excluyentes`() {
        val sanitized = AudioDeviceProfiles.sanitize(
            AudioProfileSettings(
                crossfadeSec = 99,
                normalizeMode = " DESCONOCIDO ",
                autoMixMaxPct = -3,
                stereoSurroundLevel = 7,
                crossfeedLevel = 3,
                eqMode = " PARAMETRIC ",
                eqBands = "  ",
                eqPreamp = 20f,
                eqFilters = "  filtro  ",
            ),
        )

        assertEquals(12, sanitized.crossfadeSec)
        assertEquals("rms", sanitized.normalizeMode)
        assertEquals(5, sanitized.autoMixMaxPct)
        assertEquals(3, sanitized.stereoSurroundLevel)
        assertEquals(0, sanitized.crossfeedLevel)
        assertEquals("parametric", sanitized.eqMode)
        assertNull(sanitized.eqBands)
        assertEquals(12f, sanitized.eqPreamp)
        assertEquals("filtro", sanitized.eqFilters)

        val lower = AudioDeviceProfiles.sanitize(
            AudioProfileSettings(crossfadeSec = -1, autoMixMaxPct = 99, eqPreamp = Float.NaN),
        )
        assertEquals(0, lower.crossfadeSec)
        assertEquals(30, lower.autoMixMaxPct)
        assertEquals(0f, lower.eqPreamp)
    }

    @Test
    fun `roundtrip conserva caracteres especiales y todos los ajustes`() {
        val profile = profile(
            key = "route:\"uno\\dos\n三",
            label = "Coche \"principal\" \\ 🚗\n",
            updatedAt = 42,
            settings = AudioProfileSettings(
                crossfadeSec = 11,
                normalizeVolume = true,
                normalizeMode = "replaygain",
                autoMix = true,
                autoMixMaxPct = 27,
                spatialAudioAuto = false,
                stereoSurroundLevel = 0,
                crossfeedLevel = 2,
                eqEnabled = true,
                eqMode = "parametric",
                eqBands = "[1,2,\"ñ\"]",
                eqPreamp = -3.5f,
                eqFilters = "{\"tipo\":\"cálido\"}",
            ),
        )

        assertEquals(listOf(profile.copy(label = profile.label.trim())), AudioDeviceProfileCodec.decode(AudioDeviceProfileCodec.encode(listOf(profile))))
    }

    @Test
    fun `decode no lanza con json corrupto tipos extra o entradas invalidas`() {
        assertEquals(emptyList(), AudioDeviceProfileCodec.decode("{roto"))
        assertEquals(emptyList(), AudioDeviceProfileCodec.decode("{}"))
        assertEquals(emptyList(), AudioDeviceProfileCodec.decode("[null, 1, true, \"x\"]"))

        val json = """[
          {"key":"", "label":"Sin clave", "updatedAt":1},
          {"key":"ok", "label":" ", "updatedAt":2},
          {"key":" válido ", "label":" Perfil ", "updatedAt":3,
           "settings":{"crossfadeSec":999,"normalizeMode":"mal","eqBands":" "}}
        ]"""
        val decoded = AudioDeviceProfileCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("válido", decoded.single().key)
        assertEquals("Perfil", decoded.single().label)
        assertEquals(12, decoded.single().settings.crossfadeSec)
        assertNull(decoded.single().settings.eqBands)
    }

    @Test
    fun `canonical deduplica por clave gana mas reciente y ordena descendente`() {
        val result = AudioDeviceProfiles.canonical(
            listOf(
                profile("a", "Viejo", 1),
                profile("b", "Segundo", 5),
                profile("a", "Nuevo", 9),
                profile("c", "Tercero", 5),
                profile(" ", "Inválido", 100),
            ),
        )

        assertEquals(listOf("a", "b", "c"), result.map { it.key })
        assertEquals("Nuevo", result.first().label)
    }

    @Test
    fun `empate de updatedAt conserva la ultima version`() {
        val result = AudioDeviceProfiles.canonical(
            listOf(profile("a", "Primero", 7), profile("a", "Último", 7)),
        )

        assertEquals("Último", result.single().label)
    }

    @Test
    fun `upsert reemplaza remove elimina y ambos mantienen forma canonica`() {
        val initial = listOf(profile("a", "A", 1), profile("b", "B", 2))
        val updated = AudioDeviceProfiles.upsert(initial, profile("a", "A nuevo", 4))

        assertEquals(listOf("a", "b"), updated.map { it.key })
        assertEquals("A nuevo", updated.first().label)
        assertEquals(listOf("a"), AudioDeviceProfiles.remove(updated, " b ").map { it.key })
        assertEquals(updated, AudioDeviceProfiles.remove(updated, "no-existe"))
    }

    @Test
    fun `limita a los 32 perfiles mas recientes tambien al codificar`() {
        val profiles = (0L..39L).map { profile("key-$it", "Perfil $it", it) }
        val canonical = AudioDeviceProfiles.canonical(profiles)
        val decoded = AudioDeviceProfileCodec.decode(AudioDeviceProfileCodec.encode(profiles))

        assertEquals(32, canonical.size)
        assertEquals((39L downTo 8L).toList(), canonical.map { it.updatedAt })
        assertEquals(canonical, decoded)
    }

    @Test
    fun `decode aplica defaults a settings ausentes`() {
        val decoded = AudioDeviceProfileCodec.decode(
            """[{"key":"x","label":"X","updatedAt":1,"settings":{}}]""",
        ).single()

        assertEquals(AudioProfileSettings(), decoded.settings)
    }

    private fun profile(
        key: String,
        label: String,
        updatedAt: Long,
        settings: AudioProfileSettings = AudioProfileSettings(),
    ) = SavedAudioDeviceProfile(key, label, settings, updatedAt)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
