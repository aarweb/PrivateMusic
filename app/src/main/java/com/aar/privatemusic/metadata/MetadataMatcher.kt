package com.aar.privatemusic.metadata

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A candidate identification for a song, merged from one or more sources. */
data class TrackMatch(
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val mbid: String? = null,
    val isrc: String? = null,
    val artworkUrl: String? = null,
    val durationSec: Int? = null,
    val source: String = "",
    /** 0..1 heuristic score (duration + text closeness + source agreement). */
    val score: Float = 0f,
)

data class MatchResult(
    val candidates: List<TrackMatch>,
    /** Top pick is safe to apply without asking. */
    val confident: Boolean,
) {
    val best: TrackMatch? get() = candidates.firstOrNull()
}

/**
 * Turns a messy YouTube title into canonical track metadata using only keyless
 * public APIs: iTunes Search + Deezer for the match, MusicBrainz to enrich the
 * winner with stable IDs and a clean featured-artist split. The file duration
 * is the main disambiguator, so pass it whenever available.
 */
class MetadataMatcher {

    private data class Parsed(val artist: String?, val title: String, val featured: List<String>, val version: String?)

    // MusicBrainz demands <=1 req/s and a descriptive User-Agent.
    private val mbLock = Mutex()
    private var lastMbCall = 0L

    private val ua =
        "PrivateMusic/${com.aar.privatemusic.BuildConfig.VERSION_NAME} ( aarcarpas@gmail.com )"

    suspend fun identify(rawTitle: String, rawArtist: String, durationSec: Int): MatchResult =
        withContext(Dispatchers.IO) {
            val parsed = cleanTitle(rawTitle)
            // Prefer the artist parsed from the title; the YouTube "artist" is
            // usually the uploader/channel and unreliable. Keep it as a fallback.
            val artistGuess = parsed.artist?.takeIf { it.isNotBlank() } ?: rawArtist
            search(artistGuess, parsed.title, durationSec, reliable = false)
        }

    /**
     * Search with an already-clean artist+title (e.g. resolved by an audio
     * fingerprint). The input is trusted, so the confidence bar is relaxed.
     */
    suspend fun searchByArtistTitle(artist: String, title: String, durationSec: Int): MatchResult =
        withContext(Dispatchers.IO) { search(artist, title, durationSec, reliable = true) }

    private suspend fun search(
        artistGuess: String?,
        title: String,
        durationSec: Int,
        reliable: Boolean,
    ): MatchResult =
        withContext(Dispatchers.IO) {
            val query = listOfNotNull(artistGuess, title).joinToString(" ").trim()
            if (query.isBlank()) return@withContext MatchResult(emptyList(), false)

            val candidates = buildList {
                addAll(runCatching { itunesSearch(query) }.getOrDefault(emptyList()))
                addAll(runCatching { deezerSearch(artistGuess, title, query) }.getOrDefault(emptyList()))
            }
            if (candidates.isEmpty()) return@withContext MatchResult(emptyList(), false)

            val scored = candidates
                .map { it.copy(score = score(it, artistGuess, title, durationSec)) }
                .sortedByDescending { it.score }

            // Merge duplicates (same normalized artist+title) so agreement across
            // iTunes+Deezer boosts confidence and fills gaps (e.g. ISRC vs artwork).
            val merged = mergeDuplicates(scored)
            var top = merged.firstOrNull()

            // Two independent sources agreeing on the winner + a close duration is
            // our "confident" bar for auto-apply. A fingerprint-derived query is
            // already trustworthy, so relax to a single strong hit.
            val agreement = top?.source?.contains("+") == true
            val durationOk = top?.durationSec?.let {
                durationSec <= 0 || kotlin.math.abs(it - durationSec) <= 4
            } ?: false
            val confident = top != null && durationOk &&
                if (reliable) top!!.score >= 0.6f else (top!!.score >= 0.8f && agreement)

            // Enrich the winner with MusicBrainz (MBID, original year, feat split).
            if (top != null) {
                runCatching { enrichWithMusicBrainz(top!!, durationSec) }.getOrNull()?.let { top = it }
            }
            val finalList = (listOfNotNull(top) + merged.drop(1)).distinctBy { it.title + "|" + it.artist }
            MatchResult(finalList, confident)
        }

