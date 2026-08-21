package com.aar.privatemusic.lyrics

import android.icu.text.Transliterator

/**
 * Pasa a alfabeto latino las letras en hangul, kana/kanji, cirílico, árabe,
 * hebreo, tailandés o griego, para poder cantarlas en el karaoke. Usa el ICU
 * del sistema (API 24+), sin modelos ni red.
 */
object Romanizer {
    private val foreign = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.ARABIC,
        Character.UnicodeScript.HEBREW, Character.UnicodeScript.THAI,
        Character.UnicodeScript.GREEK, Character.UnicodeScript.DEVANAGARI,
        Character.UnicodeScript.GEORGIAN, Character.UnicodeScript.ARMENIAN,
    )

    // Crear el transliterador cuesta ~100 ms la primera vez; se reutiliza.
    private val transliterator: Transliterator by lazy {
        Transliterator.getInstance("Any-Latin; Latin-ASCII")
    }

    /** True si hay algo que romanizar (al menos un carácter de otro alfabeto). */
    fun needsRomanization(lyrics: Lyrics): Boolean =
        lyrics.lines.any { line -> line.text.any { Character.UnicodeScript.of(it.code) in foreign } }

    fun romanize(text: String): String = runCatching { transliterator.transliterate(text) }.getOrDefault(text)

    /**
     * Romaniza sin perder el resaltado por palabra: cada palabra se translitera
     * por separado, así que conserva su tramo de tiempo. El texto de la línea se
     * romaniza entero (con su contexto), que para leer queda mejor.
     */
    fun romanize(lyrics: Lyrics): Lyrics = lyrics.copy(
        lines = lyrics.lines.map { line ->
            line.copy(
                text = romanize(line.text),
                words = line.words.map { it.copy(text = romanize(it.text)) },
            )
        }
    )
}
