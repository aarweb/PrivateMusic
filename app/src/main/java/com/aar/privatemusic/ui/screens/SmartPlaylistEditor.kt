package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.Condition
import com.aar.privatemusic.data.FieldKind
import com.aar.privatemusic.data.RuleContext
import com.aar.privatemusic.data.RuleField
import com.aar.privatemusic.data.RuleGroup
import com.aar.privatemusic.data.RuleOp
import com.aar.privatemusic.data.RuleSort
import com.aar.privatemusic.data.SmartRuleEngine
import com.aar.privatemusic.data.SmartRules
import com.aar.privatemusic.data.db.SmartPlaylist
import kotlinx.coroutines.launch

/**
 * Editor de reglas de una playlist inteligente. Sirve para crear y para editar:
 * [existing] null = crear. Muestra en vivo cuántas canciones cumplen, que es la
 * única forma de saber si una regla hace lo que crees antes de guardarla.
 */
@Composable
fun SmartPlaylistEditorDialog(
    app: PrivateMusicApp,
    existing: SmartPlaylist? = null,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val songs by app.repository.observeSongs().collectAsState(initial = emptyList())
    val counts by app.repository.observePlayCounts().collectAsState(initial = emptyList())
    val lastPlays by app.repository.observeLastPlayed().collectAsState(initial = emptyList())

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var rules by remember { mutableStateOf(existing?.let { SmartRuleEngine.rulesOf(it) } ?: SmartRules()) }

    val ctx = remember(counts, lastPlays) {
        RuleContext(
            playCounts = counts.associate { it.songId to it.plays },
            lastPlayed = lastPlays.associate { it.songId to it.lastPlayed },
        )
    }
    val matching = remember(rules, songs, ctx) { SmartRuleEngine.evaluate(rules, songs, ctx) }

    fun save() {
        val json = SmartRuleEngine.toJson(rules)
        scope.launch {
            if (existing == null) {
                app.repository.createSmartPlaylist(
                    SmartPlaylist(
                        name = name.trim(),
                        artistContains = null,
                        onlyFavorites = false,
                        minPlays = 0,
                        addedWithinDays = 0,
                        createdAt = System.currentTimeMillis(),
                        rulesJson = json,
                    ),
                )
            } else {
                app.repository.updateSmartPlaylist(existing.copy(name = name.trim(), rulesJson = json))
            }
        }
    }

    // decorFitsSystemWindows = false: sin esto los insets dentro del diálogo
    // valen cero y `systemBarsPadding` no haría nada.
    Dialog(
        onDismiss,
        DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            // Sin los insets, la barra de acciones queda DEBAJO de la barra de
            // navegación del sistema y "Guardar" no se puede pulsar.
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Cerrar")
                    }
                    Text(
                        if (existing == null) "Nueva playlist inteligente" else "Editar reglas",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Arriba y no abajo: la barra de navegación del sistema tapa el
                    // fondo del diálogo y allí "Guardar" resultaba impulsable.
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = { save(); onDismiss() },
                    ) { Text("Guardar") }
                }

                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nombre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    )

                    GroupEditor(
                        group = rules.root,
                        depth = 0,
                        onChange = { rules = rules.copy(root = it) },
                        onRemove = null,
                    )

                    Text(
                        "Orden y límite",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dropdown(
                            label = rules.sort.label,
                            options = RuleSort.entries.map { it to it.label },
                            onSelect = { rules = rules.copy(sort = it) },
                        )
                        if (rules.sort != RuleSort.RANDOM) {
                            Checkbox(
                                checked = rules.descending,
                                onCheckedChange = { rules = rules.copy(descending = it) },
                            )
                            Text("descendente", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedTextField(
                        value = if (rules.limit > 0) rules.limit.toString() else "",
                        onValueChange = { t ->
                            rules = rules.copy(limit = t.filter { it.isDigit() }.toIntOrNull() ?: 0)
                        },
                        label = { Text("Máximo de canciones (vacío = todas)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )

                    Text(
                        SmartRuleEngine.describe(rules),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                Surface(tonalElevation = 3.dp) {
                    Text(
                        "${matching.size} de ${songs.size} canciones cumplen",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
    }
}

/** Un grupo de condiciones. [depth] 1 ya no admite subgrupos: anidar más confunde. */
@Composable
private fun GroupEditor(
    group: RuleGroup,
    depth: Int,
    onChange: (RuleGroup) -> Unit,
    onRemove: (() -> Unit)?,
) {
    Surface(
        tonalElevation = (depth + 1).dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = group.matchAll,
                    onClick = { onChange(group.copy(matchAll = true)) },
                    label = { Text("Cumple todas") },
                )
                FilterChip(
                    selected = !group.matchAll,
                    onClick = { onChange(group.copy(matchAll = false)) },
                    label = { Text("Cumple alguna") },
                    modifier = Modifier.padding(start = 8.dp),
                )
                Box(Modifier.weight(1f))
                if (onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = "Quitar grupo")
                    }
                }
            }

            group.conditions.forEachIndexed { i, condition ->
                ConditionEditor(
                    condition = condition,
                    onChange = { updated ->
                        onChange(group.copy(conditions = group.conditions.toMutableList().also { it[i] = updated }))
                    },
                    onRemove = {
                        onChange(group.copy(conditions = group.conditions.filterIndexed { j, _ -> j != i }))
                    },
                )
            }

            group.groups.forEachIndexed { i, sub ->
                GroupEditor(
                    group = sub,
                    depth = depth + 1,
                    onChange = { updated ->
                        onChange(group.copy(groups = group.groups.toMutableList().also { it[i] = updated }))
                    },
                    onRemove = {
                        onChange(group.copy(groups = group.groups.filterIndexed { j, _ -> j != i }))
                    },
                )
            }

            Row {
                TextButton(onClick = {
                    onChange(
                        group.copy(
                            conditions = group.conditions + Condition(RuleField.ARTIST, RuleOp.CONTAINS),
                        ),
                    )
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Condición", Modifier.padding(start = 4.dp))
                }
                if (depth == 0) {
                    TextButton(onClick = {
                        onChange(group.copy(groups = group.groups + RuleGroup(matchAll = false)))
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("Subgrupo", Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConditionEditor(
    condition: Condition,
    onChange: (Condition) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dropdown(
                label = condition.field.label,
                options = RuleField.entries.map { it to it.label },
                modifier = Modifier.weight(1f),
                onSelect = { field ->
                    // Al cambiar de campo el operador viejo puede no aplicar:
                    // "contiene" no significa nada sobre un número.
                    val op = if (condition.op.kind == field.kind) condition.op
                    else RuleOp.forKind(field.kind).first()
                    onChange(condition.copy(field = field, op = op))
                },
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Quitar condición")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown(
                label = condition.op.label,
                options = RuleOp.forKind(condition.field.kind).map { it to it.label },
                onSelect = { onChange(condition.copy(op = it)) },
            )
            when (condition.field.kind) {
                FieldKind.TEXT -> OutlinedTextField(
                    value = condition.text,
                    onValueChange = { onChange(condition.copy(text = it)) },
                    singleLine = true,
                    label = { Text("Valor") },
                    modifier = Modifier.weight(1f),
                )

                FieldKind.NUMBER, FieldKind.DAYS -> {
                    NumberField(
                        value = condition.value,
                        label = if (condition.field.kind == FieldKind.DAYS) "Días" else "Valor",
                        onChange = { onChange(condition.copy(value = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    if (condition.op == RuleOp.BETWEEN) {
                        NumberField(
                            value = condition.value2,
                            label = "y",
                            onChange = { onChange(condition.copy(value2 = it)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                FieldKind.BOOL -> Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NumberField(
    value: Double,
    label: String,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = if (value == 0.0) "" else SmartRuleEngine.formatNumber(value),
        onValueChange = { t -> onChange(t.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0) },
        singleLine = true,
        label = { Text(label) },
        modifier = modifier,
    )
}

@Composable
private fun <T> Dropdown(
    label: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onSelect(value); open = false },
                    modifier = Modifier.width(280.dp),
                )
            }
        }
    }
}