    // ---- Title cleanup (offline) ----

    private val noiseTags = Regex(
        "\\((official\\s*(music\\s*)?(video|audio|lyric[s]?|visualizer)|" +
            "lyric[s]?|audio|visualizer|m/v|explicit|clean|hd|hq|4k|full\\s*album|" +
            "remaster(ed)?(\\s*\\d{4})?|prod\\.?\\s*by[^)]*)\\)",
        RegexOption.IGNORE_CASE,
    )
    private val bracketTags = Regex("\\[[^\\]]*\\]")
    private val hashTags = Regex("#\\p{L}+")
    private val featRe = Regex("[\\(\\[]?\\s*(feat\\.?|ft\\.?|featuring|con)\\s+([^\\)\\]]+)[\\)\\]]?", RegexOption.IGNORE_CASE)
    private val versionRe = Regex("\\((.*?(remix|acoustic|live|edit|version|mix|cover).*?)\\)", RegexOption.IGNORE_CASE)
    // Broad emoji / pictograph stripper.
    private val emojiRe = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2190}-\\x{21FF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{200D}]")

    private fun cleanTitle(raw: String): Parsed {
        var s = raw
        val version = versionRe.find(s)?.groupValues?.getOrNull(1)?.trim()
        val featured = featRe.findAll(s).flatMap { m ->
            m.groupValues[2].split(",", "&", " y ", " and ").map { it.trim() }.filter { it.isNotBlank() }
        }.toList()
        s = s.replace(featRe, " ")
        s = s.replace(noiseTags, " ")
        s = s.replace(bracketTags, " ")
        s = s.replace(hashTags, " ")
        s = s.replace(emojiRe, " ")
        s = s.replace(Regex("\\|\\|.*$"), " ") // trailing "|| channel spam"
        s = s.replace(Regex("\\s+"), " ").trim().trim('-', '–', '—', '·', '|', ':').trim()

        // Split "Artist - Title" on the first top-level dash-like separator.
        val sep = Regex("\\s+[\\-–—]\\s+")
        val parts = sep.split(s, limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            Parsed(parts[0].trim(), parts[1].trim(), featured, version)
        } else {
            Parsed(null, s, featured, version)
        }
    }

    // ---- Sources ----

    private fun itunesSearch(query: String): List<TrackMatch> {
        val url = "https://itunes.apple.com/search?media=music&entity=song&limit=6&term=" + enc(query)
        val json = httpGet(url) ?: return emptyList()
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val art = o.optString("artworkUrl100").takeIf { it.isNotBlank() }
                ?.replace("100x100bb.jpg", "600x600bb.jpg")
            TrackMatch(
                title = o.optString("trackName"),
                artist = o.optString("artistName"),
                album = o.optString("collectionName").takeIf { it.isNotBlank() },
                year = o.optString("releaseDate").take(4).toIntOrNull(),
                trackNumber = o.optInt("trackNumber").takeIf { it > 0 },
                artworkUrl = art,
                durationSec = (o.optLong("trackTimeMillis") / 1000).toInt().takeIf { it > 0 },
                source = "itunes",
            )
        }.filter { it.title.isNotBlank() && it.artist.isNotBlank() }
    }

    private fun deezerSearch(artist: String?, title: String, fallback: String): List<TrackMatch> {
        val q = if (!artist.isNullOrBlank())
            "artist:\"$artist\" track:\"$title\"" else fallback
        val url = "https://api.deezer.com/search?limit=6&q=" + enc(q)
        val json = httpGet(url) ?: return emptyList()
        val arr = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val album = o.optJSONObject("album")
            TrackMatch(
                title = o.optString("title"),
                artist = o.optJSONObject("artist")?.optString("name").orEmpty(),
                album = album?.optString("title")?.takeIf { it.isNotBlank() },
                isrc = o.optString("isrc").takeIf { it.isNotBlank() },
                artworkUrl = album?.optString("cover_xl")?.takeIf { it.isNotBlank() },
                durationSec = o.optInt("duration").takeIf { it > 0 },
                source = "deezer",
            )
        }.filter { it.title.isNotBlank() && it.artist.isNotBlank() }
    }

    private suspend fun enrichWithMusicBrainz(match: TrackMatch, durationSec: Int): TrackMatch? {
        val q = "recording:\"${match.title}\" AND artist:\"${match.artist}\""
        val url = "https://musicbrainz.org/ws/2/recording?fmt=json&limit=3&query=" + enc(q)
        val json = mbLock.withLock {
            val since = System.currentTimeMillis() - lastMbCall
            if (since < 1100) delay(1100 - since)
            lastMbCall = System.currentTimeMillis()
            httpGet(url)
        } ?: return null
        val recs = JSONObject(json).optJSONArray("recordings") ?: return null
        // Pick the recording whose length best matches the file.
        var best: JSONObject? = null
        var bestDelta = Long.MAX_VALUE
        for (i in 0 until recs.length()) {
            val r = recs.optJSONObject(i) ?: continue
            val len = r.optLong("length", 0L)
            val delta = if (durationSec > 0 && len > 0) kotlin.math.abs(len - durationSec * 1000L) else 0L
            if (delta < bestDelta) { bestDelta = delta; best = r }
        }
        val r = best ?: return null
        val credits = r.optJSONArray("artist-credit")
        val primary = StringBuilder()
        if (credits != null) for (i in 0 until credits.length()) {
            val c = credits.optJSONObject(i) ?: continue
            primary.append(c.optString("name")).append(c.optString("joinphrase"))
        }
        val rel = r.optJSONArray("releases")?.optJSONObject(0)
        val year = rel?.optString("date")?.take(4)?.toIntOrNull()
        return match.copy(
            artist = primary.toString().ifBlank { match.artist },
            mbid = r.optString("id").takeIf { it.isNotBlank() },
            year = match.year ?: year,
            album = match.album ?: rel?.optString("title")?.takeIf { it.isNotBlank() },
            source = match.source + "+mb",
        )
    }

    // ---- Scoring / merge ----

    private fun score(m: TrackMatch, artist: String?, title: String, durationSec: Int): Float {
        var s = 0f
        s += 0.5f * textCloseness(m.title, title)
        if (!artist.isNullOrBlank()) s += 0.3f * textCloseness(m.artist, artist)
        else s += 0.15f
        if (durationSec > 0 && m.durationSec != null) {
            val d = kotlin.math.abs(m.durationSec - durationSec)
            s += when { d <= 2 -> 0.2f; d <= 5 -> 0.12f; d <= 10 -> 0.05f; else -> 0f }
        } else s += 0.1f
        return s.coerceIn(0f, 1f)
    }

    private fun mergeDuplicates(list: List<TrackMatch>): List<TrackMatch> {
        val out = LinkedHashMap<String, TrackMatch>()
        for (m in list) {
            val key = normalize(m.artist) + "|" + normalize(m.title)
            val existing = out[key]
            if (existing == null) {
                out[key] = m
            } else {
                // Combine: keep higher score, note agreement, fill missing fields.
                out[key] = existing.copy(
                    album = existing.album ?: m.album,
                    year = existing.year ?: m.year,
                    trackNumber = existing.trackNumber ?: m.trackNumber,
                    isrc = existing.isrc ?: m.isrc,
                    artworkUrl = existing.artworkUrl ?: m.artworkUrl,
                    durationSec = existing.durationSec ?: m.durationSec,
                    source = existing.source + "+" + m.source,
                    score = maxOf(existing.score, m.score) + 0.1f,
                )
            }
        }
        return out.values.sortedByDescending { it.score }
    }

    private fun textCloseness(a: String, b: String): Float {
        val na = normalize(a); val nb = normalize(b)
        if (na == nb) return 1f
        if (na.isEmpty() || nb.isEmpty()) return 0f
        if (na.contains(nb) || nb.contains(na)) return 0.85f
        val ta = na.split(" ").toSet(); val tb = nb.split(" ").toSet()
        val inter = ta.intersect(tb).size.toFloat()
        return inter / maxOf(ta.size, tb.size)
    }

    private fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String): String? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode != 200) return@runCatching null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }.onFailure { Log.w("MetadataMatcher", "GET failed: $url", it) }.getOrNull()
}
