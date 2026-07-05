package com.aar.privatemusic.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.BackupManager
import com.aar.privatemusic.data.MusicRepository
import com.aar.privatemusic.downloader.SpotifySync
import com.aar.privatemusic.util.AppUpdater
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: PrivateMusicApp, onOpenStats: () -> Unit, onOpenEq: () -> Unit = {}) {
    val crossfade by app.settings.crossfadeSec.collectAsState()
    val normalize by app.settings.normalizeVolume.collectAsState()
    val autoMix by app.settings.autoMix.collectAsState()
    val prefs = androidx.compose.ui.platform.LocalContext.current
        .getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var btAutoplay by remember { mutableStateOf(prefs.getBoolean("bt_autoplay", false)) }
    var btDevicesOpen by remember { mutableStateOf(false) }
    val btPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            btAutoplay = true
            prefs.edit().putBoolean("bt_autoplay", true).apply()
        }
    }
    var scanRequested by remember { mutableStateOf(false) }
    val audioPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scanRequested = true
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var storage by remember { mutableStateOf<MusicRepository.StorageInfo?>(null) }
    var operationResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { storage = app.repository.storageInfo() }
    LaunchedEffect(scanRequested) {
        if (scanRequested) {
            scanRequested = false
            com.aar.privatemusic.util.Feedback.show("Escaneando la música del dispositivo…")
            // App-scoped: navigating away must not cancel the scan.
            val appContext = context.applicationContext
            app.appScope.launch {
                val added = runCatching {
                    app.repository.importLocal(appContext)
                }.onFailure {
                    android.util.Log.e("LocalImporter", "scan failed", it)
                }.getOrDefault(-1)
                com.aar.privatemusic.util.Feedback.show(
                    when {
                        added > 0 -> "$added canciones locales añadidas a la biblioteca"
                        added == 0 -> "No se encontró música nueva en el dispositivo"
                        else -> "Error al escanear el dispositivo"
                    }
                )
            }
        }
    }

    val csvImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            operationResult = "Buscando y descargando canciones del CSV…"
            val count = app.downloader.importCsvAndDownload(context, uri) { name ->
                app.repository.createPlaylist(name)
            }
            operationResult = when {
                count > 0 -> "CSV importado: $count canciones en cola de descarga"
                count == 0 -> "No se encontraron canciones en el CSV"
                else -> "Error al importar el CSV"
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = BackupManager.exportLibraryZip(context, uri, app.repository)
            operationResult = if (ok) "Biblioteca exportada correctamente" else "Error al exportar"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val count = BackupManager.importPlaylist(context, uri, app.repository)
            operationResult = when {
                count > 0 -> "Playlist importada: $count canciones encontradas"
                count == 0 -> "No se encontraron coincidencias en tu biblioteca"
                else -> "Error al importar el archivo"
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.titleLarge)

        // --- Reproducción ---
        Text(
            "Reproducción",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )

        Text("Fundido entre canciones", style = MaterialTheme.typography.bodyLarge)
        Text(
            if (crossfade == 0) "Desactivado"
            else "$crossfade segundos (baja el volumen al final y sube al empezar)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = crossfade.toFloat(),
            onValueChange = { app.settings.setCrossfadeSec(it.toInt()) },
            valueRange = 0f..12f,
            steps = 11,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("AutoMix (igualar BPM)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (crossfade == 0)
                        "Requiere el fundido activado"
                    else "Al mezclar, desliza el tempo de la canción saliente hasta el BPM de la siguiente (sin cambiar el tono)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoMix,
                enabled = crossfade > 0,
                onCheckedChange = { app.settings.setAutoMix(it) },
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Normalizar volumen", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Atenúa las canciones más altas hacia un nivel uniforme (-14 dB)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = normalize, onCheckedChange = { app.settings.setNormalizeVolume(it) })
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Reanudar al conectar Bluetooth", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Al conectar tus auriculares o el coche, sigue la cola (o arranca el Mix de hoy)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = btAutoplay,
                onCheckedChange = { on ->
                    if (on) {
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            btPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            btAutoplay = true
                            prefs.edit().putBoolean("bt_autoplay", true).apply()
                        }
                    } else {
                        btAutoplay = false
                        prefs.edit().putBoolean("bt_autoplay", false).apply()
                    }
                },
            )
        }
        if (btAutoplay) {
            SettingsAction(
                title = "Dispositivos Bluetooth permitidos",
                subtitle = "Vacío = cualquiera. Elige tus auriculares/coche",
            ) { btDevicesOpen = true }
        }

        var karaokeEngine by remember {
            mutableStateOf(prefs.getString("karaoke_engine", "umx") ?: "umx")
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Motor de karaoke de alta calidad", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (karaokeEngine == "mdx")
                        "MDX-Net (67 MB): voces mucho más limpias, tarda más"
                    else "Rápido (36 MB). Activa para usar MDX-Net: mejor separación, más lento",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = karaokeEngine == "mdx",
                onCheckedChange = { on ->
                    karaokeEngine = if (on) "mdx" else "umx"
                    prefs.edit().putString("karaoke_engine", karaokeEngine).apply()
                },
            )
        }
        if (btDevicesOpen) {
            BtDevicesDialog(prefs = prefs, onDismiss = { btDevicesOpen = false })
        }

        SettingsAction(
            title = "Ecualizador",
            subtitle = "Bandas y presets del sistema sobre el motor de audio",
        ) { onOpenEq() }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        // --- Descargas ---
        Text(
            "Descargas",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        val sponsorBlock by app.settings.sponsorBlock.collectAsState()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("SponsorBlock", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Recorta intros, outros y partes sin música al descargar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = sponsorBlock, onCheckedChange = { app.settings.setSponsorBlock(it) })
        }

        SettingsAction(
            title = "Importar CSV de Spotify (buscar y descargar)",
            subtitle = "Busca cada canción en YouTube y la descarga a una playlist nueva",
        ) { csvImportLauncher.launch(arrayOf("*/*")) }

        val watchedSources by app.repository.observeWatchedSources().collectAsState(initial = emptyList())
        if (watchedSources.isNotEmpty()) {
            Text(
                "Fuentes observadas (auto-descarga cada 6 h)",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            watchedSources.forEach { source ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        source.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        scope.launch {
                            SpotifySync.clearSeen(context, source.id)
                            app.repository.unwatchSource(source)
                        }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Dejar de observar")
                    }
                }
            }
        }

        // --- Scrobbling ---
        var tokenDialogOpen by remember { mutableStateOf(false) }
        val lbToken by app.settings.listenBrainzToken.collectAsState()
        SettingsAction(
            title = "Scrobbling a ListenBrainz",
            subtitle = if (lbToken.isBlank()) "Desactivado — toca para configurar tu token"
            else "Activo — enviando tus escuchas",
        ) { tokenDialogOpen = true }

        if (tokenDialogOpen) {
            var token by remember { mutableStateOf(lbToken) }
            AlertDialog(
                onDismissRequest = { tokenDialogOpen = false },
                title = { Text("Token de ListenBrainz") },
                text = {
                    Column {
                        Text(
                            "Consíguelo en listenbrainz.org/settings. Deja vacío para desactivar.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            singleLine = true,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        app.settings.setListenBrainzToken(token)
                        tokenDialogOpen = false
                    }) { Text("Guardar") }
                },
                dismissButton = { TextButton(onClick = { tokenDialogOpen = false }) { Text("Cancelar") } },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        // --- Biblioteca ---
        Text(
            "Biblioteca",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenStats)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Estadísticas", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Tu Replay: minutos, canciones y artistas más escuchados",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }

        storage?.let { info ->
            Column(Modifier.padding(vertical = 12.dp)) {
                Text("Almacenamiento", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${info.songCount} canciones · ${"%.1f".format(info.totalBytes / 1024f / 1024f)} MB " +
                        "(audio + carátulas + letras)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsAction(
            title = "Exportar biblioteca (ZIP)",
            subtitle = "Playlists en M3U + catálogo CSV, a donde tú elijas",
        ) { exportLauncher.launch("privatemusic-export.zip") }

        SettingsAction(
            title = "Importar playlist (M3U/CSV)",
            subtitle = "Crea una playlist emparejando con tu biblioteca",
        ) { importLauncher.launch(arrayOf("*/*")) }

        SettingsAction(
            title = "Escanear música del dispositivo",
            subtitle = "Añade tus MP3/FLAC existentes a la biblioteca (sin copiarlos)",
        ) {
            val perm = if (android.os.Build.VERSION.SDK_INT >= 33)
                android.Manifest.permission.READ_MEDIA_AUDIO
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
            audioPermission.launch(perm)
        }

        SettingsAction(
            title = "Copia de seguridad ahora",
            subtitle = "Guarda la base de datos (se conservan las últimas 5)",
        ) {
            scope.launch {
                val file = BackupManager.backupDatabase(context)
                operationResult = if (file != null) "Copia creada: ${file.name}" else "Error al crear la copia"
            }
        }

        operationResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        SettingsAction(
            title = "Actualizar motor de descarga",
            subtitle = "Fuerza la actualización de yt-dlp (también se hace al arrancar)",
        ) { app.downloader.updateYtDlp() }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        // --- Aplicación ---
        Text(
            "Aplicación",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        var updateInfo by remember { mutableStateOf<AppUpdater.UpdateInfo?>(null) }
        var updateStatus by remember { mutableStateOf<String?>(null) }
        var downloadProgress by remember { mutableStateOf<Int?>(null) }

        SettingsAction(
            title = "Buscar actualizaciones",
            subtitle = downloadProgress?.let { "Descargando… $it%" }
                ?: updateStatus
                ?: "Versión actual ${com.aar.privatemusic.BuildConfig.VERSION_NAME} · desde GitHub Releases",
        ) {
            updateStatus = "Comprobando…"
            scope.launch {
                val info = AppUpdater.check()
                when {
                    info == null -> updateStatus = "No se pudo comprobar (¿sin conexión o sin releases?)"
                    info.isNewer -> {
                        updateInfo = info
                        updateStatus = "Nueva versión ${info.version} disponible"
                    }
                    else -> updateStatus = "Estás al día (${com.aar.privatemusic.BuildConfig.VERSION_NAME})"
                }
            }
        }

        updateInfo?.let { info ->
            AlertDialog(
                onDismissRequest = { updateInfo = null },
                title = { Text("Actualizar a ${info.version}") },
                text = {
                    Column {
                        if (info.notes.isNotBlank()) {
                            Text(info.notes, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Se descargará el APK y Android te pedirá confirmar la instalación.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    val cached = AppUpdater.hasCached(context, info.version)
                    TextButton(onClick = {
                        val url = info.apkUrl
                        val version = info.version
                        updateInfo = null
                        scope.launch {
                            if (cached) {
                                updateStatus = if (AppUpdater.installCached(context))
                                    "Instalador abierto" else "Error al abrir el instalador"
                            } else {
                                downloadProgress = 0
                                val ok = AppUpdater.downloadAndInstall(context, url, version) {
                                    downloadProgress = it
                                }
                                downloadProgress = null
                                updateStatus = if (ok) "Instalador abierto" else "Error al descargar la actualización"
                            }
                        }
                    }) { Text(if (cached) "Instalar (ya descargada)" else "Descargar e instalar") }
                },
                dismissButton = { TextButton(onClick = { updateInfo = null }) { Text("Ahora no") } },
            )
        }

        Text(
            "PrivateMusic ${com.aar.privatemusic.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

@Composable
private fun SettingsAction(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
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
    }
}


@androidx.compose.runtime.Composable
private fun BtDevicesDialog(
    prefs: android.content.SharedPreferences,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bonded = remember {
        runCatching {
            val mgr = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
            mgr?.adapter?.bondedDevices?.map { it.name to it.address } ?: emptyList()
        }.getOrDefault(emptyList())
    }
    val selected = remember {
        androidx.compose.runtime.mutableStateListOf<String>().apply {
            addAll(prefs.getStringSet("bt_autoplay_devices", emptySet()) ?: emptySet())
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dispositivos permitidos") },
        text = {
            Column {
                if (bonded.isEmpty()) {
                    Text("No hay dispositivos emparejados (o falta el permiso de Bluetooth).")
                }
                bonded.forEach { (name, address) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = address in selected,
                            onCheckedChange = { on ->
                                if (on) selected.add(address) else selected.remove(address)
                            },
                        )
                        Text(name ?: address, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                prefs.edit().putStringSet("bt_autoplay_devices", selected.toSet()).apply()
                onDismiss()
            }) { Text("Guardar") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
