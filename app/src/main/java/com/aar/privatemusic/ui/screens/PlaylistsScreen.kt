package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.PlaylistFolder
import com.aar.privatemusic.ui.components.PlaylistCover
import kotlinx.coroutines.launch

@Composable
fun PlaylistsScreen(
    app: PrivateMusicApp,
    onOpenPlaylist: (Long) -> Unit,
    onOpenAuto: (AutoPlaylistType) -> Unit,
    onOpenSmart: (Long) -> Unit,
) {
    val playlists by app.repository.observePlaylists().collectAsState(initial = emptyList())
    val smartPlaylists by app.repository.observeSmartPlaylists().collectAsState(initial = emptyList())
    val folders by app.repository.observeFolders().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var creatingSmart by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderForRename by remember { mutableStateOf<PlaylistFolder?>(null) }
    var playlistForFolder by remember { mutableStateOf<Playlist?>(null) }
    var fabMenuOpen by remember { mutableStateOf(false) }
    var playlistForCover by remember { mutableStateOf<Playlist?>(null) }
    var playlistForRename by remember { mutableStateOf<Playlist?>(null) }
    var playlistForDelete by remember { mutableStateOf<Playlist?>(null) }
    var folderForDelete by remember { mutableStateOf<PlaylistFolder?>(null) }
    var smartForDelete by remember { mutableStateOf<com.aar.privatemusic.data.db.SmartPlaylist?>(null) }
    // Saveable: survives navigating into a playlist and coming back, and rotation.
    val collapsedFolders = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        )
    ) { mutableStateListOf<Long>() }

    val context = LocalContext.current
    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val playlist = playlistForCover
        playlistForCover = null
        if (uri != null && playlist != null) {
            scope.launch { app.repository.setPlaylistCover(context, playlist, uri) }
        }
    }

    Scaffold(
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabMenuOpen = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Nueva playlist")
                }
                DropdownMenu(expanded = fabMenuOpen, onDismissRequest = { fabMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Playlist normal") },
                        onClick = {
                            fabMenuOpen = false
                            creating = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Playlist inteligente") },
                        onClick = {
                            fabMenuOpen = false
                            creatingSmart = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Carpeta") },
                        onClick = {
                            fabMenuOpen = false
                            creatingFolder = true
                        },
                    )
}
            }
        },
    ) { padding ->
        // El FAB flota sobre la lista: sin este hueco al final, tapaba el menú
        // de la última playlist y no había forma de abrirlo.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
        ) {
            item(key = "auto-header") {
                Text(
                    "Automáticas",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "daily-mix") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                val mix = app.repository.buildDailyMix()
                                if (mix.isNotEmpty()) app.playerController.playQueue(mix, 0)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PlayCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text("Mix de hoy", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Se renueva cada día con tu historial y favoritas",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(AutoPlaylistType.entries, key = { "auto-${it.route}" }) { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAuto(type) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        type.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            if (smartPlaylists.isNotEmpty()) {
                item(key = "smart-header") {
                    Text(
                        "Inteligentes",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(smartPlaylists, key = { "smart-${it.id}" }) { sp ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSmart(sp.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            sp.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp),
                        )
                        IconButton(onClick = { smartForDelete = sp }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Eliminar")
                        }
                    }
                }
            }
            item(key = "own-header") {
                Text(
                    "Tus playlists",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (playlists.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No hay playlists todavía. Crea una con el botón +.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            val playlistRowItem: @Composable (Playlist, Boolean) -> Unit = { pl, indent ->
                PlaylistRow(
                    app = app,
                    playlist = pl,
                    hasFolders = folders.isNotEmpty(),
                    indent = indent,
                    onClick = { onOpenPlaylist(pl.id) },
                    onDelete = { playlistForDelete = pl },
                    onRename = { playlistForRename = pl },
                    onDuplicate = {
                        scope.launch {
                            app.repository.duplicatePlaylist(pl)
                            com.aar.privatemusic.util.Feedback.show("Playlist duplicada")
                        }
                    },
                    onChangeCover = {
                        playlistForCover = pl
                        coverPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onMoveToFolder = { playlistForFolder = pl },
                )
            }

            for (folder in folders) {
                val collapsed = folder.id in collapsedFolders
                val inFolder = playlists.filter { it.folderId == folder.id }
                    .sortedByDescending { it.isPinned }
                item(key = "folder-${folder.id}") {
                    FolderHeader(
                        folder = folder,
                        count = inFolder.size,
                        collapsed = collapsed,
                        onToggle = {
                            if (collapsed) collapsedFolders.remove(folder.id)
                            else collapsedFolders.add(folder.id)
                        },
                        onRename = { folderForRename = folder },
                        onDelete = { folderForDelete = folder },
                    )
                }
                if (!collapsed) {
                    items(inFolder, key = { "pl-${it.id}" }) { pl -> playlistRowItem(pl, true) }
                }
            }

            val looseFolder = playlists.filter { it.folderId == null }.sortedByDescending { it.isPinned }
            items(looseFolder, key = { it.id }) { pl -> playlistRowItem(pl, false) }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Nueva playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Nombre") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) {
                            scope.launch { app.repository.createPlaylist(trimmed) }
                        }
                        creating = false
                    },
                ) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancelar") } },
        )
    }

    if (creatingSmart) {
        SmartPlaylistEditorDialog(app, onDismiss = { creatingSmart = false })
    }

    if (creatingFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creatingFolder = false },
            title = { Text("Nueva carpeta") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Nombre") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) scope.launch { app.repository.createFolder(trimmed) }
                    creatingFolder = false
                }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { creatingFolder = false }) { Text("Cancelar") } },
        )
    }

    folderForRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { folderForRename = null },
            title = { Text("Renombrar carpeta") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) scope.launch { app.repository.renameFolder(folder.id, trimmed) }
                    folderForRename = null
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { folderForRename = null }) { Text("Cancelar") } },
        )
    }

    playlistForRename?.let { pl ->
        var name by remember(pl.id) { mutableStateOf(pl.name) }
        var description by remember(pl.id) { mutableStateOf(pl.description.orEmpty()) }
        AlertDialog(
            onDismissRequest = { playlistForRename = null },
            title = { Text("Editar playlist") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nombre") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Descripción (opcional)") },
                        modifier = Modifier.padding(top = 8.dp),
                        maxLines = 3,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        scope.launch { app.repository.renamePlaylist(pl.id, name, description) }
                        playlistForRename = null
                    },
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { playlistForRename = null }) { Text("Cancelar") } },
        )
    }

    playlistForDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { playlistForDelete = null },
            title = { Text("¿Eliminar playlist?") },
            text = { Text("Se eliminará \u201c${pl.name}\u201d. Las canciones seguirán en tu biblioteca.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.repository.deletePlaylist(pl) }
                    com.aar.privatemusic.util.Feedback.show("Playlist eliminada")
                    playlistForDelete = null
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { playlistForDelete = null }) { Text("Cancelar") } },
        )
    }

    folderForDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderForDelete = null },
            title = { Text("¿Eliminar carpeta?") },
            text = { Text("Se eliminará \u201c${folder.name}\u201d. Las playlists que contiene NO se borran: vuelven al nivel principal.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.repository.deleteFolder(folder) }
                    com.aar.privatemusic.util.Feedback.show("Carpeta eliminada")
                    folderForDelete = null
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { folderForDelete = null }) { Text("Cancelar") } },
        )
    }

    smartForDelete?.let { sp ->
        AlertDialog(
            onDismissRequest = { smartForDelete = null },
            title = { Text("¿Eliminar playlist inteligente?") },
            text = { Text("Se eliminará \u201c${sp.name}\u201d y sus reglas.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.repository.deleteSmartPlaylist(sp) }
                    com.aar.privatemusic.util.Feedback.show("Playlist inteligente eliminada")
                    smartForDelete = null
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { smartForDelete = null }) { Text("Cancelar") } },
        )
    }

    playlistForFolder?.let { pl ->
        AlertDialog(
            onDismissRequest = { playlistForFolder = null },
            title = { Text("Mover “${pl.name}”") },
            text = {
                Column {
                    if (pl.folderId != null) {
                        DropdownMenuItem(
                            text = { Text("Sacar de la carpeta") },
                            onClick = {
                                scope.launch { app.repository.movePlaylistToFolder(pl.id, null) }
                                playlistForFolder = null
                            },
                        )
                    }
                    folders.forEach { folder ->
                        DropdownMenuItem(
                            text = { Text(folder.name) },
                            trailingIcon = {
                                if (folder.id == pl.folderId) {
                                    Icon(Icons.Filled.Check, contentDescription = "Actual")
                                }
                            },
                            onClick = {
                                scope.launch { app.repository.movePlaylistToFolder(pl.id, folder.id) }
                                playlistForFolder = null
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { playlistForFolder = null }) { Text("Cerrar") } },
        )
    }
}

@Composable
private fun FolderHeader(
    folder: PlaylistFolder,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (collapsed) Icons.Filled.Folder else Icons.Filled.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
        Text(
            folder.name,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        Text(
            "$count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            contentDescription = if (collapsed) "Expandir" else "Contraer",
            modifier = Modifier.padding(start = 8.dp),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Opciones de carpeta")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Renombrar") },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Eliminar carpeta") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    app: PrivateMusicApp,
    playlist: Playlist,
    hasFolders: Boolean,
    indent: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onChangeCover: () -> Unit,
    onMoveToFolder: () -> Unit,
) {
    val count by app.repository.observePlaylistSize(playlist.id).collectAsState(initial = 0)
    val art by app.repository.observePlaylistArt(playlist.id).collectAsState(initial = emptyList())
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (indent) 32.dp else 16.dp, end = 16.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(playlist.coverPath, art, 48.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playlist.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "Fijada",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp).height(16.dp),
                    )
                }
                Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
            }
            val size = if (count == 1) "1 canción" else "$count canciones"
            Text(
                playlist.description?.takeIf { it.isNotBlank() }?.let { "$size · $it" } ?: size,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Opciones")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Renombrar") },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Duplicar") },
                    onClick = {
                        menuOpen = false
                        onDuplicate()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (playlist.isPinned) "Desfijar" else "Fijar arriba") },
                    onClick = {
                        menuOpen = false
                        scope.launch { app.repository.setPlaylistPinned(playlist.id, !playlist.isPinned) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Ordenar para mezclar (BPM/tonalidad)") },
                    onClick = {
                        menuOpen = false
                        scope.launch { app.repository.sonicReorderPlaylist(playlist.id) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Cambiar portada") },
                    onClick = {
                        menuOpen = false
                        onChangeCover()
                    },
                )
                if (hasFolders || playlist.folderId != null) {
                    DropdownMenuItem(
                        text = { Text(if (playlist.folderId != null) "Cambiar de carpeta" else "Mover a carpeta") },
                        onClick = {
                            menuOpen = false
                            onMoveToFolder()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Eliminar playlist") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}
