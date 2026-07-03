package com.aar.privatemusic.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.aar.privatemusic.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update via GitHub Releases: checks the latest release of the repo,
 * downloads the APK asset and hands it to the system installer.
 */
object AppUpdater {

    private const val LATEST_URL =
        "https://api.github.com/repos/aarweb/PrivateMusic/releases/latest"

    data class UpdateInfo(
        val version: String,
        val apkUrl: String,
        val notes: String,
        val isNewer: Boolean,
    )

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(body)
            val version = json.optString("tag_name").removePrefix("v")
            if (version.isBlank()) return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            UpdateInfo(
                version = version,
                apkUrl = apkUrl ?: return@withContext null,
                notes = json.optString("body").take(500),
                isNewer = isNewer(version, BuildConfig.VERSION_NAME),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.trim().toIntOrNull() }
        val l = local.split(".").mapNotNull { it.trim().toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    /** Downloads the APK and opens the system installer. */
    suspend fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apk = File(dir, "update.apk")

            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress((copied * 100 / total).toInt())
                    }
                }
            }
            conn.disconnect()

            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
