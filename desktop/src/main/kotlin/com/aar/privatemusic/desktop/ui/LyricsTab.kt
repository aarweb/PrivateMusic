package com.aar.privatemusic.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.aar.privatemusic.desktop.lyrics.LyricsState
import com.aar.privatemusic.lyrics.Lyrics
import kotlinx.coroutines.flow.StateFlow

/**
 * Recoge la posición aquí dentro y no en el panel: así el tic de la reproducción
 * sólo recompone la letra, y no las pestañas ni la carátula.
 */
@Composable
fun LyricsPane(state: LyricsState, positionMs: StateFlow<Long>) {
    val position by positionMs.collectAsState()
    LyricsTab(state, position)
}

/**
 * La letra de lo que suena. Si LRCLIB la da sincronizada, la línea en curso se
 * ilumina y la lista se desplaza sola; si sólo la da en texto plano, se lee como
 * un texto y no se finge una sincronización que no existe.
 */
@Composable
fun LyricsTab(state: LyricsState, positionMs: Long) {
    when (state) {
        LyricsState.Idle -> EmptyState("Sin letra", "Reproduce algo y la buscaré")
        LyricsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        }
        LyricsState.Missing -> EmptyState(
            "Sin letra",
            "LRCLIB no tiene la letra de esta canción. Si la suben, aparecerá al reabrir la app.",
        )
        is LyricsState.Found ->
            if (state.lyrics.synced) SyncedLyrics(state.lyrics, positionMs) else PlainLyrics(state.lyrics)
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, positionMs: Long) {
    // La línea en curso es la última que ya empezó. Antes de la primera, ninguna.
    val currentLine = remember(lyrics, positionMs) {
        lyrics.lines.indexOfLast { it.timeMs <= positionMs }
    }
    val listState = rememberLazyListState()

    // Se desplaza dejando la línea a un tercio de la altura, no pegada arriba:
    // así se ve lo que viene, que es la mitad de la gracia de una letra.
    LaunchedEffect(currentLine) {
        if (currentLine >= 0) listState.animateScrollToItem((currentLine - 3).coerceAtLeast(0))
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 12.dp, bottom = 220.dp,
        ),
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val active = index == currentLine
            val color by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                line.text,
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
            )
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Sin sincronizar",
            Modifier.padding(start = 20.dp, top = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        ) {
            itemsIndexed(lyrics.lines) { _, line ->
                Text(
                    line.text,
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
