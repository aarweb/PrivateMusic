package com.aar.privatemusic.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.util.Feedback

/** Modo correr: cadencia manual o del podómetro, y la cola se elige por BPM. */
@Composable
fun RunningDialog(app: PrivateMusicApp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val running = app.runningMode
    val state by running.state.collectAsState()
    var spm by remember { mutableFloatStateOf((state?.targetSpm ?: 170).toFloat()) }
    var useSensor by remember { mutableStateOf(state?.useSensor ?: running.hasStepSensor) }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACTIVITY_RECOGNITION,
        ) == PackageManager.PERMISSION_GRANTED

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            useSensor = false
            Feedback.show("Sin permiso de actividad física, la cadencia se fija a mano")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correr") },
        text = {
            Column {
                Text(
                    "Canciones cuyo BPM cuadra con tus pasos (también a medio o doble tiempo), " +
                        "con el tempo ajustado hasta un 6 % para clavar la cadencia sin cambiar el tono.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Cadencia: ${spm.toInt()} pasos/min",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Slider(
                    value = spm,
                    onValueChange = { spm = it },
                    valueRange = 140f..200f,
                    steps = 59,
                    onValueChangeFinished = { if (state != null) running.setTarget(spm.toInt()) },
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Seguir al podómetro", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (!running.hasStepSensor) "Este móvil no tiene podómetro"
                            else state?.measuredSpm?.let { "Midiendo: $it pasos/min" } ?: "Corrige la cadencia con tus pasos reales",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = useSensor,
                        enabled = running.hasStepSensor,
                        onCheckedChange = { on ->
                            useSensor = on
                            if (on && !hasPermission()) permission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        },
                    )
                }
                state?.let { s ->
                    Text(
                        buildString {
                            append("En marcha: ${s.queueSize} canciones")
                            s.songBpm?.let { append(" · ${it.toInt()} BPM × ${"%.2f".format(s.speed)}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                running.start(spm.toInt(), useSensor && hasPermission())
                onDismiss()
            }) { Text(if (state == null) "Empezar" else "Rehacer la cola") }
        },
        dismissButton = {
            if (state != null) {
                TextButton(onClick = { running.stop(); onDismiss() }) { Text("Parar") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}
