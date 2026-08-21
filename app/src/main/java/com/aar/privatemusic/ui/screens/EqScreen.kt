package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.AppSettings
import com.aar.privatemusic.dsp.AutoEq
import com.aar.privatemusic.dsp.EqFilter
import com.aar.privatemusic.dsp.EqFilterType
import com.aar.privatemusic.player.AudioDsp
import com.aar.privatemusic.player.EqHolder

@Composable
fun EqScreen(app: PrivateMusicApp) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AppSettings.readEqMode(context)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text("Ecualizador", style = MaterialTheme.typography.titleLarge)

        // Selector de modo: gráfico (sistema) o paramétrico (DSP propio). Sólo uno
        // activo a la vez para no encadenar dos EQ.
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == "graphic",
                onClick = {
                    mode = "graphic"
                    AppSettings.writeEqMode(context, "graphic")
                    AudioDsp.reload(context) // paramétrico -> passthrough
                },
                label = { Text("Gráfico") },
            )
            FilterChip(
                selected = mode == "parametric",
                onClick = {
                    mode = "parametric"
                    AppSettings.writeEqMode(context, "parametric")
                    EqHolder.setEnabled(context, false) // apaga el del sistema
                    AudioDsp.reload(context)
                },
                label = { Text("Paramétrico") },
            )
        }

        if (mode == "parametric") ParametricEqSection(app) else GraphicEqSection(app)

        CrossfeedSection(app)
    }
}

@Composable
private fun GraphicEqSection(app: PrivateMusicApp) {
    val context = LocalContext.current
    val eq = EqHolder.equalizer
    var version by remember { mutableIntStateOf(0) }

    if (eq == null) {
        Text(
            "Reproduce una canción primero para inicializar el motor de audio.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        return
    }

    var enabled by remember { mutableStateOf(eq.enabled) }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Activado", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = { enabled = it; EqHolder.setEnabled(context, it) })
    }

    val presetCount = eq.numberOfPresets.toInt()
    if (presetCount > 0) {
        Text("Presets", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items((0 until presetCount).toList()) { p ->
                FilterChip(selected = false, onClick = { EqHolder.usePreset(context, p.toShort()); version++ }, label = { Text(eq.getPresetName(p.toShort())) })
            }
        }
    }

    Text("Bandas", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp))
    val range = eq.bandLevelRange
    val minLevel = range[0].toFloat()
    val maxLevel = range[1].toFloat()
    androidx.compose.runtime.key(version) {
        for (band in 0 until eq.numberOfBands) {
            val freq = eq.getCenterFreq(band.toShort()) / 1000
            val label = if (freq >= 1000) "${freq / 1000} kHz" else "$freq Hz"
            var level by remember(band, version) { mutableStateOf(eq.getBandLevel(band.toShort()).toFloat()) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                Slider(value = level, onValueChange = { level = it; EqHolder.setBand(context, band, it.toInt().toShort()) }, valueRange = minLevel..maxLevel, modifier = Modifier.weight(1f))
                Text("${(level / 100).toInt()} dB", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
            }
        }
    }
}

@Composable
private fun ParametricEqSection(app: PrivateMusicApp) {
    val context = LocalContext.current
    val stored = remember { AppSettings.readEqFilters(context) }
    val filters = remember { mutableStateListOf<EqFilter>().apply { addAll(AudioDsp.decodeFilters(stored)) } }
    var preamp by remember { mutableStateOf(AudioDsp.decodePreamp(stored)) }
    var importOpen by remember { mutableStateOf(false) }

    fun persist() {
        AppSettings.writeEqPreamp(context, preamp)
        AppSettings.writeEqFilters(context, AudioDsp.encodeFilters(preamp, filters.toList()))
        AudioDsp.reload(context)
    }

    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Preamp", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        Slider(value = preamp, onValueChange = { preamp = it }, onValueChangeFinished = { persist() }, valueRange = -12f..12f, modifier = Modifier.weight(1f))
        Text("${"%+.1f".format(preamp)} dB", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { importOpen = true }) { Text("Importar AutoEQ") }
        Button(onClick = { filters.add(EqFilter(EqFilterType.PK, 1000.0, 0.0, 1.0)); persist() }) {
            Icon(Icons.Filled.Add, contentDescription = "Añadir banda"); Text("Banda")
        }
    }

    if (filters.isEmpty()) {
        Text("Sin bandas. Añade una o importa un perfil AutoEQ de tus auriculares.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }

    filters.forEachIndexed { i, f ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = true,
                    onClick = {
                        val next = when (f.type) { EqFilterType.PK -> EqFilterType.LS; EqFilterType.LS -> EqFilterType.HS; EqFilterType.HS -> EqFilterType.PK }
                        filters[i] = f.copy(type = next); persist()
                    },
                    label = { Text(when (f.type) { EqFilterType.PK -> "Pico"; EqFilterType.LS -> "Graves"; EqFilterType.HS -> "Agudos" }) },
                )
                OutlinedTextField(
                    value = f.freqHz.toInt().toString(),
                    onValueChange = { v -> v.toDoubleOrNull()?.let { filters[i] = f.copy(freqHz = it.coerceIn(20.0, 20000.0)) } },
                    label = { Text("Hz") }, singleLine = true,
                    modifier = Modifier.width(96.dp).padding(start = 8.dp),
                )
                OutlinedTextField(
                    value = "%.2f".format(f.q),
                    onValueChange = { v -> v.replace(',', '.').toDoubleOrNull()?.let { filters[i] = f.copy(q = it.coerceIn(0.1, 10.0)) } },
                    label = { Text("Q") }, singleLine = true,
                    modifier = Modifier.width(84.dp).padding(start = 8.dp),
                )
                IconButton(onClick = { filters.removeAt(i); persist() }) { Icon(Icons.Filled.Delete, contentDescription = "Quitar") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(value = f.gainDb.toFloat(), onValueChange = { filters[i] = f.copy(gainDb = it.toDouble()) }, onValueChangeFinished = { persist() }, valueRange = -15f..15f, modifier = Modifier.weight(1f))
                Text("${"%+.1f".format(f.gainDb)} dB", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
            }
        }
    }

    if (importOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importOpen = false },
            title = { Text("Importar perfil AutoEQ") },
            text = {
                Column {
                    Text("Pega el contenido de un ParametricEQ.txt de AutoEQ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 4)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val profile = AutoEq.parse(text)
                    if (profile.filters.isNotEmpty()) {
                        filters.clear(); filters.addAll(profile.filters)
                        preamp = profile.preampDb.toFloat().coerceIn(-12f, 12f)
                        persist()
                    }
                    importOpen = false
                }) { Text("Importar") }
            },
            dismissButton = { TextButton(onClick = { importOpen = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun CrossfeedSection(app: PrivateMusicApp) {
    val context = LocalContext.current
    var level by remember { mutableIntStateOf(AppSettings.readCrossfeedLevel(context)) }
    Text("Crossfeed (auriculares)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    Text("Suaviza el estéreo para que no suene metido en la cabeza.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "Off", 1 to "Suave", 2 to "Medio", 3 to "Fuerte").forEach { (lvl, label) ->
            FilterChip(
                selected = level == lvl,
                onClick = { level = lvl; AppSettings.writeCrossfeedLevel(context, lvl); AudioDsp.reload(context) },
                label = { Text(label) },
            )
        }
    }
}
