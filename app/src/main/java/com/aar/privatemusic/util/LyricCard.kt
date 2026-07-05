package com.aar.privatemusic.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.palette.graphics.Palette
import com.aar.privatemusic.data.db.Song
import java.io.File

/** Spotify-style shareable lyric card: cover + selected lines + branding. */
object LyricCard {

    fun render(context: Context, song: Song, lines: List<String>): File {
        val w = 1080
        val h = 1920
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Dynamic colours from the cover.
        val cover = song.artPath?.let { BitmapFactory.decodeFile(it) }
        val palette = cover?.let { Palette.from(it).generate() }
        val top = palette?.darkVibrantSwatch?.rgb
            ?: palette?.darkMutedSwatch?.rgb
            ?: Color.parseColor("#1A1B26")
        val bottom = Color.parseColor("#101018")
        canvas.drawRect(
            0f, 0f, w.toFloat(), h.toFloat(),
            Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            },
        )

        // Cover art, rounded, centered.
        cover?.let {
            val size = 560
            val left = (w - size) / 2f
            val topY = 220f
            val dst = RectF(left, topY, left + size, topY + size)
            val path = android.graphics.Path().apply { addRoundRect(dst, 40f, 40f, android.graphics.Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(it, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        }

        // Lyric lines, wrapped, big and bold.
        val textPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            textSize = 64f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        var y = 940f
        val maxWidth = w - 200f
        lines.forEach { raw ->
            val words = raw.trim().split(" ")
            var line = ""
            words.forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (textPaint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                    canvas.drawText(line, 100f, y, textPaint)
                    y += 88f
                    line = word
                } else line = candidate
            }
            if (line.isNotEmpty()) {
                canvas.drawText(line, 100f, y, textPaint)
                y += 108f
            }
        }

        // Song credit + branding.
        val credit = Paint().apply {
            color = Color.parseColor("#E4E7EE")
            isAntiAlias = true
            textSize = 44f
        }
        y += 40f
        canvas.drawText(song.title.take(40), 100f, y, credit)
        credit.color = Color.parseColor("#AEB6C4")
        canvas.drawText(song.artist.take(40), 100f, y + 60f, credit)

        val footer = Paint().apply {
            color = Color.parseColor("#8AB4F8")
            textSize = 40f
            isAntiAlias = true
        }
        canvas.drawText("PrivateMusic", 100f, h - 100f, footer)

        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "lyric_card.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        cover?.recycle()
        return file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir letra"))
    }
}
