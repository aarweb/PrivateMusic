package com.aar.privatemusic.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.FilterChip
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
import com.aar.privatemusic.data.FullBackup
import com.aar.privatemusic.data.HistoryImport
import com.aar.privatemusic.data.MusicRepository
import com.aar.privatemusic.downloader.SpotifySync
import com.aar.privatemusic.util.AppUpdater
import com.aar.privatemusic.util.UpdateGate
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: PrivateMusicApp, onOpenStats: () -> Unit, onOpenEq: () -> Unit = {}) {
    val crossfade by app.settings.crossfadeSec.collectAsState()
    val normalize by app.settings.normalizeVolume.collectAsState()
    val autoMix by app.settings.autoMix.collectAsState()
    val shareWithPc by app.settings.shareWithPc.collectAsState()
    val shareAddress by app.libraryShare.address.collectAsState()
    var syncToken by remember { mutableStateOf(app.settings.syncToken) }
    var regenerateSyncToken by remember { mutableStateOf(false) }
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
    var deleteAllVideosOpen by remember { mutableStateOf(false) }
    var normMode by remember { mutableStateOf(com.aar.privatemusic.data.AppSettings.readNormalizeMode(context)) }
    // Duplicate finder: null = closed, non-null (possibly empty) = dialog open.
    var dupGroups by remember { mutableStateOf<List<List<com.aar.privatemusic.data.db.Song>>?>(null) }
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

    val fullExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) FullBackup.export(context, uri, app.appScope)
    }
    // Restaurar sustituye la biblioteca: se confirma antes de empezar.
    var restoreCandidate by remember { mutableStateOf<android.net.Uri?>(null) }
    val fullImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) restoreCandidate = uri }
    val backupProgress by FullBackup.progress.collectAsState()
    val backupOutcome by FullBackup.outcome.collectAsState()
    val historyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) HistoryImport.start(context, uri, app.musicDao, app.appScope) }
    val historyProgress by HistoryImport.progress.collectAsState()
    val historyOutcome by HistoryImport.outcome.collectAsState()
    historyOutcome?.let { msg ->
        LaunchedEffect(msg) {
            operationResult = msg
            HistoryImport.clearOutcome()
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
            val r = BackupManager.importPlaylist(context, uri, app.repository)
            operationResult = when {
                r == null -> "Error al importar el archivo"
                r.smartImported == 1 -> "Importada 1 playlist inteligente"
                r.smartImported > 1 -> "Importadas ${r.smartImported} playlists inteligentes"
                r.smartImported == 0 && r.matched == 0 && r.added == 0 && r.playlistName == "Playlists inteligentes" ->
                    "Esas playlists inteligentes ya estaban"
                r.matched == 0 -> "No se encontraron coincidencias en tu biblioteca"
                r.merged && r.added == 0 -> "\"${r.playlistName}\" ya tenía esas ${r.matched} canciones"
                r.merged -> "Añadidas ${r.added} a \"${r.playlistName}\" (${r.matched - r.added} ya estaban)"
                else -> "Playlist \"${r.playlistName}\" importada: ${r.added} canciones"
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

        if (autoMix && crossfade > 0) {
            val autoMixMax by app.settings.autoMixMaxPct.collectAsState()
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 8.dp)) {
                Text(
                    "Ajuste máximo de tempo: ±$autoMixMax%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Cuánto puede estirarse la canción saliente para cuadrar con la " +
                        "siguiente. Más margen cuadra más parejas; pasado el 20% el " +
                        "time-stretch empieza a notarse.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 4.dp)) {
                    listOf(10, 15, 20).forEach { pct ->
                        androidx.compose.material3.FilterChip(
                            selected = autoMixMax == pct,
                            onClick = { app.settings.setAutoMixMaxPct(pct) },
                            label = { Text("±$pct%") },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
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

        val djVoice by app.settings.djVoice.collectAsState()
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Voz del DJ", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Cuando el DJ está en directo, presenta cada bloque en voz alta " +
                        "(voz del sistema, sin conexión). Si está apagado, solo texto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = djVoice, onCheckedChange = { app.settings.setDjVoice(it) })
        }

        val animatedBg by app.settings.animatedBackground.collectAsState()
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Fondo animado del reproductor", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Cuando la canción no tiene vídeo, anima el fondo con su color y su ritmo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = animatedBg, onCheckedChange = { app.settings.setAnimatedBackground(it) })
        }

        if (normalize) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Fuente:", style = MaterialTheme.typography.bodySmall)
                FilterChip(
                    selected = normMode == "rms",
                    onClick = { normMode = "rms"; com.aar.privatemusic.data.AppSettings.writeNormalizeMode(context, "rms") },
                    label = { Text("RMS medido") },
                )
                FilterChip(
                    selected = normMode == "replaygain",
                    onClick = { normMode = "replaygain"; com.aar.privatemusic.data.AppSettings.writeNormalizeMode(context, "replaygain") },
                    label = { Text("ReplayGain (tag)") },
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Compartir con el PC", style = MaterialTheme.typography.bodyLarge)
                Text(
                    shareAddress?.let { "Escuchando en $it" }
                        ?: "Publica la biblioteca en la red local para el reproductor de escritorio",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = shareWithPc, onCheckedChange = { app.settings.setShareWithPc(it) })
        }
        if (shareWithPc) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Clave de enlace", style = MaterialTheme.typography.labelMedium)
                    Text(
                        syncToken.chunked(4).joinToString("-"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = { regenerateSyncToken = true }) { Text("Regenerar") }
            }
        }

        if (regenerateSyncToken) {
            AlertDialog(
                onDismissRequest = { regenerateSyncToken = false },
                title = { Text("¿Regenerar la clave?") },
                text = { Text("El PC dejará de sincronizar hasta que copies allí la clave nueva.") },
                confirmButton = {
                    TextButton(onClick = {
                        regenerateSyncToken = false
                        app.libraryShare.stop()
                        syncToken = app.settings.regenerateSyncToken()
                        if (shareWithPc) app.libraryShare.start()
                    }) { Text("Regenerar") }
                },
                dismissButton = {
                    TextButton(onClick = { regenerateSyncToken = false }) { Text("Cancelar") }
                },
            )
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

        val autoVideo by app.settings.autoDownloadVideo.collectAsState()
        val videoMetered by app.settings.videoOnMetered.collectAsState()
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Descargar vídeo de fondo automáticamente", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Baja el videoclip de YouTube de cada canción para el fondo del reproductor",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoVideo, onCheckedChange = { app.settings.setAutoDownloadVideo(it) })
        }
        if (autoVideo) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Usar también datos móviles", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Por defecto solo con WiFi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = videoMetered, onCheckedChange = { app.settings.setVideoOnMetered(it) })
            }
            val fillProgress by app.videoAuto.fillProgress.collectAsState()
            SettingsAction(
                title = if (fillProgress != null)
                    "Bajando vídeos: ${fillProgress!!.done}/${fillProgress!!.total}"
                else "Descargar los vídeos que faltan",
                subtitle = "Busca en YouTube el vídeo de las canciones que aún no lo tienen",
            ) {
                if (fillProgress == null) app.videoAuto.fillMissing { got, total ->
                    com.aar.privatemusic.util.Feedback.show(
                        when {
                            got < 0 -> "Necesita WiFi (o activa los datos móviles arriba)"
                            total == 0 -> "Todas las canciones ya tienen vídeo o se intentó"
                            else -> "Vídeos descargados: $got de $total"
                        }
                    )
                }
            }
        }
        SettingsAction(
            title = "Borrar todos los vídeos descargados",
            subtitle = "Libera espacio y permite volver a generar todos los Canvas con el selector nuevo",
        ) { deleteAllVideosOpen = true }

        if (deleteAllVideosOpen) {
            AlertDialog(
                onDismissRequest = { deleteAllVideosOpen = false },
                title = { Text("¿Borrar todos los vídeos?") },
                text = {
                    Text(
                        "Se borrarán los Canvas descargados o añadidos, los intentos anteriores y " +
                            "cualquier descarga de vídeos en curso. Las canciones y carátulas no se tocarán."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteAllVideosOpen = false
                        app.appScope.launch {
                            val count = app.videoAuto.deleteAllVideos()
                            com.aar.privatemusic.util.Feedback.show(
                                if (count == 1) "1 vídeo borrado" else "$count vídeos borrados",
                            )
                        }
                    }) { Text("Borrar vídeos") }
                },
                dismissButton = {
                    TextButton(onClick = { deleteAllVideosOpen = false }) { Text("Cancelar") }
                },
            )
        }

        YoutubeClientSetting(app)

        SettingsAction(
            title = "Importar CSV de Spotify (buscar y descargar)",
            subtitle = "Busca cada canción en YouTube y la descarga a una playlist nueva",
        ) { csvImportLauncher.launch(arrayOf("*/*")) }

        DeezerSettings(app)

        SubsonicSettings(app)

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
            subtitle = "M3U con rutas relativas, catálogo CSV y las reglas inteligentes",
        ) { exportLauncher.launch("privatemusic-export.zip") }

        SettingsAction(
            title = "Importar playlist o reglas",
            subtitle = "M3U, CSV o smart-playlists.json; si la playlist ya existe, se fusiona",
        ) { importLauncher.launch(arrayOf("*/*")) }

        SettingsAction(
            title = "Importar historial de escucha",
            subtitle = historyProgress ?: "Spotify (StreamingHistory*.json), Last.fm (CSV) o YouTube Takeout " +
                "(watch-history.json): tu Recap y tus playlists automáticas con memoria desde el primer día",
        ) { if (historyProgress == null) historyLauncher.launch(arrayOf("*/*")) }

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
            title = "Buscar duplicados",
            subtitle = "Encuentra canciones repetidas (mismo título y artista) y déjalas en una sola",
        ) {
            scope.launch { dupGroups = app.repository.duplicateGroups() }
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

        SettingsAction(
            title = "Escribir ReplayGain en los archivos",
            subtitle = "Guarda el volumen normalizado como tag REPLAYGAIN_TRACK_GAIN (FLAC/OGG/Opus)",
        ) {
            operationResult = "Escribiendo ReplayGain…"
            app.appScope.launch {
                val n = app.repository.writeReplayGainToFiles()
                com.aar.privatemusic.util.Feedback.show(
                    if (n > 0) "ReplayGain escrito en $n archivos" else "No se pudo escribir en ningún archivo",
                )
            }
        }

        val sizeHint = storage?.let { " · ~${"%.0f".format(it.totalBytes / 1024f / 1024f)} MB" } ?: ""
        SettingsAction(
            title = "Copia completa para cambiar de móvil",
            subtitle = "ZIP con todas las canciones, carátulas, letras, playlists, historial y ajustes " +
                "(no incluye contraseñas ni sesiones)$sizeHint",
        ) {
            if (backupProgress == null) {
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                fullExportLauncher.launch("privatemusic-completo-$stamp.zip")
            }
        }
        SettingsAction(
            title = "Restaurar copia completa",
            subtitle = "Desde un ZIP de copia completa o un music-*.db; sustituye la biblioteca de este móvil y reinicia la app",
        ) {
            if (backupProgress == null) fullImportLauncher.launch(arrayOf("*/*"))
        }

        backupProgress?.let { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(p.phase, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                val f = p.fraction
                if (f != null) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text(
                        "${"%.0f".format(p.done / 1024f / 1024f)} / ${"%.0f".format(p.total / 1024f / 1024f)} MB" +
                            (if (p.files > 0) " · ${p.files} archivos" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }

        restoreCandidate?.let { uri ->
            AlertDialog(
                onDismissRequest = { restoreCandidate = null },
                title = { Text("¿Restaurar la copia completa?") },
                text = {
                    Text(
                        "La biblioteca, las playlists, el historial y los ajustes de este móvil se " +
                            "sustituirán por los de la copia. Las canciones se copian a su sitio; " +
                            "al terminar, la app se reiniciará."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        restoreCandidate = null
                        FullBackup.import(context, uri, app.appScope)
                    }) { Text("Restaurar") }
                },
                dismissButton = { TextButton(onClick = { restoreCandidate = null }) { Text("Cancelar") } },
            )
        }

        when (val o = backupOutcome) {
            null -> Unit
            is FullBackup.Outcome.Restored -> AlertDialog(
                onDismissRequest = {},
                title = { Text("Copia restaurada") },
                text = {
                    Text(
                        (if (o.files > 0) {
                            "${o.songs} canciones y ${o.files} archivos (${"%.0f".format(o.bytes / 1024f / 1024f)} MB) listos."
                        } else {
                            "Biblioteca con ${o.songs} canciones lista."
                        }) + " La app se reiniciará para cargarla; si no se vuelve a abrir sola, ábrela tú."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        FullBackup.clearOutcome()
                        FullBackup.restartApp(context)
                    }) { Text("Reiniciar") }
                },
            )
            is FullBackup.Outcome.Exported -> LaunchedEffect(o) {
                operationResult = "Copia completa creada: ${o.files} archivos, ${"%.0f".format(o.bytes / 1024f / 1024f)} MB"
                FullBackup.clearOutcome()
            }
            is FullBackup.Outcome.Failed -> LaunchedEffect(o) {
                operationResult = o.message
                FullBackup.clearOutcome()
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

        dupGroups?.let { groups ->
            val totalExtra = groups.sumOf { it.size - 1 }
            AlertDialog(
                onDismissRequest = { dupGroups = null },
                title = { Text(if (totalExtra == 0) "Sin duplicados" else "$totalExtra duplicados") },
                text = {
                    if (totalExtra == 0) {
                        Text("No hay canciones repetidas en tu biblioteca.")
                    } else {
                        Column {
                            Text(
                                "Se conservará la mejor calidad de cada una y se eliminarán las copias:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            groups.take(12).forEach { g ->
                                Text(
                                    "• ${g.first().title} · ${g.first().artist}  ×${g.size}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            if (groups.size > 12) {
                                Text(
                                    "…y ${groups.size - 12} más",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (totalExtra == 0) {
                        TextButton(onClick = { dupGroups = null }) { Text("Cerrar") }
                    } else {
                        TextButton(onClick = {
                            dupGroups = null
                            scope.launch {
                                val n = app.repository.removeDuplicates()
                                storage = app.repository.storageInfo()
                                operationResult = "$n duplicados eliminados"
                            }
                        }) { Text("Eliminar $totalExtra", color = MaterialTheme.colorScheme.error) }
                    }
                },
                dismissButton = {
                    if (totalExtra > 0) {
                        TextButton(onClick = { dupGroups = null }) { Text("Cancelar") }
                    }
                },
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

        val themeMode by app.settings.themeMode.collectAsState()
        Text("Tema", style = MaterialTheme.typography.bodyLarge)
        Text(
            "El acento sigue saliendo del color del sistema",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
            com.aar.privatemusic.data.ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { app.settings.setThemeMode(mode) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = themeMode == mode,
                        onClick = { app.settings.setThemeMode(mode) },
                    )
                    Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        var updateInfo by remember { mutableStateOf<AppUpdater.UpdateInfo?>(null) }
        var updateStatus by remember { mutableStateOf<String?>(null) }
        var downloadProgress by remember { mutableStateOf<Int?>(null) }

        var autoUpdate by remember { mutableStateOf(UpdateGate.autoUpdate(context)) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Actualizar automáticamente", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Al abrir la app, descarga la versión nueva si hay Wi-Fi. Instalarla sigue " +
                        "pidiendo tu confirmación: Android no deja hacerlo solo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoUpdate,
                onCheckedChange = { UpdateGate.setAutoUpdate(context, it); autoUpdate = it },
            )
        }

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

/**
 * Cliente de YouTube (avanzado): desde 2026 los clientes por defecto de yt-dlp
 * (`web`, `android`, `ios`) exigen un PO token y fallan sin él. Elegir una
 * cadena de reserva (`android_vr`, `web_embedded`, `tv`) evita bastantes fallos.
 */
@Composable
private fun YoutubeClientSetting(app: PrivateMusicApp) {
    val current by app.settings.youtubeClient.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    // etiqueta visible -> valor de player_client ("" = por defecto de yt-dlp)
    val options = listOf(
        "Automático (por defecto)" to "",
        "Recomendado (VR + embebido + TV)" to "default,android_vr,web_embedded,tv",
        "Sólo TV" to "tv",
        "Sólo web embebido" to "web_embedded",
    )
    val currentLabel = options.firstOrNull { it.second == current }?.first ?: "Personalizado"

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Cliente de YouTube (avanzado)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    currentLabel + " · si las descargas fallan, prueba otro",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, value) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { app.settings.setYoutubeClient(value); expanded = false },
                )
            }
        }
    }
}

/** Sección de Deezer: login por WebView, estado del plan y calidad de descarga. */
@Composable
private fun DeezerSettings(app: PrivateMusicApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val arl by app.settings.deezerArl.collectAsState()
    val user by app.settings.deezerUser.collectAsState()
    val quality by app.settings.deezerQuality.collectAsState()
    var loginOpen by remember { mutableStateOf(false) }
    var arlOpen by remember { mutableStateOf(false) }
    val expired by app.settings.deezerArlExpired.collectAsState()
    val connected = arl.isNotBlank()

    Text(
        "Deezer HQ",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 12.dp),
    )
    if (connected && expired) {
        Text(
            "🟠 Sesión caducada ($user) — vuelve a iniciar sesión o pega el ARL",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        SettingsAction(
            title = "Volver a iniciar sesión en Deezer",
            subtitle = "Deezer ha dejado de aceptar la sesión guardada",
        ) { loginOpen = true }
        SettingsAction(
            title = "Pegar ARL a mano",
            subtitle = "Copia la cookie «arl» desde el navegador de tu PC",
        ) { arlOpen = true }
        SettingsAction(
            title = "Cerrar sesión en Deezer",
            subtitle = "Olvida la sesión caducada",
        ) {
            app.settings.clearDeezerSession()
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
            }
        }
    } else if (connected) {
        Text(
            "🟢 Conectado como $user" +
                (app.settings.deezerCountry.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                " · Máx: " + when {
                    app.settings.deezerHasFlac -> "FLAC (HiFi)"
                    app.settings.deezerHasHq -> "MP3 320"
                    else -> "MP3 128"
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Calidad de descarga: sólo las que permite el plan.
        val options = buildList {
            if (app.settings.deezerHasFlac) add("FLAC" to "FLAC (lossless)")
            if (app.settings.deezerHasHq) add("MP3_320" to "MP3 320 kbps")
            add("MP3_128" to "MP3 128 kbps")
        }
        options.forEach { (value, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { app.settings.setDeezerQuality(value) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(
                    selected = quality == value,
                    onClick = { app.settings.setDeezerQuality(value) },
                )
                Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        SettingsAction(
            title = "Cerrar sesión en Deezer",
            subtitle = "Olvida tu sesión en este dispositivo",
        ) {
            app.settings.clearDeezerSession()
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
            }
        }
    } else {
        SettingsAction(
            title = "Iniciar sesión en Deezer",
            subtitle = "🔴 No conectado — descarga FLAC/MP3 directo con tu cuenta",
        ) { loginOpen = true }
        SettingsAction(
            title = "Pegar ARL a mano",
            subtitle = "Si el inicio de sesión falla: copia la cookie «arl» desde el navegador de tu PC",
        ) { arlOpen = true }
    }

    if (loginOpen) {
        DeezerLoginDialog(app) { loginOpen = false }
    }
    if (arlOpen) {
        DeezerArlDialog(app, onDismiss = { arlOpen = false }, onDone = {})
    }
}

@Composable
private fun SubsonicSettings(app: PrivateMusicApp) {
    val scope = rememberCoroutineScope()
    val url by app.settings.subsonicUrl.collectAsState()
    val user by app.settings.subsonicUser.collectAsState()
    var dialogOpen by remember { mutableStateOf(false) }
    val configured = url.isNotBlank() && user.isNotBlank()

    Text(
        "Servidor de música (Subsonic/Navidrome/Jellyfin)",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 12.dp),
    )
    if (configured) {
        Text(
            "🟢 $user @ $url",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsAction(
            title = "Cambiar servidor",
            subtitle = "Busca y descarga desde tu servidor en la pantalla de Búsqueda",
        ) { dialogOpen = true }
        SettingsAction(
            title = "Quitar servidor",
            subtitle = "Olvida la conexión en este dispositivo",
        ) { app.settings.setSubsonicServer("", "", "") }
    } else {
        SettingsAction(
            title = "Conectar un servidor",
            subtitle = "🔴 Navidrome, Airsonic o Jellyfin (con endpoint Subsonic activado)",
        ) { dialogOpen = true }
    }

    if (dialogOpen) {
        SubsonicDialog(app, onDismiss = { dialogOpen = false }, scope = scope)
    }
}

@Composable
private fun SubsonicDialog(
    app: PrivateMusicApp,
    onDismiss: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var urlField by remember { mutableStateOf(app.settings.subsonicUrl.value) }
    var userField by remember { mutableStateOf(app.settings.subsonicUser.value) }
    var passField by remember { mutableStateOf(app.settings.subsonicPass.value) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!testing) onDismiss() },
        title = { Text("Servidor de música") },
        text = {
            Column {
                OutlinedTextField(
                    value = urlField, onValueChange = { urlField = it; result = null },
                    label = { Text("URL (https://mi-servidor:4533)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = userField, onValueChange = { userField = it; result = null },
                    label = { Text("Usuario") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = passField, onValueChange = { passField = it; result = null },
                    label = { Text("Contraseña") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                result?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    "Jellyfin necesita tener activado su endpoint OpenSubsonic.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !testing && urlField.isNotBlank() && userField.isNotBlank(),
                onClick = {
                    testing = true
                    result = null
                    scope.launch {
                        val cfg = com.aar.privatemusic.downloader.SubsonicConfig(
                            urlField.trim(), userField.trim(), passField,
                        )
                        val ping = app.subsonic.ping(cfg)
                        testing = false
                        ok = ping.ok
                        if (ping.ok) {
                            app.settings.setSubsonicServer(urlField, userField, passField)
                            result = "✅ ${ping.type} ${ping.serverVersion}"
                        } else {
                            result = "❌ ${ping.error ?: "no se pudo conectar"}"
                        }
                    }
                },
            ) { Text(if (testing) "Probando…" else "Probar y guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !testing) { Text("Cerrar") } },
    )
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
