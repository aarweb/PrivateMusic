package com.aar.privatemusic.metadata

import android.content.Context
import android.util.Log
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.util.saveCoverFromUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ties the identify pipeline together: [MetadataMatcher] resolves canonical tags
 * from a messy title, then we download cover art and refresh lyrics (via the
 * existing [com.aar.privatemusic.lyrics.LyricsFetcher], which caches `.lrc`/`.txt`
 * by song id). Used automatically after a YouTube download (auto-apply only when
 * confident) and manually from a song's ⋮ menu (always returns candidates so the
 * user can pick when unsure).
 */
class MetadataService(
    private val context: Context,
    private val dao: MusicDao,
    private val musicDir: File,
) {
    private val matcher = MetadataMatcher()
    private val fingerprinter = Fingerprinter()

    /**
     * Searches online sources for candidate matches (no changes applied). Text
     * search first; if it isn't confident, fall back to an audio fingerprint
     * (AcoustID) and re-enrich from the reliable artist+title it returns.
     */
    suspend fun identify(song: Song): MatchResult {
        val text = matcher.identify(song.title, song.artist, song.durationSec)
        if (text.confident || !fingerprinter.enabled) return text

        val fp = runCatching { fingerprinter.identify(File(song.filePath)) }.getOrNull()
            ?: return text
        val enriched = runCatching {
            matcher.searchByArtistTitle(fp.artist, fp.title, song.durationSec)
        }.getOrNull()
        val candidates = if (enriched != null && enriched.candidates.isNotEmpty()) {
            (enriched.candidates + text.candidates)
        } else {
            listOf(fp) + text.candidates
        }.distinctBy { it.title + "|" + it.artist }
        val confident = enriched?.confident == true || fp.score >= 0.85f
        return MatchResult(candidates, confident)
    }

    /** Applies a chosen match: canonical tags, cover art, and refreshed lyrics. */
    suspend fun apply(song: Song, match: TrackMatch) = withContext(Dispatchers.IO) {
        val newTitle = match.title.ifBlank { song.title }
        val newArtist = match.artist.ifBlank { song.artist }
        runCatching {
            dao.updateCanonicalMeta(
                song.id, newTitle, newArtist, match.album, match.albumArtist,
                match.year, match.trackNumber, match.mbid, match.isrc,
            )
        }.onFailure { Log.w("MetadataService", "meta update failed", it) }

        match.artworkUrl?.let { url ->
            saveCoverFromUrl(url, musicDir, song.id)?.let { f ->
                val old = song.artPath
                dao.updateSongArt(song.id, f.absolutePath)
                if (old != null && old != f.absolutePath) runCatching { File(old).delete() }
            }
        }

        // The tags changed, so any lyrics cached under the old name are stale:
        // drop them and re-fetch with the corrected artist/title so the karaoke
        // view has the right (synced) lyrics ready.
        runCatching {
            File(musicDir, "${song.id}.lrc").delete()
            File(musicDir, "${song.id}.txt").delete()
            com.aar.privatemusic.lyrics.LyricsFetcher.getOrFetch(
                song.copy(title = newTitle, artist = newArtist), musicDir,
            )
        }
    }

    /**
     * Auto path (after download / background): identify and apply only if the
     * match is confident; otherwise just mark it attempted so we don't re-hit
     * the network on every launch. Returns the result for optional UI use.
     */
    suspend fun autoIdentify(song: Song): MatchResult {
        val result = runCatching { identify(song) }.getOrElse { MatchResult(emptyList(), false) }
        val best = result.best
        if (result.confident && best != null) {
            apply(song, best)
        } else {
            runCatching { dao.setMetadataResolved(song.id, true) }
        }
        return result
    }
}
