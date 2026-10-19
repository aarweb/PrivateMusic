package com.aar.privatemusic.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.hilight.HiLightStatus

@Composable
internal fun HiLightSetting(app: PrivateMusicApp) {
    val controller = app.hiLight
    if (!controller.visible) return
    val status by controller.status.collectAsState()
    val enabled by app.settings.hiLightEnabled.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.refresh()
        }
        lifecycle.addObserver(observer)
        controller.refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    HiLightSettingContent(status, enabled, controller::setEnabled, controller::requestAccess,
        controller::refresh, onSetup = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(intent) }
                .onFailure { com.aar.privatemusic.util.Feedback.show("Abre Shizuku y autoriza PrivateMusic.") }
        })
}

@Composable
internal fun HiLightSettingContent(
    status: HiLightStatus,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onAuthorize: () -> Unit,
    onRetry: () -> Unit,
    onSetup: () -> Unit,
) {
    if (status == HiLightStatus.UNSUPPORTED) return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HiLight al reproducir", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when (status) {
                        HiLightStatus.CHECKING -> "Comprobando acceso a las luces…"
                        HiLightStatus.NEEDS_SHIZUKU -> "Inicia Shizuku para permitir el acceso a HiLight."
                        HiLightStatus.NEEDS_PERMISSION -> "Autoriza PrivateMusic en Shizuku para activar las luces."
                        HiLightStatus.DENIED -> "Acceso denegado. Puedes cambiarlo en Shizuku."
                        HiLightStatus.ACTIVE -> "Pulso suave durante la reproducción en este móvil"
                        HiLightStatus.READY -> if (enabled) "Esperando reproducción en este móvil" else "Pulso suave mientras suena música en este móvil"
                        HiLightStatus.ERROR -> "No se pudo controlar HiLight. Comprueba Shizuku y reintenta."
                        else -> "HiLight no está disponible en este dispositivo."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                enabled = enabled || status == HiLightStatus.READY || status == HiLightStatus.ACTIVE,
                onCheckedChange = onEnabled,
                modifier = Modifier.semantics { contentDescription = "HiLight al reproducir" },
            )
        }
        when (status) {
            HiLightStatus.NEEDS_PERMISSION -> TextButton(onClick = onAuthorize) { Text("Autorizar acceso") }
            HiLightStatus.NEEDS_SHIZUKU, HiLightStatus.DENIED -> TextButton(onClick = onSetup) { Text("Configurar Shizuku") }
            HiLightStatus.ERROR, HiLightStatus.UNAVAILABLE -> TextButton(onClick = onRetry) { Text("Volver a comprobar") }
            else -> Unit
        }
    }
}
