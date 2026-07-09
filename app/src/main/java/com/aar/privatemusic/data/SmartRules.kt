package com.aar.privatemusic.data

import com.aar.privatemusic.data.db.SmartPlaylist
import com.aar.privatemusic.data.db.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * Motor de reglas de las playlists inteligentes: grupos anidados de condiciones
 * combinadas con Y/O, más orden y límite. Se guarda como JSON en
 * `smart_playlists.rulesJson` y se evalúa en memoria contra la biblioteca.
 *
 * Las playlists creadas antes de este motor no tienen JSON: sus cuatro columnas
 * planas se traducen al vuelo con [fromLegacy], así que siguen funcionando sin
 * tocar la base de datos.
 */

/** Campo de la canción sobre el que se compara. Cada uno admite unos operadores. */
enum class RuleField(val label: String, val kind: FieldKind) {
    TITLE("Título", FieldKind.TEXT),
    ARTIST("Artista", FieldKind.TEXT),
    ALBUM("Álbum", FieldKind.TEXT),
    ALBUM_ARTIST("Grupo / artista del álbum", FieldKind.TEXT),
    CAMELOT("Tonalidad (Camelot)", FieldKind.TEXT),
    CODEC("Códec", FieldKind.TEXT),
    YEAR("Año", FieldKind.NUMBER),
    BPM("BPM", FieldKind.NUMBER),
    DURATION("Duración (segundos)", FieldKind.NUMBER),
    PLAY_COUNT("Reproducciones", FieldKind.NUMBER),
    BITRATE("Bitrate (kbps)", FieldKind.NUMBER),
    LAST_PLAYED("Última escucha", FieldKind.DAYS),
    ADDED("Añadida", FieldKind.DAYS),
    FAVORITE("Favorita", FieldKind.BOOL),
}

enum class FieldKind { TEXT, NUMBER, DAYS, BOOL }

enum class RuleOp(val label: String, val kind: FieldKind) {
    CONTAINS("contiene", FieldKind.TEXT),
    NOT_CONTAINS("no contiene", FieldKind.TEXT),
    IS("es", FieldKind.TEXT),
    IS_NOT("no es", FieldKind.TEXT),
    GT("mayor que", FieldKind.NUMBER),
    LT("menor que", FieldKind.NUMBER),
    BETWEEN("entre", FieldKind.NUMBER),
    EQ("igual a", FieldKind.NUMBER),
    IN_LAST_DAYS("en los últimos (días)", FieldKind.DAYS),
    NOT_IN_LAST_DAYS("no en los últimos (días)", FieldKind.DAYS),
    IS_TRUE("sí", FieldKind.BOOL),
    IS_FALSE("no", FieldKind.BOOL);

    companion object {
        fun forKind(kind: FieldKind) = entries.filter { it.kind == kind }
    }
}

enum class RuleSort(val label: String) {
    TITLE("Título"),
    ARTIST("Artista"),
    ADDED("Fecha añadida"),
    PLAY_COUNT("Reproducciones"),
    LAST_PLAYED("Última escucha"),
    BPM("BPM"),
    DURATION("Duración"),
    RANDOM("Aleatorio"),
}

/** `value2` sólo lo usa [RuleOp.BETWEEN]. Nunca reproducidas = PLAY_COUNT EQ 0. */
data class Condition(
    val field: RuleField,
    val op: RuleOp,
    val text: String = "",
    val value: Double = 0.0,
    val value2: Double = 0.0,
)

/** Un grupo: condiciones + subgrupos, unidos todos con Y ([matchAll]) o con O. */
data class RuleGroup(
    val matchAll: Boolean = true,
    val conditions: List<Condition> = emptyList(),
    val groups: List<RuleGroup> = emptyList(),
) {
    val isEmpty: Boolean get() = conditions.isEmpty() && groups.all { it.isEmpty }
}

/** [limit] 0 = sin límite. */
data class SmartRules(
    val root: RuleGroup = RuleGroup(),
    val sort: RuleSort = RuleSort.TITLE,
    val descending: Boolean = false,
    val limit: Int = 0,
)

/** Contexto de evaluación: lo que no vive en la fila de `songs`. */
data class RuleContext(
    val playCounts: Map<String, Int> = emptyMap(),
    val lastPlayed: Map<String, Long> = emptyMap(),
    val now: Long = System.currentTimeMillis(),
)

