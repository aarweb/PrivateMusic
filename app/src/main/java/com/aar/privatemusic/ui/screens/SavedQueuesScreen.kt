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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.db.SavedQueueWithCount
import com.aar.privatemusic.util.Feedback
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Colas guardadas: fotos de la cola de reproducción con nombre. Reproducir
 * reemplaza la cola actual; "Añadir a la cola" la encola detrás de lo que suena.
 */
@Composable
fun SavedQueuesScreen(app: PrivateMusicApp, onBack: () -> Unit) {
    val queues by app.repository.observeSavedQueues().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<SavedQueueWithCount?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
            }
            Text(
                "Colas guardadas",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        if (queues.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Aún no has guardado ninguna cola.\nDesde la cola de reproducción: Guardar → Guardar como cola.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(32.dp),
                )
            }
            return
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(queues, key = { it.queue.id }) { item ->
                SavedQueueRow(
                    item = item,
                    onPlay = {
                        scope.launch {
                            val songs = app.repository.savedQueueSongs(item.queue.id)
                            if (songs.isEmpty()) {
                                Feedback.show("Esa cola ya no tiene canciones")
                            } else {
                                app.playerController.playQueue(songs, 0)
                                Feedback.show("Reproduciendo \"${item.queue.name}\"")
                            }
                        }
                    },
                    onAppend = {
                        scope.launch {
                            val songs = app.repository.savedQueueSongs(item.queue.id)
                            songs.forEach { app.playerController.addToQueue(it) }
                            Feedback.show("${songs.size} canciones añadidas a la cola")
                        }
                    },
                    onRename = { renaming = item },
                    onDelete = {
                        scope.launch {
                            app.repository.deleteSavedQueue(item.queue.id)
                            Feedback.show("Cola \"${item.queue.name}\" borrada")
                        }
                    },
                )
            }
        }
    }

    renaming?.let { item ->
        var name by remember { mutableStateOf(item.queue.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renombrar cola") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nombre") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        scope.launch { app.repository.renameSavedQueue(item.queue.id, name.trim()) }
                        renaming = null
                    },
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SavedQueueRow(
    item: SavedQueueWithCount,
    onPlay: () -> Unit,
    onAppend: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val date = remember(item.queue.createdAt) {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(item.queue.createdAt))
    }
    Row(
        Modifier.fillMaxWidth().clickable { onPlay() }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.queue.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "${item.songCount} canciones · $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Reproducir")
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Más")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Añadir a la cola") }, onClick = { menu = false; onAppend() })
                DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Borrar") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
