package com.aar.privatemusic.downloader

import android.content.Context
import com.aar.privatemusic.data.db.WatchedSource

/**
 * Keeps a watched Spotify playlist in sync: resolves the current track list,
 * matches anything new on YouTube and queues the download. Processed tracks
 * are remembered (per source) so each check only handles additions.
 */
object SpotifySync {

    private fun prefs(context: Context) =
        context.getSharedPreferences("spotify_sync", Context.MODE_PRIVATE)

    fun seenKeys(context: Context, sourceId: Long): Set<String> =
        prefs(context).getStringSet("seen_$sourceId", emptySet()) ?: emptySet()

    fun markSeen(context: Context, sourceId: Long, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val merged = seenKeys(context, sourceId) + keys
        prefs(context).edit().putStringSet("seen_$sourceId", merged).apply()
    }

    fun clearSeen(context: Context, sourceId: Long) {
        prefs(context).edit().remove("seen_$sourceId").apply()
    }

    /** Returns how many new tracks were queued for download. */
    suspend fun sync(context: Context, downloader: YtDownloader, source: WatchedSource): Int {
        val (_, tracks) = SpotifyResolver.resolve(source.url)
        val seen = seenKeys(context, source.id)
        val processed = mutableListOf<String>()
        var queued = 0

        tracks.filter { it.key !in seen }.forEach { track ->
            val match = downloader.searchBestMatch(track.searchQuery, track.durationSec)
            if (match != null) {
                downloader.enqueue(match, source.targetPlaylistId)
                processed += track.key
                queued++
            }
        }
        markSeen(context, source.id, processed)
        return queued
    }
}
