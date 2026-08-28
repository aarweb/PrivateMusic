package com.aar.privatemusic.metadata

import com.aar.privatemusic.BuildConfig
import com.aar.privatemusic.data.GenreCodec
import com.aar.privatemusic.data.db.Song
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CreditsLoad(val credits: MusicCredits, val fromCache: Boolean)

/** Consulta MusicBrainz bajo demanda y conserva su respuesta original sin caducidad. */
class MusicCreditsService(
    private val musicDir: File,
    private val onGenresResolved: (suspend (songId: String, genresJson: String) -> Unit)? = null,
) {

    suspend fun load(song: Song, forceRefresh: Boolean = false): Result<CreditsLoad> =
        withContext(Dispatchers.IO) {
            val sourceKey = sourceKey(song)
            val cached = readCache(song.id, sourceKey)
            val loaded = if (cached != null && !forceRefresh) {
                CreditsLoad(cached, fromCache = true)
            } else {
                try {
                    val mbid = song.mbid?.takeIf(::isMbid) ?: searchRecording(song)
                        ?: error("MusicBrainz no encontró una grabación compatible")
                    val raw = musicBrainzGet(
                        "$BASE_URL/recording/$mbid" +
                            "?inc=artist-rels+label-rels+work-rels+work-level-rels+recording-rels+genres+isrcs&fmt=json",
                    ) ?: error("MusicBrainz no devolvió los créditos")
                    val credits = MusicCreditsParser.parseRecording(raw)
                        ?: error("Respuesta de créditos no válida")
                    writeCache(song.id, sourceKey, raw)
                    CreditsLoad(credits, fromCache = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // Forzar es best-effort: una caída de red no inutiliza lo que ya funcionaba.
                    cached?.let { CreditsLoad(it, fromCache = true) }
                        ?: return@withContext Result.failure(error)
                }
            }

            try {
                // También se guarda `[]`: null significa pendiente, no "sin géneros".
                onGenresResolved?.invoke(song.id, GenreCodec.encode(loaded.credits.genres))
                Result.success(loaded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }

    fun invalidate(songId: String) {
        runCatching { cacheFile(songId).delete() }
        val prefix = cacheFile(songId).name + ".tmp-"
        musicDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach {
            runCatching { it.delete() }
        }
    }

    private suspend fun searchRecording(song: Song): String? {
        val query = "recording:\"${escapeQuery(song.title)}\" AND artist:\"${escapeQuery(song.artist)}\""
        val raw = musicBrainzGet(
            "$BASE_URL/recording?fmt=json&limit=$SEARCH_LIMIT&query=${encode(query)}",
        ) ?: return null
        val recordings = runCatching {
            JSONObject(raw).optJSONArray("recordings")
        }.getOrNull() ?: return null

        return recordings.objects()
            .mapNotNull { candidate ->
                val id = candidate.text("id")?.takeIf(::isMbid) ?: return@mapNotNull null
                val title = candidate.text("title").orEmpty()
                val artist = artistCredit(candidate.optJSONArray("artist-credit"))
                val lengthMs = candidate.optLong("length", 0L)
                if (!isCompatibleCandidate(song, title, artist, lengthMs)) return@mapNotNull null
                id to candidateScore(song, title, artist, lengthMs)
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun isCompatibleCandidate(
        song: Song,
        candidateTitle: String,
        candidateArtist: String,
        candidateLengthMs: Long,
    ): Boolean {
        val title = normalize(song.title)
        val otherTitle = normalize(candidateTitle)
        if (!sameIdentityText(title, otherTitle)) return false

        val artist = normalize(song.artist)
        val otherArtist = normalize(candidateArtist)
        if (artist.isNotBlank() && !sameIdentityText(artist, otherArtist)) return false

        if (song.durationSec > 0 && candidateLengthMs > 0 &&
            kotlin.math.abs(candidateLengthMs - song.durationSec * 1_000L) > 20_000L
        ) return false
        return true
    }

    private fun sameIdentityText(first: String, second: String): Boolean {
        if (first.isBlank() || second.isBlank()) return false
        if (first == second || first in second || second in first) return true
        val firstTokens = first.split(' ').filter { it.length > 1 }.toSet()
        val secondTokens = second.split(' ').filter { it.length > 1 }.toSet()
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) return false
        val common = firstTokens.intersect(secondTokens).size
        return common.toDouble() / maxOf(firstTokens.size, secondTokens.size) >= 0.6
    }

    private fun candidateScore(song: Song, title: String, artist: String, lengthMs: Long): Int {
        val expectedTitle = normalize(song.title)
        val candidateTitle = normalize(title)
        val expectedArtist = normalize(song.artist)
        val candidateArtist = normalize(artist)
        var score = when {
            expectedTitle == candidateTitle -> 8
            expectedTitle in candidateTitle || candidateTitle in expectedTitle -> 4
            else -> tokenOverlap(expectedTitle, candidateTitle) * 2
        }
        score += when {
            expectedArtist == candidateArtist -> 6
            expectedArtist in candidateArtist || candidateArtist in expectedArtist -> 3
            else -> tokenOverlap(expectedArtist, candidateArtist)
        }
        if (song.durationSec > 0 && lengthMs > 0) {
            score += when (kotlin.math.abs(lengthMs - song.durationSec * 1_000L)) {
                in 0..3_000 -> 6
                in 3_001..8_000 -> 4
                in 8_001..20_000 -> 2
                else -> 0
            }
        }
        return score
    }

    private suspend fun musicBrainzGet(url: String): String? =
        MusicBrainzRateLimiter.run { httpGet(url) }

    private fun httpGet(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val declared = connection.contentLengthLong
            if (declared > MAX_RESPONSE_BYTES) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(
                    declared.takeIf { it in 1..MAX_RESPONSE_BYTES }?.toInt() ?: 16_384,
                )
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readCache(songId: String, expectedSourceKey: String): MusicCredits? {
        val file = cacheFile(songId)
        if (!file.isFile || file.length() !in 1..MAX_CACHE_BYTES) return null
        return runCatching {
            val wrapper = JSONObject(file.readText(StandardCharsets.UTF_8))
            if (wrapper.optInt("version") != CACHE_VERSION) return null
            if (wrapper.text("sourceKey") != expectedSourceKey) return null
            val raw = wrapper.text("raw") ?: return null
            MusicCreditsParser.parseRecording(raw)
        }.getOrNull()
    }

    private fun writeCache(songId: String, sourceKey: String, raw: String) {
        musicDir.mkdirs()
        val destination = cacheFile(songId)
        val temp = File(musicDir, "${destination.name}.tmp-${UUID.randomUUID()}")
        try {
            val wrapper = JSONObject()
                .put("version", CACHE_VERSION)
                .put("sourceKey", sourceKey)
                .put("raw", raw)
                .toString()
            require(wrapper.toByteArray(StandardCharsets.UTF_8).size <= MAX_CACHE_BYTES)
            temp.writeText(wrapper, StandardCharsets.UTF_8)
            try {
                Files.move(
                    temp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                if (!temp.renameTo(destination)) {
                    Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } finally {
            temp.delete()
        }
    }

    private fun cacheFile(songId: String): File =
        File(musicDir, ".credits_${sha256(songId)}.$CACHE_EXTENSION")

    private fun sourceKey(song: Song): String = sha256(
        listOf(song.mbid.orEmpty(), song.title, song.artist, song.durationSec.toString())
            .joinToString("\u0000"),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun normalize(value: String): String = java.text.Normalizer
        .normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun tokenOverlap(first: String, second: String): Int {
        if (first.isBlank() || second.isBlank()) return 0
        return first.split(' ').toSet().intersect(second.split(' ').toSet()).size
    }

    private fun artistCredit(array: JSONArray?): String {
        if (array == null) return ""
        return buildString {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                append(item.text("name") ?: item.optJSONObject("artist")?.text("name").orEmpty())
                append(item.text("joinphrase").orEmpty())
            }
        }.trim()
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull(::optJSONObject)

    private fun JSONObject.text(key: String): String? =
        optString(key).takeUnless { it.isBlank() || it == "null" }

    private fun escapeQuery(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun isMbid(value: String): Boolean = MBID.matches(value)

    private companion object {
        const val BASE_URL = "https://musicbrainz.org/ws/2"
        const val SEARCH_LIMIT = 8
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_CACHE_BYTES = 3L * 1024 * 1024
        const val CACHE_VERSION = 1
        const val CACHE_EXTENSION = "pmcredits"
        val MBID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        val USER_AGENT = "PrivateMusic/${BuildConfig.VERSION_NAME} (credits; aarcarpas@gmail.com)"
    }
}
