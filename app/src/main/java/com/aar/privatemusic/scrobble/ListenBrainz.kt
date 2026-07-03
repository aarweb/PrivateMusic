package com.aar.privatemusic.scrobble

import android.content.Context
import android.util.Log
import com.aar.privatemusic.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Fire-and-forget listen submission to ListenBrainz (open Last.fm alternative). */
object ListenBrainz {

    suspend fun submitListen(context: Context, title: String, artist: String) {
        val token = AppSettings.readListenBrainzToken(context)
        if (token.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("listen_type", "single")
                    put("payload", JSONArray().put(
                        JSONObject().apply {
                            put("listened_at", System.currentTimeMillis() / 1000)
                            put("track_metadata", JSONObject().apply {
                                put("track_name", title)
                                put("artist_name", artist)
                            })
                        }
                    ))
                }
                val conn = URL("https://api.listenbrainz.org/1/submit-listens")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Authorization", "Token $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                conn.responseCode // trigger request
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("ListenBrainz", "listen submit failed (offline?)", e)
            }
        }
    }
}
