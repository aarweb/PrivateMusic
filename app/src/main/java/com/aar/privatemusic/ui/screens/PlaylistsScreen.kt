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
import androidx.compose.material.icons.filled.QueueMusic
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.PlaylistFolder
import com.aar.privatemusic.ui.components.ArtImage
import kotlinx.coroutines.launch
import java.io.File

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
    val collapsedFolders = remember { mutableStateListOf<Long>() }

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
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
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
                        IconButton(onClick = { scope.launch { app.repository.deleteSmartPlaylist(sp) } }) {
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
                    onDelete = { scope.launch { app.repository.deletePlaylist(pl) } },
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
                        onDelete = { scope.launch { app.repository.deleteFolder(folder) } },
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
        CreateSmartPlaylistDialog(app, onDismiss = { creatingSmart = false })
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
    onChangeCover: () -> Unit,
    onMoveToFolder: () -> Unit,
) {
    val count by app.repository.observePlaylistSize(playlist.id).collectAsState(initial = 0)
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
        if (playlist.coverPath != null) {
            ArtImage(File(playlist.coverPath), 48.dp)
        } else {
            Icon(Icons.Filled.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
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
            Text(
                "$count canciones",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Opciones")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
