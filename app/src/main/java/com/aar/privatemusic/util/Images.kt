package com.aar.privatemusic.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Copies a user-picked image into the app's music dir as a JPEG,
 * downscaled to at most 1024px. Returns the new file, or null on failure.
 * A timestamped name is used so Coil's cache never shows the old image.
 */
fun saveCoverImage(context: Context, uri: Uri, destDir: File, baseName: String): File? {
    return try {
        val resolver = context.contentResolver

        // First pass: bounds only, to pick a sample size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1024) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val file = File(destDir, "${baseName}_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()
        file
    } catch (e: Exception) {
        null
    }
}

/**
 * Downloads a remote cover image (e.g. iTunes/Deezer/Cover Art Archive) into the
 * music dir as a downscaled JPEG. Follows redirects (Cover Art Archive uses 307).
 * Returns the new file, or null on failure.
 */
fun saveCoverFromUrl(url: String, destDir: File, baseName: String): File? {
    return try {
        val bytes = run {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PrivateMusic")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.use { it.readBytes() }
            } finally { conn.disconnect() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val file = File(destDir, "${baseName}_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        bitmap.recycle()
        file
    } catch (e: Exception) {
        null
    }
}
