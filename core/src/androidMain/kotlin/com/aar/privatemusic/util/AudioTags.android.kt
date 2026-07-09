package com.aar.privatemusic.util

import android.media.MediaMetadataRetriever

actual fun readAudioTags(path: String): AudioTags {
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(path)
        AudioTags(
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() },
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() },
            durationSec = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.let { (it / 1000).toInt() } ?: 0,
        )
    } catch (e: Exception) {
        AudioTags(null, null, 0)
    } finally {
        runCatching { mmr.release() }
    }
}
