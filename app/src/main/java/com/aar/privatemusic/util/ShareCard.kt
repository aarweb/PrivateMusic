package com.aar.privatemusic.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.content.FileProvider
import com.aar.privatemusic.data.MusicRepository
import java.io.File

/** Renders the recap as a 1080x1920 PNG story card and opens the system share sheet. */
object ShareCard {

    fun renderRecap(context: Context, recap: MusicRepository.Recap, periodLabel: String): File {
        val w = 1080
        val h = 1920
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.parseColor("#1A1B26"), Color.parseColor("#0D3B4F"),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val accent = Paint().apply {
            color = Color.parseColor("#8AB4F8")
            isAntiAlias = true
        }
        val white = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
        }
        val grey = Paint().apply {
            color = Color.parseColor("#B8C0CC")
            isAntiAlias = true
        }

        var y = 180f
        fun drawText(text: String, paint: Paint, size: Float, x: Float = 80f, dy: Float = size * 1.5f) {
            paint.textSize = size
            canvas.drawText(text.take(38), x, y, paint)
            y += dy
        }

        drawText("PrivateMusic", accent, 56f)
        drawText("Tu Recap · $periodLabel", white, 88f, dy = 140f)

        drawText("${recap.minutes} minutos escuchados", white, 64f)
        drawText("${recap.plays} reproducciones · ${recap.distinctSongs} canciones", grey, 44f)
        drawText("${recap.listeningDays} días con música", grey, 44f, dy = 110f)

        drawText("${recap.personalityEmoji} ${recap.personality.substringBefore(':')}", accent, 60f, dy = 130f)

        drawText("Top canciones", accent, 48f)
        recap.topSongs.take(5).forEachIndexed { i, s ->
            drawText("${i + 1}. ${s.song.title}", white, 46f)
        }
        y += 60f
        drawText("Top artistas", accent, 48f)
        recap.topArtists.take(3).forEachIndexed { i, a ->
            drawText("${i + 1}. ${a.artist} (${a.plays})", white, 46f)
        }

        val footer = Paint().apply {
            color = Color.parseColor("#8AB4F8")
            textSize = 40f
            isAntiAlias = true
        }
        canvas.drawText("Generado con PrivateMusic — tu música, offline", 80f, h - 100f, footer)

        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "recap.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir Recap"))
    }
}
