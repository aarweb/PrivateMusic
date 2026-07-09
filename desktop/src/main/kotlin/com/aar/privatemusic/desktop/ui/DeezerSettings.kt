package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.desktop.DesktopSettings
import com.aar.privatemusic.downloader.DeezerDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sesión de Deezer en el PC. En el móvil el ARL se saca de un WebView; aquí no
 * hay navegador embebido, así que se pega a mano. Se valida contra Deezer antes
 * de guardarlo: un ARL caducado fallaría luego, en mitad de una descarga.
 *
 * El ARL es la sesión completa de la cuenta. Nunca se muestra una vez guardado.
 */
@Composable
fun DeezerSettings(settings: DesktopSettings) {
    val arl by settings.deezerArl.collectAsState()
    val user by settings.deezerUser.collectAsState()
    val quality by settings.deezerQuality.collectAsState()
    var dialogOpen by remember { mutableStateOf(false) }

    Text("Deezer", Modifier.padding(top = 28.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)

    if (arl.isBlank()) {
        Text(
            "Inicia sesión para descargar en FLAC o MP3 320 con tu propia cuenta.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button({ dialogOpen = true }, Modifier.padding(top = 12.dp)) { Text("Iniciar sesión en Deezer") }
    } else {
        InfoRow("Cuenta", user.ifBlank { "Deezer" })
        val options = buildList {
            if (settings.deezerHasFlac) add("FLAC" to "FLAC (sin pérdida)")
            if (settings.deezerHasHq) add("MP3_320" to "MP3 320 kbps")
            add("MP3_128" to "MP3 128 kbps")
        }
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(quality == value) { settings.setDeezerQuality(value) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(quality == value, onClick = { settings.setDeezerQuality(value) })
                Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        TextButton({ settings.clearDeezerSession() }, Modifier.padding(top = 8.dp)) { Text("Cerrar sesión") }
    }

    if (dialogOpen) {
        DeezerLoginDialog(settings, onDismiss = { dialogOpen = false })
    }
}

@Composable
private fun DeezerLoginDialog(settings: DesktopSettings, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text("Iniciar sesión en Deezer") },
        text = {
            Column {
                Text(
                    "Abre deezer.com en tu navegador, entra en tu cuenta y copia el valor " +
                        "de la cookie «arl» (Herramientas de desarrollo → Aplicación → Cookies). " +
                        "Es la sesión de tu cuenta: no la compartas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    modifier = Modifier.padding(top = 16.dp),
                    label = { Text("Cookie arl") },
                    singleLine = true,
                    enabled = !checking,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                )
                error?.let {
                    Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = value.isNotBlank() && !checking,
                onClick = {
                    checking = true
                    error = null
                    scope.launch {
                        val info = withContext(Dispatchers.IO) {
                            DeezerDownloader.fetchUserInfo(value.trim())
                        }
                        checking = false
                        if (info == null) {
                            error = "Ese ARL no vale (¿caducado, o mal copiado?)"
                        } else {
                            settings.setDeezerSession(
                                value.trim(), info.name, info.country, info.hasFlac, info.hasHq,
                            )
                            onDismiss()
                        }
                    }
                },
            ) {
                if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Comprobar y guardar")
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !checking) { Text("Cancelar") } },
    )
}
