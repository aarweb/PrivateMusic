package com.aar.privatemusic.metadata

import org.json.JSONArray
import org.json.JSONObject

enum class CreditGroup { WRITING, PRODUCTION, PERFORMANCE, TECHNICAL, OTHER }

data class MusicCredit(
    val group: CreditGroup,
    val role: String,
    val name: String,
    val details: List<String> = emptyList(),
)

data class MusicConnection(
    val kind: String,
    val title: String,
    val artist: String? = null,
)

data class MusicCredits(
    val recordingMbid: String,
    val title: String,
    val artist: String,
    val credits: List<MusicCredit>,
    val connections: List<MusicConnection>,
    val genres: List<String>,
    val isrcs: List<String>,
)

/** Convierte la respuesta heterogénea de MusicBrainz en datos estables para la interfaz. */
object MusicCreditsParser {
    fun parseRecording(json: String): MusicCredits? = runCatching {
        val root = JSONObject(json)
        val id = root.text("id") ?: return null
        val title = root.text("title") ?: return null
        val artist = artistCredit(root.optJSONArray("artist-credit"))

        val credits = LinkedHashMap<String, MusicCredit>()
        val connections = LinkedHashMap<String, MusicConnection>()

        fun addCredit(credit: MusicCredit) {
            val key = listOf(
                credit.group.name,
                credit.role.lowercase(),
                credit.name.lowercase(),
            ).joinToString("\u0000")
            val previous = credits[key]
            credits[key] = if (previous == null) credit else previous.copy(
                details = (previous.details + credit.details).distinct(),
            )
        }

        fun addArtistRelation(relation: JSONObject) {
            if (relation.text("target-type")?.lowercase() != "artist") return
            val target = relation.optJSONObject("artist") ?: return
            val name = target.text("name") ?: target.text("sort-name") ?: return
            val type = relation.text("type") ?: return
            val (group, role) = roleFor(type)
            val attributes = relation.visibleAttributes()
            val creditedAs = relation.text("credited-as")
                ?: relation.text("target-credit")
                ?: target.text("credited-as")
            val details = buildList {
                addAll(attributes)
                if (!creditedAs.isNullOrBlank() && !creditedAs.equals(name, ignoreCase = true)) {
                    add("Acreditado como: $creditedAs")
                }
            }.distinct()
            addCredit(MusicCredit(group, role, name, details))
        }

        fun addLabelRelation(relation: JSONObject) {
            if (relation.text("target-type")?.lowercase() != "label") return
            if (!relation.text("type").equals("publisher", ignoreCase = true)) return
            val label = relation.optJSONObject("label") ?: return
            val name = label.text("name") ?: label.text("sort-name") ?: return
            addCredit(
                MusicCredit(
                    CreditGroup.WRITING,
                    "Publicación",
                    name,
                    relation.visibleAttributes(),
                ),
            )
        }

        fun addConnection(relation: JSONObject) {
            if (relation.text("target-type")?.lowercase() != "recording") return
            val recording = relation.optJSONObject("recording") ?: return
            val relatedTitle = recording.text("title") ?: return
            val type = relation.text("type") ?: return
            val connection = MusicConnection(
                kind = connectionKind(type, relation.text("direction")),
                title = relatedTitle,
                artist = artistCredit(recording.optJSONArray("artist-credit")).ifBlank { null },
            )
            val key = listOf(
                connection.kind.lowercase(),
                connection.title.lowercase(),
                connection.artist.orEmpty().lowercase(),
            ).joinToString("\u0000")
            connections.putIfAbsent(key, connection)
        }

        val relations = root.optJSONArray("relations")
        relations?.objects()?.forEach { relation ->
            when (relation.text("target-type")?.lowercase()) {
                "artist" -> addArtistRelation(relation)
                "recording" -> addConnection(relation)
                "work" -> {
                    val work = relation.optJSONObject("work")
                    work?.optJSONArray("relations")?.objects()?.forEach { workRelation ->
                        addArtistRelation(workRelation)
                        addLabelRelation(workRelation)
                    }
                }
            }
        }

        MusicCredits(
            recordingMbid = id,
            title = title,
            artist = artist,
            credits = credits.values.toList(),
            connections = connections.values.toList(),
            genres = root.optJSONArray("genres")?.objects()
                ?.mapNotNull { it.text("name") }
                ?.distinct().orEmpty(),
            isrcs = root.stringList("isrcs").distinct(),
        )
    }.getOrNull()