object SmartRuleEngine {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun evaluate(rules: SmartRules, songs: List<Song>, ctx: RuleContext): List<Song> {
        val matched = if (rules.root.isEmpty) songs else songs.filter { matches(rules.root, it, ctx) }
        val sorted = when (rules.sort) {
            RuleSort.RANDOM -> matched.shuffled()
            RuleSort.TITLE -> matched.sortedBy { it.title.lowercase() }
            RuleSort.ARTIST -> matched.sortedBy { it.artist.lowercase() }
            RuleSort.ADDED -> matched.sortedBy { it.addedAt }
            RuleSort.PLAY_COUNT -> matched.sortedBy { ctx.playCounts[it.id] ?: 0 }
            // Lo nunca escuchado va primero al ordenar por última escucha.
            RuleSort.LAST_PLAYED -> matched.sortedBy { ctx.lastPlayed[it.id] ?: 0L }
            RuleSort.BPM -> matched.sortedBy { it.bpm ?: 0f }
            RuleSort.DURATION -> matched.sortedBy { it.durationSec }
        }
        // Un orden aleatorio ya invertido sigue siendo aleatorio: no lo toques.
        val ordered = if (rules.descending && rules.sort != RuleSort.RANDOM) sorted.reversed() else sorted
        return if (rules.limit > 0) ordered.take(rules.limit) else ordered
    }

    private fun matches(group: RuleGroup, song: Song, ctx: RuleContext): Boolean {
        if (group.isEmpty) return true
        val results = group.conditions.map { matches(it, song, ctx) } +
            group.groups.filter { !it.isEmpty }.map { matches(it, song, ctx) }
        if (results.isEmpty()) return true
        return if (group.matchAll) results.all { it } else results.any { it }
    }

    private fun matches(c: Condition, song: Song, ctx: RuleContext): Boolean = when (c.field.kind) {
        FieldKind.TEXT -> {
            val actual = when (c.field) {
                RuleField.TITLE -> song.title
                RuleField.ARTIST -> song.artist
                RuleField.ALBUM -> song.album
                RuleField.ALBUM_ARTIST -> song.albumArtist
                RuleField.CAMELOT -> song.camelot
                else -> song.codec
            }.orEmpty()
            val needle = c.text.trim()
            when (c.op) {
                RuleOp.CONTAINS -> needle.isBlank() || actual.contains(needle, ignoreCase = true)
                RuleOp.NOT_CONTAINS -> needle.isBlank() || !actual.contains(needle, ignoreCase = true)
                RuleOp.IS -> actual.equals(needle, ignoreCase = true)
                RuleOp.IS_NOT -> !actual.equals(needle, ignoreCase = true)
                else -> true
            }
        }

        FieldKind.NUMBER -> {
            // null = dato sin medir (BPM, año...): no puede cumplir una comparación.
            val actual: Double? = when (c.field) {
                RuleField.YEAR -> song.year?.toDouble()
                RuleField.BPM -> song.bpm?.toDouble()
                RuleField.DURATION -> song.durationSec.toDouble()
                RuleField.BITRATE -> song.bitrateKbps?.toDouble()
                else -> (ctx.playCounts[song.id] ?: 0).toDouble()
            }
            if (actual == null) false else when (c.op) {
                RuleOp.GT -> actual > c.value
                RuleOp.LT -> actual < c.value
                RuleOp.EQ -> actual == c.value
                RuleOp.BETWEEN -> actual >= minOf(c.value, c.value2) && actual <= maxOf(c.value, c.value2)
                else -> true
            }
        }

        FieldKind.DAYS -> {
            val stamp = when (c.field) {
                RuleField.LAST_PLAYED -> ctx.lastPlayed[song.id]
                else -> song.addedAt
            }
            val cutoff = ctx.now - (c.value.toLong().coerceAtLeast(0) * DAY_MS)
            when (c.op) {
                // Nunca escuchada (stamp null) nunca está "en los últimos N días",
                // y siempre está "fuera" de ellos: es lo que espera el usuario.
                RuleOp.IN_LAST_DAYS -> stamp != null && stamp >= cutoff
                RuleOp.NOT_IN_LAST_DAYS -> stamp == null || stamp < cutoff
                else -> true
            }
        }

        FieldKind.BOOL -> if (c.op == RuleOp.IS_TRUE) song.isFavorite else !song.isFavorite
    }

    // ------------------------------------------------------------------ JSON

    fun toJson(rules: SmartRules): String = JSONObject().apply {
        put("root", groupToJson(rules.root))
        put("sort", rules.sort.name)
        put("desc", rules.descending)
        put("limit", rules.limit)
    }.toString()

