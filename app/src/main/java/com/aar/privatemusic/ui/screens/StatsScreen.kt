package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.MusicRepository
import com.aar.privatemusic.util.ShareCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private enum class Period(val label: String) { MONTH("Este mes"), YEAR("Este año"), ALL("Siempre") }

private fun Period.sinceMillis(): Long {
    val cal = Calendar.getInstance()
    return when (this) {
        Period.MONTH -> {
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.timeInMillis
        }
        Period.YEAR -> {
            cal.set(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.timeInMillis
        }
        Period.ALL -> 0L
    }
}

@Composable
fun StatsScreen(app: PrivateMusicApp) {
    var period by remember { mutableStateOf(Period.MONTH) }
    var recap by remember { mutableStateOf<MusicRepository.Recap?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(period) {
        recap = app.repository.recap(period.sinceMillis())
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Tu Recap", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    recap?.let { r ->
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                ShareCard.renderRecap(context, r, period.label)
                            }
                            ShareCard.share(context, file)
                        }
                    }
                },
                enabled = recap != null && recap!!.plays > 0,
            ) {
                Icon(Icons.Filled.Share, contentDescription = "Compartir Recap")
            }
        }

        Row(
            Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Period.entries.forEach { p ->
                FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) })
            }
        }

        recap?.let { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Minutos", "${r.minutes}", Modifier.weight(1f))
                StatCard("Reproducciones", "${r.plays}", Modifier.weight(1f))
            }
            Spacer(Modifier.padding(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Canciones", "${r.distinctSongs}", Modifier.weight(1f))
                StatCard("Días con música", "${r.listeningDays}", Modifier.weight(1f))
            }

            // Listener archetype
            Card(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "${r.personalityEmoji} Tu personalidad de oyente",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(r.personality, style = MaterialTheme.typography.bodyLarge)
                }
            }

            SectionTitle("Canciones más escuchadas")
            if (r.topSongs.isEmpty()) EmptyNote()
            r.topSongs.forEachIndexed { i, item ->
                RankRow(i + 1, item.song.title, "${item.plays}")
            }

            SectionTitle("Artistas más escuchados")
            if (r.topArtists.isEmpty()) EmptyNote()
            r.topArtists.forEachIndexed { i, item ->
                RankRow(i + 1, item.artist, "${item.plays}")
            }

            if (r.memorableDays.isNotEmpty()) {
                SectionTitle("Días memorables")
                r.memorableDays.forEach { d ->
                    RankRow(0, d.day, "${d.plays} reproducciones")
                }
            }

            if (r.badges.isNotEmpty()) {
                SectionTitle("Logros")
                r.badges.forEach { badge ->
                    Text(badge, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptyNote() {
    Text("Sin datos en este periodo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RankRow(rank: Int, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (rank > 0) {
            Text("$rank.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