    private fun roleFor(rawType: String): Pair<CreditGroup, String> {
        val type = rawType.lowercase().trim()
        return when {
            type == "composer" -> CreditGroup.WRITING to "Composición"
            type == "lyricist" -> CreditGroup.WRITING to "Letra"
            type == "writer" -> CreditGroup.WRITING to "Autoría"
            type == "arranger" || "arranger" in type -> CreditGroup.WRITING to "Arreglos"
            type == "translator" -> CreditGroup.WRITING to "Traducción"
            type == "publisher" -> CreditGroup.WRITING to "Publicación"
            type == "producer" || type.endsWith(" producer") -> CreditGroup.PRODUCTION to "Producción"
            type == "mix" || type == "mixing" || "mix engineer" in type -> CreditGroup.PRODUCTION to "Mezcla"
            type == "mastering" || "mastering engineer" in type -> CreditGroup.PRODUCTION to "Masterización"
            type == "recording" -> CreditGroup.TECHNICAL to "Grabación"
            type == "engineer" || type.endsWith(" engineer") -> CreditGroup.TECHNICAL to "Ingeniería"
            type == "performer" -> CreditGroup.PERFORMANCE to "Interpretación"
            type == "instrument" || "instrument" in type -> CreditGroup.PERFORMANCE to "Instrumento"
            type == "vocals" || type == "vocal" || "vocal" in type -> CreditGroup.PERFORMANCE to "Voz"
            else -> CreditGroup.OTHER to rawType.trim()
        }
    }

    private fun connectionKind(rawType: String, direction: String?): String {
        val type = rawType.lowercase().trim()
        return when {
            "sample" in type -> if (direction.equals("backward", true)) "Muestreada por" else "Muestra"
            "remix" in type -> if (direction.equals("backward", true)) "Remezclada por" else "Remezcla"
            "cover" in type -> "Versión"
            "other version" in type -> "Otra versión"
            "instrumental" in type -> "Instrumental"
            "karaoke" in type -> "Karaoke"
            else -> rawType.trim()
        }
    }

    private fun artistCredit(array: JSONArray?): String {
        if (array == null) return ""
        return buildString {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                append(item.text("name") ?: item.optJSONObject("artist")?.text("name").orEmpty())
                append(item.text("joinphrase").orEmpty())
            }
        }.trim()
    }

    private fun JSONObject.text(key: String): String? =
        optString(key).takeUnless { it.isBlank() || it == "null" }

    private fun JSONObject.stringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeUnless { it.isBlank() || it == "null" }
        }
    }

    /** MusicBrainz ofrece el nombre canónico, su valor y el texto acreditado. */
    private fun JSONObject.visibleAttributes(): List<String> {
        val attributes = stringList("attributes")
        val credits = optJSONObject("attribute-credits")
        val values = optJSONObject("attribute-values")
        val consumed = HashSet<String>()
        return buildList {
            attributes.forEach { attribute ->
                val visible = credits.visibleText(attribute)
                    ?: values.visibleText(attribute)
                    ?: attribute
                if (visible.isNotBlank()) add(visible)
                consumed += attribute
            }
            credits.visibleEntries().forEach { (key, value) ->
                if (key !in consumed && value.isNotBlank()) add(value)
                consumed += key
            }
            values.visibleEntries().forEach { (key, value) ->
                if (key !in consumed && value.isNotBlank()) add(value)
            }
        }.distinct()
    }

    private fun JSONObject?.visibleText(key: String): String? {
        val value = this?.opt(key) ?: return null
        if (value == JSONObject.NULL) return null
        return value.toString().takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject?.visibleEntries(): List<Pair<String, String>> {
        if (this == null) return emptyList()
        return keys().asSequence().mapNotNull { key ->
            visibleText(key)?.let { key to it }
        }.toList()
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull(::optJSONObject)
}
