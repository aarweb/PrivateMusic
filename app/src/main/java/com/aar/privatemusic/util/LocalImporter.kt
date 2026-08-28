package com.aar.privatemusic.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import com.aar.privatemusic.data.GenreCodec
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Indexes music already on the device (MediaStore) into the library.
 * Files are referenced in place — never copied or deleted — and flow
 * through the same pipeline as downloads (loudness, BPM, tail silence…)
 * via the regular backfills.
 */
object LocalImporter {

    const val ID_PREFIX = "local_"

    suspend fun scan(context: Context, dao: MusicDao, musicDir: File): Int =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
            )
            var added = 0
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = ID_PREFIX + c.getLong(0)
                    if (dao.songExists(id)) continue
                    val path = c.getString(4) ?: continue
                    val file = File(path)
                    if (!file.canRead()) continue
                    // Skip our own downloads (already in the library).
                    if (file.absolutePath.startsWith(musicDir.absolutePath)) continue
                    val durationSec = (c.getLong(3) / 1000).toInt()
                    if (durationSec < 30) continue // ringtones, clips

                    val title = c.getString(1)?.takeIf { it.isNotBlank() }
                        ?: file.nameWithoutExtension
                    val artist = c.getString(2)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: "Desconocido"
                    // Dedup: no reimportar si ya tienes la misma canción (otra fuente).
                    if (dao.findByTitleArtist(title, artist) != null) continue

                    // Tags embebidos y portada. Cada lectura es best-effort: un
                    // fichero con metadatos dañados sigue entrando en la biblioteca.
                    var artPath: String? = null
                    var album: String? = null
                    var albumArtist: String? = null
                    var year: Int? = null
                    var trackNumber: Int? = null
                    var genresJson: String? = null
                    runCatching { MediaMetadataRetriever() }.getOrNull()?.let { mmr ->
                        try {
                            mmr.setDataSource(path)
                            album = mmr.textTag(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                            albumArtist = mmr.textTag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                            year = mmr.textTag(MediaMetadataRetriever.METADATA_KEY_YEAR)
                                ?.take(4)
                                ?.toIntOrNull()
                                ?.takeIf { it in 1000..2100 }
                            trackNumber = mmr.textTag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                                ?.substringBefore('/')
                                ?.trim()
                                ?.toIntOrNull()
                                ?.takeIf { it > 0 }
                            genresJson = GenreCodec.encodeRaw(
                                mmr.textTag(MediaMetadataRetriever.METADATA_KEY_GENRE),
                            )
                            runCatching { mmr.embeddedPicture }.getOrNull()?.let { bytes ->
                                val out = File(musicDir, "$id.jpg")
                                runCatching { out.writeBytes(bytes) }
                                    .onSuccess { artPath = out.absolutePath }
                            }
                        } catch (_: Exception) {
                            // MediaStore conserva título/artista aunque los tags no se puedan leer.
                        } finally {
                            runCatching { mmr.release() }
                        }
                    }

                    dao.insertSong(
                        Song(
                            id = id,
                            title = title,
                            artist = artist,
                            durationSec = durationSec,
                            filePath = path,
                            artPath = artPath,
                            thumbnailUrl = null,
                            addedAt = System.currentTimeMillis(),
                            album = album,
                            albumArtist = albumArtist,
                            year = year,
                            trackNumber = trackNumber,
                            genresJson = genresJson,
                        )
                    )
                    added++
                }
            }
            added
        }

    private fun MediaMetadataRetriever.textTag(key: Int): String? =
        runCatching { extractMetadata(key) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
