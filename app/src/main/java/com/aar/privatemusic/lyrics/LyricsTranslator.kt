package com.aar.privatemusic.lyrics

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface LyricsTranslationState {
    data object DetectingLanguage : LyricsTranslationState
    data object DownloadingModel : LyricsTranslationState
    data class Translating(val completed: Int, val total: Int) : LyricsTranslationState
}

sealed interface LyricsTranslationResult {
    data class Translated(val lyrics: Lyrics, val fromCache: Boolean) : LyricsTranslationResult
    data object AlreadySpanish : LyricsTranslationResult
    data class UnsupportedLanguage(val languageTag: String) : LyricsTranslationResult
    data object EmptyLyrics : LyricsTranslationResult
    data class Failed(val cause: Throwable) : LyricsTranslationResult
}

/** Traduce letras con modelos ML Kit que se ejecutan en el dispositivo. */
class LyricsTranslator(private val cache: LyricsTranslationCache) {

    suspend fun translateToSpanish(
        songId: String,
        source: Lyrics,
        onState: (LyricsTranslationState) -> Unit = {},
    ): LyricsTranslationResult {
        withContext(Dispatchers.IO) { cache.read(songId, SPANISH, source) }
            ?.let { return LyricsTranslationResult.Translated(it, fromCache = true) }

        val translatable = source.lines.filter { line ->
            line.text.isNotBlank() && !isBlockDelimiter(line.text)
        }
        if (translatable.isEmpty()) return LyricsTranslationResult.EmptyLyrics

        return runCatching {
            onState(LyricsTranslationState.DetectingLanguage)
            val identifier = LanguageIdentification.getClient()
            val sourceLanguage = try {
                identifier.identifyLanguage(
                    translatable.joinToString("\n") { it.text }.take(MAX_LANGUAGE_SAMPLE_CHARS),
                ).awaitTask()
            } finally {
                identifier.close()
            }

            if (sourceLanguage == SPANISH) return LyricsTranslationResult.AlreadySpanish
            val mlKitSource = TranslateLanguage.fromLanguageTag(sourceLanguage)
                ?: return LyricsTranslationResult.UnsupportedLanguage(sourceLanguage)

            val translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(mlKitSource)
                    .setTargetLanguage(TranslateLanguage.SPANISH)
                    .build(),
            )
            try {
                onState(LyricsTranslationState.DownloadingModel)
                translator.downloadModelIfNeeded().awaitTask()

                var completed = 0
                val total = translatable.size
                val translatedLines = source.lines.map { original ->
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val translatedText = when {
                        original.text.isBlank() || isBlockDelimiter(original.text) -> original.text
                        else -> translator.translate(original.text).awaitTask()
                    }
                    if (original.text.isNotBlank() && !isBlockDelimiter(original.text)) {
                        completed++
                        onState(LyricsTranslationState.Translating(completed, total))
                    }
                    original.copy(text = translatedText, words = emptyList())
                }
                val translated = source.copy(lines = translatedLines)
                // Sólo una traducción completa y válida reemplaza la caché buena.
                withContext(Dispatchers.IO) {
                    cache.write(songId, SPANISH, source, translated)
                }
                LyricsTranslationResult.Translated(translated, fromCache = false)
            } finally {
                translator.close()
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            LyricsTranslationResult.Failed(it)
        }
    }

    private fun isBlockDelimiter(text: String): Boolean {
        val value = text.trim()
        if (value.length < 2) return false
        return (value.startsWith('[') && value.endsWith(']')) ||
            (value.startsWith('{') && value.endsWith('}')) ||
            (value.startsWith('<') && value.endsWith('>'))
    }

    private suspend fun <T> Task<T>.awaitTask(): T =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { value ->
                if (continuation.isActive) continuation.resume(value)
            }
            addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            addOnCanceledListener { continuation.cancel() }
        }

    companion object {
        private const val SPANISH = "es"
        private const val MAX_LANGUAGE_SAMPLE_CHARS = 5_000
    }
}
