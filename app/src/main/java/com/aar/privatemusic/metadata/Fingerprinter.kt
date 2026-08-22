package com.aar.privatemusic.metadata

import android.util.Log
import com.lalilu.fpcalc.Fpcalc
import com.lalilu.fpcalc.FpcalcParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Audio-fingerprint identification via Chromaprint ([Fpcalc], on-device using
 * MediaCodec) + AcoustID lookup. This is the fallback for songs whose title is
 * useless ("lofi mix", "New Ringtone", auto-generated uploads): the fingerprint
 * yields a trustworthy artist+title that the text matcher then enriches.
 *
 * Needs the free AcoustID application key in BuildConfig.ACOUSTID_KEY; when that
 * is blank the fingerprint fallback is simply disabled.
 *
 * `libfpcalc.so` es la única librería nativa del APK que sigue alineada a 4 KB:
 * su proyecto está abandonado (ninguna build de JitPack, ni las de master, la
 * alinea a 16 KB) y no hay recambio en Maven. En un móvil con páginas de 16 KB
 * `System.loadLibrary` fallará, así que la clase se toca **sólo aquí y sólo al
 * identificar**: el error se queda dentro de [nativeAvailable] y lo que se
 * pierde es esta comprobación por huella, no la app ni el resto de la
 * identificación (iTunes/Deezer/MusicBrainz siguen funcionando).
 */
class Fingerprinter {

    private val key = com.aar.privatemusic.BuildConfig.ACOUSTID_KEY

    val enabled: Boolean get() = key.isNotBlank()

    /**
     * Si la librería nativa carga en este dispositivo. Se resuelve la primera
     * vez que se pregunta (nunca al arrancar) y se recuerda: cargar una .so mal
     * alineada lanza `UnsatisfiedLinkError`, que es un `Error`, no una
     * `Exception`, y hay que atraparlo explícitamente.
     */
    private val nativeAvailable: Boolean by lazy {
        try {
            Fpcalc.toString() // fuerza el <clinit>, que hace el loadLibrary
            true
        } catch (e: Throwable) {
            Log.w("Fingerprinter", "fpcalc no disponible en este dispositivo: ${e.javaClass.simpleName}", e)
            false
        }
    }

    /** Para que quien llame pueda decírselo al usuario en vez de callar. */
    val fingerprintSupported: Boolean get() = nativeAvailable

    /** Returns a reliable (artist, title, mbid, album) match, or null. */
    suspend fun identify(file: File): TrackMatch? = withContext(Dispatchers.IO) {
        if (!enabled || !file.exists() || !nativeAvailable) return@withContext null
        val res = runCatching {
            Fpcalc.calc(
                FpcalcParams(
                    targetFilePath = file.absolutePath,
                    gMaxDuration = 120,
                    gAlgorithm = 2, // AcoustID's default algorithm
                )
            )
        }.onFailure { Log.w("Fingerprinter", "fpcalc failed", it) }.getOrNull() ?: return@withContext null

        val fp = res.fingerprint
        val dur = (res.sourceDurationMs / 1000).toInt()
        if (fp.isNullOrBlank() || dur <= 0) {
            Log.w("Fingerprinter", "no fingerprint (err=${res.errorMessage})")
            return@withContext null
        }
        lookup(fp, dur)
    }

    private fun lookup(fingerprint: String, durationSec: Int): TrackMatch? {
        val body = "client=" + enc(key) +
            "&meta=recordings+releasegroups+compress" +
            "&duration=" + durationSec +
            "&fingerprint=" + enc(fingerprint)
        val json = httpPost("https://api.acoustid.org/v2/lookup", body) ?: return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optString("status") != "ok") return null
        val results = root.optJSONArray("results") ?: return null

        var best: JSONObject? = null
        var bestScore = -1.0
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val s = r.optDouble("score", 0.0)
            if (s > bestScore && r.optJSONArray("recordings") != null) { bestScore = s; best = r }
        }
        val rec = best?.optJSONArray("recordings")?.optJSONObject(0) ?: return null
        val title = rec.optString("title")
        val artist = rec.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
        if (title.isBlank() || artist.isBlank()) return null
        val rg = rec.optJSONArray("releasegroups")?.optJSONObject(0)
        val rgMbid = rg?.optString("id")?.takeIf { it.isNotBlank() }
        val art = rgMbid?.let { "https://coverartarchive.org/release-group/$it/front-500" }
        return TrackMatch(
            title = title,
            artist = artist,
            album = rg?.optString("title")?.takeIf { it.isNotBlank() },
            mbid = rec.optString("id").takeIf { it.isNotBlank() },
            artworkUrl = art,
            durationSec = rec.optInt("duration").takeIf { it > 0 },
            source = "acoustid",
            score = bestScore.toFloat(),
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpPost(url: String, body: String): String? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 10000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "PrivateMusic/${com.aar.privatemusic.BuildConfig.VERSION_NAME}")
        }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) return@runCatching null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }.onFailure { Log.w("Fingerprinter", "AcoustID lookup failed", it) }.getOrNull()
}
