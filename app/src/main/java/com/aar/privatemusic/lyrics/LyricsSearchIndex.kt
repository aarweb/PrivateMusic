package com.aar.privatemusic.lyrics

import com.aar.privatemusic.data.db.Song
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Índice incremental en memoria de las letras que ya existen en el teléfono. */
class LyricsSearchIndex(private val musicDir: File) {
    private data class Entry(
        val sourceProbeStamp: Long,
        val translationStamp: Long,
        val sourceStamp: Long,
        val contentStamp: Long,
        val sourceLyrics: Lyrics?,
        val document: LyricSearchDocument?,
    )

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private val translations = LyricsTranslationCache(musicDir)

    suspend fun search(
        songs: List<Song>,
        query: String,
        limit: Int = 100,
    ): List<LyricSearchHit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val songIds = songs.asSequence().map { it.id }.toSet()
            entries.keys.retainAll(songIds)

            songIds.forEach { songId ->
                val sourceProbe = LyricsFetcher.cachedCandidatesStamp(songId, musicDir)
                val translationStamp = translations.cacheStamp(songId, TARGET_LANGUAGE)
                val previous = entries[songId]
                if (previous?.sourceProbeStamp == sourceProbe &&
                    previous.translationStamp == translationStamp
                ) return@forEach

                val cached = if (previous?.sourceProbeStamp == sourceProbe && previous.sourceLyrics != null) {
                    null
                } else {
                    runCatching { LyricsFetcher.readCachedWithSource(songId, musicDir) }.getOrNull()
                }
                val sourceLyrics = cached?.lyrics ?: previous?.sourceLyrics
                    ?.takeIf { previous.sourceProbeStamp == sourceProbe }
                if (sourceLyrics == null) {
                    entries[songId] = Entry(sourceProbe, translationStamp, 0L, 0L, null, null)
                    return@forEach
                }

                val sourceStamp = cached?.sourceFile?.let { source ->
                    combine(
                        source.absolutePath.hashCode().toLong(),
                        source.lastModified(),
                        source.length(),
                    )
                } ?: previous!!.sourceStamp
                val effectiveStamp = combine(sourceStamp, translationStamp)
                val translated = runCatching {
                    translations.read(songId, TARGET_LANGUAGE, sourceLyrics)
                }.getOrNull()
                val document = LyricSearchDocument(
                    songId = songId,
                    originalLines = sourceLyrics.lines.map { it.text },
                    translatedLines = translated?.lines?.map { it.text }.orEmpty(),
                )
                entries[songId] = Entry(
                    sourceProbe, translationStamp, sourceStamp, effectiveStamp, sourceLyrics, document,
                )
            }

            LyricSearch.search(entries.values.mapNotNull { it.document }, query, limit)
        }
    }

    private fun combine(vararg values: Long): Long =
        values.fold(1_125_899_906_842_597L) { acc, value -> acc * 31 + value }

    private companion object {
        const val TARGET_LANGUAGE = "es"
    }
}
