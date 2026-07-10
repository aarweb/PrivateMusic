package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.data.db.SmartPlaylist
import com.aar.privatemusic.data.db.Playlist

/** Los destinos fijos de la barra lateral. Las playlists van aparte, debajo. */
enum class Destination(val label: String, val icon: ImageVector) {
    INICIO("Inicio", Icons.Filled.Home),
    BUSCAR("Buscar", Icons.Filled.Search),
    BIBLIOTECA("Biblioteca", Icons.Filled.LibraryMusic),
    ARTISTAS("Artistas", Icons.Filled.Person),
    ALBUMES("Álbumes", Icons.Filled.QueueMusic),
    FAVORITAS("Favoritas", Icons.Filled.Favorite),
    AJUSTES("Ajustes", Icons.Filled.Settings),
}

/**
 * Barra lateral de dos estados: rail de iconos, o expandida con las playlists
 * dentro. Es lo que separa un reproductor de escritorio de una app de móvil
 * estirada: las playlists son destinos de primera, no una pestaña más.
 *
 * Las ancladas (`isPinned`) suben arriba, como en Spotify.
 */
@Composable
fun Sidebar(
    expanded: Boolean,
    onToggle: () -> Unit,
    selected: Destination?,
    onSelect: (Destination) -> Unit,
    playlists: List<Playlist>,
    openPlaylistId: Long?,
    onOpenPlaylist: (Playlist) -> Unit,
    smartPlaylists: List<SmartPlaylist>,
    openSmartId: Long?,
    onOpenSmart: (SmartPlaylist) -> Unit,
) {
    Surface(
        Modifier.width(if (expanded) 260.dp else 84.dp).fillMaxHeight(),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Menu, if (expanded) "Contraer" else "Expandir")
                }
                if (expanded) {
                    Text(
                        "PrivateMusic",
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Destination.entries.forEach { destination ->
                SidebarItem(
                    label = destination.label,
                    icon = destination.icon,
                    selected = destination == selected,
                    expanded = expanded,
                    onClick = { onSelect(destination) },
                )
            }

            if (!expanded || (playlists.isEmpty() && smartPlaylists.isEmpty())) return@Column

            // Las dos secciones comparten una sola lista: dos `LazyColumn` en una
            // `Column` se pelean por la altura y la segunda no se ve.
            LazyColumn(Modifier.fillMaxWidth()) {
                if (playlists.isNotEmpty()) {
                    item { SidebarSection("TUS PLAYLISTS") }
                    // Ancladas primero: el orden de `observePlaylists` no las distingue.
                    items(playlists.sortedByDescending { it.isPinned }, key = { "p${it.id}" }) { playlist ->
                        PlaylistItem(playlist, playlist.id == openPlaylistId) { onOpenPlaylist(playlist) }
                    }
                }
                if (smartPlaylists.isNotEmpty()) {
                    item { SidebarSection("INTELIGENTES") }
                    items(smartPlaylists, key = { "s${it.id}" }) { smart ->
                        SmartPlaylistItem(smart, smart.id == openSmartId) { onOpenSmart(smart) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarSection(label: String) {
    Text(
        label,
        Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Se distingue de una playlist normal por el icono: sus canciones las elige una regla. */
@Composable
private fun SmartPlaylistItem(smart: SmartPlaylist, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            null,
            Modifier.size(16.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            smart.name,
            Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SidebarItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val background =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val tint =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .height(44.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
    ) {
        Icon(icon, label, Modifier.size(20.dp), tint = tint)
        if (expanded) {
            Text(
                label,
                Modifier.padding(start = 14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PlaylistItem(playlist: Playlist, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .height(34.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playlist.isPinned) {
            Icon(
                Icons.Filled.PushPin,
                "Anclada",
                Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            playlist.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
