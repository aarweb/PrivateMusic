package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.data.db.Song

/** Alto de fila. Una biblioteca de 200 canciones se recorre distinto que una de 20. */
enum class RowDensity(val label: String, val height: Dp, val showCover: Boolean) {
    COMPACTA("Compacta", 40.dp, false),
    NORMAL("Normal", 64.dp, true),
    GRANDE("Grande", 88.dp, true),
}

enum class SortKey { NUMERO, TITULO, ARTISTA, ALBUM, DURACION }

data class SortState(val key: SortKey, val ascending: Boolean)

/** Lo que el menú del clic derecho puede hacer con una canción. */
data class SongActions(
    val onAddToQueue: (Song) -> Unit,
    val onGoToArtist: ((Song) -> Unit)? = null,
    val onGoToAlbum: ((Song) -> Unit)? = null,
)

/**
 * La lista de canciones como una tabla, no como una lista de móvil: cabecera
 * fija, columnas que ordenan al pulsarlas, y densidad ajustable. Es la diferencia
 * entre ver 12 canciones y ver 30.
 *
 * La columna Álbum se muestra sólo si un cuarto de las canciones tienen álbum:
 * en una biblioteca bajada de YouTube casi ninguna lo tiene, y una columna de
 * huecos es peor que ninguna columna.
 */
@Composable
fun SongTable(
    songs: List<Song>,
    density: RowDensity,
    currentId: String?,
    onPlay: (List<Song>, Int) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    modifier: Modifier = Modifier,
    actions: SongActions? = null,
    /** En un álbum el orden natural es el del disco, no el alfabético. */
    initialSort: SortState = SortState(SortKey.NUMERO, ascending = true),
) {
    var sort by remember(songs) { mutableStateOf(initialSort) }
    // No basta con que "alguna" tenga álbum: en una biblioteca bajada de YouTube
    // lo tienen 15 de 203, y la columna sale vacía en el 93% de las filas. Con un
    // cuarto de cobertura ya dice algo; por debajo, estorba.
    val showAlbum = remember(songs) {
        songs.isNotEmpty() && songs.count { !it.album.isNullOrBlank() } * 4 >= songs.size
    }

    val sorted = remember(songs, sort) {
        val base = when (sort.key) {
            SortKey.NUMERO -> songs
            SortKey.TITULO -> songs.sortedBy { it.title.lowercase() }
            SortKey.ARTISTA -> songs.sortedBy { it.artist.lowercase() }
            SortKey.ALBUM -> songs.sortedBy { it.album.orEmpty().lowercase() }
            SortKey.DURACION -> songs.sortedBy { it.durationSec }
        }
        if (sort.ascending) base else base.reversed()
    }

    fun toggle(key: SortKey) {
        sort = if (sort.key == key) sort.copy(ascending = !sort.ascending) else SortState(key, true)
    }

    Column(modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderCell("#", Modifier.width(44.dp), sort, SortKey.NUMERO, TextAlign.End) { toggle(SortKey.NUMERO) }
                Box(Modifier.width(if (density.showCover) 60.dp else 12.dp))
                HeaderCell("Título", Modifier.weight(3f), sort, SortKey.TITULO) { toggle(SortKey.TITULO) }
                HeaderCell("Artista", Modifier.weight(2f), sort, SortKey.ARTISTA) { toggle(SortKey.ARTISTA) }
                if (showAlbum) {
                    HeaderCell("Álbum", Modifier.weight(2f), sort, SortKey.ALBUM) { toggle(SortKey.ALBUM) }
                }
                HeaderCell("Duración", Modifier.width(80.dp), sort, SortKey.DURACION, TextAlign.End) {
                    toggle(SortKey.DURACION)
                }
                Box(Modifier.width(48.dp))
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(sorted, key = { _, song -> song.id }) { index, song ->
                val row = @Composable {
                    SongTableRow(
                        song = song,
                        position = index + 1,
                        density = density,
                        showAlbum = showAlbum,
                        playing = song.id == currentId,
                        onClick = { onPlay(sorted, index) },
                        onToggleFavorite = { onToggleFavorite(song) },
                    )
                }
                if (actions == null) row() else {
                    ContextMenuArea(items = { menuFor(song, sorted, index, onPlay, onToggleFavorite, actions) }) {
                        row()
                    }
                }
            }
        }
    }
}

/**
 * El menú del clic derecho. "Ir al álbum" sólo aparece si la canción tiene
 * álbum: una entrada que no lleva a ninguna parte enseña a no usar el menú.
 */
private fun menuFor(
    song: Song,
    sorted: List<Song>,
    index: Int,
    onPlay: (List<Song>, Int) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    actions: SongActions,
): List<ContextMenuItem> = buildList {
    add(ContextMenuItem("Reproducir") { onPlay(sorted, index) })
    add(ContextMenuItem("Añadir a la cola") { actions.onAddToQueue(song) })
    actions.onGoToArtist?.let { add(ContextMenuItem("Ir al artista") { it(song) }) }
    if (!song.album.isNullOrBlank()) {
        actions.onGoToAlbum?.let { add(ContextMenuItem("Ir al álbum") { it(song) }) }
    }
    add(
        ContextMenuItem(if (song.isFavorite) "Quitar de favoritas" else "Marcar como favorita") {
            onToggleFavorite(song)
        },
    )
}

@Composable
private fun HeaderCell(
    label: String,
    modifier: Modifier,
    sort: SortState,
    key: SortKey,
    align: TextAlign = TextAlign.Start,
    onClick: () -> Unit,
) {
    val active = sort.key == key
    Row(
        modifier.clickable(onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (align == TextAlign.End) {
            androidx.compose.foundation.layout.Arrangement.End
        } else {
            androidx.compose.foundation.layout.Arrangement.Start
        },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (active) {
            Icon(
                if (sort.ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                null,
                Modifier.size(12.dp).padding(start = 2.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SongTableRow(
    song: Song,
    position: Int,
    density: RowDensity,
    showAlbum: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val tint = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().height(density.height)
            .background(
                if (playing) MaterialTheme.colorScheme.surfaceContainerHigh
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterEnd) {
            // La fila que suena cambia su número por un icono: se localiza sin leer.
            if (playing) {
                Icon(Icons.Filled.VolumeUp, "Sonando", Modifier.size(16.dp), tint = tint)
            } else {
                Text(
                    "$position",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (density.showCover) {
            Cover(song.artPath, density.height - 16.dp, Modifier.padding(start = 12.dp))
            Box(Modifier.width(12.dp))
        } else {
            Box(Modifier.width(12.dp))
        }
        Text(
            song.title,
            Modifier.weight(3f).padding(end = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            song.artist,
            Modifier.weight(2f).padding(end = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showAlbum) {
            Text(
                song.album.orEmpty(),
                Modifier.weight(2f).padding(end = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            formatDuration(song.durationSec * 1000L),
            Modifier.width(80.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
        Box(
            Modifier.width(48.dp).clickable(onClick = onToggleFavorite),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (song.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                "Favorita",
                Modifier.size(16.dp),
                tint = if (song.isFavorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
