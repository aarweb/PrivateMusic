package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.player.EqHolder

@Composable
fun EqScreen(app: PrivateMusicApp) {
    val context = LocalContext.current
    val eq = EqHolder.equalizer
    // Version counter to refresh slider positions after preset changes.
    var version by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Ecualizador", style = MaterialTheme.typography.titleLarge)

        if (eq == null) {
            Text(
                "Reproduce una canción primero para inicializar el motor de audio.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            return
        }

        var enabled by remember { mutableStateOf(eq.enabled) }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Activado", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                EqHolder.setEnabled(context, it)
            })
        }

        // System presets
        val presetCount = eq.numberOfPresets.toInt()
        if (presetCount > 0) {
            Text(
                "Presets",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0 until presetCount).toList()) { p ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            EqHolder.usePreset(context, p.toShort())
                            version++
                        },
                        label = { Text(eq.getPresetName(p.toShort())) },
                    )
                }
            }
        }

        Text(
            "Bandas",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        val range = eq.bandLevelRange
        val minLevel = range[0].toFloat()
        val maxLevel = range[1].toFloat()

        androidx.compose.runtime.key(version) {
            for (band in 0 until eq.numberOfBands) {
                val freq = eq.getCenterFreq(band.toShort()) / 1000
                val label = if (freq >= 1000) "${freq / 1000} kHz" else "$freq Hz"
                var level by remember(band, version) {
                    mutableStateOf(eq.getBandLevel(band.toShort()).toFloat())
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(64.dp),
                    )
                    Slider(
                        value = level,
                        onValueChange = {
                            level = it
                            EqHolder.setBand(context, band, it.toInt().toShort())
                        },
                        valueRange = minLevel..maxLevel,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(level / 100).toInt()} dB",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(48.dp),
                    )
                }
            }
        }
    }
}
