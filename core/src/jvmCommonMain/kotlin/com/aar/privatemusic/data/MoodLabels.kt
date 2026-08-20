package com.aar.privatemusic.data

import com.aar.privatemusic.data.db.Song

/** Etiquetas legibles de los rasgos de ánimo; sólo las que superan el umbral. */
object MoodLabels {
    const val THRESHOLD = 0.6f

    fun of(song: Song?): List<String> {
        if (song == null) return emptyList()
        return buildList {
            if ((song.moodAggressive ?: 0f) >= THRESHOLD) add("Enérgica")
            if ((song.moodRelaxed ?: 0f) >= THRESHOLD) add("Tranquila")
            if ((song.moodHappy ?: 0f) >= THRESHOLD) add("Alegre")
            if ((song.moodSad ?: 0f) >= THRESHOLD) add("Triste")
            if ((song.danceability ?: 0f) >= THRESHOLD) add("Bailable")
            song.vocalness?.let { if (it < 1f - THRESHOLD) add("Instrumental") }
        }
    }
}
