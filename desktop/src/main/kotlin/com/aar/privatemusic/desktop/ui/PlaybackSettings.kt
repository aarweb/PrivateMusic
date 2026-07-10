package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.desktop.DesktopSettings
import com.aar.privatemusic.desktop.audio.AudioEngine

/**
 * Los ajustes de sonido, los mismos que en el móvil: fundido, AutoMix,
 * normalización y ecualizador. El motor los aplica en cuanto se tocan; nada
 * espera a la siguiente canción salvo el fundido, que sólo tiene sentido al
 * final de la que suena.
 */
@Composable
fun PlaybackSettings(settings: DesktopSettings, engine: AudioEngine) {
    val crossfade by settings.crossfadeSec.collectAsState()
    val autoMix by settings.autoMix.collectAsState()
    val normalize by settings.normalizeVolume.collectAsState()

    Text("Reproducción", Modifier.padding(top = 28.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)

    Text("Fundido entre canciones", style = MaterialTheme.typography.bodyLarge)
    Text(
        if (crossfade == 0) "Desactivado" else "$crossfade segundos",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = crossfade.toFloat(),
        onValueChange = { settings.setCrossfadeSec(it.toInt()) },
        valueRange = 0f..12f,
        steps = 11,
        modifier = Modifier.width(320.dp),
    )

    SettingSwitch(
        title = "AutoMix (igualar BPM)",
        subtitle = if (crossfade == 0) "Requiere el fundido activado"
        else "Lleva el tempo de la que sale al de la que entra",
        checked = autoMix,
        enabled = crossfade > 0,
        onCheckedChange = settings::setAutoMix,
    )

    SettingSwitch(
        title = "Normalizar volumen",
        subtitle = "Iguala la sonoridad de todas las canciones a -14 dB",
        checked = normalize,
        onCheckedChange = settings::setNormalizeVolume,
    )

    EqualizerSettings(settings, engine)
}

/** Apariencia: por ahora sólo el alto de fila de las tablas. */
@Composable
fun AppearanceSettings(settings: DesktopSettings) {
    val current by settings.rowDensity.collectAsState()
    Text("Apariencia", Modifier.padding(top = 28.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
    Text("Alto de las filas", style = MaterialTheme.typography.bodyLarge)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RowDensity.entries.forEach { density ->
            FilterChip(
                selected = current == density.name,
                onClick = { settings.setRowDensity(density.name) },
                label = { Text(density.label) },
            )
        }
    }
}

@Composable
internal fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked, onCheckedChange, enabled = enabled)
    }
}

/**
 * El ecualizador de libVLC: diez bandas y los preajustes que trae de fábrica.
 * Las amplificaciones se guardan en dB, que es como las quiere libVLC y como
 * las entiende cualquiera que haya visto un ecualizador antes.
 */
@Composable
private fun EqualizerSettings(settings: DesktopSettings, engine: AudioEngine) {
    val enabled by settings.eqEnabled.collectAsState()
    val savedAmps by settings.eqAmps.collectAsState()
    val preamp by settings.eqPreamp.collectAsState()
    val bands = remember { engine.equalizerBands }
    val presets = remember { engine.equalizerPresets }
    var preset by remember { mutableStateOf<String?>(null) }

    // Una lista guardada de otra versión de libVLC podría no cuadrar con las
    // bandas de ésta: se rellena o se recorta antes de tocar nada.
    val amps = remember(savedAmps, bands) {
        FloatArray(bands.size) { savedAmps.getOrElse(it) { 0f } }
    }

    fun apply(newAmps: FloatArray, newPreamp: Float = preamp, on: Boolean = enabled) {
        engine.setEqualizer(on, newPreamp, newAmps)
        settings.setEqAmps(newAmps.toList())
        settings.setEqPreamp(newPreamp)
    }

    Text("Ecualizador", Modifier.padding(top = 28.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Activado", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(enabled, {
            settings.setEqEnabled(it)
            engine.setEqualizer(it, preamp, amps)
        })
    }

    if (!enabled) return

    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { name ->
            FilterChip(
                selected = preset == name,
                onClick = {
                    preset = name
                    engine.presetAmps(name)?.let { apply(it) }
                },
                label = { Text(name, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }

    // Bandas en horizontal, una por fila. Los deslizadores verticales de los
    // ecualizadores de siempre no existen en Material 3, y girar uno a mano deja
    // el área táctil donde no está el dibujo.
    BandRow("Pre", preamp, -20f..20f) { value ->
        preset = null
        apply(amps, newPreamp = value)
    }
    bands.forEachIndexed { index, hz ->
        BandRow(formatHz(hz), amps.getOrElse(index) { 0f }, -20f..20f) { value ->
            preset = null
            apply(amps.copyOf().also { it[index] = value })
        }
    }

    TextButton({
        preset = null
        apply(FloatArray(bands.size), newPreamp = 0f)
    }, Modifier.padding(top = 8.dp)) { Text("Restablecer") }
}

@Composable
private fun BandRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.width(48.dp),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
        )
        Slider(value, onChange, valueRange = range, modifier = Modifier.width(280.dp).padding(horizontal = 12.dp))
        Text(
            "%+.0f dB".format(value),
            Modifier.width(56.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatHz(hz: Float): String =
    if (hz >= 1000f) "${(hz / 1000f).toInt()}k" else hz.toInt().toString()
