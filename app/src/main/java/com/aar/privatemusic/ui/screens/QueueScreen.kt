package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.ui.components.ArtImage
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

@Composable
fun QueueScreen(app: PrivateMusicApp, onBack: () -> Unit) {
    val controller = app.playerController
    val queue by controller.queue.collectAsState()
    val currentIndex by controller.currentIndex.collectAsState()

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        controller.moveQueueItem(from.index, to.index)
    }

    // La clave llevaba la posición dentro ("id-3"). Al arrastrar una canción, la
    // posición de todas las de en medio cambia, así que cambiaban todas las
    // claves: la lista se creía nueva entera y la animación de arrastre saltaba.
    // La posición no puede estar en la clave; lo que sí la necesita es distinguir
    // la misma canción repetida en la cola, y para eso vale contar repeticiones.
    val queueKeys = androidx.compose.runtime.remember(queue) {
        val seen = HashMap<String, Int>()
        queue.map { item ->
            val n = seen.merge(item.mediaId, 1, Int::plus)!!
            "${item.mediaId}#$n"
        }
    }
    // Open anchored on the playing track, not the top of a 50-song queue.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (currentIndex > 0) lazyListState.scrollToItem(currentIndex)
    }
    var savingQueue by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Cerrar")
            }
            Text(
                "Cola de reproducción",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { controller.reshuffleUpcoming() }) {
                Icon(Icons.Filled.Casino, contentDescription = "Rebarajar lo siguiente")
            }
            IconButton(onClick = { savingQueue = true }) {
                Icon(Icons.Filled.PlaylistAdd, contentDescription = "Guardar cola como playlist")
            }
        }

        if (savingQueue) {
            var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { savingQueue = false },
                title = { Text("Guardar cola como playlist") },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Nombre") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) {
                            val ids = queue.map { it.mediaId }
                            scope.launch {
                                val plId = app.repository.createPlaylist(trimmed)
                                ids.forEach { app.repository.addToPlaylist(plId, it) }
                            }
                        }
                        savingQueue = false
                    }) { Text("Guardar") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { savingQueue = false }) { Text("Cancelar") }
                },
            )
        }

        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("La cola está vacía.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }

        LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(queue, key = { index, _ -> queueKeys[index] }) { index, item ->
                ReorderableItem(reorderableState, key = queueKeys[index]) { isDragging ->
                    val isCurrent = index == currentIndex
                    Surface(tonalElevation = if (isDragging) 4.dp else 0.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { controller.playQueueItem(index) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ArtImage(item.artPath?.let { File(it) }, 40.dp)
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                            ) {
                                Text(
                                    item.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    item.artist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { controller.removeQueueItem(index) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Quitar de la cola")
                            }
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(),
                            ) {
                                Icon(Icons.Filled.DragHandle, contentDescription = "Reordenar")
                            }
                        }
                    }
                }
            }
        }
    }
}
