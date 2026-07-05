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

    private fun cachedApk(context: Context) = File(context.cacheDir, "updates/update.apk")

    /** True if the APK for this exact version is already downloaded. */
    fun hasCached(context: Context, version: String): Boolean {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getString("cached_update_version", null) == version &&
            cachedApk(context).length() > 1_000_000
    }

    /** Re-launches the installer with the already-downloaded APK (no re-download). */
    fun installCached(context: Context): Boolean = runCatching {
        launchInstaller(context, cachedApk(context))
        true
    }.getOrDefault(false)

    /** Downloads the APK and opens the system installer. */
    suspend fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        version: String,
        onProgress: (Int) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val apk = cachedApk(context).apply { parentFile?.mkdirs() }
        val tmp = File(apk.parentFile, "${apk.name}.part")
        try {
            // A failed download must never leave a half-written file where a
            // previously valid cached APK was: write aside and rename on success.
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().remove("cached_update_version").apply()

            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val total = conn.contentLengthLong
            var copied = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
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
            if ((total > 0 && copied != total) || !tmp.renameTo(apk)) {
                tmp.delete()
                return@withContext false
            }

            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putString("cached_update_version", version).apply()

            launchInstaller(context, apk)
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
