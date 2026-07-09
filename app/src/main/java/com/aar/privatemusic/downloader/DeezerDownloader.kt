package com.aar.privatemusic.downloader

import android.content.Context
import android.util.Log
import com.aar.privatemusic.data.AppSettings
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.PlaylistSongCrossRef
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.util.readAudioQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Info del usuario y su plan, para validar el ARL y saber qué calidades ofrecer. */
data class DeezerUserInfo(
    val name: String,
    val country: String,
    val hasFlac: Boolean,
    val hasHq: Boolean,
)

/**
 * Descarga directa desde Deezer (FLAC / MP3 320 / 128) usando la sesión (cookie
 * ARL) del propio usuario. El audio de Deezer viaja cifrado (BF_CBC_STRIPE); se
 * descarga y se desencripta con [BlowfishDecryptor], y la pista entra en la
 * biblioteca como cualquier otra Song (metadatos limpios de Deezer + carátula).
 *
 * Comparte el modelo [DownloadState] con [YtDownloader] para reutilizar la UI.
 */
class DeezerDownloader(
    private val context: Context,
    private val dao: MusicDao,
    private val scope: CoroutineScope,
) {
    // Mismo directorio que las descargas de YouTube. Deezer escribe una pista a
    // la vez (temp cifrado -> final), así que no dispara el bug de FUSE que sí
    // afectaba a los torrents (muchos ficheros a la vez).
    val musicDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "music")
        .apply { mkdirs() }

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    private val slots = Semaphore(2)

    /** Sesión gw-light cacheada (token CSRF + license token), se refresca sola. */
    @Volatile private var session: Session? = null
    private data class Session(val apiToken: String, val licenseToken: String, val sid: String, val ts: Long)

    fun stateKey(trackId: Long) = "dz_$trackId"

    /**
     * [targetPlaylistId] + [position] preservan el orden original al importar una
     * playlist: sin la posición explícita las canciones quedarían en el orden en
     * que acaban de descargarse, que no es el mismo.
     */
    fun enqueue(
        track: DeezerTrack,
        quality: String,
        targetPlaylistId: Long? = null,
        position: Int? = null,
    ) {
        val key = stateKey(track.id)
        val current = _downloads.value[key]
        if (current is DownloadState.Queued || current is DownloadState.Downloading) return
        setState(key, DownloadState.Queued)
        scope.launch(Dispatchers.IO) {
            slots.withPermit {
                try {
                    if (!dao.songExists(key)) download(track, quality)
                    setState(key, DownloadState.Done)
                    targetPlaylistId?.let { addToPlaylist(it, track, key, position) }
                } catch (e: Exception) {
                    Log.e("DeezerDownloader", "download failed for ${track.id}", e)
                    setState(key, DownloadState.Failed(e.message ?: "error"))
                }
            }
        }
    }

    /**
     * `download` se salta la descarga si ya tienes la canción con otro id (dedup
     * por título+artista), así que la playlist debe apuntar a ESA, no a `dz_<id>`.
     */
    private suspend fun addToPlaylist(playlistId: Long, track: DeezerTrack, key: String, position: Int?) {
        val songId = if (dao.songExists(key)) key
        else dao.findByTitleArtist(track.title, track.artist)?.id ?: return
        runCatching {
            dao.addToPlaylist(
                PlaylistSongCrossRef(playlistId, songId, position ?: dao.playlistSize(playlistId)),
            )
        }.onFailure { Log.w("DeezerDownloader", "no se pudo añadir a la playlist", it) }
    }

    private suspend fun download(track: DeezerTrack, quality: String) {
        // Dedup: si ya tienes esta canción (mismo título+artista) no la bajes otra vez.
        dao.findByTitleArtist(track.title, track.artist)?.let { dup ->
            if (dup.id != stateKey(track.id)) {
                com.aar.privatemusic.util.Feedback.show("Ya tienes \"${track.title}\" en la biblioteca")
                return
            }
        }
        val sess = ensureSession()

        // 1) Datos del track: TRACK_TOKEN para pedir la URL de descarga.
        val songData = gwCall("song.getData", sess.apiToken, JSONObject().put("sng_id", track.id))
        val results = songData.optJSONObject("results")
            ?: throw IllegalStateException("Deezer no devolvió datos del track")
        val trackToken = results.optString("TRACK_TOKEN").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Sin TRACK_TOKEN (¿sesión caducada?)")

        // 2) URL de descarga en la mejor calidad disponible <= la pedida.
        val media = requestUrl(sess.licenseToken, trackToken, quality)
        val ext = if (media.format == "FLAC") "flac" else "mp3"

        // 3) Descargar cifrado -> desencriptar -> limpiar temporal.
        val encFile = File(musicDir, "${stateKey(track.id)}.$ext.enc")
        val audioFile = File(musicDir, "${stateKey(track.id)}.$ext")
        try {
            downloadTo(media.url, encFile)
            BlowfishDecryptor.decryptFile(encFile, audioFile, track.id.toString())
        } finally {
            encFile.delete()
        }

        // 4) Carátula del álbum como fichero contiguo (igual que YtDownloader).
        val artFile = File(musicDir, "${stateKey(track.id)}.jpg")
        if (track.coverUrl.isNotBlank()) {
            runCatching { downloadTo(track.coverUrl, artFile) }
        }

        val quality3 = runCatching { readAudioQuality(audioFile.absolutePath, track.durationSec) }.getOrNull()
        dao.insertSong(
            Song(
                id = stateKey(track.id),
                title = track.title,
                artist = track.artist,
                durationSec = track.durationSec,
                filePath = audioFile.absolutePath,
                artPath = artFile.takeIf { it.exists() }?.absolutePath,
                thumbnailUrl = track.coverUrl.ifBlank { null },
                addedAt = System.currentTimeMillis(),
                codec = quality3?.codec,
                bitrateKbps = quality3?.bitrateKbps,
                sampleRateHz = quality3?.sampleRateHz,
            )
        )
    }

    private data class Media(val url: String, val format: String)

    /** Pide a media.deezer.com la mejor calidad disponible desde [preferred] hacia abajo. */
    private fun requestUrl(licenseToken: String, trackToken: String, preferred: String): Media {
        val ladder = when (preferred) {
            "FLAC" -> listOf("FLAC", "MP3_320", "MP3_128")
            "MP3_320" -> listOf("MP3_320", "MP3_128")
            else -> listOf("MP3_128")
        }
        val formats = JSONArray()
        ladder.forEach { f ->
            formats.put(JSONObject().put("cipher", "BF_CBC_STRIPE").put("format", f))
        }
        val payload = JSONObject().apply {
            put("license_token", licenseToken)
            put("media", JSONArray().put(JSONObject().apply {
                put("type", "FULL")
                put("formats", formats)
            }))
            put("track_tokens", JSONArray().put(trackToken))
        }
        val resp = JSONObject(postJson("https://media.deezer.com/v1/get_url", payload.toString(), null))
        val entry = resp.optJSONArray("data")?.optJSONObject(0)
            ?: throw IllegalStateException("get_url sin datos")
        entry.optJSONArray("errors")?.optJSONObject(0)?.let {
            throw IllegalStateException("Deezer: ${it.optString("message", "no disponible en esta calidad")}")
        }
        val mediaObj = entry.optJSONArray("media")?.optJSONObject(0)
            ?: throw IllegalStateException("Pista no disponible para descargar")
        val url = mediaObj.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Sin fuente de descarga")
        return Media(url, mediaObj.optString("format", ladder.first()))
    }

    /** Devuelve una sesión válida, refrescándola si falta o lleva más de 30 min. */
    private fun ensureSession(): Session {
        session?.let { if (System.currentTimeMillis() - it.ts < 30 * 60_000) return it }
        val arl = AppSettings.readDeezerArl(context)
        if (arl.isBlank()) throw IllegalStateException("Inicia sesión en Deezer en Ajustes")
        val pair = fetchSession(context, arl)
            ?: throw IllegalStateException("Sesión de Deezer inválida (vuelve a iniciar sesión)")
        val info = pair.first
        // Mantén al día el plan por si cambió (p.ej. renovó HiFi).
        AppSettings(context).setDeezerSession(arl, info.name, info.country, info.hasFlac, info.hasHq)
        session = pair.second
        return pair.second
    }

    private fun gwCall(method: String, apiToken: String, body: JSONObject): JSONObject {
        val url = "https://www.deezer.com/ajax/gw-light.php" +
            "?method=$method&input=3&api_version=1.0&api_token=$apiToken"
        val arl = AppSettings.readDeezerArl(context)
        val sid = session?.sid.orEmpty()
        val cookie = buildString {
            append("arl=$arl")
            if (sid.isNotBlank()) append("; sid=$sid")
        }
        return JSONObject(postJson(url, body.toString(), cookie))
    }

    private fun downloadTo(url: String, out: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", UA)
        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
        conn.disconnect()
    }

    private fun setState(key: String, state: DownloadState) {
        _downloads.update { it + (key to state) }
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        /** POST simple; devuelve el cuerpo. Con [cookie] adjunta la sesión. */
        private fun postJson(url: String, body: String, cookie: String?): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", "application/json")
            cookie?.let { conn.setRequestProperty("Cookie", it) }
            conn.outputStream.use { it.write(body.toByteArray()) }
            return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        }

        /**
         * Valida el ARL y obtiene el plan del usuario. Devuelve también la sesión
         * (token CSRF + license token + sid) para reutilizarla en las descargas.
         */
        private fun fetchSession(context: Context, arl: String): Pair<DeezerUserInfo, Session>? = runCatching {
            val conn = URL(
                "https://www.deezer.com/ajax/gw-light.php" +
                    "?method=deezer.getUserData&input=3&api_version=1.0&api_token="
            ).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Cookie", "arl=$arl")
            conn.outputStream.use { it.write("{}".toByteArray()) }
            // El sid llega en Set-Cookie de esta primera llamada.
            val sid = conn.headerFields["Set-Cookie"].orEmpty()
                .mapNotNull { it.split(";").firstOrNull()?.trim() }
                .firstOrNull { it.startsWith("sid=") }?.removePrefix("sid=").orEmpty()
            val body = conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
            val results = JSONObject(body).optJSONObject("results")
                ?: return@runCatching null
            val user = results.optJSONObject("USER") ?: return@runCatching null
            // USER_ID 0 => el ARL no es válido / caducó.
            if (user.optLong("USER_ID", 0L) == 0L) return@runCatching null
            val options = user.optJSONObject("OPTIONS")
            val apiToken = results.optString("checkForm")
            val licenseToken = options?.optString("license_token").orEmpty()
            val info = DeezerUserInfo(
                name = user.optString("BLOG_NAME").ifBlank { user.optString("EMAIL", "Deezer") },
                // El país viene en results.COUNTRY (no dentro de USER).
                country = results.optString("COUNTRY").ifBlank { user.optString("COUNTRY") },
                hasFlac = options?.optBoolean("web_lossless", false) == true ||
                    options?.optBoolean("mobile_lossless", false) == true,
                hasHq = options?.optBoolean("web_hq", false) == true ||
                    options?.optBoolean("mobile_hq", false) == true,
            )
            info to Session(apiToken, licenseToken, sid, System.currentTimeMillis())
        }.getOrNull()

        /** Sólo la info del usuario, para el flujo de login. */
        fun fetchUserInfo(context: Context, arl: String): DeezerUserInfo? =
            fetchSession(context, arl)?.first
    }
}