    private fun groupToJson(g: RuleGroup): JSONObject = JSONObject().apply {
        put("all", g.matchAll)
        put("conditions", JSONArray().apply {
            g.conditions.forEach { c ->
                put(JSONObject().apply {
                    put("field", c.field.name)
                    put("op", c.op.name)
                    put("text", c.text)
                    put("value", c.value)
                    put("value2", c.value2)
                })
            }
        })
        put("groups", JSONArray().apply { g.groups.forEach { put(groupToJson(it)) } })
    }

    /** Devuelve null si el JSON está corrupto: el llamante cae a las reglas viejas. */
    fun fromJson(json: String?): SmartRules? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            SmartRules(
                root = groupFromJson(o.getJSONObject("root")),
                sort = enumValueOf<RuleSort>(o.optString("sort", RuleSort.TITLE.name)),
                descending = o.optBoolean("desc", false),
                limit = o.optInt("limit", 0),
            )
        }.getOrNull()
    }

    private fun groupFromJson(o: JSONObject): RuleGroup {
        val conds = o.optJSONArray("conditions") ?: JSONArray()
        val groups = o.optJSONArray("groups") ?: JSONArray()
        return RuleGroup(
            matchAll = o.optBoolean("all", true),
            conditions = (0 until conds.length()).mapNotNull { i ->
                val c = conds.optJSONObject(i) ?: return@mapNotNull null
                runCatching {
                    Condition(
                        field = enumValueOf(c.getString("field")),
                        op = enumValueOf(c.getString("op")),
                        text = c.optString("text"),
                        value = c.optDouble("value", 0.0),
                        value2 = c.optDouble("value2", 0.0),
                    )
                }.getOrNull()
            },
            groups = (0 until groups.length()).mapNotNull { i ->
                groups.optJSONObject(i)?.let { groupFromJson(it) }
            },
        )
    }

    // ---------------------------------------------------------------- Legacy

    /** Traduce las cuatro columnas planas de antes del motor a reglas equivalentes. */
    fun fromLegacy(sp: SmartPlaylist): SmartRules {
        val conditions = buildList {
            sp.artistContains?.takeIf { it.isNotBlank() }?.let {
                add(Condition(RuleField.ARTIST, RuleOp.CONTAINS, text = it))
            }
            if (sp.onlyFavorites) add(Condition(RuleField.FAVORITE, RuleOp.IS_TRUE))
            if (sp.minPlays > 0) {
                add(Condition(RuleField.PLAY_COUNT, RuleOp.GT, value = (sp.minPlays - 1).toDouble()))
            }
            if (sp.addedWithinDays > 0) {
                add(Condition(RuleField.ADDED, RuleOp.IN_LAST_DAYS, value = sp.addedWithinDays.toDouble()))
            }
        }
        return SmartRules(root = RuleGroup(matchAll = true, conditions = conditions))
    }

    fun rulesOf(sp: SmartPlaylist): SmartRules = fromJson(sp.rulesJson) ?: fromLegacy(sp)

    // ------------------------------------------------------------ Descripción

    fun describe(rules: SmartRules): String {
        val body = describeGroup(rules.root).ifBlank { "todas las canciones" }
        val tail = buildList {
            if (rules.sort != RuleSort.TITLE || rules.descending) {
                add("orden: ${rules.sort.label}${if (rules.descending) " ↓" else ""}")
            }
            if (rules.limit > 0) add("máx. ${rules.limit}")
        }
        return if (tail.isEmpty()) body else "$body · ${tail.joinToString(" · ")}"
    }

    private fun describeGroup(g: RuleGroup): String {
        val parts = g.conditions.map { describe(it) } +
            g.groups.filter { !it.isEmpty }.map { "(${describeGroup(it)})" }
        return parts.joinToString(if (g.matchAll) " Y " else " O ")
    }

    private fun describe(c: Condition): String = when (c.field.kind) {
        FieldKind.TEXT -> "${c.field.label} ${c.op.label} \"${c.text}\""
        FieldKind.BOOL -> if (c.op == RuleOp.IS_TRUE) "favorita" else "no favorita"
        FieldKind.DAYS -> {
            val days = c.value.toInt()
            if (c.op == RuleOp.IN_LAST_DAYS) "${c.field.label} en los últimos $days días"
            else "${c.field.label} hace más de $days días"
        }
        FieldKind.NUMBER -> when (c.op) {
            RuleOp.BETWEEN -> "${c.field.label} entre ${formatNumber(c.value)} y ${formatNumber(c.value2)}"
            else -> "${c.field.label} ${c.op.label} ${formatNumber(c.value)}"
        }
    }

    /** Sin decimales cuando no los hay: "120", no "120.0". */
    fun formatNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
